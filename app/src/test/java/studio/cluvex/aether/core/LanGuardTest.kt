package studio.cluvex.aether.core

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide who may use this phone's shared tunnel.
 *
 * Written because the 1.2.9 audit's F-5 was an authorization hole, and an
 * authorization fix that is only ever tested by pointing one laptop at one phone
 * is a fix that regresses the next time someone touches the accept path. Every
 * assertion below is a case the bridge has to get right on a hostile network:
 * the IPv4-mapped form a dual-stack listener actually reports, carrier NAT, a
 * malformed `Proxy-Authorization` header, a credential that differs only in
 * length.
 *
 * Pure JVM: [LanGuard] deliberately contains no Android type, and every address
 * here is a literal, so `InetAddress.getByName` never touches a resolver.
 */
class LanGuardTest {

    // ------------------------------------------------------- source scope

    @Test
    fun `private ipv4 ranges are local`() {
        listOf("10.0.0.7", "172.16.4.9", "172.31.255.254", "192.168.1.5", "127.0.0.1")
            .forEach { ip ->
                assertTrue(ip, LanGuard.isLocalNetworkAddress(InetAddress.getByName(ip)))
            }
    }

    @Test
    fun `link local ipv4 is local`() {
        assertTrue(LanGuard.isLocalNetworkAddress(InetAddress.getByName("169.254.10.10")))
    }

    @Test
    fun `public ipv4 is refused`() {
        listOf("8.8.8.8", "1.1.1.1", "104.28.246.167", "172.32.0.1", "192.169.0.1")
            .forEach { ip ->
                assertFalse(ip, LanGuard.isLocalNetworkAddress(InetAddress.getByName(ip)))
            }
    }

    /**
     * Carrier-grade NAT is NOT a local network: on mobile data other subscribers
     * can share the pool, and "someone else in my carrier's NAT" is exactly the
     * peer this check exists to keep out.
     */
    @Test
    fun `carrier grade nat is refused`() {
        assertFalse(LanGuard.isLocalNetworkAddress(InetAddress.getByName("100.64.0.1")))
        assertFalse(LanGuard.isLocalNetworkAddress(InetAddress.getByName("100.127.255.254")))
    }

    @Test
    fun `ipv6 unique local and link local are local, public v6 is not`() {
        assertTrue(LanGuard.isLocalNetworkAddress(InetAddress.getByName("fd00::1")))
        assertTrue(LanGuard.isLocalNetworkAddress(InetAddress.getByName("fc00::1")))
        assertTrue(LanGuard.isLocalNetworkAddress(InetAddress.getByName("fe80::1")))
        assertTrue(LanGuard.isLocalNetworkAddress(InetAddress.getByName("::1")))
        assertFalse(LanGuard.isLocalNetworkAddress(InetAddress.getByName("2606:4700::1111")))
    }

    /**
     * A dual-stack `ServerSocket` reports an IPv4 peer as `::ffff:a.b.c.d`. If the
     * mapped form were classified as plain IPv6 every LAN client would be refused,
     * and a mapped PUBLIC address would sail through the v6 branch.
     */
    @Test
    fun `ipv4 mapped ipv6 is judged by its ipv4 address`() {
        assertTrue(LanGuard.isLocalNetworkAddress(InetAddress.getByName("::ffff:192.168.1.20")))
        assertTrue(LanGuard.isLoopback(InetAddress.getByName("::ffff:127.0.0.1")))
        assertFalse(LanGuard.isLocalNetworkAddress(InetAddress.getByName("::ffff:8.8.8.8")))
    }

    @Test
    fun `null address is never local`() {
        assertFalse(LanGuard.isLocalNetworkAddress(null))
        assertFalse(LanGuard.isLoopback(null))
    }

    @Test
    fun `loopback is distinguished from the rest of the lan`() {
        assertTrue(LanGuard.isLoopback(InetAddress.getByName("127.0.0.1")))
        assertFalse(LanGuard.isLoopback(InetAddress.getByName("192.168.1.5")))
    }

    // ------------------------------------------------------- http basic

    @Test
    fun `basic credential round trips`() {
        val header = LanGuard.basicAuthValue("aether", "s3cret:with:colons")
        val parsed = LanGuard.parseBasicCredential(header)
        assertEquals("aether", parsed?.first)
        // Only the FIRST colon separates user from password.
        assertEquals("s3cret:with:colons", parsed?.second)
    }

    @Test
    fun `basic parsing is case insensitive on the scheme`() {
        val parsed = LanGuard.parseBasicCredential("basic " + "YWV0aGVyOnB3")
        assertEquals("aether", parsed?.first)
        assertEquals("pw", parsed?.second)
    }

    @Test
    fun `malformed credentials never authenticate`() {
        assertNull(LanGuard.parseBasicCredential(""))
        assertNull(LanGuard.parseBasicCredential("Basic"))
        assertNull(LanGuard.parseBasicCredential("Basic    "))
        assertNull(LanGuard.parseBasicCredential("Bearer YWV0aGVyOnB3"))
        assertNull(LanGuard.parseBasicCredential("Basic !!!not-base64!!!"))
        // Valid base64, but no colon: not a credential.
        assertNull(LanGuard.parseBasicCredential("Basic YWV0aGVy"))
    }

    // ------------------------------------------------------- comparison

    @Test
    fun `secret comparison accepts only an exact match`() {
        assertTrue(LanGuard.secretEquals("hunter2", "hunter2"))
        assertFalse(LanGuard.secretEquals("hunter2", "hunter3"))
        assertFalse(LanGuard.secretEquals("hunter2", "hunter"))
        assertFalse(LanGuard.secretEquals("hunter2", "hunter22"))
        assertFalse(LanGuard.secretEquals("hunter2", ""))
    }

    /**
     * An empty EXPECTED secret must never match, or a device that failed to load
     * its credential would accept everyone - the exact fail-open this change
     * exists to remove.
     */
    @Test
    fun `an empty expected secret matches nothing`() {
        assertFalse(LanGuard.secretEquals("", ""))
        assertFalse(LanGuard.secretEquals("", "anything"))
    }

    // ------------------------------------------------------- generation

    @Test
    fun `generated passwords are long, unambiguous and unique`() {
        // 200 draws, not two. With one draw per run this test only failed when the
        // random generator happened to pick the offending character, which is how
        // an 'o' survived in the alphabet through several releases: a 16-character
        // password missed it three runs out of four. A confusable character in a
        // password the user reads off one screen and types on another device is a
        // real defect, so the test now makes it deterministic.
        val forbidden = setOf('0', 'O', 'o', '1', 'l', 'I')
        val seen = mutableSetOf<String>()
        repeat(200) {
            val password = LanGuard.randomPassword()
            assertEquals(16, password.length)
            assertTrue(password, password.none { it in forbidden })
            assertTrue(password, password.all { it.isLetterOrDigit() })
            assertTrue("repeated password: $password", seen.add(password))
        }
    }

    @Test
    fun `sha256 hex is lowercase and 64 chars`() {
        val hex = LanGuard.sha256Hex("abc".toByteArray())
        assertEquals(64, hex.length)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }
}
