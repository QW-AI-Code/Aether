package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the per-network memory keeps, and — more importantly — what it lets go.
 *
 * Only the pure parts are covered here ([SmartPlusMemory.Entry] and
 * [SmartPlusMemory.prune]); the read/write path needs a `Context` and this project
 * has no Robolectric, the same reason `AccessTokenTest` tests the parser rather
 * than the store.
 *
 * The expiry cases are the point of the class. Evidence that never expires is how
 * a cache turns into a wrong guess the user cannot clear, and the reference
 * implementation documents what that cost: a phone that had connected at home
 * opened every later session on a filtered mobile network by waiting two and a
 * half minutes for a route that network does not carry.
 */
class SmartPlusMemoryTest {

    private val now = 1_758_000_000_000L

    // -------------------------------------------------------------- freshness

    @Test
    fun `a proven route is trusted for two weeks and not a millisecond longer`() {
        val fresh = SmartPlusMemory.Entry("masque.h2", now - SmartPlusMemory.PROVEN_TTL_MS + 1)
        assertTrue(fresh.isProvenAt(now))
        val stale = SmartPlusMemory.Entry("masque.h2", now - SmartPlusMemory.PROVEN_TTL_MS - 1)
        assertFalse(stale.isProvenAt(now))
    }

    @Test
    fun `an entry with no route is never proven`() {
        assertFalse(SmartPlusMemory.Entry(null, now).isProvenAt(now))
    }

    /**
     * A clock that went backwards (a manual time change, an NTP correction) must
     * not make a memory immortal: the age is negative, and negative is not fresh.
     */
    @Test
    fun `a timestamp in the future is not treated as fresh`() {
        val future = SmartPlusMemory.Entry("wg", now + 60_000)
        assertFalse(future.isProvenAt(now))
    }

    @Test
    fun `failures are remembered for six hours`() {
        val entry = SmartPlusMemory.Entry(
            tacticId = null,
            provenAtMs = 0L,
            failedAtMs = mapOf(
                "wg" to (now - 60_000),
                "gool" to (now - SmartPlusMemory.FAILED_TTL_MS - 1),
            ),
        )
        assertEquals(setOf("wg"), entry.failedIdsAt(now))
    }

    // ------------------------------------------------------------------ prune

    @Test
    fun `pruning drops stale failures`() {
        val map = mapOf(
            "netA" to SmartPlusMemory.Entry(
                "wg",
                now,
                mapOf("gool" to (now - SmartPlusMemory.FAILED_TTL_MS - 1)),
            ),
        )
        val pruned = SmartPlusMemory.prune(map, now)
        assertEquals(emptyMap<String, Long>(), pruned.getValue("netA").failedAtMs)
        assertEquals("the proven route survives", "wg", pruned.getValue("netA").tacticId)
    }

    @Test
    fun `an entry that has nothing left to say is removed`() {
        val map = mapOf(
            "netA" to SmartPlusMemory.Entry(null, 0L, mapOf("wg" to (now - SmartPlusMemory.FAILED_TTL_MS - 1))),
            "netB" to SmartPlusMemory.Entry("wg", now),
        )
        val pruned = SmartPlusMemory.prune(map, now)
        assertEquals(setOf("netB"), pruned.keys)
    }

    @Test
    fun `the file is bounded and keeps the most recent networks`() {
        val map = (1..40).associate { i ->
            "net$i" to SmartPlusMemory.Entry("wg", now - i * 1_000L)
        }
        val pruned = SmartPlusMemory.prune(map, now, max = 32)
        assertEquals(32, pruned.size)
        assertTrue("the newest is kept", pruned.containsKey("net1"))
        assertFalse("the oldest is dropped", pruned.containsKey("net40"))
    }

    @Test
    fun `a network known only by a fresh failure is still worth keeping`() {
        val map = mapOf("netA" to SmartPlusMemory.Entry(null, 0L, mapOf("wg" to now)))
        assertEquals(setOf("netA"), SmartPlusMemory.prune(map, now).keys)
    }

    @Test
    fun `pruning an empty map is an empty map`() {
        assertEquals(emptyMap<String, SmartPlusMemory.Entry>(), SmartPlusMemory.prune(emptyMap(), now))
    }
}
