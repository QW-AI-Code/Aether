package studio.cluvex.aether.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The uplink governor must do two things and never the opposite of either:
 *  * on a thin uplink that is being over-driven (home Wi-Fi + Gemini Live, loge2)
 *    it must clamp to the bottleneck and keep the queue short;
 *  * on a fat uplink (mobile, loge1) it must stay open, jitter or not.
 *
 * Driven by a tiny fluid model of a FIFO bottleneck: rtt = base + queue / cap.
 */
class UplinkGovernorTest {

    private class Result(val maxQueueMsAfterSettle: Double, val deliveredBps: Double, val everLimited: Boolean)

    private fun simulate(capBps: Double, demandBps: Double, baseMs: Double, jitterMs: Double, seed: Int = 1): Result {
        val rnd = Random(seed)
        val c = UplinkRateController()
        var q = 0.0
        var achieved = 0.0
        var released = 0.0
        var lastReleased = 0.0
        var nextTick = 250L
        var lastProbe = 0L
        var maxQ = 0.0
        var delivered = 0.0
        var loadMs = 0L
        var everLimited = false
        var t = 0L
        while (t < 60_000L) {
            t += 10
            val demand = if (t > 5_000L) demandBps else 2_048.0
            val send = minOf(demand, c.rateBps) * 0.01
            released += send
            q = (q + send - capBps * 0.01).coerceIn(0.0, capBps * 20)
            if (t >= nextTick) {
                val inst = (released - lastReleased) / 0.25
                lastReleased = released
                achieved = achieved * 0.7 + inst * 0.3
                c.onTick(achieved)
                nextTick += 250
            }
            val fast = t < 8_000L || c.limited || achieved >= UplinkTuning.ENGAGE_BPS
            if (t - lastProbe >= if (fast) 400 else 3_000) {
                lastProbe = t
                val rtt = baseMs + q / capBps * 1000 + rnd.nextDouble() * jitterMs
                c.onRtt(t, rtt, achieved)
            }
            everLimited = everLimited || c.limited
            if (t > 20_000L) maxQ = maxOf(maxQ, q / capBps * 1000)
            if (t > 12_000L) {
                delivered += minOf(demand, c.rateBps)
                loadMs += 1
            }
        }
        return Result(maxQ, delivered / loadMs, everLimited)
    }

    @Test
    fun thinUplinkIsClampedAndQueueStaysShort() {
        // 40 KB/s upstream, 120 KB/s of camera + mic: the loge2 shape.
        val r = simulate(capBps = 40.0 * 1024, demandBps = 120.0 * 1024, baseMs = 160.0, jitterMs = 30.0)
        assertTrue(r.everLimited)
        // Unmanaged this queue reaches 20 s (the CPE FIFO cap in the model);
        // settled, it must stay far under the Psiphon SSH keep-alive budget.
        assertTrue("queue ${r.maxQueueMsAfterSettle} ms", r.maxQueueMsAfterSettle < 1_500.0)
        assertTrue("delivered ${r.deliveredBps}", r.deliveredBps > 0.6 * 40 * 1024)
    }

    @Test
    fun fatUplinkIsNeverThrottledMeaningfully() {
        for (seed in 1..8) {
            val r = simulate(capBps = 1_500.0 * 1024, demandBps = 120.0 * 1024, baseMs = 160.0, jitterMs = 80.0, seed = seed)
            assertTrue("seed $seed delivered ${r.deliveredBps}", r.deliveredBps > 0.85 * 120 * 1024)
        }
    }

    @Test
    fun cleanFatUplinkStaysOpen() {
        val r = simulate(capBps = 1_500.0 * 1024, demandBps = 120.0 * 1024, baseMs = 160.0, jitterMs = 0.0)
        assertFalse(r.everLimited)
    }

    @Test
    fun idleClampDissolves() {
        val c = UplinkRateController()
        var t = 0L
        repeat(10) { c.onRtt(t, 150.0, 0.0); t += 400 }
        // Growing queue under load.
        var rtt = 150.0
        repeat(12) { rtt += 150; c.onRtt(t, rtt, 100.0 * 1024); t += 400 }
        assertTrue(c.limited)
        repeat(4_000) { c.onTick(0.0) }
        assertFalse(c.limited)
    }

    @Test
    fun sparseFlowOvertakesBulk() {
        val shaper = UplinkShaper()
        shaper.setRate(16.0 * 1024)
        val bulk = UplinkShaper.Flow()
        val sparse = UplinkShaper.Flow()
        // Put the bucket into debt with bulk traffic.
        val th = Thread { repeat(8) { shaper.acquire(bulk, 8 * 1024) } }.apply { start() }
        Thread.sleep(200)
        val t0 = System.nanoTime()
        shaper.acquire(sparse, 200)
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("sparse waited $waitedMs ms", waitedMs < 700)
        shaper.shutdown()
        th.join(5_000)
    }
}
