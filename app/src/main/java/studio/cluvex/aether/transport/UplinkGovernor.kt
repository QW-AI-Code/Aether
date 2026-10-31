package studio.cluvex.aether.transport

import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Smart Queue Management for the Psiphon uplink (1.4.0-r3).
 *
 * ## The failure it removes (loge2 / loge3, home Wi-Fi + Gemini Live)
 *
 * Gemini Live streams camera + microphone UP. On mobile data the uplink has
 * megabits to spare, so nothing queues (loge1: 150-300 ms for minutes). A home
 * fixed line has a thin upstream behind a CPE with a deep, dumb FIFO. Nothing in
 * the chain knew that rate, so the upload was accepted as fast as the phone could
 * hand it over and the excess piled up in places no counter can see:
 *
 *  1. every app flow is multiplexed onto Psiphon's ONE SSH connection, whose
 *     per-channel window (2 MB in x/crypto/ssh) lets a single uploader park
 *     megabytes in front of every other flow, the SSH keep-alive included;
 *  2. the CPE's upstream FIFO, which turns that excess into seconds of delay and
 *     then tail-drops.
 *
 * The log shows the consequence chain exactly: ping 157 ms -> 1183 / 1476 ms,
 * a stage-1 flow with "NOTHING acknowledged for 8 s", Psiphon "underlying conn is
 * closed" / "sendSshKeepAlive timed out", tunnel torn down, redial, repeat - and
 * recovery the moment Gemini stops uploading. The same happens over Tor because
 * the queue is ABOVE stage 1; the stage-1 transport is irrelevant.
 *
 * ## The fix: SQM at the last point where flows are still separate
 *
 * The standard cure for bufferbloat (RFC 8290 FQ-CoDel, CAKE, and the delay-
 * driven autorate controllers used for variable-rate links) is to move the
 * bottleneck queue onto a device you control: shape egress slightly BELOW the
 * real bottleneck rate so the dumb queue downstream stays empty, and schedule the
 * queue you now own fairly, with sparse (interactive) flows first. Here the only
 * place that still sees individual flows before Psiphon merges them is
 * [PsiphonSocksFront], so that is where the governor sits:
 *
 *  * [UplinkRateController] - a delay-gradient controller. It samples the round
 *    trip THROUGH the whole chain, keeps a windowed baseline, and only when the
 *    uplink is actually loaded AND the delay has risen above the target does it
 *    clamp the rate to a fraction of the measured delivery rate; otherwise it
 *    probes back up and finally opens completely. A fat uplink (mobile, loge1)
 *    never shows load-correlated delay, so it is never limited at all.
 *  * [UplinkShaper] - a token-bucket pacer with start-time fair queueing between
 *    bulk flows and FQ-CoDel's sparse-flow rule: a flow that has sent only a few
 *    bytes recently (DNS, TLS handshakes, the latency probe, a chat message, an
 *    HTTP request) is served immediately, ahead of every bulk flow.
 *
 * Backpressure from the shaper leaves this process the correct way: the
 * uploading app's loopback leg stops being read, hev's TCP window to the app
 * closes, and the app's own congestion control (Gemini's included) adapts its
 * bitrate - instead of the SSH keep-alive timing out behind its queue.
 */
internal object UplinkTuning {
    const val TICK_MS = 250L
    const val PROBE_LOADED_MS = 400L
    const val PROBE_IDLE_MS = 3_000L

    /** Below this delivery rate a delay rise is not attributed to the uplink. */
    const val ENGAGE_BPS = 16.0 * 1024

    /** Never pace below this: 96 kbit/s keeps every interactive flow alive. */
    const val MIN_RATE_BPS = 12.0 * 1024

    /** Above this the shaper is removed entirely. */
    const val OPEN_RATE_BPS = 6.0 * 1024 * 1024

    const val TARGET_MIN_MS = 40.0
    const val TARGET_MAX_MS = 250.0

    /** Delay floor = min of this many recent samples (queue vs jitter). */
    const val FLOOR_SAMPLES = 4

    /** Probe fast for this long after start / a path change to learn the path. */
    const val LEARN_MS = 8_000L
    const val TARGET_FRACTION = 0.25

    const val INCREASE = 1.03
    const val INCREASE_ADD_BPS = 1024.0

    /** Relaxation per idle tick, so a stale clamp dissolves by itself. */
    const val IDLE_RELAX = 1.01

    const val BASELINE_WINDOW_MS = 60_000L

    /** Bulk slice size while pacing: fairness granularity, not a buffer. */
    const val SLICE_BYTES = 8 * 1024

    /** FQ-CoDel sparse-flow rule: first bytes of a burst after >= window idle. */
    const val SPARSE_BYTES = 3_000L
    const val SPARSE_WINDOW_MS = 250L

    /** Most debt sparse grants may run the bucket into, in seconds of rate. */
    const val MAX_DEBT_S = 0.5
}

/**
 * Pure, clock-injected delay-gradient rate controller. No Android, no sockets.
 *
 * Signals (all per RTT sample):
 *  * `floor`    - min of the last [UplinkTuning.FLOOR_SAMPLES] samples. A queue
 *                 raises the FLOOR of the delay; jitter only raises single
 *                 samples. This is what keeps a jittery-but-fat mobile path
 *                 from ever being mistaken for bufferbloat.
 *  * `baseline` - windowed min of clean samples (60 s), reset on a new path.
 *  * `jitter`   - EWMA of |sample-to-sample change| on clean samples; the target
 *                 is never tighter than twice the path's own jitter.
 *
 * Entry (open -> paced) needs the uplink to be loaded AND either a floor over
 * target on two consecutive samples that is still GROWING or stands at 3x
 * target, or two consecutive samples both over 4x target (a deep excursion,
 * caught one sample earlier). Once paced, the rate follows the delay:
 *  * floor over target -> rate = estimated capacity x 0.9 (x 0.5 to drain a deep
 *    queue), capacity estimated from the delivery rate and the delay gradient
 *    (queue growth = (in - out) / out), with a hold-off so cuts never compound;
 *  * floor well under target -> multiplicative probe up (8 % / 3 %);
 *  * uplink idle -> the clamp relaxes by itself and finally opens.
 */
internal class UplinkRateController {
    /** Bytes per second, or [Double.POSITIVE_INFINITY] when open. */
    var rateBps: Double = Double.POSITIVE_INFINITY
        private set
    var baselineMs: Double = Double.NaN
        private set
    var lastRttMs: Double = Double.NaN
        private set
    var jitterMs: Double = 0.0
        private set
    var decreases: Long = 0
        private set

    private class Sample(val atMs: Long, val rttMs: Double, val floorMs: Double)

    private var holdUntilMs = 0L
    private var overCount = 0
    private var jitterN = 0
    private var prevClean = Double.NaN
    private val clean = ArrayDeque<Pair<Long, Double>>()
    private val recent = ArrayDeque<Sample>()

    val limited: Boolean get() = rateBps.isFinite()

    fun targetMs(): Double {
        val b = if (baselineMs.isNaN()) 0.0 else baselineMs
        return maxOf(b * UplinkTuning.TARGET_FRACTION, UplinkTuning.TARGET_MIN_MS, 2 * jitterMs)
            .coerceAtMost(UplinkTuning.TARGET_MAX_MS)
    }

    fun reset() {
        rateBps = Double.POSITIVE_INFINITY
        baselineMs = Double.NaN
        lastRttMs = Double.NaN
        jitterMs = 0.0
        jitterN = 0
        prevClean = Double.NaN
        holdUntilMs = 0
        overCount = 0
        clean.clear()
        recent.clear()
    }

    fun loaded(achievedBps: Double): Boolean =
        if (limited) achievedBps >= rateBps * 0.6 else achievedBps >= UplinkTuning.ENGAGE_BPS

    /**
     * Feeds one round-trip sample. [achievedBps] is the uplink delivery rate the
     * shaper measured over the last second.
     */
    fun onRtt(nowMs: Long, rttMs: Double, achievedBps: Double) {
        lastRttMs = rttMs
        while (recent.isNotEmpty() && nowMs - recent.first().atMs > 3_000L) recent.removeFirst()
        var floor = rttMs
        var counted = 1
        for (i in recent.indices.reversed()) {
            if (counted >= UplinkTuning.FLOOR_SAMPLES || nowMs - recent[i].atMs > 1_600L) break
            floor = min(floor, recent[i].rttMs)
            counted++
        }
        // Fast floor (this + previous sample): only used to catch a DEEP
        // excursion one sample earlier; the slow floor above does the rest.
        val prev = recent.lastOrNull()
        val fastFloor = if (prev != null && nowMs - prev.atMs <= 1_600L) min(rttMs, prev.rttMs) else rttMs
        val isLoaded = loaded(achievedBps)
        updateBaseline(nowMs, rttMs, isLoaded)
        var delta = floor - baselineMs
        val target = targetMs()
        val fastDelta = fastFloor - baselineMs

        val older = recent.lastOrNull { nowMs - it.atMs >= 900L }
        val growing = older != null && floor - older.floorMs > target / 2
        recent.addLast(Sample(nowMs, rttMs, floor))

        overCount = if (delta > target) overCount + 1 else 0
        val engage = limited || fastDelta > 4 * target ||
            (overCount >= 2 && (growing || delta > 3 * target))
        if (!limited) delta = max(delta, fastDelta)

        if (delta > target && isLoaded && engage && nowMs >= holdUntilMs) {
            val capacity = capacityEstimate(nowMs, floor, older, achievedBps)
            val gain = if (delta < 4 * target) 0.9 else 0.5
            rateBps = max(UplinkTuning.MIN_RATE_BPS, capacity * gain)
            decreases++
            holdUntilMs = nowMs + 800L
        } else if (limited && isLoaded && delta < target / 2) {
            val step = if (delta < target / 4) 1.08 else UplinkTuning.INCREASE
            rateBps = rateBps * step + UplinkTuning.INCREASE_ADD_BPS
            openIfFast()
        }
    }

    /**
     * Bottleneck rate from the delay gradient: while a FIFO fills, its delay
     * grows at (in - out) / out per unit time, so out = in / (1 + slope).
     */
    private fun capacityEstimate(nowMs: Long, floor: Double, older: Sample?, achievedBps: Double): Double {
        if (older == null) return achievedBps
        val slope = (floor - older.floorMs) / (nowMs - older.atMs).toDouble()
        val est = if (slope > -0.9) achievedBps / (1 + slope) else achievedBps * 2
        return est.coerceIn(achievedBps * 0.25, achievedBps * 2)
    }

    /** Called every tick; lets an unused clamp dissolve. */
    fun onTick(achievedBps: Double) {
        if (!limited) return
        if (!loaded(achievedBps)) {
            rateBps *= UplinkTuning.IDLE_RELAX
            openIfFast()
        }
    }

    private fun openIfFast() {
        if (rateBps >= UplinkTuning.OPEN_RATE_BPS) rateBps = Double.POSITIVE_INFINITY
    }

    private fun updateBaseline(nowMs: Long, rttMs: Double, isLoaded: Boolean) {
        // Only samples NOT taken on top of our own standing queue count.
        val isClean = !isLoaded || baselineMs.isNaN() || rttMs - baselineMs < targetMs()
        if (isClean) {
            clean.addLast(nowMs to rttMs)
            if (!prevClean.isNaN()) {
                val step = abs(rttMs - prevClean)
                jitterMs = if (jitterN == 0) step else jitterMs * 0.9 + step * 0.1
                jitterN++
            }
            prevClean = rttMs
        }
        while (clean.isNotEmpty() && nowMs - clean.first().first > UplinkTuning.BASELINE_WINDOW_MS) {
            clean.removeFirst()
        }
        val windowMin = clean.minOfOrNull { it.second }
        baselineMs = when {
            windowMin != null -> windowMin
            baselineMs.isNaN() -> rttMs
            else -> baselineMs
        }
        if (rttMs < baselineMs) baselineMs = rttMs
    }
}

/**
 * Token-bucket pacer + start-time fair queueing + sparse-flow priority.
 */
internal class UplinkShaper(private val nanoTime: () -> Long = System::nanoTime) {

    class Flow {
        internal var finishTag = 0.0
        internal var lastActiveMs = Long.MIN_VALUE / 2
        internal var burstBytes = 0L
    }

    private class Waiter(val tag: Double, val seq: Long)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val waiters = PriorityQueue<Waiter>(compareBy<Waiter>({ it.tag }, { it.seq }))
    private var seq = 0L
    private var virtualTime = 0.0
    private var tokens = 0.0
    private var lastRefillNs = nanoTime()

    @Volatile var rateBps: Double = Double.POSITIVE_INFINITY
        private set

    val released = AtomicLong(0)
    val sparseGrants = AtomicLong(0)
    private val active = AtomicBoolean(true)

    fun setRate(bps: Double) {
        lock.withLock {
            refill()
            rateBps = bps
            if (!bps.isFinite()) tokens = 0.0
            changed.signalAll()
        }
    }

    fun shutdown() {
        active.set(false)
        setRate(Double.POSITIVE_INFINITY)
    }

    fun restart() {
        active.set(true)
        setRate(Double.POSITIVE_INFINITY)
    }

    private fun burst(): Double = max(4.0 * 1024, rateBps * 0.02)

    private fun refill() {
        val now = nanoTime()
        if (rateBps.isFinite()) {
            tokens = min(burst(), tokens + (now - lastRefillNs) / 1e9 * rateBps)
        }
        lastRefillNs = now
    }

    /**
     * Blocks until [bytes] may enter the tunnel for [flow]. [priority] forces the
     * sparse path (udpgw DNS frames).
     */
    @Throws(InterruptedException::class)
    fun acquire(flow: Flow, bytes: Int, priority: Boolean = false) {
        if (!rateBps.isFinite() || !active.get()) {
            released.addAndGet(bytes.toLong())
            return
        }
        lock.withLock {
            refill()
            val nowMs = nanoTime() / 1_000_000L
            // FQ-CoDel's sparse-flow rule, per flow: a flow that was idle for a
            // whole window starts a new burst; the first SPARSE_BYTES of it are
            // served ahead of every bulk flow. A continuous uploader spends that
            // once and is bulk from then on, so it cannot farm priority.
            if (nowMs - flow.lastActiveMs > UplinkTuning.SPARSE_WINDOW_MS) flow.burstBytes = 0
            flow.lastActiveMs = nowMs
            flow.burstBytes += bytes
            val sparse = priority || flow.burstBytes <= UplinkTuning.SPARSE_BYTES
            // Sparse traffic is charged to the bucket too, and may run it into
            // debt only up to MAX_DEBT_S of the rate; past that it queues at the
            // HEAD (tag = virtual time) instead of bypassing the shaper.
            if (sparse && tokens >= -rateBps * UplinkTuning.MAX_DEBT_S) {
                tokens -= bytes
                sparseGrants.incrementAndGet()
                released.addAndGet(bytes.toLong())
                return
            }
            val tag = if (sparse) virtualTime else max(virtualTime, flow.finishTag) + bytes
            if (!sparse) flow.finishTag = tag
            val me = Waiter(tag, seq++)
            waiters.add(me)
            try {
                while (true) {
                    if (!rateBps.isFinite() || !active.get()) break
                    refill()
                    if (waiters.peek() === me && tokens >= 0) {
                        tokens -= bytes
                        break
                    }
                    val waitNs = if (waiters.peek() === me) {
                        max(1_000_000L, (-tokens / rateBps * 1e9).toLong())
                    } else {
                        50_000_000L
                    }
                    changed.awaitNanos(waitNs)
                }
            } finally {
                waiters.remove(me)
                virtualTime = max(virtualTime, tag)
                changed.signalAll()
            }
            released.addAndGet(bytes.toLong())
        }
    }
}

/**
 * Wires controller + shaper + an RTT probe into one lifecycle.
 *
 * @param probe one round trip through the full chain, in ms, or null on failure.
 * @param log   line sink (ConnectionLog in production).
 */
internal class UplinkGovernor(
    private val probe: () -> Long?,
    private val log: (String) -> Unit,
) {
    val shaper = UplinkShaper()
    private val controller = UplinkRateController()
    private val running = AtomicBoolean(false)

    /** Loops of a previous session exit even if they outlive stop(). */
    private val generation = AtomicLong(0)
    private var ticker: Thread? = null
    private var prober: Thread? = null

    @Volatile private var achievedBps = 0.0
    @Volatile private var probeStartedMs = 0L
    private var lastReleased = 0L
    private var lastTickMs = 0L
    private var lastReportMs = 0L
    private var peakAchieved = 0.0
    private var lastWatchdogMs = 0L

    private fun nowMs() = System.nanoTime() / 1_000_000L

    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        controller.reset()
        shaper.restart()
        lastReleased = shaper.released.get()
        lastTickMs = nowMs()
        lastReportMs = lastTickMs
        achievedBps = 0.0
        learnUntilMs = lastTickMs + UplinkTuning.LEARN_MS
        val gen = generation.incrementAndGet()
        ticker = Thread({ tickLoop(gen) }, "uplink-governor-tick").apply { isDaemon = true; start() }
        prober = Thread({ probeLoop(gen) }, "uplink-governor-probe").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        shaper.shutdown()
        ticker?.interrupt()
        prober?.interrupt()
        ticker = null
        prober = null
        log(summary("stopped"))
    }

    /** A new Psiphon server is a new path: its baseline and capacity are unknown. */
    fun onPathChanged() {
        synchronized(controller) { controller.reset() }
        learnUntilMs = nowMs() + UplinkTuning.LEARN_MS
        shaper.setRate(Double.POSITIVE_INFINITY)
    }

    private fun tickLoop(gen: Long) {
        try {
            while (running.get() && generation.get() == gen) {
                Thread.sleep(UplinkTuning.TICK_MS)
                val now = nowMs()
                val rel = shaper.released.get()
                val dt = max(1L, now - lastTickMs) / 1000.0
                val inst = (rel - lastReleased) / dt
                lastReleased = rel
                lastTickMs = now
                achievedBps = achievedBps * 0.7 + inst * 0.3
                peakAchieved = max(peakAchieved, achievedBps)
                // A probe stuck behind a queue IS the bloat signal; waiting for
                // it to finish would react seconds late.
                val started = probeStartedMs
                if (started > 0) {
                    val pendingMs = now - started
                    val base = controller.baselineMs
                    val trip = if (base.isNaN()) 1_500.0 else max(1_000.0, 3 * base)
                    if (pendingMs > trip && now - lastWatchdogMs >= 500L) {
                        lastWatchdogMs = now
                        feed(now, pendingMs.toDouble())
                    }
                }
                val wasLimited: Boolean
                val isLimited: Boolean
                synchronized(controller) {
                    wasLimited = controller.limited
                    controller.onTick(achievedBps)
                    isLimited = controller.limited
                }
                if (wasLimited && !isLimited) {
                    shaper.setRate(Double.POSITIVE_INFINITY)
                    log(summary("uplink clamp released - the path carries this load without queueing"))
                } else if (isLimited) {
                    shaper.setRate(controller.rateBps)
                }
                if (isLimited && now - lastReportMs >= 15_000L) {
                    lastReportMs = now
                    log(summary("pacing"))
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun feed(now: Long, rttMs: Double) {
        val wasLimited: Boolean
        val before: Double
        val after: Double
        synchronized(controller) {
            wasLimited = controller.limited
            before = controller.rateBps
            controller.onRtt(now, rttMs, achievedBps)
            after = controller.rateBps
        }
        shaper.setRate(after)
        if (after < before) {
            log(
                summary(
                    if (wasLimited) "queue still building - uplink clamp lowered"
                    else "uplink bufferbloat detected - pacing to the bottleneck instead of queueing in the modem",
                ),
            )
        }
    }

    @Volatile private var learnUntilMs = 0L

    private fun probeLoop(gen: Long) {
        try {
            var lastProbeMs = 0L
            while (running.get() && generation.get() == gen) {
                // 100 ms granules: the cadence follows the load immediately
                // instead of finishing a 3 s idle sleep first.
                Thread.sleep(100L)
                val now = nowMs()
                val fast = now < learnUntilMs || controller.limited ||
                    achievedBps >= UplinkTuning.ENGAGE_BPS
                val interval = if (fast) UplinkTuning.PROBE_LOADED_MS else UplinkTuning.PROBE_IDLE_MS
                if (now - lastProbeMs < interval) continue
                lastProbeMs = now
                probeStartedMs = now
                val rtt = try { probe() } catch (_: Exception) { null }
                probeStartedMs = 0
                if (generation.get() != gen) break
                if (rtt != null && rtt >= 0) feed(nowMs(), rtt.toDouble())
            }
        } catch (_: InterruptedException) {
        }
    }

    fun summary(prefix: String): String {
        val rate = controller.rateBps
        val rateText = if (rate.isFinite()) "${(rate / 1024).toInt()} KB/s" else "open"
        fun ms(v: Double) = if (v.isNaN()) "n/a" else "${v.toInt()} ms"
        return "UplinkGovernor $prefix: rate=$rateText, baseline=${ms(controller.baselineMs)}, " +
            "target=+${controller.targetMs().toInt()} ms, last rtt=${ms(controller.lastRttMs)}, " +
            "uplink=${(achievedBps / 1024).toInt()} KB/s (peak ${(peakAchieved / 1024).toInt()}), " +
            "cuts=${controller.decreases}, sparse grants=${shaper.sparseGrants.get()}"
    }
}
