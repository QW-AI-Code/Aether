package studio.cluvex.aether.ai

import org.json.JSONArray
import org.json.JSONObject

/** What the log analysis came back with. */
data class AiAdvice(
    /** What the operator's inspection appears to be doing, in the user's language. */
    val dpi: String,
    /** "high" | "medium" | "low" - how much the log actually supports the reading. */
    val confidence: String,
    /** One short paragraph the user reads first. */
    val summary: String,
    /** Proposed setting changes, already filtered to [AiPatch.WRITABLE] keys. */
    val changes: List<AiChange>,
)

/**
 * Every prompt the app sends, and the parser for what comes back.
 *
 * ## Two rules that shape all of it
 *
 * **1. This is a settings optimiser, not an evasion adviser.** The feature reads
 * the app's own connection log and tunes the app's own options. So the system
 * instructions say that explicitly and bound the model to the options listed in
 * [AiPatch.WRITABLE] - a model asked to "get around DPI" free-form will happily
 * produce confident, wrong, unactionable advice about other people's
 * infrastructure. Asked "which of these 29 knobs best fits the failures in this
 * log", it produces something the app can actually apply and the user can
 * actually judge.
 *
 * **2. Answer in the user's language, ask in ours.** The topic descriptions in
 * [AiTopic] and the setting keys in [AiPatch] are English, because they are
 * technical ground truth and translating them would blur it. The instruction to
 * reply in Persian is separate, so a Persian user gets a Persian answer about an
 * accurately described setting rather than an English answer or an accurate-
 * sounding Persian answer about the wrong thing.
 */
object AiPrompts {

    /** The fenced block the model uses to request setting changes. */
    const val APPLY_FENCE = "aether-apply"

    private fun languageRule(persian: Boolean): String =
        if (persian) {
            "Answer in Persian (فارسی), in a natural, friendly, technically precise " +
                "register. Keep setting names, protocol names, numbers and units in " +
                "Latin script (MTU, MASQUE, WireGuard, 1280) because that is how they " +
                "appear in the app's own UI."
        } else {
            "Answer in English, plainly and precisely."
        }

    private val PRODUCT = """
        You are the built-in assistant of Aether, an Android censorship-circumvention
        VPN app used mainly in Iran. Facts about the app you must not contradict:
        - Two network backends: "Aether" (one hop through a bundled Aether/WARP engine)
          and "Aether -> Psiphon" (the engine runs first as a local SOCKS5 proxy and
          Psiphon dials out through it, so the exit IP is Psiphon's).
        - Transports: MASQUE (QUIC or HTTP/2), WireGuard, and WARP*2 ("gool", WARP in
          WARP), plus a Smart mode that tries strategies and keeps what works.
        - Anti-DPI options it really has: Amnezia-style obfuscation profiles ("noize"),
          TLS ClientHello fragmentation with size and delay ranges, Encrypted Client
          Hello, MTU control, endpoint scanning strategies, and TLS group selection.
        - You are talking to the user THROUGH that tunnel: this conversation only
          works while the app is connected in the chained "Aether -> Psiphon" mode.
    """.trimIndent()

    private val SCOPE = """
        Your job is to OPTIMISE THIS APP'S OWN SETTINGS for the network the user is
        on. Diagnose what the app's log shows and map it onto the app's options. Do
        not speculate about, or give instructions for, attacking or defeating any
        third party's infrastructure - you have no information about it and the app
        has no controls for it. If the log does not support a conclusion, say so
        instead of inventing one.
    """.trimIndent()

    /**
     * r4: how the assistant must reason before it recommends anything.
     *
     * The field complaint this answers: the assistant "suggested" settings the
     * user already had - e.g. "set MTU to 1280" to someone on 1280 - which is the
     * fastest way to lose a user's trust in every other thing it says. The
     * settings block was always in the prompt; nothing told the model to CHECK
     * against it. So the check is now an explicit, ordered procedure (done
     * silently, reported only as its result), and the app verifies the outcome
     * again in code (AiPatch.verificationFeedback + one self-correction round in
     * AiSession) - the prompt is the first line of defence, not the only one.
     */
    private val GROUNDING = """
        HOW TO THINK BEFORE YOU ANSWER (do this silently; show only the result):
        1. Read THE USER'S CURRENT SETTINGS below. They are the ground truth about
           this device right now. Each line says whether the value is the app's
           default or was changed by the user.
        2. Work out the most likely cause from what the user said (and the log, if
           one is given). Rank candidate fixes by how likely they are to help.
        3. For EVERY candidate change, compare it with the current value. If the
           user already has that value, it is NOT a fix - drop it. If it was your
           best idea, say plainly that it is already set (e.g. "your MTU is already
           1280, so that is not the cause") and move on to the next candidate.
        4. Check the candidate actually applies to the current mode (backend and
           protocol): do not recommend a knob that has no effect in that mode. If
           you are not sure whether it applies, say so instead of guessing.
        5. Prefer ONE or TWO changes at a time, most likely first, and say how the
           user will know whether it helped. Changing five things at once makes it
           impossible to learn what worked.
        6. Never invent settings, values, menu names, features, numbers or facts.
           If the information needed is not here, say what you would need to know,
           or ask ONE short clarifying question.
        7. If everything relevant already looks right, say so honestly and suggest
           the next diagnostic step instead of inventing a change.
    """.trimIndent()

    /**
     * r4: answer formatting, modelled on Gemini's own app. The app renders this
     * Markdown properly (AiMarkdown + AiRichText), so structure is welcome - but
     * only the subset that renders well on a phone.
     */
    private val FORMAT = """
        FORMATTING (the app renders Markdown like the Gemini app does):
        - Open with a direct one- or two-sentence answer. No preamble, no "Great
          question", no restating the question.
        - For anything longer, use short sections with a "### " title, or a bold
          mini-title on its own line (**Like this**), then one to three sentences.
        - Use "- " bullets for options or facts and "1. " numbered steps for things
          to do in order. One level of nesting at most.
        - Put setting names and key values in **bold**; put exact values the user
          types (1280, X25519:P-256) in `backticks`.
        - Short paragraphs (one to three sentences). A blank line between blocks.
        - No tables unless you are comparing three or more options side by side.
        - Never wrap the whole answer in a code block; no emoji clutter; no
          closing sign-off.
    """.trimIndent()

    /** System instruction for the "what is this setting" sheet. */
    fun explain(persian: Boolean, topic: AiTopic, currentValue: String?): String = """
        $PRODUCT

        ${languageRule(persian)}

        The user tapped the AI icon next to one specific option and wants to
        understand it. Explain it in exactly three parts and nothing else. Each
        part starts with a bold mini-title on its own line, followed by one to
        three short sentences (or up to three "- " bullets):
        **What it is**
        **What it is for** - what problem it solves.
        **How to use it** - when to change it, what to set it to, and the
        trade-off. If the sensible answer is "leave it alone", say that.
        Translate the three mini-titles into the answer language.

        Be concrete and short: about 120 words in total. No preamble, no
        pleasantries, no "#" headings, no bullet nesting, no closing line.

        Take the user's CURRENT value into account: say what it means for them.
        If their current value is already the sensible choice, say so explicitly.
        Never tell them to set the option to the value it already has.

        THE OPTION (this description is authoritative - do not contradict it and do
        not guess beyond it):
        name: ${topic.label}
        behaviour: ${topic.detail}
        ${if (currentValue != null) "the user's current value: $currentValue" else ""}
    """.trimIndent()

    /**
     * System instruction for the DPI / log analysis.
     *
     * @param strict added on a re-ask after an answer that could not be parsed.
     *   Kept out of the first attempt on purpose: a wall of "DO NOT" makes shorter,
     *   more timid answers, and the first attempt is the one that succeeds almost
     *   every time now that the request runs in JSON mode with a real token budget.
     */
    fun advisor(persian: Boolean, profileSnapshot: String, strict: Boolean = false): String = """
        $PRODUCT

        $SCOPE

        $GROUNDING

        ${languageRule(persian)}

        You will be given a REDACTED excerpt of the app's own connection log (public
        IP addresses are masked to a /16 and credentials are removed) plus the user's
        current settings. Work out what the operator's traffic inspection appears to
        be doing to THIS connection - for example: blocking UDP or QUIC, resetting on
        the TLS ClientHello, blocking specific edge IP ranges, throttling after a
        volume, breaking long-lived connections, or nothing unusual at all - and
        propose the settings that fit that evidence.

        Reply with ONE JSON object and nothing else. No prose, no code fence:
        {
          "dpi": "one or two sentences: what the inspection appears to be doing",
          "confidence": "high" | "medium" | "low",
          "summary": "one short paragraph for the user, plain language",
          "changes": [
            { "key": "<setting key>", "value": "<new value>", "why": "one short sentence" }
          ]
        }

        Rules for "changes":
        - Only these keys, with these accepted values:
        ${AiPatch.WRITABLE.entries.joinToString("\n        ") { "  ${it.key}: ${it.value}" }}
        - Propose at most 5 changes, and only ones the log actually justifies. An
          empty "changes" array is the correct answer when the connection looks
          healthy - say so in "summary" and do not invent work.
        - Never propose a value the user already has: compare every change with
          THE USER'S CURRENT SETTINGS before you write it. A change whose value
          equals the current one is an error, and the app will reject it.
        - Every change must be tied to something specific in the log; the "why"
          names that evidence in one short sentence.
        - If the obvious fix is already in place, say so in "summary" and propose
          the next most likely one instead (or none).
        - "dpi", "summary" and every "why" must be in the user's language, plain
          sentences without Markdown; "key" and "value" must be exactly as listed
          above.

        THE USER'S CURRENT SETTINGS:
        $profileSnapshot
        ${if (strict) STRICT_JSON else ""}
    """.trimIndent()

    /**
     * The extra pressure applied only on a re-ask.
     *
     * "Shorter" is the operative instruction, not "please comply": the previous
     * attempt failed because the answer did not fit, so asking for the same answer
     * more firmly would fail the same way.
     */
    private val STRICT_JSON = """

        THIS IS A SECOND ATTEMPT. The previous answer could not be used. Output the
        JSON object and NOTHING else - no code fence, no leading sentence, no
        trailing note. Keep every string SHORT: "dpi" at most two sentences,
        "summary" at most three, each "why" one clause. Emit at most 3 changes. A
        complete short answer is required; a long truncated one is worthless.
    """.trimIndent()

    /** System instruction for the in-app chat. */
    fun chat(
        persian: Boolean,
        profileSnapshot: String,
        model: String,
        connectionStatus: String = "",
        logAccess: Boolean = false,
        logDigest: String? = null,
    ): String = """
        $PRODUCT

        $SCOPE

        $GROUNDING

        $FORMAT

        ${languageRule(persian)}

        You are answering inside the app's own chat screen. You are $model. Be
        useful, precise and brief: say exactly what is needed, no filler, no
        self-description unless asked. You may answer general questions too, not
        only questions about the app. Stay consistent with what you said earlier
        in this conversation; if you got something wrong before, correct it
        explicitly.
        ${if (connectionStatus.isNotBlank()) "Connection status right now: $connectionStatus." else ""}

        SETTING CHANGES. Whenever you recommend a concrete change to one of the
        settings listed below - whether or not the user asked for it - append at
        the very END of your reply one fenced block with exactly those changes (the
        user still has to press a button to apply them, and the app checks each
        one against the current settings):

        ```$APPLY_FENCE
        {"changes":[{"key":"mtu","value":"1280","why":"short reason"}]}
        ```

        - Put your normal answer above the block; the app strips the block out and
          turns it into a button the user has to press. Never claim you already
          changed something - you are proposing it.
        - Only these keys and values are possible:
        ${AiPatch.WRITABLE.entries.joinToString("\n        ") { "  ${it.key}: ${it.value}" }}
        - Anything else - the network backend, the upstream proxy, routing rules,
          which apps are tunnelled, pinning a manual endpoint, organization
          credentials - you CANNOT change, by design, because those decide which
          traffic is protected and where it goes. If asked, explain where to change
          it by hand instead: Settings, then the relevant page.
        - Tunnel settings are handed to the engine when it starts, so an applied
          change takes effect on the NEXT connect. Say so when it matters.
        - Never put a change in the block whose value equals the current one.
          Do not add the block at all when you are only explaining something.

        ${chatLogSection(logAccess, logDigest)}

        THE USER'S CURRENT SETTINGS:
        $profileSnapshot
    """.trimIndent()

    /**
     * 1.4.0-r5: what the chat model is told about the app's log.
     *
     * Three states, each with an explicit instruction, because the bug this fixes
     * was the model defaulting to "I have no access, please paste it" even when
     * the user had granted access:
     *  - access ON and a digest attached: read it, never ask for a paste;
     *  - access ON but the log is still empty: say so (nothing to read yet);
     *  - access OFF: do not pretend to have read it, and point to the switch.
     *
     * Every digest line is prefixed with [LOG_INDENT]: trimIndent() runs on the
     * interpolated string, so an unindented log line would drop the common indent
     * to zero and leave the whole prompt indented.
     */
    private fun chatLogSection(logAccess: Boolean, logDigest: String?): String =
        chatLogText(logAccess, logDigest).replace("\n", "\n" + LOG_INDENT)

    private fun chatLogText(logAccess: Boolean, logDigest: String?): String = when {
        logAccess && !logDigest.isNullOrBlank() -> buildString {
            append("APP LOG ACCESS: ON. The user has allowed you to read this app's own ")
            append("connection log. A fresh, redacted excerpt of the current session is ")
            append("included below and is refreshed with every message. Use it whenever ")
            append("the user asks about the log, errors, connection problems or speed. ")
            append("NEVER say you have no access to the log and NEVER ask the user to ")
            append("copy or send it - you already have it. Addresses and identifiers ")
            append("appear as [REDACTED]/masked; that is intentional, do not ask for them. ")
            append("Quote the relevant lines briefly when they support your answer.\n")
            append("--- BEGIN APP LOG (redacted, most recent last) ---\n")
            append(logDigest)
            append("\n--- END APP LOG ---")
        }
        logAccess -> "APP LOG ACCESS: ON, but the log of this session is empty so far " +
            "(nothing has been recorded yet). Say so if the user asks about the log; do " +
            "not ask them to paste it."
        else -> "APP LOG ACCESS: OFF. You have NOT been given the app's log. If the user " +
            "asks you to check the log, say that log access is turned off and that they " +
            "can turn on \"Analyse the log on every connect\" in Settings > Aether AI; " +
            "once it is on you read the log automatically. Do not claim to have read it."
    }

    /**
     * The template above is indented by 8 spaces and trimIndent() strips the
     * common indent; continuation lines of an interpolated block must carry the
     * same 8 spaces or the whole prompt keeps its indentation.
     */
    private const val LOG_INDENT = "        "

    /**
     * r4: the self-correction turn.
     *
     * Sent - invisibly to the user - when the reply just produced failed the
     * app's own verification (a proposed value the user already has, a value the
     * option does not accept, a key the model may not touch). The model rewrites
     * its WHOLE answer with the verified facts in front of it, so the text the
     * user reads never recommends something the button underneath it would
     * refuse. One round only: the second answer is shown whatever it says, with
     * the same code-level filter on its buttons.
     */
    fun chatRevision(feedback: String): String = """
        AUTOMATIC CHECK BY THE APP (not written by the user): your previous reply
        failed verification against the user's real settings:
        $feedback

        Rewrite your whole reply for the user now. Keep the same language and
        format. Do not mention this check or apologise for it. Do not recommend
        anything the user already has - where it matters, state that it is
        already set - and offer the next most likely fix instead, or say honestly
        that the settings already look right. Only include the fenced
        `$APPLY_FENCE` block if a valid, non-redundant change remains.
    """.trimIndent()

    /** r4: the same self-correction for the advisor's JSON answer. */
    fun advisorRevision(feedback: String): String = """
        AUTOMATIC CHECK BY THE APP: some entries in your "changes" failed
        verification against the user's real settings:
        $feedback

        Answer again with the complete JSON object. Remove or replace those
        entries; never propose a value the user already has. If the obvious fix is
        already in place, say so in "summary". JSON only.
    """.trimIndent()

    /**
     * Builds the user turn that carries the log for [advisor].
     *
     * The instruction stays in English even for a Persian user: it is addressed to
     * the model, not to the user, and the language the ANSWER comes back in is set
     * once, in the system instruction.
     */
    fun advisorRequest(logDigest: String): String = buildString {
        append("Here is the log of my current session. Analyse it and answer with the JSON object.")
        append("\n\n--- BEGIN LOG (redacted) ---\n")
        append(logDigest)
        append("\n--- END LOG ---")
    }

    // ---- parsing ---------------------------------------------------------

    /**
     * Parses the advisor's JSON answer.
     *
     * Tolerant on purpose. Models wrap JSON in fences, prepend "Here you go:", and
     * occasionally emit a trailing comma; a strict parse would turn a perfectly
     * usable answer into "something went wrong", so the outermost balanced object
     * is extracted first and only then parsed. If that fails there is genuinely
     * nothing to use, and null is returned so the caller can show the raw text.
     */
    fun parseAdvice(raw: String): AiAdvice? {
        val json = extractJsonObject(raw) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val changes = parseChanges(obj.optJSONArray("changes"))
        val summary = obj.optString("summary").trim()
        val dpi = obj.optString("dpi").trim()
        if (summary.isEmpty() && dpi.isEmpty() && changes.isEmpty()) return null
        return AiAdvice(
            dpi = dpi,
            confidence = obj.optString("confidence").trim().lowercase(),
            summary = summary,
            changes = changes,
        )
    }

    /**
     * Splits a chat reply into the prose the user sees and the changes it proposes.
     *
     * The block is REMOVED from the visible text: leaving raw JSON in a chat bubble
     * is how an assistant announces that it is a script. The user sees a sentence
     * and a button.
     */
    fun splitChatReply(raw: String): Pair<String, List<AiChange>> {
        val fenceStart = raw.indexOf("```$APPLY_FENCE")
        if (fenceStart < 0) return raw.trim() to emptyList()
        val bodyStart = raw.indexOf('\n', fenceStart)
        if (bodyStart < 0) return raw.trim() to emptyList()
        val fenceEnd = raw.indexOf("```", bodyStart)
        val block = if (fenceEnd < 0) raw.substring(bodyStart) else raw.substring(bodyStart, fenceEnd)
        val visible = (
            raw.substring(0, fenceStart) +
                if (fenceEnd < 0) "" else raw.substring(fenceEnd + 3)
            ).trim()
        val json = extractJsonObject(block) ?: return visible to emptyList()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return visible to emptyList()
        return visible to parseChanges(obj.optJSONArray("changes"))
    }

    private fun parseChanges(array: JSONArray?): List<AiChange> {
        if (array == null) return emptyList()
        val out = mutableListOf<AiChange>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val key = item.optString("key").trim()
            if (key.isEmpty()) continue
            // Numbers and booleans arrive unquoted about half the time; optString
            // coerces both, which is why the whole pipeline speaks text.
            val value = item.optString("value").trim()
            out += AiChange(key = key, value = value, why = item.optString("why").trim())
        }
        return out
    }

    /** Returns the first balanced `{...}` in [raw], or null. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
