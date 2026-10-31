package studio.cluvex.aether.scanner

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.model.ConnectionState
import java.util.concurrent.atomic.AtomicLong

/** App-owned cancellable IO work. No Activity or scrollable dialog owns a scan. */
object CleanIpScanManager {
    data class Options(val profile: ScanProfile = ScanProfile.DEFAULT,
        val manual: Boolean = true, val ceiling: Int = 2000,
        /** When on, scans use ONLY [customRanges] instead of the built-in Cloudflare ranges. */
        val customEnabled: Boolean = false, val customRanges: String = "")
    internal data class ResultEvent(val id: Long, val report: CleanIpScanEngine.Report)
    internal data class State(val running: Boolean = false, val progress: Int = 0,
        val validating: Boolean = false, val applied: Int = 0, val completed: Int = 0,
        val total: Int = 0, val message: String = "", val messageId: Long = 0,
        val messageConfigs: List<CustomConfig> = emptyList(), val messageTab: String = ConfigCatalog.ALL,
        val messageConnection: ConnectionState? = null, val targetId: String? = null,
        val pending: List<ResultEvent> = emptyList())
    private val mutableState = MutableStateFlow(State())
    internal val state: StateFlow<State> = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = AtomicLong()
    private var job: Job? = null
    const val NOTICE_DURATION_MS = 4_000L
    const val MAX_RANGES_TEXT = 8_000
    init {
        // Process the initial snapshot too: a lazily started collector can first run
        // AFTER a deletion. drop(1) would lose that deletion and leave a stale notice.
        scope.launch {
            CustomConfigStore.state.map { it.configs to it.activeTab }.distinctUntilChanged().collect { _ ->
                invalidateMessage()
                val ids = CustomConfigStore.state.value.configs.map { it.id }.toSet()
                mutableState.update { s -> s.copy(pending = s.pending.filter { it.report.configId in ids }) }
            }
        }
        scope.launch { AetherController.state.collect { invalidateMessage() } }
    }
    internal fun invalidateMessage() {
        mutableState.update { s ->
            val current = CustomConfigStore.state.value
            // A delayed observer of the scanner's own commit must NOT erase a
            // newer completion notice that already describes that commit.
            if (s.messageConfigs != current.configs || s.messageTab != current.activeTab ||
                s.messageConnection != AetherController.state.value) s.copy(message = "") else s
        }
    }
    internal fun clearMessage(expectedId: Long? = null) {
        mutableState.update { s ->
            if (expectedId == null || s.messageId == expectedId) s.copy(message = "") else s
        }
    }
    internal fun postMessage(text: String, durationMs: Long = NOTICE_DURATION_MS) {
        val id = events.incrementAndGet()
        mutableState.update {
            val current = CustomConfigStore.state.value
            it.copy(message = text, messageId = id, messageConfigs = current.configs,
                messageTab = current.activeTab, messageConnection = AetherController.state.value)
        }
        scope.launch { delay(durationMs); clearMessage(id) }
    }
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences("clean-ip-scanner", Context.MODE_PRIVATE)
    fun options(context: Context): Options {
        val p = preferences(context)
        return Options(ScanProfile.fromName(p.getString("profile", null)),
            p.getBoolean("manual", true), p.getInt("ceiling", 2000).coerceIn(50, 5000),
            p.getBoolean("custom_enabled", false),
            p.getString("custom_ranges", "").orEmpty().take(MAX_RANGES_TEXT))
    }
    fun saveOptions(context: Context, options: Options) {
        require(options.ceiling in 50..5000)
        preferences(context).edit().putString("profile", options.profile.name)
            .putBoolean("manual", options.manual).putInt("ceiling", options.ceiling)
            .putBoolean("custom_enabled", options.customEnabled)
            .putString("custom_ranges", options.customRanges.take(MAX_RANGES_TEXT)).apply()
    }
    internal fun allowed(): Boolean = AetherController.state.value.let {
        it is ConnectionState.Idle || it is ConnectionState.Error
    }
    fun supported(config: CustomConfig): Boolean =
        !config.locked && !config.isExpired() && CleanIpEndpoint.describe(config.outbound) != null
    @Synchronized fun stop() { job?.cancel(); CleanIpScanner.closeActiveSockets() }
    internal fun dismissResult(id: Long) {
        mutableState.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }
    }
    fun scanServer(context: Context, config: CustomConfig) = scanServers(context, listOf(config))
    @Synchronized fun scanServers(context: Context, configs: List<CustomConfig>) {
        if (job?.isCompleted == false || mutableState.value.running) return
        if (!allowed() || ConfigPingStore.testingAll.value) {
            postMessage("locked"); return
        }
        val servers = configs.filter(::supported).distinctBy { it.id }
        if (servers.isEmpty()) { postMessage("empty"); return }
        val app = context.applicationContext
        val options = options(app) // One consistent profile and ceiling per group.
        // Custom Cloudflare ranges: when enabled, ONLY these are scanned. Enabled with no
        // valid range means nothing sensible to scan, so refuse instead of silently
        // falling back to the built-in ranges.
        val custom = if (options.customEnabled) CustomIpRanges.rangesOf(options.customRanges) else emptyList()
        if (options.customEnabled && custom.isEmpty()) { postMessage("ranges"); return }
        mutableState.value = State(running = true, total = servers.size, targetId = servers.first().id)
        job = scope.launch {
            val owner = currentCoroutineContext().job
            CleanIpScanner.useCustomRanges(custom)
            val watcher = launch {
                AetherController.state.first { it !is ConnectionState.Idle && it !is ConnectionState.Error }
                owner.cancel(); CleanIpScanner.closeActiveSockets()
            }
            val backend = object : CleanIpScanEngine.Backend {
                override suspend fun currentConfigs() = CustomConfigStore.load(app).configs
                override suspend fun discover(config: CustomConfig, excluded: Set<String>,
                    progress: (Int) -> Unit): List<CleanIpValidation.Candidate> {
                    check(allowed())
                    val endpoint = requireNotNull(CleanIpEndpoint.describe(config.outbound))
                    val p = options.profile
                    val ports = if (endpoint.tls) (listOf(endpoint.port) + CleanIpScanner.DEFAULT_PORTS).distinct()
                        else CleanIpScanner.DEFAULT_PORTS
                    return CleanIpScanner.scanPool(CleanIpValidation.MAX_CANDIDATES, ports,
                        p.timeoutMs, p.probesPerIp, p.parallelism, exclude = excluded,
                        candidateBudget = p.candidateBudget, neighborPerHit = p.neighborPerHit,
                        onProgress = { done, total -> progress(done * 100 / total.coerceAtLeast(1)) })
                        .map { CleanIpValidation.Candidate(it.ip, CleanIpEndpoint.applicablePort(endpoint, it.port)) }
                }
                override suspend fun measure(config: CustomConfig,
                    candidates: List<CleanIpValidation.Candidate>): List<Long> {
                    currentCoroutineContext().ensureActive(); check(allowed())
                    val temporary = candidates.map { candidate -> config.copy(id = java.util.UUID.randomUUID().toString(),
                        outbound = CleanIpEndpoint.replace(config.outbound, candidate.ip, candidate.port)) }
                    val measured = ConfigPingStore.measureEphemeral(app, temporary)
                    return temporary.map { measured[it.id] ?: -1L }
                }
                override suspend fun commit(expected: CustomConfig, pick: CleanIpValidation.Pick) {
                    currentCoroutineContext().ensureActive(); check(allowed())
                    val outbound = CleanIpEndpoint.replace(expected.outbound, pick.ip, pick.port)
                    val raw = JSONObject().put("outbounds", JSONArray().put(JSONObject(outbound))).toString()
                    CustomConfigStore.replaceScanned(app, expected, expected.copy(outbound = outbound,
                        server = "${pick.ip}:${pick.port}", raw = raw, format = ConfigFormat.XRAY_JSON))
                    ConfigPingStore.recordScanned(expected.id, pick.pingMs)
                }
            }
            try {
                CleanIpScanEngine(backend).run(servers, options.ceiling.takeIf { options.manual },
                    onProgress = { p -> mutableState.update { s -> s.copy(targetId = p.targetId,
                        progress = maxOf(if (s.targetId == p.targetId) s.progress else 0, p.percent),
                        validating = p.validating, completed = p.completed) } },
                    onResult = { report ->
                        val event = ResultEvent(events.incrementAndGet(), report)
                        mutableState.update { s -> s.copy(
                            applied = s.applied + if (report.outcome == CleanIpScanEngine.Outcome.APPLIED) 1 else 0,
                            completed = s.completed + 1, pending = s.pending + event) }
                        // 1.6.0: result cards are sticky; only the user's X dismisses them.
                    })
                mutableState.update { it.copy(progress = 100) }; postMessage("finished")
            } catch (e: CancellationException) {
                postMessage("cancelled"); throw e
            } catch (_: Exception) { postMessage("failed")
            } finally {
                watcher.cancel(); CleanIpScanner.closeActiveSockets()
                CleanIpScanner.useCustomRanges(emptyList())
                mutableState.update { it.copy(running = false, validating = false, targetId = null) }
            }
        }
    }
}
