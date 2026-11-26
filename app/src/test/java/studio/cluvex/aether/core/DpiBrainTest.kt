package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol

/** The Smart brain (1.7.0, same version): listen, diagnose, learn, adapt. */
class DpiBrainTest {

    private val now = 1_758_000_000_000L

    private fun masqueLane() = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.MASQUE, DpiClass.OPEN, onMobileData = false)

    private fun warpLane() = SmartPlusPlan.tacticsFor(SmartPlusPlan.Family.WARP, DpiClass.OPEN, onMobileData = false)

    @Test
    fun `the field log UTF-8 death is read as a damaged identity`() {
        val line = "Error: Io(Error { kind: InvalidData, message: \"stream did not contain valid UTF-8\" })"
        val bits = DpiBrain.signalsOf(line)
        assertTrue(bits and DpiBrain.S_IDENTITY != 0)
        assertEquals(
            DpiBrain.Verdict.IDENTITY_DAMAGED,
            DpiBrain.diagnose(bits, died = true, aliveMs = 400, ridesUdp = true, fatal = line),
        )
    }

    @Test
    fun `a recovered identity is not a symptom`() {
        val line = "[-] identity file aether.toml is not valid UTF-8; moved it to aether.toml.corrupt and provisioning a fresh identity"
        assertEquals(0, DpiBrain.signalsOf(line) and DpiBrain.S_IDENTITY)
    }

    @Test
    fun `ordinary engine chatter carries no symptom`() {
        assertEquals(0, DpiBrain.signalsOf("[*] performance profile: Medium (cpus=4 mem=2815MB)"))
        assertEquals(0, DpiBrain.signalsOf("[+] socks5 server listening on 127.0.0.1:1819"))
    }

    @Test
    fun `a silent UDP carrier is a UDP verdict and a reset TLS handshake on TCP is DPI on TLS`() {
        assertEquals(
            DpiBrain.Verdict.UDP_BLOCKED,
            DpiBrain.diagnose(0, died = false, aliveMs = 20_000, ridesUdp = true, fatal = null),
        )
        val reset = DpiBrain.signalsOf("[h2] tls handshake failure: connection reset by peer")
        assertEquals(
            DpiBrain.Verdict.TLS_INTERFERENCE,
            DpiBrain.diagnose(reset, died = false, aliveMs = 20_000, ridesUdp = false, fatal = null),
        )
        val empty = DpiBrain.signalsOf("[-] scan deadline reached with no gateway")
        assertEquals(
            DpiBrain.Verdict.GATEWAYS_FILTERED,
            DpiBrain.diagnose(empty, died = false, aliveMs = 20_000, ridesUdp = false, fatal = null),
        )
    }

    @Test
    fun `two UDP strikes or one with a weak fingerprint mean UDP is blocked`() {
        val k = DpiBrain.Knowledge().learn(DpiBrain.Verdict.UDP_BLOCKED)
        assertFalse(k.udpBlocked)
        assertTrue(k.learn(DpiBrain.Verdict.UDP_BLOCKED).udpBlocked)
        assertTrue(DpiBrain.Knowledge(udpWeakHint = true).learn(DpiBrain.Verdict.UDP_BLOCKED).udpBlocked)
    }

    @Test
    fun `once UDP is blocked TCP carriers lead and nothing is dropped`() {
        val k = DpiBrain.Knowledge(udpStrikes = 2)
        val lane = masqueLane()
        val adapted = DpiBrain.adapt(lane, k)
        assertFalse(DpiBrain.ridesUdp(adapted.first()))
        assertTrue(adapted.first().http2)
        // Every family member is still there (possibly hardened), none twice.
        assertEquals(adapted.size, adapted.map { DpiBrain.signature(it) }.toSet().size)
        assertTrue(adapted.all { it.family == SmartPlusPlan.Family.MASQUE })
        assertTrue(adapted.any { it.protocol == Protocol.MASQUE && !it.http2 })
    }

    @Test
    fun `TLS interference fragments every TCP carrier`() {
        val k = DpiBrain.Knowledge(tlsStrikes = 1)
        val adapted = DpiBrain.adapt(masqueLane(), k)
        adapted.filter { !DpiBrain.ridesUdp(it) }.forEach {
            assertTrue(it.id, it.fragment)
            assertTrue(it.id, it.noize.ordinal >= Noize.FIREWALL.ordinal)
        }
    }

    @Test
    fun `silent gateways give WireGuard the heaviest disguise`() {
        val k = DpiBrain.Knowledge(udpStrikes = 2)
        DpiBrain.adapt(warpLane(), k).forEach {
            assertTrue(it.id, it.noize.ordinal >= Noize.GFW.ordinal)
            assertEquals(SmartPlusPlan.Family.WARP, it.family)
        }
    }

    @Test
    fun `nothing learned changes nothing`() {
        val lane = masqueLane()
        assertEquals(lane, DpiBrain.adapt(lane, DpiBrain.Knowledge()))
    }

    @Test
    fun `a hopeless UDP rung is abandoned early and a TCP one never`() {
        val k = DpiBrain.Knowledge(udpStrikes = 2)
        val h3 = masqueLane().first { it.id == "masque.h3" }
        val h2 = masqueLane().first { it.id == "masque.h2" }
        assertFalse(DpiBrain.shouldAbandon(h3, k, 1_000, serving = false))
        assertTrue(DpiBrain.shouldAbandon(h3, k, DpiBrain.EARLY_ABANDON_MS, serving = false))
        assertFalse(DpiBrain.shouldAbandon(h3, k, DpiBrain.EARLY_ABANDON_MS, serving = true))
        assertFalse(DpiBrain.shouldAbandon(h2, k, 60_000, serving = false))
    }

    @Test
    fun `a remembered hardened tactic is rebuilt and leads its lane`() {
        val offered = SmartPlusPlan.allTactics(DpiClass.OPEN, onMobileData = false)
        val resolved = DpiBrain.resolve("masque.h2" + DpiBrain.BOOST_SUFFIX, offered)
        assertNotNull(resolved)
        assertTrue(resolved!!.fragment)
        assertNull(DpiBrain.resolve("no-such-tactic", offered))
        val memory = SmartPlusMemory.Entry("masque.h2" + DpiBrain.BOOST_SUFFIX, now)
        val lanes = SmartPlusPlan.plan(DpiClass.OPEN, false, memory, now)
        val masque = lanes.first { it.tactics.first().family == SmartPlusPlan.Family.MASQUE }
        assertEquals("masque.h2" + DpiBrain.BOOST_SUFFIX, masque.tactics.first().id)
        lanes.forEach { lane -> assertTrue(lane.tactics.all { it.family == lane.tactics.first().family }) }
    }

    @Test
    fun `the operator key is stable hashed and absent for an unknown operator`() {
        val a = DpiBrain.operatorKey("IR-MCI", true)
        assertEquals(a, DpiBrain.operatorKey(" ir-mci ", true))
        assertFalse(a!!.contains("mci", ignoreCase = true))
        assertNull(DpiBrain.operatorKey("unknown", false))
        assertNull(DpiBrain.operatorKey("", false))
    }
}
