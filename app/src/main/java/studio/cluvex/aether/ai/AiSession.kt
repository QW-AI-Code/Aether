package studio.cluvex.aether.ai

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.data.AiSettings
import studio.cluvex.aether.data.GeminiStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState

/** One bubble in the chat. */
data class AiMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    /** Render as a failure (red, retry affordance) rather than as an answer. */
    val failed: Boolean = false,
    /**
     * Why it failed, so the UI can print a translated sentence.
     *
     * The failure bubble used to carry raw text - `gate.name`, or Google's own
     * English message - straight into the chat. That is how a Persian user ended
     * up reading "DISCONNECTED" in a red bubble. The bubble now carries the CAUSE
     * and the screen owns the wording.
     */
    val errorKind: AiErrorKind? = null,
    /** Set instead of [errorKind] when the app itself blocked the request. */
    val gate: AiGate? = null,
    /**
     * The prompt that produced this bubble, kept so "try again" can resend it.
     *
     * Stored on the FAILURE rather than looked up by walking backwards through the
     * list: after an edit or a delete the bubble above a failure is not
     * necessarily the prompt that caused it, and resending the wrong message is a
     * worse bug than no retry button.
     */
    val sourcePrompt: String? = null,
    /** Setting changes this answer proposes, already validated against [AiPatch]. */
    val changes: List<AiChange> = emptyList(),
    /** True once the user has pressed Apply on [changes]. */
    val applied: Boolean = false,
    /** True when the user has edited their own message since sending it. */
    val edited: Boolean = false,
)

/** State of the "test the API connection" action. */
sealed interface AiProbe {
    data object Idle : AiProbe
    data object Running : AiProbe
    data class Ok(val modelCount: Int, val via: String) : AiProbe
    data class Failed(
        val message: String,
        val kind: AiErrorKind,
        /** Set when the app refused before any request went out. */
        val gate: AiGate? = null,
    ) : AiProbe
}

/** State of the log analysis. */
sealed interface AiAdviceState {
    data object Idle : AiAdviceState
    data object Running : AiAdviceState
    data class Ready(val advice: AiAdvice, val atMillis: Long, val applied: Boolean = false) :
        AiAdviceState

    /**
     * @param kind the API-level cause, when there was one.
     * @param gate set when the app refused before any request went out.
     * @param reason set when the app itself could not use a successful answer.
     */
    data class Failed(
        val message: String,
        val kind: AiErrorKind? = null,
        val gate: AiGate? = null,
        val reason: AiAdviceProblem? = null,
    ) : AiAdviceState
}

/** App-side reasons an analysis produced nothing usable, for translation in the UI. */
enum class AiAdviceProblem {
    /** The diagnostics log had nothing in it to analyse. */
    EMPTY_LOG,

    /** Two answers in a row could not be read as the JSON contract. */
    UNREADABLE,
}

/**
 * The single owner of AI state for the whole app, in the same spirit as
 * [studio.cluvex.aether.core.AetherController].
 *
 * ## Why a singleton and not a ViewModel per screen
 *
 * The chat, the AI settings page and the advisor card are three different screens
 * that must agree about one key, one model and one conversation, and the settings
 * area disposes a page the moment the user navigates away (that disposal is the
 * performance fix the whole settings rewrite was built around - see
 * `SettingsUi.kt`). A per-screen holder would lose the conversation on every back
 * gesture and would fire a second model-discovery request per screen. State that
 * outlives the screen lives here; the screens are pure renderers of it.
 *
 * Nothing in here touches the network unless [AiGate] says it can, and every
 * request is dialled through the tunnel's SOCKS proxy - see [GeminiHttp].
 */
object AiSession {

    /**
     * A process-lifetime scope, not a screen's.
     *
     * `SupervisorJob` so one failed request cannot cancel the scope and take the
     * conversation with it, and `Dispatchers.IO` because every call in here ends in
     * a blocking socket read.
     *
     * ## Why the handler (1.2.9 crash fix)
     *
     * `SupervisorJob` stops a failure from cancelling its SIBLINGS. It does not
     * stop an unhandled exception from reaching the thread's default handler,
     * which on Android means the process dies. That is exactly how a single
     * `IndexOutOfBoundsException` inside the log redactor - a helper with no
     * network, no state and no user interaction - became "the app closes a minute
     * after connecting": the throw happened on `DefaultDispatcher-worker-*`,
     * nothing in `analyze` caught it, and the crash handler in `AetherApp` wrote
     * `last_crash.txt` on the way out.
     *
     * The AI feature is an OPTIONAL advisor sitting next to a VPN tunnel. Nothing
     * it can fail at justifies taking the tunnel down with it, so the scope now
     * has a last-resort handler: the failure is written to the diagnostics log,
     * the two AI-facing states are moved out of `Running` so the UI cannot hang
     * on a spinner forever, and the app keeps running.
     */
    private val crashGuard = CoroutineExceptionHandler { _, t -> containFailure(t) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)

    @Volatile
    private var store: GeminiStore? = null

    private val _settings = MutableStateFlow(AiSettings())
    val settings: StateFlow<AiSettings> = _settings.asStateFlow()

    private val _models = MutableStateFlow<List<GeminiModel>>(emptyList())
    val models: StateFlow<List<GeminiModel>> = _models.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _probe = MutableStateFlow<AiProbe>(AiProbe.Idle)
    val probe: StateFlow<AiProbe> = _probe.asStateFlow()

    private val _messages = MutableStateFlow<List<AiMessage>>(emptyList())
    val messages: StateFlow<List<AiMessage>> = _messages.asStateFlow()

    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    private val _advice = MutableStateFlow<AiAdviceState>(AiAdviceState.Idle)
    val advice: StateFlow<AiAdviceState> = _advice.asStateFlow()

    /**
     * Cache of explanations, keyed by topic + value + language.
     *
     * Reopening the same sheet must not cost another request: these answers do not
     * change, the user's key has a daily quota, and the request only works while the
     * tunnel is up. Bounded, because a session could otherwise walk all ~50 topics
     * in both languages and keep every answer forever.
     */
    private val explainCache = LinkedHashMap<String, String>()
    private const val EXPLAIN_CACHE_MAX = 60

    private var chatJob: Job? = null
    private var adviceJob: Job? = null

    /** Millis of the last automatic analysis, so a reconnect storm cannot spam it. */
    @Volatile
    private var lastAutoAnalysisAt = 0L

    /**
     * Debounced key writes. See [setKey].
     */
    private val keySaves = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Call once from `Application.onCreate`. Idempotent. */
    fun attach(context: Context) {
        if (store != null) return
        val created = GeminiStore(context.applicationContext)
        store = created
        scope.launch {
            keySaves.conflate().debounce(KEY_SAVE_DEBOUNCE_MS).collect { pending ->
                created.saveKey(pending)
            }
        }
        scope.launch {
            created.settings.collect { loaded ->
                _settings.value = loaded
                // Keep the picker populated from the cache while offline, so the
                // model list is never mysteriously empty after a restart.
                if (_models.value.isEmpty() && loaded.discoveredModels.isNotEmpty()) {
                    // Filtered on the way OUT of the cache as well as on the way in.
                    // A cache written by 1.2.8 holds the unfiltered list, and
                    // replaying it would put image and pro models back in the
                    // picker on first launch after the update - the filter has to
                    // hold on every path, not just on refresh. See [AiModelPolicy].
                    _models.value = AiModelPolicy.filterIds(loaded.discoveredModels).map {
                        GeminiModel(it, it, "", 0, 0, chatCapable = true)
                    }
                }
            }
        }
    }

    // ---- settings --------------------------------------------------------

    /**
     * Records a new API key.
     *
     * Debounced, and that is not a micro-optimisation: every save encrypts the
     * value with a hardware-backed Keystore key and commits a SharedPreferences
     * file, so writing on each keystroke of a 39-character key means 39 Keystore
     * operations and 39 disk commits while the user is typing. Same reasoning, and
     * the same conflate + debounce shape, as the profile writer in
     * [studio.cluvex.aether.MainActivity].
     */
    fun setKey(key: String) {
        _probe.value = AiProbe.Idle
        if (key.isBlank()) {
            scope.launch {
                store?.forget()
                _models.value = emptyList()
            }
            return
        }
        keySaves.tryEmit(key)
    }

    fun setModel(id: String) {
        scope.launch { store?.saveModel(id, byUser = true) }
    }

    fun setAutoOptimize(enabled: Boolean) {
        scope.launch { store?.saveAutoOptimize(enabled) }
    }

    fun setAutoApply(enabled: Boolean) {
        scope.launch { store?.saveAutoApply(enabled) }
    }

    fun setShowHints(enabled: Boolean) {
        scope.launch { store?.saveShowHints(enabled) }
    }

    fun forget() {
        scope.launch {
            store?.forget()
            _models.value = emptyList()
            _probe.value = AiProbe.Idle
            _advice.value = AiAdviceState.Idle
            _messages.value = emptyList()
            _queuedQuestion.value = null
            explainCache.clear()
        }
    }

    // ---- gate ------------------------------------------------------------

    fun gate(state: ConnectionState, profile: ConnectionProfile): AiGate {
        val current = _settings.value
        return AiAvailability.evaluate(
            state = state,
            backend = profile.backend,
            hasKey = current.hasKey,
            // A key with no discovered models yet is still usable: discovery is
            // what the user is about to do. Only report NO_MODEL once we know the
            // list and still have nothing selected.
            hasModel = current.effectiveModel.isNotBlank() || _models.value.isEmpty(),
        )
    }

    // ---- model discovery + connection test -------------------------------

    /**
     * Asks the key which models it may use, and remembers the answer.
     *
     * Also auto-selects a model when the user has none: the point of the feature is
     * that entering a key is enough, and making somebody pick from a list of
     * thirty ids before anything works is the kind of setup step people abandon.
     * The preference order is Flash-class first (fast and cheap on a free key),
     * then anything chat-capable.
     */
    fun discoverModels(profile: ConnectionProfile, state: ConnectionState) {
        if (_discovering.value) return
        val current = _settings.value
        val gate = gate(state, profile)
        if (!current.hasKey) {
            _probe.value = AiProbe.Failed("no key", AiErrorKind.BAD_KEY)
            return
        }
        if (gate == AiGate.DISCONNECTED || gate == AiGate.WRONG_MODE) {
            _probe.value = AiProbe.Failed(gate.name, AiErrorKind.TRANSPORT, gate = gate)
            return
        }
        _discovering.value = true
        _probe.value = AiProbe.Running
        scope.launch {
            val port = AiAvailability.socksPort(profile.backend)
            when (val result = GeminiClient.listModels(current.apiKey, port)) {
                is AiResult.Ok -> {
                    // GeminiClient has already applied the allow-list; re-applying
                    // is cheap and means this stays correct if the client ever
                    // stops doing it.
                    _models.value = AiModelPolicy.filter(result.value)
                    store?.saveDiscovered(_models.value.map { it.id })
                    // 1.4.0-r5: the app's own pick (not a deliberate user choice)
                    // always follows the default policy - Gemini 3.1 Flash-Lite
                    // first, for its higher free daily allowance. A model the USER
                    // chose is kept for as long as the key can still use it.
                    val chosenStillOffered = _models.value.any { it.id == current.model }
                    if (current.model.isBlank() || !chosenStillOffered || !current.modelUserPicked) {
                        AiModelPolicy.pickDefault(_models.value)?.let {
                            if (it != current.model || current.modelUserPicked) {
                                store?.saveModel(it, byUser = false)
                            }
                        }
                    }
                    _probe.value = AiProbe.Ok(
                        modelCount = _models.value.size,
                        via = profile.backend.pipelineLabel,
                    )
                    DiagnosticsLog.i("ai", "model discovery: ${_models.value.size} usable model(s)")
                }
                is AiResult.Err -> {
                    _probe.value = AiProbe.Failed(result.message, result.kind)
                    DiagnosticsLog.w("ai", "model discovery failed: ${result.kind}")
                }
            }
            _discovering.value = false
        }
    }

    /**
     * The "Test API connection" button.
     *
     * Deliberately the SAME round trip as discovery rather than a separate ping:
     * listing models is the cheapest authenticated call in the API, and a test that
     * proves the key works while also filling the model picker is one action
     * instead of two that can disagree with each other.
     */
    fun testConnection(profile: ConnectionProfile, state: ConnectionState) {
        discoverModels(profile, state)
    }

    // ---- explain one option ---------------------------------------------

    /**
     * Explains one setting. Blocking work is confined to [Dispatchers.IO].
     *
     * Returns the cached answer instantly when there is one, which is what makes
     * the AI icons feel like part of the UI rather than a network feature.
     */
    suspend fun explain(
        topic: AiTopic,
        profile: ConnectionProfile,
        state: ConnectionState,
        persian: Boolean,
    ): AiResult<String> {
        val current = _settings.value
        val value = topic.profileKey?.let { AiPatch.read(profile, it) }
        val cacheKey = "${topic.id}|$value|${if (persian) "fa" else "en"}|${current.effectiveModel}"
        synchronized(explainCache) { explainCache[cacheKey] }?.let { return AiResult.Ok(it) }

        val gate = gate(state, profile)
        if (!gate.ready) return AiResult.Err(gate.name, AiErrorKind.TRANSPORT)


        return withContext(Dispatchers.IO) {
            val result = GeminiClient.generate(
                apiKey = current.apiKey,
                socksPort = AiAvailability.socksPort(profile.backend),
                model = current.effectiveModel,
                system = AiPrompts.explain(persian, topic, value),
                history = listOf(
                    GeminiTurn(
                        fromUser = true,
                        text = "Explain the option \"${topic.label}\".",
                    ),
                ),
                temperature = 0.2,
                maxOutputTokens = 700,
            )
            if (result is AiResult.Ok) {
                synchronized(explainCache) {
                    explainCache[cacheKey] = result.value
                    while (explainCache.size > EXPLAIN_CACHE_MAX) {
                        val oldest = explainCache.keys.firstOrNull() ?: break
                        explainCache.remove(oldest)
                    }
                }
            }
            result
        }
    }

    // ---- chat ------------------------------------------------------------

    fun send(text: String, profile: ConnectionProfile, state: ConnectionState, persian: Boolean) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _thinking.value) return
        appendMessage(AiMessage(nextId(), fromUser = true, text = prompt))
        dispatch(prompt, profile, state, persian)
    }

    /**
     * Sends [prompt] against the CURRENT conversation without adding a bubble for it.
     *
     * Split out of [send] because retry and edit both need to ask the same question
     * again with the user's bubble already on screen - retry must not duplicate it,
     * and edit has just rewritten it. A retry that appends a second copy of the
     * question is the standard bug in hand-rolled chat screens.
     */
    private fun dispatch(
        prompt: String,
        profile: ConnectionProfile,
        state: ConnectionState,
        persian: Boolean,
    ) {
        val current = _settings.value
        val gate = gate(state, profile)
        if (!gate.ready) {
            appendMessage(
                AiMessage(
                    id = nextId(),
                    fromUser = false,
                    text = "",
                    failed = true,
                    gate = gate,
                    sourcePrompt = prompt,
                ),
            )
            return
        }

        _thinking.value = true
        chatJob?.cancel()
        chatJob = scope.launch {
            // History EXCLUDING failure bubbles: a "you are not connected" notice is
            // an app message, not something the model said, and feeding it back
            // would have the model apologising for an error it never made.
            val history = _messages.value
                .filterNot { it.failed }
                .map { GeminiTurn(fromUser = it.fromUser, text = it.text) }
                .takeLast(MAX_HISTORY_TURNS)
            // r4: one system instruction for every round of this answer, built
            // once - the settings snapshot, the defaults and the live connection
            // status the model reasons over must be identical in the first answer
            // and in its self-correction.
            // 1.4.0-r5 LOG ACCESS FIX. The chat never received the log at all:
            // only the connect-time analyser did. So with "Analyse the log on
            // every connect" ON, the assistant still told users it had no access
            // and asked them to paste the log by hand. The switch now also means
            // "the assistant may read the log": when it is on, a fresh redacted
            // digest (same AiRedaction rules as the analyser - no addresses, no
            // identifiers, the user's own key removed) travels with every chat
            // request. When it is off, nothing is sent and the prompt tells the
            // model exactly how the user can turn access on.
            val logDigest: String? = if (current.autoOptimize) {
                runCatching {
                    AiRedaction.digest(
                        fullLog = DiagnosticsLog.exportText(),
                        ownApiKey = current.apiKey,
                        maxLines = CHAT_LOG_LINES,
                    )
                }.getOrElse { t ->
                    DiagnosticsLog.w("ai", "chat: could not build the redacted log digest: $t")
                    ""
                }
            } else {
                null
            }
            val system = AiPrompts.chat(
                persian = persian,
                profileSnapshot = AiPatch.snapshot(profile),
                model = current.effectiveModel,
                connectionStatus = statusForPrompt(state),
                logAccess = current.autoOptimize,
                logDigest = logDigest,
            )
            suspend fun ask(turns: List<GeminiTurn>, budget: Int) = GeminiClient.generate(
                apiKey = current.apiKey,
                socksPort = AiAvailability.socksPort(profile.backend),
                model = current.effectiveModel,
                system = system,
                history = turns,
                temperature = CHAT_TEMPERATURE,
                maxOutputTokens = budget,
            )
            var result = ask(history, CHAT_TOKENS)
            // A cut-off answer is worth exactly one more try with room to finish.
            // This is the visible half of the truncation bug: the model had used
            // its whole budget and the bubble showed a sentence that stopped
            // mid-word. Retried rather than shown, because a partial answer about
            // a VPN setting is worse than a slightly slower complete one.
            if (result is AiResult.Err && result.kind == AiErrorKind.TRUNCATED) {
                DiagnosticsLog.i("ai", "chat answer was truncated; retrying with a larger budget")
                result = ask(history, CHAT_TOKENS_RETRY)
            }
            when (val outcome = result) {
                is AiResult.Ok -> {
                    var raw = outcome.value
                    var (visible, proposed) = AiPrompts.splitChatReply(raw)
                    // Validate against the allow-list BEFORE the bubble is drawn,
                    // so a change the app would refuse never appears as a button.
                    var patch = AiPatch.apply(profile, proposed)

                    // r4: VERIFY, then let the model correct itself - once.
                    //
                    // Dropping a redundant change from the buttons was never
                    // enough: the TEXT above them still said "set MTU to 1280" to a
                    // user on 1280. So when anything in the proposal fails
                    // verification (already set, invalid value, not writable), the
                    // model gets the verified facts and rewrites the whole answer
                    // before the user sees any of it. The revised answer goes
                    // through the same filter; there is no second round.
                    val feedback = AiPatch.verificationFeedback(profile, patch)
                    if (feedback != null) {
                        DiagnosticsLog.i(
                            "ai",
                            "chat: ${patch.rejected.size} proposal(s) failed verification; " +
                                "asking the model to revise",
                        )
                        val revised = ask(
                            history + GeminiTurn(fromUser = false, text = raw) +
                                GeminiTurn(fromUser = true, text = AiPrompts.chatRevision(feedback)),
                            CHAT_TOKENS_RETRY,
                        )
                        if (revised is AiResult.Ok) {
                            val split = AiPrompts.splitChatReply(revised.value)
                            if (split.first.isNotBlank()) {
                                raw = revised.value
                                visible = split.first
                                proposed = split.second
                                patch = AiPatch.apply(profile, proposed)
                            }
                        } else {
                            DiagnosticsLog.w("ai", "chat: revision round failed; showing the verified original")
                        }
                    }
                    appendMessage(
                        AiMessage(
                            id = nextId(),
                            fromUser = false,
                            text = visible.ifBlank { raw.trim() },
                            changes = patch.applied,
                        ),
                    )
                }
                is AiResult.Err -> appendMessage(
                    AiMessage(
                        id = nextId(),
                        fromUser = false,
                        text = outcome.message,
                        failed = true,
                        errorKind = outcome.kind,
                        sourcePrompt = prompt,
                    ),
                )
            }
            _thinking.value = false
        }
    }

    /**
     * Sends the failed message again.
     *
     * The failure bubble is removed first, so a retry that fails again replaces it
     * rather than stacking a column of identical red bubbles. The prompt comes off
     * the bubble itself ([AiMessage.sourcePrompt]) and only falls back to the
     * nearest preceding user turn for bubbles written by an older version.
     */
    fun retry(
        messageId: Long,
        profile: ConnectionProfile,
        state: ConnectionState,
        persian: Boolean,
    ) {
        if (_thinking.value) return
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val failure = messages[index]
        if (!failure.failed) return
        val prompt = failure.sourcePrompt
            ?: messages.take(index).lastOrNull { it.fromUser }?.text
            ?: return
        _messages.value = messages.filterNot { it.id == messageId }
        dispatch(prompt.trim(), profile, state, persian)
    }

    /**
     * Rewrites one of the user's own messages and asks again from that point.
     *
     * Everything AFTER the edited message is dropped, because it is a conversation
     * that answered a question that no longer exists: leaving the old reply under a
     * changed question is how a chat screen starts lying about what was said. This
     * is the same model Gemini's and ChatGPT's own apps use.
     */
    fun editMessage(
        messageId: Long,
        newText: String,
        profile: ConnectionProfile,
        state: ConnectionState,
        persian: Boolean,
    ) {
        val prompt = newText.trim()
        if (prompt.isEmpty() || _thinking.value) return
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0 || !messages[index].fromUser) return
        if (messages[index].text == prompt) return
        chatJob?.cancel()
        _messages.value = messages.take(index) +
            messages[index].copy(text = prompt, edited = true)
        dispatch(prompt, profile, state, persian)
    }

    /** Deletes one bubble. */
    fun deleteMessage(messageId: Long) = deleteMessages(setOf(messageId))

    /**
     * Deletes a set of bubbles in one pass.
     *
     * A set rather than a loop of single deletes: selecting eleven messages and
     * removing them one at a time would publish eleven list values and animate
     * eleven separate recompositions of a LazyColumn that is being scrolled.
     */
    fun deleteMessages(ids: Set<Long>) {
        if (ids.isEmpty()) return
        _messages.value = _messages.value.filterNot { it.id in ids }
        if (_messages.value.isEmpty()) {
            chatJob?.cancel()
            _thinking.value = false
        }
    }

    fun stop() {
        chatJob?.cancel()
        _thinking.value = false
    }

    fun clearChat() {
        chatJob?.cancel()
        _thinking.value = false
        _messages.value = emptyList()
    }

    // ---- handing an explanation to the chat -------------------------------

    /**
     * A question parked for the chat screen to pick up when it opens.
     *
     * The AI icon on a settings row opens a sheet, and that sheet needs a "I did
     * not understand this - ask the assistant" button. The chat screen does not
     * exist yet at that moment, so the text cannot be handed to it directly; it is
     * left here and consumed on the other side. One slot, not a queue: pressing
     * the button twice means the user wants to ask about the second thing.
     */
    private val _queuedQuestion = MutableStateFlow<String?>(null)
    val queuedQuestion: StateFlow<String?> = _queuedQuestion.asStateFlow()

    fun askInChat(question: String) {
        val text = question.trim()
        if (text.isNotEmpty()) _queuedQuestion.value = text
    }

    /** Returns the parked question once and clears it. */
    fun consumeQueuedQuestion(): String? {
        val pending = _queuedQuestion.value
        _queuedQuestion.value = null
        return pending
    }

    fun markChangesApplied(messageId: Long) {
        _messages.value = _messages.value.map {
            if (it.id == messageId) it.copy(applied = true) else it
        }
    }

    // ---- DPI / log analysis ---------------------------------------------

    /**
     * Reads the session log, asks for an assessment, and optionally applies it.
     *
     * @param auto true when this was triggered by a connect rather than by a tap.
     *   Automatic runs are rate-limited and silently skipped when the feature is
     *   off, because a reconnect loop must never turn into a request loop against
     *   the user's quota.
     * @param onApply invoked with the patched profile when, and only when, the user
     *   has opted into automatic apply. The caller owns the profile, so applying is
     *   its job, not ours.
     */
    fun analyze(
        profile: ConnectionProfile,
        state: ConnectionState,
        persian: Boolean,
        auto: Boolean,
        onApply: (ConnectionProfile) -> Unit = {},
    ) {
        val current = _settings.value
        if (auto && !current.autoOptimize) return
        if (auto) {
            val now = System.currentTimeMillis()
            if (now - lastAutoAnalysisAt < AUTO_ANALYSIS_MIN_GAP_MS) return
            lastAutoAnalysisAt = now
        }
        val gate = gate(state, profile)
        if (!gate.ready) {
            if (!auto) _advice.value = AiAdviceState.Failed(gate.name, gate = gate)
            return
        }
        if (_advice.value is AiAdviceState.Running) return

        _advice.value = AiAdviceState.Running
        adviceJob?.cancel()
        adviceJob = scope.launch {
            // Belt as well as braces. The redactor is hardened line-by-line now
            // (see AiRedaction.safeRedactLine), but building the digest is the one
            // step here that runs BEFORE any network call and therefore before any
            // of the error handling below - so a throw in it used to be fatal
            // rather than merely a failed analysis. It is now an ordinary failure:
            // no digest, no request, the card says so, the tunnel is untouched.
            val digest = runCatching {
                AiRedaction.digest(
                    fullLog = DiagnosticsLog.exportText(),
                    ownApiKey = current.apiKey,
                )
            }.getOrElse { t ->
                DiagnosticsLog.e("ai", "could not build a redacted digest, analysis skipped: $t")
                _advice.value = AiAdviceState.Failed(
                    "log digest failed",
                    reason = AiAdviceProblem.EMPTY_LOG,
                )
                return@launch
            }
            if (digest.isBlank()) {
                _advice.value = AiAdviceState.Failed(
                    "empty log",
                    reason = AiAdviceProblem.EMPTY_LOG,
                )
                return@launch
            }

            /**
             * One analysis round.
             *
             * `jsonOutput` puts the API itself in JSON mode, so the model cannot
             * wrap the object in a fence or introduce it with a sentence - which
             * removes most of what the tolerant extractor in AiPrompts had to
             * survive, and most of what made this answer unparsable.
             */
            suspend fun requestAdvice(budget: Int, strict: Boolean) = GeminiClient.generate(
                apiKey = current.apiKey,
                socksPort = AiAvailability.socksPort(profile.backend),
                model = current.effectiveModel,
                system = AiPrompts.advisor(persian, AiPatch.snapshot(profile), strict = strict),
                history = listOf(GeminiTurn(true, AiPrompts.advisorRequest(digest))),
                // Low temperature: this answer is parsed as JSON and turned into
                // configuration. Creativity is the enemy here.
                temperature = 0.15,
                maxOutputTokens = budget,
                jsonOutput = true,
            )

            // The budget starts where the old code ENDED, because 1400 tokens was
            // the cause rather than a coincidence: the advisor prompt carries a
            // 12,000-character log digest and asks for four prose fields plus up to
            // five change objects, in Persian, where a character costs more tokens
            // than it does in English. See AiErrorKind.TRUNCATED.
            var result = requestAdvice(ADVICE_TOKENS, strict = false)
            if (result is AiResult.Err && result.kind == AiErrorKind.TRUNCATED) {
                DiagnosticsLog.i("ai", "advisor answer was truncated; retrying with a larger budget")
                result = requestAdvice(ADVICE_TOKENS_RETRY, strict = true)
            }
            when (result) {
                is AiResult.Ok -> {
                    var parsed = AiPrompts.parseAdvice(result.value)
                    // The exact JSON the parsed advice came from, so a revision
                    // round shows the model its OWN previous answer.
                    var parsedFrom = result.value
                    if (parsed == null) {
                        // Answered, in JSON mode, and still not the contract. Worth
                        // exactly one stricter re-ask before telling the user the
                        // model is not cooperating; two failures in a row is a
                        // model problem, not a transient one.
                        DiagnosticsLog.w("ai", "advisor answer did not match the contract; re-asking")
                        val second = requestAdvice(ADVICE_TOKENS_RETRY, strict = true)
                        parsed = (second as? AiResult.Ok)?.let { AiPrompts.parseAdvice(it.value) }
                        if (second is AiResult.Ok) parsedFrom = second.value
                    }
                    if (parsed == null) {
                        _advice.value = AiAdviceState.Failed(
                            "unreadable answer",
                            reason = AiAdviceProblem.UNREADABLE,
                        )
                        DiagnosticsLog.w("ai", "advisor answer could not be parsed as JSON")
                        return@launch
                    }
                    var validated = AiPatch.apply(profile, parsed.changes)
                    // r4: same verify-and-revise round as the chat. A proposal that
                    // repeats the user's current value (or names an invalid one) is
                    // handed back with the real values, once, and the corrected
                    // answer replaces the first if it parses.
                    val feedback = AiPatch.verificationFeedback(profile, validated)
                    if (feedback != null) {
                        DiagnosticsLog.i(
                            "ai",
                            "advisor: ${validated.rejected.size} proposal(s) failed verification; re-asking",
                        )
                        val revised = GeminiClient.generate(
                            apiKey = current.apiKey,
                            socksPort = AiAvailability.socksPort(profile.backend),
                            model = current.effectiveModel,
                            system = AiPrompts.advisor(persian, AiPatch.snapshot(profile), strict = true),
                            history = listOf(
                                GeminiTurn(true, AiPrompts.advisorRequest(digest)),
                                GeminiTurn(false, parsedFrom),
                                GeminiTurn(true, AiPrompts.advisorRevision(feedback)),
                            ),
                            temperature = 0.1,
                            maxOutputTokens = ADVICE_TOKENS_RETRY,
                            jsonOutput = true,
                        )
                        val reparsed = (revised as? AiResult.Ok)?.let { AiPrompts.parseAdvice(it.value) }
                        if (reparsed != null) {
                            parsed = reparsed
                            validated = AiPatch.apply(profile, reparsed.changes)
                        }
                    }
                    val advice = parsed.copy(changes = validated.applied)
                    if (validated.rejected.isNotEmpty()) {
                        DiagnosticsLog.i(
                            "ai",
                            "advisor: ${validated.rejected.size} proposal(s) refused by the allow-list",
                        )
                    }
                    val autoApplied = current.autoApply && advice.changes.isNotEmpty()
                    if (autoApplied) onApply(validated.profile)
                    _advice.value = AiAdviceState.Ready(
                        advice = advice,
                        atMillis = System.currentTimeMillis(),
                        applied = autoApplied,
                    )
                    DiagnosticsLog.i(
                        "ai",
                        "advisor: ${advice.changes.size} tuning change(s) proposed" +
                            if (autoApplied) ", applied automatically" else "",
                    )
                }
                is AiResult.Err -> {
                    val outcome = result as AiResult.Err
                    _advice.value = AiAdviceState.Failed(outcome.message, kind = outcome.kind)
                    DiagnosticsLog.w("ai", "advisor failed: ${outcome.kind}")
                }
            }
        }
    }

    fun markAdviceApplied() {
        val current = _advice.value
        if (current is AiAdviceState.Ready) _advice.value = current.copy(applied = true)
    }

    fun dismissAdvice() {
        adviceJob?.cancel()
        _advice.value = AiAdviceState.Idle
    }

    /**
     * r4: the live connection status in words the model can use ("connected",
     * "reconnecting, attempt 2 of 5"). Addresses are deliberately left out.
     */
    private fun statusForPrompt(state: ConnectionState): String = when (state) {
        is ConnectionState.Connected -> "connected"
        is ConnectionState.Reconnecting -> "reconnecting (attempt ${state.attempt} of ${state.maxAttempts})"
        ConnectionState.Verifying -> "tunnel up, end-to-end self-test still running"
        ConnectionState.Connecting, ConnectionState.Launching -> "connecting"
        ConnectionState.Disconnecting -> "disconnecting"
        ConnectionState.Idle -> "disconnected"
        is ConnectionState.Error -> "error"
    }

    // ---- plumbing --------------------------------------------------------

    /**
     * Last-resort landing place for an unhandled throw inside [scope].
     *
     * Called from the scope's [CoroutineExceptionHandler], so by the time we are
     * here the coroutine is already dead - the only jobs left are to say so in the
     * log and to leave no state stuck in `Running`, because a spinner that never
     * resolves is how an invisible crash becomes an unusable screen.
     */
    private fun containFailure(t: Throwable) {
        DiagnosticsLog.e("ai", "AI coroutine failed (contained, app kept running): $t")
        if (_advice.value is AiAdviceState.Running) {
            _advice.value = AiAdviceState.Failed("internal error: $t")
        }
        _thinking.value = false
    }

    private fun appendMessage(message: AiMessage) {
        _messages.value = _messages.value + message
    }

    private var idCounter = 0L

    private fun nextId(): Long = synchronized(this) { ++idCounter }

    /**
     * How many turns of history travel with each request.
     *
     * Bounded because the REST API is stateless: every turn is re-sent every time,
     * so an unbounded conversation grows each request until it is refused for
     * length - over a tunnel, on a metered mobile connection.
     */
    private const val MAX_HISTORY_TURNS = 20

    /** Output budget for a chat answer, and for the one retry after a cut-off. */
    /**
     * r4: 0.6 -> 0.35. The chat's job is precise, repeatable advice about a VPN's
     * settings; at 0.6 the same question could come back with a different
     * recommendation on a retry, which reads as the assistant not knowing.
     */
    private const val CHAT_TEMPERATURE = 0.35

    /**
     * 1.4.0-r5: log lines handed to the chat when log access is on. Smaller than
     * the analyser's 220 because it rides along with EVERY question (and the
     * conversation history); the digest is also capped by characters in
     * [AiRedaction.digest].
     */
    private const val CHAT_LOG_LINES = 160

    private const val CHAT_TOKENS = 2048
    private const val CHAT_TOKENS_RETRY = 4096

    /** Floor between two automatic analyses. A reconnect loop must not spam Google. */
    private const val AUTO_ANALYSIS_MIN_GAP_MS = 90_000L

    /** Output budget for the advisor's JSON answer, and for the stricter re-ask. */
    private const val ADVICE_TOKENS = 3072
    private const val ADVICE_TOKENS_RETRY = 5120

    /** How long a typed key waits before it is sealed and written. */
    private const val KEY_SAVE_DEBOUNCE_MS = 500L
}
