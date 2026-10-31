package studio.cluvex.aether.transport

import android.net.VpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PortLease
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.PsiphonEngine
import studio.cluvex.aether.core.TunnelConfig
import java.io.File

/**
 * Thrown when a hand-picked Psiphon exit country cannot be served, so the caller
 * rebuilds the chain once with an automatic exit (a working tunnel in the wrong
 * country beats no tunnel - the same promise the app made before 1.4.0).
 */
class PsiphonRegionFallback(message: String) : IllegalStateException(message)

/**
 * The Psiphon stage of `Aether -> Psiphon` and `Tor -> Psiphon`, REWIRED in 1.4.0
 * onto the Psiphon that lives inside the Aether engine (core 2.1.0).
 *
 * ```
 *   engine  --psiphon --psiphon-bind 127.0.0.1:1827 [--psiphon-region CC]
 *           spawns libpsiphon.so (psiphon-tunnel-core), dialling via 1819
 *   front   PsiphonSocksFront -> SOCKS5 127.0.0.1:1825 <- tun2socks talks here
 * ```
 *
 * The app's own Psiphon (the psiphontunnel AAR, its embedded server list and the
 * rotation watchdog) is gone, so two Psiphon implementations can no longer fight
 * over one session. This class starts no Psiphon itself: it waits until the
 * engine's Psiphon is ready ([PsiphonEngine], plus a real CONNECT through it as a
 * fallback), then puts the UDP/DNS-capable [PsiphonSocksFront] in front of it -
 * psiphon-tunnel-core's local proxy is still CONNECT-only.
 */
class PsiphonTransport(
    private val service: VpnService,
    private val region: String,
    private val engineAlive: () -> Boolean,
    private val localSocksPort: Int = PortLease.psiphon,
    private val frontPort: Int = PortLease.chain,
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
) : ExternalTransport {

    override suspend fun start(): Int = withContext(Dispatchers.IO) {
        retireLegacyDataStore()
        val wanted = region.trim().uppercase()
        PsiphonEngine.onServerChanged = { PsiphonSocksFront.onServerRotated() }
        DiagnosticsLog.i(
            TAG,
            "Waiting for the engine's Psiphon on ${TunnelConfig.SOCKS_HOST}:$localSocksPort " +
                "(exit=${ExitRegions.name(wanted)}, front=$frontPort)",
        )
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        var nextProbe = 0L
        while (true) {
            if (!engineAlive()) {
                throw IllegalStateException("The engine exited before its Psiphon hop was ready")
            }
            val state = PsiphonEngine.snapshot
            val failure = state.failure
            if (failure != null) {
                if (wanted.isNotEmpty()) {
                    throw PsiphonRegionFallback(
                        "Psiphon could not establish in ${ExitRegions.name(wanted)} ($failure) - " +
                            "retrying with an automatic exit.",
                    )
                }
                throw IllegalStateException("Psiphon (inside the engine) failed: $failure")
            }
            if (wanted.isNotEmpty() && state.regions.isNotEmpty() && wanted !in state.regions) {
                throw PsiphonRegionFallback(
                    "Psiphon offers no exit in ${ExitRegions.name(wanted)} right now " +
                        "(on offer: ${state.regions.joinToString(" ")}) - retrying with an automatic exit.",
                )
            }
            if (state.ready) break
            val now = System.currentTimeMillis()
            if (now >= nextProbe) {
                nextProbe = now + PROBE_INTERVAL_MS
                // 1.4.0-r1: no bare connect-and-close first; that is what filled the
                // log with "socksPeekByte() failed: EOF" every 4 s.
                if (NetProbe.checkTcpViaProxy(
                        TunnelConfig.SOCKS_HOST, localSocksPort, "1.1.1.1", 80, PROBE_TIMEOUT_MS,
                    )
                ) {
                    break
                }
            }
            if (now >= deadline) {
                if (wanted.isNotEmpty()) {
                    throw PsiphonRegionFallback(
                        "Psiphon found no server in ${ExitRegions.name(wanted)} within " +
                            "${readyTimeoutMs / 1000}s - retrying with an automatic exit.",
                    )
                }
                throw IllegalStateException("Psiphon found no usable server within ${readyTimeoutMs / 1000}s")
            }
            delay(POLL_MS)
        }
        check(PsiphonSocksFront.start(frontPort, localSocksPort)) {
            "Psiphon SOCKS front could not bind ${TunnelConfig.SOCKS_HOST}:$frontPort"
        }
        DiagnosticsLog.i(TAG, "Psiphon is up inside the engine - front $frontPort -> psiphon $localSocksPort")
        frontPort
    }

    /**
     * Usable, not merely present: the engine is alive, the front is up and the
     * engine has not reported its Psiphon as gone. Deliberately no port probe:
     * a bare connect to psiphon's listener every second would be logged by it.
     */
    override fun isAlive(): Boolean =
        engineAlive() && PsiphonSocksFront.isRunning && PsiphonEngine.snapshot.failure == null

    override fun stop() {
        PsiphonEngine.onServerChanged = null
        PsiphonSocksFront.stop()
    }

    /** The AAR's BoltDB datastore (filesDir/psiphon) is dead weight from 1.4.0 on. */
    private fun retireLegacyDataStore() {
        val legacy = File(service.filesDir, "psiphon")
        if (legacy.exists()) {
            runCatching { legacy.deleteRecursively() }
            DiagnosticsLog.i(TAG, "Removed the old in-app Psiphon datastore (replaced by the engine's).")
        }
    }

    private companion object {
        const val TAG = "Transport"
        const val READY_TIMEOUT_MS = 200_000L
        const val POLL_MS = 500L
        const val PROBE_INTERVAL_MS = 4_000L
        const val PROBE_TIMEOUT_MS = 5_000
    }
}
