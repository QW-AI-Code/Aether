package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TransportBackend

/**
 * The rules Smart Plus plans by.
 *
 * Every case here is a rule that cannot be checked on a phone without a network
 * that behaves in a particular way — which is the whole reason the planner holds
 * no Android types and does no I/O. The invariant in
 * [lanes never mix identity families] is the important one: it is what stops two
 * engines writing the same `aether*.toml`, and a regression there would not show
 * up as a crash but as a WARP identity that has to re-enrol.
 */
class SmartPlusPlanTest {

    private val now = 1_758_000_000_000L

    private fun race(steps: List<SmartPlusPlan.Step>): SmartPlusPlan.Step.Race =
        steps.filterIsInstance<SmartPlusPlan.Step.Race>().single()

    private fun remembered(steps: List<SmartPlusPlan.Step>): SmartPlusPlan.Step.Remembered? =
        steps.filterIsInstance<SmartPlusPlan.Step.Remembered>().singleOrNull()

    // ------------------------------------------------------- the core invariant

    @Test
    fun `lanes never mix identity families`() {
        for (dpi in DpiClass.entries) {
            for (mobile in listOf(true, false)) {
                val lanes = race(SmartPlusPlan.plan(dpi, mobile, null, now)).lanes
                assertEquals("two lanes on $dpi/mobile=$mobile", 2, lanes.size)
                for (lane in lanes) {
                    assertEquals(
                        "one family per lane on $dpi/mobile=$mobile",
                        1,
                        lane.tactics.map { it.family }.distinct().size,
                    )
                }
                assertEquals(
                    "the two lanes are different families",
                    2,
                    lanes.map { it.tactics.first().family }.distinct().size,
                )
            }
        }
    }

    @Test
    fun `every offered tactic appears exactly once`() {
        val lanes = race(SmartPlusPlan.plan(DpiClass.OPEN, false, null, now)).lanes
        val ids = lanes.flatMap { lane -> lane.tactics.map { it.id } }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(SmartPlusPlan.allTactics(DpiClass.OPEN, false).map { it.id }.sorted(), ids.sorted())
    }

    // ------------------------------------------------------------ lane ordering

    @Test
    fun `the first lane starts at once and the second is staggered`() {
        val lanes = race(SmartPlusPlan.plan(DpiClass.OPEN, false, null, now)).lanes
        assertEquals(0L, lanes[0].startAfterMs)
        assertEquals(SmartPlusPlan.SECOND_LANE_AFTER_MS, lanes[1].startAfterMs)
    }

    @Test
    fun `MASQUE leads where UDP is throttled and WireGuard cannot work`() {
        for (dpi in listOf(DpiClass.UDP_THROTTLED, DpiClass.HOSTILE)) {
            val lanes = race(SmartPlusPlan.plan(dpi, false, null, now)).lanes
            assertEquals(SmartPlusPlan.Family.MASQUE, lanes[0].tactics.first().family)
        }
    }

    @Test
    fun `the WARP family leads on an open network`() {
        for (dpi in listOf(DpiClass.OPEN, DpiClass.SNI_FILTERING)) {
            val lanes = race(SmartPlusPlan.plan(dpi, false, null, now)).lanes
            assertEquals(SmartPlusPlan.Family.WARP, lanes[0].tactics.first().family)
        }
    }

    // --------------------------------------------------------- framing ordering

    @Test
    fun `HTTP2 leads on mobile data and HTTP3 leads elsewhere`() {
        val mobile = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.MASQUE, DpiClass.OPEN, onMobileData = true)
        assertEquals("masque.h2", mobile.first().id)
        val wifi = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.MASQUE, DpiClass.OPEN, onMobileData = false)
        assertEquals("masque.h3", wifi.first().id)
    }

    @Test
    fun `nested MASQUE is never tried first`() {
        for (dpi in DpiClass.entries) {
            for (mobile in listOf(true, false)) {
                val masque = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.MASQUE, dpi, mobile)
                assertEquals("mim", masque.last().id)
                val warp = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.WARP, dpi, mobile)
                assertTrue("gool is never first on $dpi", warp.first().id != "gool")
            }
        }
    }

    // ----------------------------------------------------------------- memory

    @Test
    fun `a fresh proven route goes first, on its own, before the race`() {
        val memory = SmartPlusMemory.Entry(tacticId = "masque.h2", provenAtMs = now - 60_000)
        val steps = SmartPlusPlan.plan(DpiClass.OPEN, onMobileData = true, memory = memory, nowMs = now)
        val first = remembered(steps)!!
        assertEquals("masque.h2", first.tactic.id)
        assertEquals(SmartPlusPlan.REMEMBERED_MS, first.tactic.budgetMs)
        assertTrue("the race still follows it", steps.last() is SmartPlusPlan.Step.Race)
        // ... and its family leads the race, because that is where the evidence is.
        val lanes = race(steps).lanes
        assertEquals(SmartPlusPlan.Family.MASQUE, lanes[0].tactics.first().family)
        assertEquals("masque.h2", lanes[0].tactics.first().id)
    }

    @Test
    fun `evidence expires`() {
        val stale = SmartPlusMemory.Entry(
            tacticId = "masque.h2",
            provenAtMs = now - SmartPlusMemory.PROVEN_TTL_MS - 1,
        )
        val steps = SmartPlusPlan.plan(DpiClass.OPEN, true, stale, now)
        assertEquals(null, remembered(steps))
        assertEquals(1, steps.size)
    }

    @Test
    fun `a route that failed here recently does not get the first slot`() {
        val memory = SmartPlusMemory.Entry(
            tacticId = "masque.h2",
            provenAtMs = now - 60_000,
            failedAtMs = mapOf("masque.h2" to (now - 60_000)),
        )
        val steps = SmartPlusPlan.plan(DpiClass.OPEN, true, memory, now)
        assertEquals("no 45 s bet on a route that just failed", null, remembered(steps))
    }

    @Test
    fun `a recent failure moves to the back of its lane but is not dropped`() {
        val memory = SmartPlusMemory.Entry(
            tacticId = null,
            provenAtMs = 0L,
            failedAtMs = mapOf("wg" to (now - 60_000)),
        )
        val lanes = race(SmartPlusPlan.plan(DpiClass.OPEN, false, memory, now)).lanes
        val warp = lanes.first { it.tactics.first().family == SmartPlusPlan.Family.WARP }
        assertEquals("wg", warp.tactics.last().id)
        assertTrue("still reachable", warp.tactics.any { it.id == "wg" })
    }

    @Test
    fun `a stale failure is ignored again`() {
        val memory = SmartPlusMemory.Entry(
            tacticId = null,
            provenAtMs = 0L,
            failedAtMs = mapOf("wg" to (now - SmartPlusMemory.FAILED_TTL_MS - 1)),
        )
        val lanes = race(SmartPlusPlan.plan(DpiClass.OPEN, false, memory, now)).lanes
        val warp = lanes.first { it.tactics.first().family == SmartPlusPlan.Family.WARP }
        assertEquals("wg", warp.tactics.first().id)
    }

    @Test
    fun `a memory naming a tactic this network does not offer is ignored`() {
        val memory = SmartPlusMemory.Entry(tacticId = "no.such.tactic", provenAtMs = now)
        val steps = SmartPlusPlan.plan(DpiClass.OPEN, false, memory, now)
        assertEquals(null, remembered(steps))
    }

    // -------------------------------------------------------- applying a tactic

    @Test
    fun `a tactic hardens but never weakens what the user chose`() {
        val user = ConnectionProfile(
            noize = Noize.AGGRESSIVE,
            fragment = true,
            ech = true,
            scanMode = ScanMode.THOROUGH,
        )
        val bare = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.WARP, DpiClass.OPEN, false)
            .first { it.id == "wg" }
        val applied = bare.applyTo(user, bindPort = 19_819)
        assertEquals("the stronger obfuscation wins", Noize.AGGRESSIVE, applied.noize)
        assertTrue(applied.fragment)
        assertTrue(applied.ech)
        assertEquals(Protocol.WIREGUARD, applied.protocol)
        assertEquals("a lane always scans in TURBO", ScanMode.TURBO, applied.scanMode)
        assertEquals(19_819, applied.bindOverride)
        assertEquals(19_819, applied.bindPort)
    }

    @Test
    fun `an ordinary connect keeps the session port`() {
        val applied = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.MASQUE, DpiClass.OPEN, false)
            .first()
            .applyTo(ConnectionProfile())
        assertEquals(0, applied.bindOverride)
    }

    @Test
    fun `HTTP2 is only forced on the MASQUE family`() {
        val warp = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.WARP, DpiClass.HOSTILE, true)
        assertTrue(warp.none { it.applyTo(ConnectionProfile()).masqueHttp2 })
        val h2 = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.MASQUE, DpiClass.OPEN, true).first()
        assertTrue(h2.applyTo(ConnectionProfile()).masqueHttp2)
    }

    // ------------------------------------------------------------- eligibility

    @Test
    fun `a default profile can be raced`() {
        assertTrue(SmartPlusPlan.eligible(ConnectionProfile()))
    }

    @Test
    fun `a pinned endpoint, a chain and the e-mail code are not raced`() {
        assertFalse(
            "nothing to search for",
            SmartPlusPlan.eligible(ConnectionProfile(endpointMode = EndpointMode.MANUAL_PEER)),
        )
        assertFalse(
            "one stdin, one code",
            SmartPlusPlan.eligible(
                ConnectionProfile(team = "acme", teamAuth = TeamAuth.EMAIL, accessEmail = "a@b.c"),
            ),
        )
        assertFalse(
            "the chain owns the ports",
            SmartPlusPlan.eligible(ConnectionProfile(backend = TransportBackend.AETHER_TOR)),
        )
        assertTrue(
            "a service token needs no user, so it can be raced",
            SmartPlusPlan.eligible(
                ConnectionProfile(
                    team = "acme",
                    teamAuth = TeamAuth.SERVICE_TOKEN,
                    accessClientId = "id",
                    accessClientSecret = "secret",
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ budgets

    @Test
    fun `a whole race fits inside its ceiling`() {
        val lanes = race(SmartPlusPlan.plan(DpiClass.HOSTILE, true, null, now)).lanes
        for (lane in lanes) {
            val worst = lane.startAfterMs + lane.tactics.sumOf { it.budgetMs }
            assertTrue(
                "a lane's worst case ($worst ms) must fit the ceiling " +
                    "(${SmartPlusPlan.RACE_CEILING_MS} ms), or its last tactic can never run",
                worst <= SmartPlusPlan.RACE_CEILING_MS,
            )
        }
        // The ceiling is what bounds the mode, and it has to be well under the
        // 330 s the Smart ladder can take, or the new mode buys nothing.
        assertTrue(SmartPlusPlan.RACE_CEILING_MS < 330_000L)
    }
}
