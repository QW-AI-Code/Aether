package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.GoolMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TransportBackend

/**
 * The rules Smart mode (1.7.0) plans and chooses by.
 *
 * The invariant in [lanes never mix identity families] is the important one: it
 * is what stops two engines writing the same `aether*.toml`. 1.6.0 broke it for
 * gool, which core 2.4.0 runs inside MASQUE on the MASQUE identity.
 */
class SmartPlusPlanTest {

    private val now = 1_758_000_000_000L

    private fun lanes(
        dpi: DpiClass = DpiClass.OPEN,
        mobile: Boolean = false,
        memory: SmartPlusMemory.Entry? = null,
        quic: Boolean? = null,
        classic: Boolean = false,
    ) = SmartPlusPlan.plan(dpi, mobile, memory, now, quic, classic)

    private fun masqueLane(lanes: List<SmartPlusPlan.Lane>) =
        lanes.first { it.tactics.first().family == SmartPlusPlan.Family.MASQUE }

    private fun warpLane(lanes: List<SmartPlusPlan.Lane>) =
        lanes.first { it.tactics.first().family == SmartPlusPlan.Family.WARP }

    // ------------------------------------------------------- the core invariant

    @Test
    fun `lanes never mix identity families`() {
        for (dpi in DpiClass.entries) for (mobile in listOf(true, false)) for (classic in listOf(true, false)) {
            val plan = lanes(dpi, mobile, classic = classic)
            assertEquals("two lanes on $dpi/mobile=$mobile", 2, plan.size)
            for (lane in plan) {
                assertEquals(1, lane.tactics.map { it.family }.distinct().size)
            }
            assertEquals(2, plan.map { it.tactics.first().family }.distinct().size)
        }
    }

    @Test
    fun `modern gool rides the MASQUE identity and classic gool the WARP one`() {
        val modern = lanes(classic = false)
        assertTrue(masqueLane(modern).tactics.any { it.protocol == Protocol.GOOL })
        assertTrue(warpLane(modern).tactics.none { it.protocol == Protocol.GOOL })
        val classic = lanes(classic = true)
        assertTrue(warpLane(classic).tactics.any { it.protocol == Protocol.GOOL })
        assertTrue(masqueLane(classic).tactics.none { it.protocol == Protocol.GOOL })
        assertTrue(SmartPlusPlan.goolClassic(ConnectionProfile(goolMode = GoolMode.CLASSIC)))
        assertFalse(SmartPlusPlan.goolClassic(ConnectionProfile()))
    }

    @Test
    fun `every offered tactic appears exactly once`() {
        val ids = lanes().flatMap { lane -> lane.tactics.map { it.id } }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(SmartPlusPlan.allTactics(DpiClass.OPEN, false).map { it.id }.sorted(), ids.sorted())
    }

    // ---------------------------------------------------------------- fairness

    @Test
    fun `both lanes start at the same instant`() {
        for (dpi in DpiClass.entries) {
            assertTrue(lanes(dpi).all { it.startAfterMs == 0L })
        }
    }

    @Test
    fun `budgets depend on the weight of a tactic, never on its family`() {
        val all = SmartPlusPlan.allTactics(DpiClass.OPEN, false)
        assertEquals(SmartPlusPlan.LIGHT_MS, all.first { it.id == "wg" }.budgetMs)
        assertEquals(SmartPlusPlan.LIGHT_MS, all.first { it.id == "masque.h3" }.budgetMs)
        assertEquals(SmartPlusPlan.LIGHT_MS, all.first { it.id == "masque.h2" }.budgetMs)
        assertEquals(SmartPlusPlan.TWO_HOP_MS, all.first { it.id == "gool" }.budgetMs)
        assertEquals(SmartPlusPlan.TWO_HOP_MS, all.first { it.id == "mim" }.budgetMs)
    }

    @Test
    fun `each lane goes from its lightest tactic to its heaviest`() {
        for (dpi in DpiClass.entries) for (mobile in listOf(true, false)) {
            for (lane in lanes(dpi, mobile)) {
                val hops = lane.tactics.map { if (it.protocol == Protocol.GOOL || it.protocol == Protocol.MIM) 2 else 1 }
                assertEquals("single-hop before two-hop on $dpi", hops.sorted(), hops)
            }
            assertEquals("mim", masqueLane(lanes(dpi, mobile)).tactics.last().id)
        }
    }

    // --------------------------------------------------------- framing ordering

    @Test
    fun `HTTP2 leads on mobile data, where QUIC is unanswered, and HTTP3 otherwise`() {
        assertEquals("masque.h2", masqueLane(lanes(mobile = true)).tactics.first().id)
        assertEquals("masque.h2", masqueLane(lanes(quic = false)).tactics.first().id)
        assertEquals("masque.h3", masqueLane(lanes(quic = true)).tactics.first().id)
        assertEquals("masque.h3", masqueLane(lanes(quic = null)).tactics.first().id)
        val iranSim = SmartPlusPlan.plan(DpiClass.OPEN, false, null, now, quicReachable = true, preferTcp = true)
        assertEquals("an Iranian SIM puts MASQUE over TCP first", "masque.h2", masqueLane(iranSim).tactics.first().id)
        assertEquals(SmartPlusPlan.Family.MASQUE, iranSim.first().tactics.first().family)
    }

    @Test
    fun `the fragmented TCP carrier follows the bare one under SNI filtering`() {
        val ids = masqueLane(lanes(DpiClass.SNI_FILTERING)).tactics.map { it.id }
        assertEquals(listOf("masque.h2", "masque.h2.frag"), ids.take(2))
    }

    @Test
    fun `Smart never turns ECH on by itself`() {
        for (dpi in DpiClass.entries) for (mobile in listOf(true, false)) {
            for (t in SmartPlusPlan.allTactics(dpi, mobile)) {
                assertFalse("${t.id} on $dpi", t.applyTo(ConnectionProfile()).ech)
            }
        }
    }

    // ----------------------------------------------------------------- memory

    @Test
    fun `a fresh proven route leads its lane and its lane is listed first`() {
        val memory = SmartPlusMemory.Entry(tacticId = "masque.h2", provenAtMs = now - 60_000)
        val plan = lanes(memory = memory, quic = true)
        assertEquals(SmartPlusPlan.Family.MASQUE, plan[0].tactics.first().family)
        assertEquals("masque.h2", plan[0].tactics.first().id)
        assertTrue("no serial bet before the race any more", plan.all { it.startAfterMs == 0L })
    }

    @Test
    fun `evidence expires`() {
        val stale = SmartPlusMemory.Entry(tacticId = "masque.h2", provenAtMs = now - SmartPlusMemory.PROVEN_TTL_MS - 1)
        assertEquals("masque.h3", masqueLane(lanes(memory = stale, quic = true)).tactics.first().id)
    }

    @Test
    fun `a recent failure moves to the back of its lane but is not dropped`() {
        val memory = SmartPlusMemory.Entry(tacticId = null, provenAtMs = 0L, failedAtMs = mapOf("wg" to (now - 60_000)))
        val warp = warpLane(lanes(memory = memory))
        assertEquals("wg", warp.tactics.last().id)
    }

    @Test
    fun `a stale failure is ignored again`() {
        val memory = SmartPlusMemory.Entry(
            tacticId = null,
            provenAtMs = 0L,
            failedAtMs = mapOf("wg" to (now - SmartPlusMemory.FAILED_TTL_MS - 1)),
        )
        assertEquals("wg", warpLane(lanes(memory = memory)).tactics.first().id)
    }

    @Test
    fun `a memory naming a tactic this network does not offer is ignored`() {
        val memory = SmartPlusMemory.Entry(tacticId = "no.such.tactic", provenAtMs = now)
        assertEquals(2, lanes(memory = memory).size)
    }

    // ----------------------------------------------------------------- ladder

    @Test
    fun `the serial ladder interleaves the families and keeps HTTP2 MASQUE`() {
        val ladder = SmartPlusPlan.ladder(DpiClass.OPEN, false, null, now, quicReachable = true)
        assertTrue(ladder.any { it.id == "masque.h2" })
        assertEquals(SmartPlusPlan.allTactics(DpiClass.OPEN, false, true).size, ladder.size)
        assertTrue("both families inside the first two rungs", ladder.take(2).map { it.family }.distinct().size == 2)
    }

    // ---------------------------------------------------------------- ranking

    @Test
    fun `lower latency wins between equal throughput`() {
        val h2 = SmartPlusPlan.allTactics(DpiClass.OPEN, false).first { it.id == "masque.h2" }
        val wg = SmartPlusPlan.allTactics(DpiClass.OPEN, false).first { it.id == "wg" }
        val ranked = SmartPlusPlan.rank(
            listOf(
                wg to TunnelQuality(rttMs = 400, downKbps = 8_000, upKbps = 4_000),
                h2 to TunnelQuality(rttMs = 90, downKbps = 8_000, upKbps = 4_000),
            ),
        )
        assertEquals(listOf("masque.h2", "wg"), ranked.map { it.id })
    }

    @Test
    fun `overhead breaks a tie in favour of the lighter route`() {
        val h3 = SmartPlusPlan.allTactics(DpiClass.OPEN, false).first { it.id == "masque.h3" }
        val gool = SmartPlusPlan.allTactics(DpiClass.OPEN, false).first { it.id == "gool" }
        val same = TunnelQuality(rttMs = 120, downKbps = 10_000, upKbps = 5_000)
        assertEquals("masque.h3", SmartPlusPlan.rank(listOf(gool to same, h3 to same)).first().id)
    }

    @Test
    fun `an unmeasured route ranks after every measured one`() {
        val all = SmartPlusPlan.allTactics(DpiClass.OPEN, false)
        val ranked = SmartPlusPlan.rank(
            listOf(
                all.first { it.id == "wg" } to null,
                all.first { it.id == "mim" } to TunnelQuality(900, 500, 200),
            ),
        )
        assertEquals(listOf("mim", "wg"), ranked.map { it.id })
    }

    @Test
    fun `an unmeasured transfer is charged, never free`() {
        val fast = SmartPlusPlan.score(TunnelQuality(100, 20_000, 10_000), 1.0)
        val unknown = SmartPlusPlan.score(TunnelQuality(100, 0, 0), 1.0)
        assertTrue(unknown > fast)
    }

    // -------------------------------------------------------- applying a tactic

    @Test
    fun `a tactic hardens but never weakens what the user chose`() {
        val user = ConnectionProfile(noize = Noize.AGGRESSIVE, fragment = true, ech = true, scanMode = ScanMode.THOROUGH)
        val bare = SmartPlusPlan.allTactics(DpiClass.OPEN, false).first { it.id == "wg" }
        val applied = bare.applyTo(user, bindPort = 19_819)
        assertEquals(Noize.AGGRESSIVE, applied.noize)
        assertTrue(applied.fragment)
        assertTrue("the user's own ECH choice is kept", applied.ech)
        assertEquals(Protocol.WIREGUARD, applied.protocol)
        assertEquals(ScanMode.TURBO, applied.scanMode)
        assertEquals(19_819, applied.bindOverride)
        assertEquals(19_819, applied.bindPort)
    }

    @Test
    fun `an ordinary connect keeps the session port`() {
        val applied = masqueLane(lanes()).tactics.first().applyTo(ConnectionProfile())
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
        assertFalse(SmartPlusPlan.eligible(ConnectionProfile(endpointMode = EndpointMode.MANUAL_PEER)))
        assertFalse(
            SmartPlusPlan.eligible(ConnectionProfile(team = "acme", teamAuth = TeamAuth.EMAIL, accessEmail = "a@b.c")),
        )
        assertFalse(SmartPlusPlan.eligible(ConnectionProfile(backend = TransportBackend.AETHER_TOR)))
        assertTrue(
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
        for (dpi in DpiClass.entries) for (classic in listOf(true, false)) {
            for (lane in lanes(dpi, true, classic = classic)) {
                val worst = lane.startAfterMs + lane.tactics.sumOf { it.budgetMs }
                assertTrue("lane worst case $worst ms on $dpi", worst <= SmartPlusPlan.RACE_CEILING_MS)
            }
        }
        assertTrue(SmartPlusPlan.RACE_CEILING_MS < 330_000L)
    }
}
