package studio.cluvex.aether.custom

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.DiagnosticsLog
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * The latency and reachability of every saved config, as last measured.
 *
 * In memory only, on purpose: a ping is a statement about THIS network at THIS
 * moment. Persisting it would show a number from the user's home Wi-Fi while they
 * are on mobile data.
 *
 * A measurement never touches the tunnel: [RealDelay] runs the config's own
 * outbound in a separate Xray core (a child of this app, so outside the VPN, like
 * a direct session) and sends one HTTP request through it. A config that carries
 * nothing is reported as no reply; a platform DNS/TCP probe cannot establish
 * whether the encrypted outbound is usable and is not mixed into this metric.
 */
object ConfigPingStore {

    private val _results = MutableStateFlow<Map<String, ConfigPing.Report>>(emptyMap())
    val results: StateFlow<Map<String, ConfigPing.Report>> = _results.asStateFlow()

    private val _testingAll = MutableStateFlow(false)
    val testingAll: StateFlow<Boolean> = _testingAll.asStateFlow()

    /** Serialises test runs, so two taps never start two sets of probe cores. */
    private val running = Mutex()

    fun report(id: String): ConfigPing.Report = _results.value[id] ?: ConfigPing.Report()

    /** Drops every result, e.g. when the network changed. */
    @Synchronized fun retain(ids: Set<String>) { _results.value = _results.value.filterKeys { it in ids } }

    @Synchronized fun clear() {
        _results.value = emptyMap()
    }

    /** Measures one config and publishes its result. */
    suspend fun test(context: Context, config: CustomConfig) {
        running.withLock { run(context.applicationContext, listOf(config)) }
    }

    /** Measures every config in [configs]; results appear one by one as they finish. */
    suspend fun testAll(context: Context, configs: List<CustomConfig>) {
        if (configs.isEmpty() || _testingAll.value) return
        _testingAll.value = true
        try {
            running.withLock { run(context.applicationContext, configs) }
            val ok = _results.value.count { (id, r) -> configs.any { it.id == id } && r.ok }
            DiagnosticsLog.i(TAG, "Real-delay test of ${configs.size} config(s): $ok answered.")
        } finally {
            _testingAll.value = false
        }
    }

    /** Automatic selection waits for a fresh, serialised run instead of using a UI run's stale values. */
    suspend fun measureForSelection(context: Context, configs: List<CustomConfig>): Map<String, ConfigPing.Report> =
        running.withLock {
            if (configs.isEmpty()) return@withLock emptyMap()
            run(context.applicationContext, configs)
            val ids = configs.map { it.id }.toSet()
            _results.value.filterKeys { it in ids }
        }

    /** Scanner candidates: exact real-delay engine, no UI-map publication or direct-network fallback. */
    internal suspend fun measureEphemeral(context: Context, configs: List<CustomConfig>): Map<String, Long> =
        running.withLock {
            if (configs.isEmpty() || !ConfigPing.networkUp(context)) return@withLock emptyMap()
            val probe = XrayProcess(context.applicationInfo.nativeLibraryDir, context.noBackupFilesDir)
            if (!probe.available()) return@withLock emptyMap()
            val launcher = RealDelay.Launcher { json, tag ->
                val process = XrayProcess(context.applicationInfo.nativeLibraryDir, context.noBackupFilesDir)
                process.start(json, tag)
                object : RealDelay.Core {
                    override fun isAlive() = process.isAlive()
                    override fun stop() = process.stop()
                }
            }
            val results = java.util.concurrent.ConcurrentHashMap<String, Long>()
            RealDelay.measureAll(configs.map { RealDelay.Item(it.id, it.outbound) }, launcher,
                withContext(Dispatchers.IO) { physicalDns(context) }, onResult = { id, result ->
                    results[id] = (result as? RealDelay.Result.Ok)?.ms ?: -1L
                })
            results.toMap()
        }

    internal fun recordScanned(id: String, ms: Long) {
        require(ms >= 0)
        publish(id, ConfigPing.Report(ms, ConfigPing.PingStatus.OK, at = now()))
    }

    private suspend fun run(context: Context, configs: List<CustomConfig>) {
        configs.forEach { publish(it.id, report(it.id).copy(status = ConfigPing.PingStatus.TESTING)) }
        val up = withContext(Dispatchers.IO) { ConfigPing.networkUp(context) }
        if (!up) {
            configs.forEach { publish(it.id, failed(ConfigPing.PingStatus.NO_NETWORK)) }
            return
        }
        val runner = XrayProcess(context.applicationInfo.nativeLibraryDir, context.filesDir)
        if (!runner.available()) {
            // Only a build without libxray.so ends up here: say what can be said.
            DiagnosticsLog.w(TAG, XrayProcess.MISSING)
            configs.forEach { publish(it.id, failed(ConfigPing.PingStatus.INVALID)) }
            return
        }
        val byId = configs.associateBy { it.id }
        val dns = withContext(Dispatchers.IO) { physicalDns(context) }
        val launcher = RealDelay.Launcher { json, tag ->
            val process = XrayProcess(context.applicationInfo.nativeLibraryDir, context.filesDir)
            process.start(json, tag)
            object : RealDelay.Core {
                override fun isAlive() = process.isAlive()
                override fun stop() = process.stop()
            }
        }
        val failures = mutableListOf<Pair<CustomConfig, RealDelay.Result>>()
        RealDelay.measureAll(
            items = configs.map { RealDelay.Item(it.id, it.outbound) },
            launcher = launcher,
            networkDns = dns,
            onResult = { id, result ->
                val cfg = byId[id] ?: return@measureAll
                when (result) {
                    is RealDelay.Result.Ok -> {
                        DiagnosticsLog.i(TAG, "Real delay ${cfg.name}: ${result.ms} ms")
                        publish(id, ConfigPing.Report(result.ms, ConfigPing.PingStatus.OK, at = now()))
                    }
                    else -> synchronized(failures) { failures += cfg to result }
                }
            },
        )
        // Preserve the result of the actual through-core request. Plain DNS/TCP probes
        // cannot diagnose VLESS/REALITY reachability and delayed the visible batch result.
        failures.forEach { (cfg, result) ->
            val status = if (result is RealDelay.Result.Invalid) ConfigPing.PingStatus.INVALID else ConfigPing.PingStatus.NO_REPLY
            publish(cfg.id, failed(status))
        }
    }

    /** The resolvers of the networks the phone is really on, outside any VPN. */
    @Suppress("DEPRECATION")
    private fun physicalDns(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching emptyList()
        cm.allNetworks
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            .flatMap { cm.getLinkProperties(it)?.dnsServers.orEmpty() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    private fun failed(status: ConfigPing.PingStatus) = ConfigPing.Report(ConfigPing.FAILED_MS, status, at = now())

    private fun now() = System.currentTimeMillis()

    @Synchronized private fun publish(id: String, report: ConfigPing.Report) {
        if (CustomConfigStore.state.value.configs.none { it.id == id }) return
        _results.value = _results.value + (id to report)
    }

    private const val TAG = "ping"
}
