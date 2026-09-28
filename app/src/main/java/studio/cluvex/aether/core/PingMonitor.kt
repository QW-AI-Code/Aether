package studio.cluvex.aether.core

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/** Result of a single latency measurement. */
data class PingResult(
    val ms: Long = -1L,
    val running: Boolean = false,
    val error: Boolean = false,
)

/**
 * On-demand TCP latency check, ported from the merged PingRepository and
 * adapted to Aether Mobile's tunnel plumbing ([TunnelConfig]).
 *
 * BATTERY DESIGN: there is deliberately NO periodic polling loop here. A
 * measurement only runs when the user taps the ping badge, or exactly once
 * after a new connection comes up. Each run is a single TCP handshake to an
 * anycast endpoint on 443 with a hard timeout, so one measurement costs one
 * packet round-trip and never keeps the CPU awake.
 *
 * ## ROOT CAUSE fixed in 1.2.8-r3
 *
 * This measured `1.1.1.1:53`. TCP port 53 is refused outright by a large share
 * of Psiphon exits, so on those exits the badge reported `Connect timed out` or
 * a multi-second number on a tunnel that was working perfectly - the "ping goes
 * to 2000" the user was reading off the screen. Worse, every one of those dials
 * was counted by the Psiphon health watchdog (removed in 1.4.0) as one more
 * destination the server had refused, helping to convict a healthy exit and
 * trigger a rotation that killed every live flow on the device.
 *
 * Port 443 is the one port an exit cannot refuse and still be an exit, and the
 * targets are registered as self-probes so they can never be counted as
 * evidence about a server again.
 */
object PingMonitor {
    private val _state = MutableStateFlow(PingResult())
    val state: StateFlow<PingResult> = _state.asStateFlow()

    /** Serialises concurrent taps so two probes can never overlap. */
    private val mutex = Mutex()

    /**
     * SOCKS5 port a tunnelled probe goes through.
     *
     * ROOT CAUSE this fixes: the probe was hardcoded to
     * [TunnelConfig.SOCKS_PORT] (1819), which in a CHAINED `Aether -> Psiphon`
     * session is stage 1's own listener. The badge therefore measured the first
     * hop only and said nothing about the path the user's traffic actually takes -
     * exactly the wrong number to be looking at while diagnosing "the ping is over
     * 1000". [AetherVpnService] publishes the finished pipeline's port here.
     */
    @Volatile
    private var tunnelPort: Int = PortLease.socks

    /** Points tunnelled probes at the port the finished pipeline exposes. */
    fun setTunnelPort(port: Int) {
        if (port in 1..65535) tunnelPort = port
    }

    /** Back to the engine's own listener; called on teardown. */
    fun resetTunnelPort() {
        tunnelPort = PortLease.socks
    }

    /**
     * Endpoints a latency measurement may use, as `host` to `port`.
     *
     * More than one so a single unreachable anycast target cannot make a healthy
     * tunnel look broken - the old single-target probe had no second opinion.
     */
    private val TARGETS = listOf(
        "1.1.1.1" to 443,
        "8.8.8.8" to 443,
        "9.9.9.9" to 443,
    )

    /** The endpoints above, for whoever needs to exempt them from health scoring. */
    fun probeTargets(): List<Pair<String, Int>> = TARGETS

    private const val TIMEOUT_MS = 8_000

    /**
     * A warm probe session: one tunnelled TCP+TLS connection kept alive across
     * measurements so a measurement is a ROUND TRIP, not a connection setup.
     */
    private class Warm(
        val socket: java.net.Socket,
        val input: java.io.InputStream,
        val output: java.io.OutputStream,
        val host: String,
    )

    @Volatile private var warm: Warm? = null

    /** Dial cost of the current warm session. Diagnostic only, never the badge. */
    @Volatile private var lastSetupMs: Long = -1L

    /**
     * Best round trip seen on the current warm session: this path's floor.
     *
     * ROOT CAUSE this fixes in 1.2.8-r7 (screenshot a1: "Latency 6442 ms, Poor",
     * on a session whose every LOGGED probe was 142-238 ms).
     *
     * r6 fixed the sample taken right after a re-warm and left the STEADY-STATE
     * sample - the one the badge actually shows almost all of the time - both
     * unfiltered and, worse, completely unlogged. The fast path at the top of
     * [measure] returns the first number it gets and writes nothing anywhere. So
     * six rounds of logs contain only the well-behaved re-warm samples, while the
     * badge was showing something no log ever recorded. That is why the screenshot
     * and the log could never be reconciled.
     *
     * And the number is not latency. The warm probe rides the same tunnel as
     * everything else, so its HEAD request queues behind whatever is already in
     * the send buffer. 128 KB of queued upload at ~40 KB/s is ~3 s of waiting
     * before the probe's first byte even leaves, which is exactly the shape of
     * 6442 ms on a 200 ms path.
     *
     * A queue that deep means something IS wrong, but "your ping is 6442 ms" is
     * the wrong way to say it and sends every diagnosis to the wrong layer. So:
     * an outlier is now re-checked against this floor before it is published, and
     * every sample is logged with the floor beside it.
     */
    @Volatile private var floorMs: Long = -1L

    /** What it cost to establish the current warm probe session, if any. */
    fun lastSetupMs(): Long = lastSetupMs

    private fun closeWarm() {
        val w = warm ?: return
        warm = null
        floorMs = -1L
        runCatching { w.socket.close() }
    }

    /** Drops the warm session; call whenever the pipeline underneath it changes. */
    fun reset() {
        closeWarm()
        lastSetupMs = -1L
        floorMs = -1L
    }

    /**
     * Opens a warm session: SOCKS5 (or direct) -> TCP -> TLS to [host]:443.
     *
     * The dial is the expensive part and is paid ONCE per session, not once per
     * measurement - which is the entire point of this class now. See [measure].
     */
    private fun openWarm(viaTunnel: Boolean, host: String): Warm? {
        val start = SystemClock.elapsedRealtime()
        val plain = try {
            val s = if (viaTunnel) {
                Socket(
                    Proxy(
                        Proxy.Type.SOCKS,
                        InetSocketAddress(TunnelConfig.SOCKS_HOST, tunnelPort),
                    ),
                )
            } else {
                Socket()
            }
            s.apply {
                connect(InetSocketAddress(host, 443), TIMEOUT_MS)
                tcpNoDelay = true
                soTimeout = TIMEOUT_MS
            }
        } catch (e: Exception) {
            return null
        }
        return try {
            val tls = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory)
                .createSocket(plain, host, 443, true) as javax.net.ssl.SSLSocket
            tls.soTimeout = TIMEOUT_MS
            tls.startHandshake()
            lastSetupMs = SystemClock.elapsedRealtime() - start
            Warm(tls, tls.inputStream, tls.outputStream, host)
        } catch (e: Exception) {
            runCatching { plain.close() }
            null
        }
    }

    /**
     * One application round trip on an ALREADY ESTABLISHED session.
     *
     * `HEAD / HTTP/1.1` + `Connection: keep-alive`, timed from the last byte
     * written to the first byte read. No dial, no handshake, no channel open: one
     * packet out, one packet back, over a connection that already exists. That is
     * a latency measurement.
     *
     * Returns -1 if the session is unusable, so the caller re-warms once.
     */
    private fun roundTrip(w: Warm): Long {
        return try {
        val request = (
            "HEAD / HTTP/1.1\r\n" +
                "Host: ${w.host}\r\n" +
                // No app name on the wire (F-5 follow-up). This ran inside TLS, so
                // it was never operator-visible - but it named the app to every
                // endpoint the latency probe touches, for nothing.
                "User-Agent: Mozilla/5.0\r\n" +
                "Accept: */*\r\n" +
                "Connection: keep-alive\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        w.output.write(request)
        w.output.flush()
        val start = SystemClock.elapsedRealtime()
        val head = ByteArray(1)
        if (w.input.read(head) < 0) return -1L
        val rtt = SystemClock.elapsedRealtime() - start
        // Drain what is buffered so the next round trip starts clean. A HEAD
        // response is a few hundred bytes and carries no body.
        val scratch = ByteArray(2048)
        while (w.input.available() > 0) {
            if (w.input.read(scratch) < 0) break
        }
        rtt
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Measures TCP handshake latency to the first endpoint that answers.
     *
     * @param viaTunnel when true the probe socket is opened THROUGH the local
     * SOCKS5 listener of the running engine, so the number reflects the
     * tunnel's real end-to-end latency; when false it connects directly and
     * shows the operator's latency instead.
     */
    suspend fun pingOnce(viaTunnel: Boolean) {
        if (!mutex.tryLock()) return
        try {
            _state.value = PingResult(running = true)
            val ms = withContext(Dispatchers.IO) { measure(viaTunnel) }
            _state.value = if (ms >= 0) PingResult(ms = ms) else PingResult(error = true)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * ## ROOT CAUSE this fixes (reported directly, screenshot a1: "Latency 5363 ms")
     *
     * This used to open a **brand new connection for every measurement** and
     * report how long the connection took. Through a chained `Aether -> Psiphon`
     * session that number is not latency, it is:
     *
     *   local SOCKS5 handshake
     *     + Psiphon opening a NEW SSH `direct-tcpip` channel across BOTH hops
     *     + the remote TCP handshake
     *
     * The channel open is the expensive term and it queues behind whatever else
     * the tunnel is carrying. So during live dubbing the badge showed **5363 ms
     * while the tunnel was moving data normally in the same second** (a1: 1.2 KB/s
     * down, 12.8 MB / 5.1 MB totals, on a 475 ms endpoint). It was measuring
     * congestion of the control path, and it reported it as the user's ping.
     *
     * It was also causing load, not just misreporting it. At one probe every four
     * seconds it opened **a fresh SSH channel through both hops roughly ninety
     * times per six-minute session, forever**, and the field log carries the
     * bill directly: `channel dial timeout: direct-tcpip`, twice, plus
     * `port forward failures for qkhOScJt: 2`. A diagnostic that generates the
     * failure it then displays is worse than no diagnostic.
     *
     * Now: the session is dialled ONCE and kept warm, and a measurement is an
     * HTTP keep-alive round trip on that existing connection - one packet each
     * way, zero channel opens. The dial cost is still recorded, as
     * [lastSetupMs], where it belongs: it is a useful number about the control
     * path, and it is not the ping.
     */
    /**
     * How far above the session floor a sample has to be before it is treated as
     * suspect rather than reported.
     *
     * Both terms are needed. The multiplier alone would re-check every sample on
     * a genuinely fast path (250 ms is a 5x outlier when the floor is 50 ms, and
     * on mobile it is just jitter); the absolute term alone would let a 3x jump
     * through on a slow path. A sample has to break both to be doubted.
     */
    private const val OUTLIER_FACTOR = 3
    private const val OUTLIER_ABSOLUTE_MS = 400L

    /**
     * Publishes a steady-state round trip, re-checking it if it is an outlier.
     *
     * See [floorMs]. This is the path the badge reads from almost every time, and
     * before r7 it had no verification and no logging at all.
     *
     * An outlier gets exactly ONE confirming sample, and the lower of the two is
     * reported - the same rule r6 applied to a re-warm, for the same reason: if
     * the path really has degraded both samples say so and the badge tells the
     * truth; if it was the probe waiting behind a full send buffer, the second
     * sample is clean. Both numbers always go to the log with the floor next to
     * them, so a screenshot can be reconciled against the log from now on.
     */
    private fun publishFast(w: Warm, first: Long): Long {
        val floor = floorMs
        val suspect = floor >= 0L &&
            first > floor * OUTLIER_FACTOR &&
            first - floor >= OUTLIER_ABSOLUTE_MS

        if (!suspect) {
            if (floor < 0L || first < floor) floorMs = first
            DiagnosticsLog.i(
                "ping",
                "Latency $first ms via ${w.host}:443 (warm round trip, session floor " +
                    "${if (floor >= 0L) "$floor ms" else "n/a"}).",
            )
            return first
        }

        val confirm = roundTrip(w)
        if (confirm < 0L) {
            // Session died mid-check. Report the sample we have and let the next
            // call re-warm; do not silently invent a better number.
            DiagnosticsLog.w(
                "ping",
                "Latency $first ms via ${w.host}:443 was ${first / maxOf(floor, 1L)}x this " +
                    "session's floor of $floor ms, and the confirming probe could not " +
                    "complete. Reporting $first ms unverified.",
            )
            closeWarm()
            return first
        }

        val rtt = minOf(first, confirm)
        if (rtt < floor) floorMs = rtt
        val queueMs = maxOf(first, confirm) - rtt
        DiagnosticsLog.w(
            "ping",
            "Latency spike via ${w.host}:443: first round trip $first ms, confirming " +
                "$confirm ms, session floor $floor ms, reporting $rtt ms. The gap of " +
                "$queueMs ms is this probe waiting behind data already queued in the " +
                "tunnel's send buffer, NOT path latency - cross-check it against " +
                "'unacked-for' and 'socket send-queue' on the [netstack] line for the " +
                "same second.",
        )
        return rtt
    }

    private fun measure(viaTunnel: Boolean): Long {
        // Fast path: a session is already warm, so this is a pure round trip.
        warm?.let { w ->
            val rtt = roundTrip(w)
            if (rtt >= 0L) return publishFast(w, rtt)
            // The peer closed a keep-alive session, which is ordinary. Re-warm
            // once below rather than reporting a failure for it.
            closeWarm()
        }

        var lastError: String? = null
        for ((host, _) in TARGETS) {
            val w = openWarm(viaTunnel, host)
            if (w == null) {
                lastError = "$host:443 dial failed"
                continue
            }
            // ROOT CAUSE fixed in 1.2.8-r6: the FIRST round trip on a session
            // that was just dialled is not a ping either.
            //
            // r5 removed the per-measurement channel dial, which was right, but
            // the sample taken immediately after a RE-warm still rides a
            // connection whose own handshake just queued behind everything the
            // tunnel was carrying. The r5 field log has it twice, 35 s apart, on
            // a path that was fine:
            //
            //   16:29:50  setup 5694 ms, round trip 3092 ms   <- badge said 3092
            //   16:30:25  setup 4496 ms, round trip  334 ms
            //   16:30:30  setup  327 ms, round trip  191 ms
            //
            // The screenshot the user sent is the 3092. The path's actual RTT was
            // ~200 ms; what the probe measured was the tail of its own dial still
            // draining out of the send queue.
            //
            // So a re-warm now takes a confirming sample on the session it just
            // established and reports the lower of the two. If the path really is
            // at three seconds both samples say so and the badge still tells the
            // truth; if it was only the dial, the second sample is clean. Both
            // numbers go in the log so the difference between them stays visible
            // - that difference IS the uplink queue depth, and it is worth more
            // than either number alone.
            val first = roundTrip(w)
            if (first >= 0L) {
                warm = w
                val confirm = roundTrip(w)
                val rtt = if (confirm >= 0L) minOf(first, confirm) else first
                // r7: seed the floor for [publishFast] from the session's own
                // first honest sample rather than leaving it unset.
                floorMs = rtt
                DiagnosticsLog.i(
                    "ping",
                    "Warm latency session up via $host:443 (viaTunnel=$viaTunnel): " +
                        "setup ${lastSetupMs} ms, first round trip $first ms, confirming round " +
                        "trip ${if (confirm >= 0L) "$confirm ms" else "n/a"}, reporting $rtt ms. " +
                        "A large gap between the two is queue left over from the dial, not " +
                        "latency. Further probes reuse this connection, so they cost no " +
                        "channel dial.",
                )
                return rtt
            }
            runCatching { w.socket.close() }
            lastError = "$host:443 no answer on an established session"
        }
        DiagnosticsLog.w("ping", "Latency probe failed (viaTunnel=$viaTunnel): $lastError")
        return -1L
    }
}
