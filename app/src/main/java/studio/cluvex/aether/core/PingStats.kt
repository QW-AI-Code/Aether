package studio.cluvex.aether.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 1.7.0 (same version): the numbers behind the latency details popup.
 * Pure, so it is unit-tested without a tunnel.
 *
 * Samples are probe results in milliseconds; a negative value is a probe that
 * got no answer and counts as a lost probe.
 */
object PingStats {
    data class Summary(
        val samples: Int = 0,
        val answered: Int = 0,
        val minMs: Long = -1,
        val maxMs: Long = -1,
        val avgMs: Long = -1,
        /** Mean absolute difference of consecutive answered probes (RFC 3550 style, unsmoothed). */
        val jitterMs: Long = -1,
        /** Lost probes in percent of all probes, 0..100. */
        val lossPercent: Int = 0,
        val lastMs: Long = -1,
    )

    fun of(samples: List<Long>): Summary {
        if (samples.isEmpty()) return Summary()
        val ok = samples.filter { it >= 0L }
        val loss = ((samples.size - ok.size) * 100.0 / samples.size).roundToLong().toInt()
        if (ok.isEmpty()) return Summary(samples = samples.size, lossPercent = loss, lastMs = samples.last())
        val jitter = if (ok.size < 2) 0L
            else (ok.zipWithNext { a, b -> abs(b - a) }.sum().toDouble() / (ok.size - 1)).roundToLong()
        return Summary(
            samples = samples.size,
            answered = ok.size,
            minMs = ok.minOrNull() ?: -1L,
            maxMs = ok.maxOrNull() ?: -1L,
            avgMs = (ok.sum().toDouble() / ok.size).roundToLong(),
            jitterMs = jitter,
            lossPercent = loss,
            lastMs = samples.last(),
        )
    }
}
