package studio.cluvex.aether.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLeakGuardTest {
    @Test
    fun `non public destinations are refused`() {
        listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "172.20.0.1", "100.64.1.1", "169.254.1.1",
            "0.0.0.0", "::1", "[::1]", "fd00::1", "localhost", "a.localhost", "printer.local",
            "2130706433", "0x7f.1", "127.1", "224.0.0.1")
            .forEach { assertFalse(it, ShareLeakGuard.destinationAllowed(it)) }
    }

    @Test
    fun `public destinations and names pass`() {
        listOf("1.1.1.1", "example.com", "2606:4700::1111", "[2606:4700::1111]")
            .forEach { assertTrue(it, ShareLeakGuard.destinationAllowed(it)) }
    }

    @Test
    fun `udp datagram check`() {
        val ok = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53)
        assertTrue(ShareLeakGuard.udpDatagramAllowed(ok, 0, ok.size))
        val loop = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, 0, 53)
        assertFalse(ShareLeakGuard.udpDatagramAllowed(loop, 0, loop.size))
        val frag = byteArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 0, 53)
        assertFalse(ShareLeakGuard.udpDatagramAllowed(frag, 0, frag.size))
    }

    @Test
    fun `subnet and interface rules`() {
        val a = InetAddress.getByName("192.168.43.1")
        assertTrue(ShareLeakGuard.sameSubnet(InetAddress.getByName("192.168.43.77"), a, 24))
        assertFalse(ShareLeakGuard.sameSubnet(InetAddress.getByName("192.168.44.77"), a, 24))
        assertEquals(ShareLeakGuard.IfaceKind.WIFI_STATION, ShareLeakGuard.classifyInterface("wlan0"))
        assertEquals(ShareLeakGuard.IfaceKind.HOTSPOT, ShareLeakGuard.classifyInterface("swlan0"))
        assertEquals(ShareLeakGuard.IfaceKind.HOTSPOT, ShareLeakGuard.classifyInterface("ap0"))
        assertEquals(ShareLeakGuard.IfaceKind.USB_TETHER, ShareLeakGuard.classifyInterface("rndis0"))
    }

    @Test
    fun `pac never falls back to direct for public hosts`() {
        val pac = ShareLeakGuard.pacScript("192.168.43.1", 10811)
        assertTrue(pac.contains("return \"PROXY 192.168.43.1:10811\";"))
        assertFalse(pac.contains("; DIRECT"))
        assertTrue(ShareLeakGuard.isIdentityHeader("X-Forwarded-For: 1.2.3.4"))
    }

    // ------------------------------------------------------------------ r6

    private fun dnsQuery(name: String, qtype: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0, qtype.toByte(), 0, 1))
        return out.toByteArray()
    }

    private val socksHeaderTo53 = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 0, 53)

    @Test
    fun `aether check A query is answered locally with the sentinel`() {
        val datagram = socksHeaderTo53 + dnsQuery("Aether.Check", 1)
        val reply = ShareLeakGuard.checkHostDnsReply(datagram, 0, datagram.size)!!
        // Same SOCKS header back, so the client files it under the server it asked.
        assertTrue(reply.copyOfRange(0, 10).contentEquals(socksHeaderTo53))
        val dns = reply.copyOfRange(10, reply.size)
        assertEquals(0x12, dns[0].toInt())
        assertEquals(0x34, dns[1].toInt())
        assertTrue(dns[2].toInt() and 0x80 != 0) // QR
        assertEquals(1, dns[7].toInt()) // ANCOUNT
        val tail = dns.copyOfRange(dns.size - 4, dns.size).map { it.toInt() and 0xFF }
        assertEquals(listOf(198, 51, 100, 254), tail)
    }

    @Test
    fun `aether check AAAA gets an empty answer and other names are relayed`() {
        val aaaa = socksHeaderTo53 + dnsQuery("aether.check", 28)
        val reply = ShareLeakGuard.checkHostDnsReply(aaaa, 0, aaaa.size)!!
        assertEquals(0, reply[10 + 7].toInt()) // ANCOUNT 0, NOERROR
        val other = socksHeaderTo53 + dnsQuery("example.com", 1)
        assertEquals(null, ShareLeakGuard.checkHostDnsReply(other, 0, other.size))
        val notDns = byteArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 1, 187.toByte()) + dnsQuery("aether.check", 1) // port 443 = 0x01BB
        assertEquals(null, ShareLeakGuard.checkHostDnsReply(notDns, 0, notDns.size))
    }

    @Test
    fun `check targets`() {
        assertTrue(ShareLeakGuard.isCheckTarget("aether.check"))
        assertTrue(ShareLeakGuard.isCheckTarget("AETHER.CHECK."))
        assertTrue(ShareLeakGuard.isCheckTarget(ShareLeakGuard.CHECK_SENTINEL_V4))
        assertFalse(ShareLeakGuard.isCheckTarget("aether.check.example.com"))
        assertFalse(ShareLeakGuard.isCheckTarget("198.51.100.1"))
    }

    @Test
    fun `stun is recognised`() {
        val stun = socksHeaderTo53.copyOf().also { it[8] = 0x0D; it[9] = 0x96.toByte() } + // port 3478
            byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42) + ByteArray(12)
        assertTrue(ShareLeakGuard.isStunDatagram(stun, 0, stun.size))
        val dns = socksHeaderTo53 + dnsQuery("example.com", 1)
        assertFalse(ShareLeakGuard.isStunDatagram(dns, 0, dns.size))
    }

    @Test
    fun `interfaces are classified by role, not vendor name`() {
        val roles = mapOf(
            "wlan0" to ShareLeakGuard.UpstreamRole.WIFI,
            "ccmni1" to ShareLeakGuard.UpstreamRole.CELLULAR,
            "tun0" to ShareLeakGuard.UpstreamRole.VPN,
        )
        assertEquals(ShareLeakGuard.IfaceKind.WIFI_STATION, ShareLeakGuard.classifyInterface("wlan0", roles))
        assertEquals(ShareLeakGuard.IfaceKind.OTHER, ShareLeakGuard.classifyInterface("ccmni1", roles))
        assertEquals(ShareLeakGuard.IfaceKind.OTHER, ShareLeakGuard.classifyInterface("tun0", roles))
        // Downstreams under names r5 did not know.
        assertEquals(ShareLeakGuard.IfaceKind.HOTSPOT, ShareLeakGuard.classifyInterface("ap_br_wlan2", roles))
        assertEquals(ShareLeakGuard.IfaceKind.HOTSPOT, ShareLeakGuard.classifyInterface("wlan2", roles))
        assertEquals(ShareLeakGuard.IfaceKind.USB_TETHER, ShareLeakGuard.classifyInterface("ncm0", roles))
        // Modem links invisible to apps are never shared.
        assertEquals(ShareLeakGuard.IfaceKind.OTHER, ShareLeakGuard.classifyInterface("rmnet_data3", roles))
        assertEquals(ShareLeakGuard.IfaceKind.OTHER, ShareLeakGuard.classifyInterface("ccmni0", roles))
        // Without context: the name rules, now including the Android 12+ bridge.
        assertEquals(ShareLeakGuard.IfaceKind.HOTSPOT, ShareLeakGuard.classifyInterface("ap_br_wlan2", null))
        assertEquals(24, ShareLeakGuard.sanePrefix(64))
        assertEquals(22, ShareLeakGuard.sanePrefix(22))
    }

    @Test
    fun `webrtc lock files never carry a secret and target the right policies`() {
        val reg = SharePages.windowsReg()
        assertTrue(reg.startsWith("Windows Registry Editor Version 5.00"))
        assertTrue(reg.contains("\"WebRtcIPHandling\"=\"disable_non_proxied_udp\""))
        assertTrue(reg.contains("\"WebRtcLocalhostIpHandling\"=\"disable_non_proxied_udp\""))
        assertTrue(SharePages.firefoxPolicies().contains("proxy_only_if_behind_proxy"))
        assertTrue(SharePages.download(SharePages.PATH_WINDOWS) != null)
        val page = SharePages.checkPage(via = "SOCKS5", exitIp = "104.28.226.224<script>", onDevice = false)
        assertTrue(page.contains("104.28.226.224"))
        assertFalse(page.contains("<script>\""))
    }

    @Test
    fun `r10 share pages are Persian-first, bilingual, locked down and never echo raw input`() {
        val setup = SharePages.setupPage(
            "192.168.1.5\"><script>",
            10811,
            10810,
            mode = ShareLeakGuard.Mode.MOBILE_HOTSPOT,
            authRequired = true,
        )
        assertTrue(setup.contains("lang=\"fa\" dir=\"rtl\""))
        assertTrue(setup.contains("data-set-lang=\"en\""))
        assertTrue(setup.contains("Content-Security-Policy"))
        assertTrue(setup.contains("192.168.1.5&quot;&gt;&lt;script&gt;"))
        assertFalse(setup.contains("192.168.1.5\"><script>"))
        assertTrue(setup.contains(" open>"))
        val check = SharePages.checkPage(via = null, exitIp = null, onDevice = false)
        assertTrue(check.contains("Vazirmatn"))
        assertFalse(check.contains(" open>"))
    }
}
