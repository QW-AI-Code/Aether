package studio.cluvex.aether.core

import java.util.Collections

/**
 * Psiphon as it runs from 1.4.0 on: INSIDE the Aether engine (core 2.1.0), and
 * read off the engine's own stdout.
 *
 * 1.4.0 removed the app's own Psiphon (the psiphontunnel AAR, its embedded server
 * list and the health watchdog that drove it). Core 2.1.0 ships Psiphon itself:
 * started with `--psiphon`, the engine spawns the psiphon-tunnel-core console
 * client (`libpsiphon.so` in this APK) and chains it behind its own exit. The app
 * owns no Psiphon controller any more, so what it needs to know about that hop
 * comes from the lines the engine already prints:
 *
 *  - `[+] psiphon is ready; ...`             the Psiphon tunnel is established
 *  - `[*] psiphon can leave from: AT BE ...` the exit countries on offer
 *  - `[+] psiphon reached a server at X`     a (new) Psiphon server
 *  - `[-] psiphon: <reason>`                 fatal only for the phrases in [FATAL]
 *
 * Pure Kotlin on purpose, like [TorBootstrap], so it is unit-testable.
 */
object PsiphonEngine {

    data class Snapshot(
        val started: Boolean = false,
        val ready: Boolean = false,
        val failure: String? = null,
        val regions: List<String> = emptyList(),
        val server: String? = null,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    /** Invoked when the Psiphon exit server changes after the hop was ready. */
    @Volatile
    var onServerChanged: (() -> Unit)? = null

    /** The app's own health-probe targets, kept for the diagnostics log only. */
    private val selfProbes: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Called when an engine is (re)started: the previous run's state is meaningless. */
    fun reset() {
        snapshot = Snapshot()
    }

    fun noteSelfProbe(host: Any?, port: Any?) {
        selfProbes.add("$host:$port")
    }

    /** Scans one engine stdout line. Every line that is not about Psiphon is ignored. */
    fun ingest(line: String) {
        if (!line.contains("psiphon")) return
        val current = snapshot
        when {
            line.contains(READY) ->
                snapshot = current.copy(started = true, ready = true, failure = null)
            line.contains(REGIONS) -> {
                val list = line.substringAfter(REGIONS).trim()
                    .split(' ', ',', '\t')
                    .map { it.trim().uppercase() }
                    .filter { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
                    .distinct()
                if (list.isNotEmpty()) snapshot = current.copy(regions = list)
            }
            line.contains(SERVER) -> {
                val address = line.substringAfter(SERVER).trim().substringBefore(' ')
                if (address.isNotEmpty()) {
                    snapshot = current.copy(server = address)
                    if (current.ready && current.server != null && current.server != address) {
                        runCatching { onServerChanged?.invoke() }
                    }
                }
            }
            line.contains(ERROR_PREFIX) && FATAL.any { line.contains(it) } -> {
                val reason = line.substringAfter(ERROR_PREFIX).trim().ifEmpty { "psiphon stopped" }
                snapshot = current.copy(ready = false, failure = reason)
            }
            line.contains(STARTING) -> snapshot = current.copy(started = true)
        }
    }

    private const val READY = "[+] psiphon is ready"
    private const val STARTING = "[*] starting psiphon"
    private const val REGIONS = "psiphon can leave from:"
    private const val SERVER = "[+] psiphon reached a server at "
    private const val ERROR_PREFIX = "[-] psiphon: "

    /** The engine's own words for a Psiphon hop that is gone (psiphon.rs, core 2.1.0). */
    private val FATAL = listOf(
        "did not come up in time",
        "stopped before it was ready",
        "psiphon stopped:",
        "would not start",
        "needs the psiphon-tunnel-core console client",
        "psiphon state dir",
        "psiphon config could not be",
        "gave no notice stream",
    )
}
