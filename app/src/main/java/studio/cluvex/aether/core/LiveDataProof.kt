package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.7.0 (same version): the SMART connection gate - replaces the optional
 * "Verify connection" switch for every engine path (Aether, Psiphon, Tor and
 * their chains).
 *
 * ## Why the old verification had to go
 *
 * Field report: with *Verify connection* on, MASQUE never connected; with it off,
 * MASQUE connected at once and browsed fine. The old gate ([Diagnostics.run])
 * demanded ALL of: a geolocation HTTPS answer through the tunnel AND a device DNS
 * answer over SOCKS5 `UDP ASSOCIATE`, retried for up to 90 s. MASQUE (and every
 * carrier whose UDP path warms up later than its TCP path) fails exactly the UDP
 * half for a while, and a geolocation provider that is slow or rate-limited fails
 * the other half - so a tunnel that carried traffic was thrown away, the ladder
 * moved on, and the user waited minutes for nothing. Turning the switch off went
 * to the other extreme: nothing proved that data really flowed.
 *
 * ## What the smart gate proves instead
 *
 * One thing, and the only thing that matters to the user: **application data
 * really crosses the tunnel, both ways**. Three independent targets are raced
 * through the SOCKS5 port every round, each with a real HTTP request whose first
 * response bytes must come back:
 *
 *  * `1.1.1.1:80` by address (no DNS at all),
 *  * `www.gstatic.com/generate_204` by name (remote DNS on the tunnel's path),
 *  * `cp.cloudflare.com/` by name (a second, independent provider).
 *
 * The FIRST answer wins and the session is Connected - typically 0.2-1.5 s after
 * the engine serves. A SOCKS5 "CONNECT succeeded" reply alone never counts (some
 * carriers acknowledge before they dial), so a fake connection cannot pass. A
 * dead engine ends the gate at once. Device DNS over UDP is still measured, in the
 * background, for the diagnostics panel - but it no longer decides anything:
 * Android resolves over DoT/DoH (TCP) when UDP is slow, and that is what made the
 * same MASQUE session work with the switch off.
 */
object LiveDataProof {

    private const val TAG = "proof"

    /** One probe's socket budget (connect, greeting, CONNECT, first bytes). */
    private const val PROBE_TIMEOUT_MS = 5_000

    /** Pause between two rounds while the tunnel is still warming up. */
    private const val ROUND_GAP_MS = 400L

    /** Default patience for a direct engine session (it already SERVES when asked). */
    const val DIRECT_BUDGET_MS = 45_000L

    private data class Target(val host: String, val port: Int, val path: String)

    private val TARGETS = listOf(
        Target("1.1.1.1", 80, "/cdn-cgi/trace"),
        Target("www.gstatic.com", 80, "/generate_204"),
        Target("cp.cloudflare.com", 80, "/"),
    )

    data class Result(val ok: Boolean, val via: String?, val afterMs: Long)

    /** Background work (UDP DNS for the panel) never blocks or outlives a connect's own scope. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Waits until data demonstrably flows through [host]:[port], at most [budgetMs].
     *
     * @param alive ends the wait at once when the engine (or Xray) has exited.
     * @param updateChecks paints the diagnostics circles (session gate only; a
     *   racing lane must not repaint them).
     */
    suspend fun prove(
        host: String,
        port: Int,
        budgetMs: Long = DIRECT_BUDGET_MS,
        alive: () -> Boolean = { true },
        label: String = "tunnel",
        updateChecks: Boolean = true,
    ): Result = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + budgetMs.coerceAtLeast(3_000L)
        if (updateChecks) {
            Diagnostics.resetChecks(host, port)
            DiagnosticsLog.updateCheck(Diagnostics.C_PORT, CheckState.RUNNING)
            DiagnosticsLog.updateCheck(Diagnostics.C_HANDSHAKE, CheckState.RUNNING)
            DiagnosticsLog.updateCheck(Diagnostics.C_TCP, CheckState.RUNNING)
            DiagnosticsLog.updateCheck(Diagnostics.C_DNS, CheckState.RUNNING)
        }
        DiagnosticsLog.i(TAG, "Smart gate: proving that data flows through $host:$port ($label)…")
        var rounds = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!alive()) {
                DiagnosticsLog.w(TAG, "Smart gate: $label stopped while it was being proven.")
                if (updateChecks) failChecks("process exited")
                return@withContext Result(false, null, SystemClock.elapsedRealtime() - started)
            }
            rounds++
            val winner = raceOnce(host, port)
            if (winner != null) {
                val after = SystemClock.elapsedRealtime() - started
                DiagnosticsLog.i(
                    TAG,
                    "Smart gate: data flows through $label via $winner after $after ms (round $rounds) - connected.",
                )
                if (updateChecks) {
                    DiagnosticsLog.updateCheck(Diagnostics.C_PORT, CheckState.PASS, "listening")
                    DiagnosticsLog.updateCheck(Diagnostics.C_HANDSHAKE, CheckState.PASS)
                    DiagnosticsLog.updateCheck(Diagnostics.C_TCP, CheckState.PASS, "data via $winner")
                    DiagnosticsLog.updateCheck(Diagnostics.C_DNS, CheckState.PASS, "HTTP answered")
                    measureUdpDnsInBackground(host, port)
                }
                return@withContext Result(true, winner, after)
            }
            delay(ROUND_GAP_MS)
        }
        DiagnosticsLog.w(
            TAG,
            "Smart gate: no data crossed $label within ${budgetMs / 1000}s ($rounds rounds) - " +
                "the tunnel is not really up, trying the next strategy.",
        )
        if (updateChecks) failChecks("no data within ${budgetMs / 1000}s")
        Result(false, null, SystemClock.elapsedRealtime() - started)
    }

    private fun failChecks(detail: String) {
        DiagnosticsLog.updateCheck(Diagnostics.C_TCP, CheckState.FAIL, detail)
        DiagnosticsLog.updateCheck(Diagnostics.C_DNS, CheckState.FAIL, detail)
        DiagnosticsLog.updateCheck(Diagnostics.C_UDP_DNS, CheckState.FAIL, "skipped")
    }

    /**
     * Races every target once; returns the first that answered, or null when all
     * of them failed. Each probe is bounded by its own socket timeouts.
     */
    private suspend fun raceOnce(host: String, port: Int): String? {
        val winner = CompletableDeferred<String?>()
        val pending = AtomicInteger(TARGETS.size)
        val jobs = TARGETS.map { t ->
            background.launch {
                val ok = runCatching { httpThrough(host, port, t, PROBE_TIMEOUT_MS) }.getOrDefault(false)
                if (ok) {
                    winner.complete("${t.host}:${t.port}")
                } else if (pending.decrementAndGet() == 0) {
                    winner.complete(null)
                }
            }
        }
        val got = withTimeoutOrNull(PROBE_TIMEOUT_MS * 3L + 1_000L) { winner.await() }
        // The losers end on their own socket timeouts; nothing waits for them.
        jobs.forEach { it.cancel() }
        return got
    }

    /** Device DNS over SOCKS5 UDP: measured for the panel, never a gate. */
    private fun measureUdpDnsInBackground(host: String, port: Int) {
        DiagnosticsLog.updateCheck(Diagnostics.C_UDP_DNS, CheckState.RUNNING)
        background.launch {
            var ok = false
            for (attempt in 1..3) {
                ok = runCatching { NetProbe.checkDnsViaSocksUdp(host, port, timeoutMs = 6_000) }.getOrDefault(false)
                if (ok) break
                delay(1_500L)
            }
            DiagnosticsLog.updateCheck(
                Diagnostics.C_UDP_DNS,
                if (ok) CheckState.PASS else CheckState.FAIL,
                if (ok) "resolver answered" else "slow over UDP - apps resolve over TCP (DoT/DoH)",
            )
            DiagnosticsLog.log(
                TAG,
                if (ok) LogLevel.INFO else LogLevel.WARN,
                "device dns via socks5 udp = $ok (informational, not a gate)",
            )
        }
    }

    /**
     * SOCKS5 CONNECT to [t] and one HTTP request; true only when the remote end
     * answered with an HTTP status line, i.e. bytes went out AND came back.
     */
    private fun httpThrough(socksHost: String, socksPort: Int, t: Target, timeoutMs: Int): Boolean {
        Socket().use { s ->
            s.connect(InetSocketAddress(socksHost, socksPort), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            input.readFully(greeting)
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) return false

            val request = ByteArrayOutputStream()
            request.write(byteArrayOf(0x05, 0x01, 0x00))
            val v4 = ipv4Bytes(t.host)
            if (v4 != null) {
                request.write(0x01)
                request.write(v4)
            } else {
                val name = t.host.toByteArray(Charsets.US_ASCII)
                request.write(0x03)
                request.write(name.size)
                request.write(name)
            }
            request.write((t.port shr 8) and 0xFF)
            request.write(t.port and 0xFF)
            out.write(request.toByteArray())
            out.flush()

            val head = ByteArray(4)
            input.readFully(head)
            if (head[0].toInt() != 0x05 || head[1].toInt() != 0x00) return false
            when (head[3].toInt()) {
                0x01 -> input.readFully(ByteArray(4 + 2))
                0x03 -> input.readFully(ByteArray(input.readUnsignedByte() + 2))
                0x04 -> input.readFully(ByteArray(16 + 2))
                else -> return false
            }

            val http = "GET ${t.path} HTTP/1.1\r\nHost: ${t.host}\r\n" +
                "User-Agent: Mozilla/5.0 (Linux; Android) AppleWebKit/537.36\r\n" +
                "Accept: */*\r\nConnection: close\r\n\r\n"
            out.write(http.toByteArray(Charsets.US_ASCII))
            out.flush()
            val buf = ByteArray(16)
            var n = 0
            while (n < 5) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n >= 5 && String(buf, 0, 5, Charsets.US_ASCII) == "HTTP/"
        }
    }

    private fun ipv4Bytes(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((i, p) in parts.withIndex()) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }
}
