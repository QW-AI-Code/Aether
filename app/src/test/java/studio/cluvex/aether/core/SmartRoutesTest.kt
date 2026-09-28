package studio.cluvex.aether.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.core.RouteRules.Verdict

class SmartRoutesTest {

    private fun ip(text: String) = RouteRules.literal(text)!!

    @Test
    fun anEmptySetSendsEverythingThroughTheTunnel() {
        val rules = RouteRules.parse("", "")
        assertTrue(rules.isEmpty)
        assertEquals(Verdict.PROXY, rules.decide("example.com", null, 443))
        assertEquals(Verdict.PROXY, rules.decide(null, ip("1.1.1.1"), 443))
    }

    @Test
    fun aBareDomainCoversItselfAndItsSubdomainsOnly() {
        val rules = RouteRules.parse("ads.example", "")
        assertEquals(Verdict.BLOCK, rules.decide("ads.example", null, 443))
        assertEquals(Verdict.BLOCK, rules.decide("x.ads.example", null, 443))
        assertEquals(Verdict.PROXY, rules.decide("notads.example", null, 443))
        assertEquals(Verdict.PROXY, rules.decide("ads.example.org", null, 443))
    }

    @Test
    fun theIrTldRuleCoversEveryIranianName() {
        val rules = RouteRules.parse("", "ir\ndigikala.com")
        assertEquals(Verdict.DIRECT, rules.decide("www.divar.ir", null, 443))
        assertEquals(Verdict.DIRECT, rules.decide("api.digikala.com", null, 443))
        assertEquals(Verdict.PROXY, rules.decide("example.iran", null, 443))
    }

    @Test
    fun blockWinsOverDirect() {
        val rules = RouteRules.parse("example.com", "example.com")
        assertEquals(Verdict.BLOCK, rules.decide("example.com", null, 443))
    }

    @Test
    fun aNameThatDecidesNothingFallsBackToTheAddress() {
        val rules = RouteRules.parse("", "5.160.0.0/13")
        assertEquals(Verdict.DIRECT, rules.decide("unknown.example", ip("5.160.1.1"), 443))
        assertEquals(Verdict.PROXY, rules.decide("unknown.example", ip("8.8.8.8"), 443))
    }

    @Test
    fun ipv4RangesAreMergedAndBinarySearched() {
        val list = (0..255).joinToString("\n") { "10.20.$it.0/24" } + "\n192.0.2.0/25\n192.0.2.128/25"
        val rules = RouteRules.parse("", list)
        for (probe in listOf("10.20.0.1", "10.20.255.254", "192.0.2.200")) {
            assertEquals(probe, Verdict.DIRECT, rules.decide(null, ip(probe), 443))
        }
        for (probe in listOf("10.19.255.255", "10.21.0.0", "192.0.3.0")) {
            assertEquals(probe, Verdict.PROXY, rules.decide(null, ip(probe), 443))
        }
    }

    @Test
    fun ipv6NetworksAndThePrivateKeywordWork() {
        val rules = RouteRules.parse("2001:db8::/32", "private")
        assertEquals(Verdict.BLOCK, rules.decide(null, ip("2001:db8::1"), 443))
        assertEquals(Verdict.PROXY, rules.decide(null, ip("2001:db9::1"), 443))
        for (lan in listOf("10.1.1.1", "192.168.1.5", "172.16.9.9", "127.0.0.1", "100.96.0.2", "fd00::1")) {
            assertEquals(lan, Verdict.DIRECT, rules.decide(null, ip(lan), 80))
        }
        assertEquals(Verdict.DIRECT, rules.decide("localhost", null, 80))
    }

    @Test
    fun kindsPortsKeywordsAndRegexesAreHonoured() {
        val rules = RouteRules.parse("port:25, keyword:doubleclick, regexp:^ad[0-9]+\\.", "full:bank.example, port:3000-3010")
        assertEquals(Verdict.BLOCK, rules.decide("mail.example", null, 25))
        assertEquals(Verdict.BLOCK, rules.decide("stats.doubleclick.net", null, 443))
        assertEquals(Verdict.BLOCK, rules.decide("ad42.example.com", null, 443))
        assertEquals(Verdict.DIRECT, rules.decide("bank.example", null, 443))
        assertEquals(Verdict.PROXY, rules.decide("www.bank.example", null, 443))
        assertEquals(Verdict.DIRECT, rules.decide("dev.example", null, 3005))
    }

    @Test
    fun malformedEntriesAreDroppedAndNeverResolved() {
        val rules = RouteRules.parse("regexp:[unclosed, port:abc, ip:not-an-ip, geoip:cn, dead:beef:zz", "")
        assertEquals(Verdict.PROXY, rules.decide(null, ip("1.2.3.4"), 443))
        assertNull(RouteRules.literal("dead:beef"))
        assertNull(RouteRules.literal("300.1.1.1"))
        assertNotNull(RouteRules.literal("::ffff:1.2.3.4"))
    }

    @Test
    fun aRoutesFileSplitsIntoItsTwoSections() {
        val (block, direct) = RouteRules.splitSections("# x\n[block]\nads.example\n[direct]\nir\n[proxy]\nfoo.example\n")
        assertTrue(block.contains("ads.example"))
        assertTrue(direct.contains("ir"))
        assertFalse(block.contains("foo.example") || direct.contains("foo.example"))
    }

    private fun query(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        return out.toByteArray()
    }

    private fun answer(name: String, address: ByteArray): ByteArray {
        val q = query(name)
        q[2] = 0x81.toByte()
        q[3] = 0x80.toByte()
        q[7] = 1
        return q + byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0x0E, 0x10, 0, 4) + address
    }

    @Test
    fun aBlockedNameGetsAnNxdomainFromTheSinkhole() {
        val rules = RouteRules.parse("ads.example", "bank.example")
        val q = query("x.ads.example")
        val reply = rules.dnsBlockReply(q)!!
        assertArrayEquals(q.copyOfRange(0, 2), reply.copyOfRange(0, 2))
        assertEquals(0x81, reply[2].toInt() and 0xFF)
        assertEquals(3, reply[3].toInt() and 0x0F)
        assertEquals(q.size, reply.size)
        assertNull(rules.dnsBlockReply(query("bank.example")))
        assertNull(rules.dnsBlockReply(query("example.com")))
        assertNull(rules.dnsBlockReply(answer("ads.example", byteArrayOf(1, 2, 3, 4))))
    }

    @Test
    fun answersAreRememberedUnderTheAskedName() {
        NameMemory.clear()
        NameMemory.learn(answer("cdn.ads.example", byteArrayOf(198.toByte(), 51, 100, 77)))
        assertEquals("cdn.ads.example", NameMemory.recall(ip("198.51.100.77")))
        assertNull(NameMemory.recall(ip("198.51.100.78")))
    }

    @Test
    fun theSniffReadsTlsSniAndHttpHost() {
        assertEquals("www.example.com", RouteRules.sniffHost("GET / HTTP/1.1\r\nHost: www.example.com:8080\r\n\r\n".toByteArray()))
        assertNull(RouteRules.sniffHost("SSH-2.0-OpenSSH\r\n".toByteArray()))
        assertEquals("digikala.com", RouteRules.sniffHost(clientHello("digikala.com")))
    }

    // ---- #66: the verdicts the Psiphon front acts on --------------------

    private val noMemory: (java.net.InetAddress) -> String? = { null }

    @Test
    fun theFrontDecidesAHostnameTargetByItsNameAlone() {
        val rules = RouteRules.parse("ads.example", "ir")
        assertEquals(Verdict.BLOCK, rules.decideTcp("x.ads.example", null, false, 443, noMemory))
        assertEquals(Verdict.DIRECT, rules.decideTcp("www.divar.ir", null, false, 443, noMemory))
        assertEquals(Verdict.PROXY, rules.decideTcp("example.com", null, false, 443, noMemory))
    }

    @Test
    fun theFrontLetsTheSniffedNameWinOverTheAddress() {
        val rules = RouteRules.parse("ads.example", "ir\n2.144.0.0/14")
        // An Iranian address announcing a blocked name is blocked (block first).
        assertEquals(Verdict.BLOCK, rules.decideTcp("2.144.1.1", "ads.example", true, 443, noMemory))
        // A foreign address announcing an Iranian name goes direct.
        assertEquals(Verdict.DIRECT, rules.decideTcp("8.8.8.8", "www.divar.ir", true, 443, noMemory))
        // No name: the address decides.
        assertEquals(Verdict.DIRECT, rules.decideTcp("2.144.1.1", null, false, 443, noMemory))
        assertEquals(Verdict.PROXY, rules.decideTcp("8.8.8.8", "example.com", true, 443, noMemory))
    }

    @Test
    fun theFrontUsesTheRememberedNameToBlockNeverToGoDirect() {
        val rules = RouteRules.parse("ads.example", "ir")
        val memory: (java.net.InetAddress) -> String? = { addr ->
            when (addr.hostAddress) {
                "9.9.9.9" -> "tracker.ads.example"
                "7.7.7.7" -> "www.divar.ir"
                else -> null
            }
        }
        // Sent bytes, announced no name, address last resolved from a blocked name.
        assertEquals(Verdict.BLOCK, rules.decideTcp("9.9.9.9", null, true, 443, memory))
        // Nothing sent (server speaks first): the memory is not consulted.
        assertEquals(Verdict.PROXY, rules.decideTcp("9.9.9.9", null, false, 443, memory))
        // A remembered DIRECT name never sends a flow out of the real uplink.
        assertEquals(Verdict.PROXY, rules.decideTcp("7.7.7.7", null, true, 443, memory))
        // UDP: same rule.
        assertEquals(Verdict.BLOCK, rules.decideUdp(ip("9.9.9.9"), null, 443, memory))
        assertEquals(Verdict.PROXY, rules.decideUdp(ip("7.7.7.7"), null, 443, memory))
    }

    @Test
    fun theFrontRoutesUdpByAddressOrHostname() {
        val rules = RouteRules.parse("port:5353", "2.144.0.0/14\nir")
        assertEquals(Verdict.DIRECT, rules.decideUdp(ip("2.147.255.1"), null, 443, noMemory))
        assertEquals(Verdict.PROXY, rules.decideUdp(ip("1.1.1.1"), null, 443, noMemory))
        assertEquals(Verdict.BLOCK, rules.decideUdp(ip("1.1.1.1"), null, 5353, noMemory))
        assertEquals(Verdict.DIRECT, rules.decideUdp(null, "api.snapp.ir", 443, noMemory))
    }

    private fun clientHello(name: String): ByteArray {
        val sni = name.toByteArray()
        val ext = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0)) // server_name
            val body = 2 + 1 + 2 + sni.size
            write(byteArrayOf((body shr 8).toByte(), body.toByte()))
            val list = 1 + 2 + sni.size
            write(byteArrayOf((list shr 8).toByte(), list.toByte(), 0, (sni.size shr 8).toByte(), sni.size.toByte()))
            write(sni)
        }.toByteArray()
        val hello = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(3, 3))
            write(ByteArray(32))
            write(0) // session id
            write(byteArrayOf(0, 2, 0x13, 0x01)) // one cipher suite
            write(byteArrayOf(1, 0)) // compression
            write(byteArrayOf((ext.size shr 8).toByte(), ext.size.toByte()))
            write(ext)
        }.toByteArray()
        val handshake = byteArrayOf(1, 0, (hello.size shr 8).toByte(), hello.size.toByte()) + hello
        return byteArrayOf(0x16, 3, 1, (handshake.size shr 8).toByte(), handshake.size.toByte()) + handshake
    }
}
