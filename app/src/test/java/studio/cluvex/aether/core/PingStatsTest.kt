package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PingStatsTest {
    @Test fun `summary of a mixed series`() {
        val s = PingStats.of(listOf(100L, 120L, -1L, 80L))
        assertEquals(4, s.samples)
        assertEquals(3, s.answered)
        assertEquals(80L, s.minMs)
        assertEquals(120L, s.maxMs)
        assertEquals(100L, s.avgMs)
        assertEquals(30L, s.jitterMs) // |120-100| = 20, |80-120| = 40 -> 30
        assertEquals(25, s.lossPercent)
        assertEquals(80L, s.lastMs)
    }

    @Test fun `empty and all lost`() {
        assertEquals(0, PingStats.of(emptyList()).samples)
        val lost = PingStats.of(listOf(-1L, -1L))
        assertEquals(100, lost.lossPercent)
        assertEquals(-1L, lost.avgMs)
    }
}
