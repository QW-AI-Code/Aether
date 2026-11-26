package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 1.7.0: "is the engine READY?" - asked the way the engine actually answers it.
 *
 * ## The bug this replaces
 *
 * Every connect path used to treat an open port as a ready engine:
 *
 * ```
 *   engine-socks-open after=369ms          <- the kernel accepted a TCP handshake
 *   socks5 handshake = false   (+4.0 s)    <- nobody was serving it yet
 *   Self-test failed - refusing to report Connected.
 * ```
 *
 * The engine binds its listener at start-up and serves it only once a tunnel is
 * validated (see [AetherProcess.servingAtMs]). Verifying a cached MASQUE gateway
 * alone takes 2-3.5 s on an Iranian link, a TLS + CONNECT-IP + data-plane check
 * another ~0.4 s, and a fresh scan many seconds more. A single 4-second
 * handshake therefore failed MASQUE "sometimes" (whenever those steps added up
 * to more than four seconds) and failed every WireGuard/MASQUE scan always.
 * That one gate is the reason Smart mode failed 9 times out of 10 in Tehran.
 *
 * ## What this does instead
 *
 * Waits, for the caller's whole budget, until EITHER the engine logs that it is
 * serving and a real SOCKS5 greeting succeeds, OR a periodic short greeting
 * succeeds on its own (engine builds whose serving line differs), and ends at
 * once when the engine process exits - so a dead engine still fails in
 * milliseconds and a working one is detected within ~150 ms of being ready.
 */
object EngineReadiness {

    enum class Outcome { READY, DIED, TIMEOUT }

    private const val TAG = "ready"

    /** Poll period while waiting for the engine's serving line. */
    private const val POLL_MS = 150L

    /** A blind greeting is tried this often even without the serving line. */
    private const val BLIND_GREETING_EVERY_MS = 2_000L

    suspend fun await(
        engine: AetherProcess,
        host: String,
        port: Int,
        budgetMs: Long,
        label: String = "engine",
        abort: () -> Boolean = { false },
    ): Outcome = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + budgetMs.coerceAtLeast(1_000L)
        var lastBlind = 0L
        var lastServingTry = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!engine.isAlive()) {
                engine.fatalLine?.let { DiagnosticsLog.w(TAG, "$label stopped: $it") }
                return@withContext Outcome.DIED
            }
            if (abort()) return@withContext Outcome.TIMEOUT
            val now = SystemClock.elapsedRealtime()
            if (engine.isServing) {
                // The engine says it serves; confirm with a real greeting. Retried
                // (not failed) on a miss: the serving line can precede accept() by
                // a few milliseconds.
                if (now - lastServingTry >= 300L) {
                    lastServingTry = now
                    if (NetProbe.checkSocksHandshake(host, port, 2_000)) {
                        DiagnosticsLog.i(
                            TAG,
                            "$label is serving SOCKS5 on $host:$port after ${now - started} ms.",
                        )
                        return@withContext Outcome.READY
                    }
                }
            } else if (now - lastBlind >= BLIND_GREETING_EVERY_MS) {
                lastBlind = now
                if (NetProbe.checkSocksHandshake(host, port, 1_000)) {
                    DiagnosticsLog.i(
                        TAG,
                        "$label answered SOCKS5 on $host:$port after ${SystemClock.elapsedRealtime() - started} ms.",
                    )
                    return@withContext Outcome.READY
                }
            }
            delay(POLL_MS)
        }
        DiagnosticsLog.w(
            TAG,
            "$label did not start serving SOCKS5 within ${budgetMs / 1000}s (engine still alive, " +
                "still looking for a gateway).",
        )
        Outcome.TIMEOUT
    }
}
