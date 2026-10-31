package studio.cluvex.aether.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bytes the Tor front stands or falls on.
 *
 * Written because 1.2.7's Tor backend failed in a way nobody could see from the
 * outside: the session connected, verified, reported a Tor exit, and no page ever
 * loaded. The cause was UDP - Tor carries TCP only, hev sends DNS as SOCKS5
 * `UDP ASSOCIATE` - and the fix in 1.3.0 is a front that parses those datagrams
 * itself. A parser that is off by one byte reproduces exactly the same silent
 * failure, so every case below is one of the ways that can happen:
 *
 *  * the port read from the wrong offset (DNS answered as QUIC, or the reverse),
 *  * all three address forms hev may send, including the domain form,
 *  * datagrams that must be refused rather than half-read: fragments, truncation,
 *    a domain length pointing past the end, an unknown ATYP,
 *  * the RFC 1035 length prefix, where a wrong value means the resolver answers
 *    nothing at all and the user sees "Tor is slow",
 *  * the reply header, which must be the client's own bytes and not a
 *    reconstruction of them.
 *
 * Pure JVM: [TorSocksWire] contains no Android type and no socket, and every
 * address here is a literal, so nothing touches a resolver.
 */
class TorSocksWireTest {

    // ---------------------------------------------------------------- helpers

    /** Builds a SOCKS5 UDP request: RSV RSV FRAG ATYP ADDR PORT DATA. */
    private fun udpRequest(
        atyp: Int,
        address: ByteArray,
        port: Int,
        payload: ByteArray,
        frag: Int = 0,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0); out.add(0); out.add(frag.toByte())
        out.add(atyp.toByte())
        if (atyp == TorSocksWire.ATYP_DOMAIN) out.add(address.size.toByte())
        address.forEach { out.add(it) }
        out.add(((port shr 8) and 0xFF).toByte())
        out.add((port and 0xFF).toByte())
        payload.forEach { out.add(it) }
        return out.toByteArray()
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int) =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    /** A minimal, plausible DNS query body. Content is irrelevant to framing. */
    private val dnsQuery = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x03, 'w'.code.toByte(), 'w'.code.toByte(), 'w'.code.toByte(),
        0x02, 'i'.code.toByte(), 'r'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01,
    )

    // ------------------------------------------------------------- happy paths

    @Test
    fun `parses an IPv4 DNS query`() {
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(1, 1, 1, 1), 53, dnsQuery)
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!

        assertEquals("1.1.1.1", request.host)
        assertEquals(53, request.port)
        // 3 header + 1 ATYP + 4 address + 2 port
        assertEquals(10, request.headerLength)
        assertArrayEquals(dnsQuery, request.payload)
        assertTrue(TorSocksWire.isDnsQuery(request))
    }

    @Test
    fun `parses the domain address form`() {
        val name = "dns.example.org".toByteArray(Charsets.US_ASCII)
        val packet = udpRequest(TorSocksWire.ATYP_DOMAIN, name, 53, dnsQuery)
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!

        assertEquals("dns.example.org", request.host)
        assertEquals(53, request.port)
        // 3 + 1 ATYP + 1 length + name + 2 port
        assertEquals(7 + name.size, request.headerLength)
        assertArrayEquals(dnsQuery, request.payload)
    }

    @Test
    fun `parses the IPv6 address form`() {
        val address = ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[15] = 0x01 }
        val packet = udpRequest(TorSocksWire.ATYP_IPV6, address, 53, dnsQuery)
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!

        assertEquals(53, request.port)
        assertEquals(22, request.headerLength)
        assertArrayEquals(dnsQuery, request.payload)
    }

    @Test
    fun `an empty payload is a valid request`() {
        // hev may associate before it has anything to send. A parser that demands a
        // payload would reject the association itself.
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(9, 9, 9, 9), 53, ByteArray(0))
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!

        assertEquals(0, request.payload.size)
        assertEquals(10, request.headerLength)
    }

    // ------------------------------------------------- the port decides the fate

    @Test
    fun `a QUIC datagram is not a DNS query`() {
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(142, 250, 0, 1), 443, byteArrayOf(1, 2, 3))
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!

        assertEquals(TorSocksWire.QUIC_PORT, request.port)
        assertFalse(TorSocksWire.isDnsQuery(request))
    }

    @Test
    fun `a high port is read correctly and is not DNS`() {
        // 53 in the low byte with a non-zero high byte: 13312 + 53 = 13365. A parser
        // that only looked at the low byte would call this DNS and answer it.
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(10, 0, 0, 1), 13_365, byteArrayOf(7))
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!

        assertEquals(13_365, request.port)
        assertFalse(TorSocksWire.isDnsQuery(request))
    }

    @Test
    fun `port 65535 does not become negative`() {
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(10, 0, 0, 1), 65_535, byteArrayOf(7))
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!

        assertEquals(65_535, request.port)
    }

    // ----------------------------------------------------- what must be refused

    @Test
    fun `a fragmented datagram is refused`() {
        // FRAG != 0 needs reassembly this front does not do. Treating a fragment as
        // a whole message would forward half a DNS query and wait forever.
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(1, 1, 1, 1), 53, dnsQuery, frag = 2)
        assertNull(TorSocksWire.parseUdpRequest(packet, packet.size))
    }

    @Test
    fun `a truncated header is refused`() {
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(1, 1, 1, 1), 53, dnsQuery)
        // Below the 10-byte minimum, and again one byte short of the port.
        assertNull(TorSocksWire.parseUdpRequest(packet, 9))
        assertNull(TorSocksWire.parseUdpRequest(packet.copyOfRange(0, 8), 8))
    }

    @Test
    fun `a domain length past the end of the packet is refused`() {
        val name = "example.org".toByteArray(Charsets.US_ASCII)
        val packet = udpRequest(TorSocksWire.ATYP_DOMAIN, name, 53, ByteArray(0))
        // Claim a name twice as long as the packet can hold.
        packet[4] = (name.size * 2).toByte()
        assertNull(TorSocksWire.parseUdpRequest(packet, packet.size))
    }

    @Test
    fun `a zero-length domain is refused`() {
        val packet = udpRequest(TorSocksWire.ATYP_DOMAIN, "a".toByteArray(), 53, ByteArray(0))
        packet[4] = 0
        assertNull(TorSocksWire.parseUdpRequest(packet, packet.size))
    }

    @Test
    fun `an unknown address type is refused`() {
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(1, 1, 1, 1), 53, dnsQuery)
        packet[3] = 9
        assertNull(TorSocksWire.parseUdpRequest(packet, packet.size))
    }

    @Test
    fun `a length longer than the buffer is refused`() {
        // DatagramPacket.getLength() is trusted everywhere else in the loop; a value
        // larger than the array would otherwise walk off the end.
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(1, 1, 1, 1), 53, dnsQuery)
        assertNull(TorSocksWire.parseUdpRequest(packet, packet.size + 32))
    }

    @Test
    fun `the declared length bounds the payload, not the array`() {
        // The real loop reuses one 4096-byte buffer for every datagram, so the array
        // is nearly always longer than the message. A parser that used data.size
        // would append the previous packet's tail to this one's payload.
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(1, 1, 1, 1), 53, dnsQuery)
        val buffer = packet.copyOf(4096).also { it.fill(0x55, packet.size, it.size) }

        val request = TorSocksWire.parseUdpRequest(buffer, packet.size)!!
        assertArrayEquals(dnsQuery, request.payload)
    }

    // --------------------------------------------------------- DNS over TCP

    @Test
    fun `frames a DNS query with a big-endian length prefix`() {
        val framed = TorSocksWire.frameDnsQuery(dnsQuery)

        assertEquals(dnsQuery.size + 2, framed.size)
        assertEquals(0, framed[0].toInt())
        assertEquals(dnsQuery.size, framed[1].toInt() and 0xFF)
        assertArrayEquals(dnsQuery, framed.copyOfRange(2, framed.size))
    }

    @Test
    fun `a query longer than 255 bytes keeps both length bytes`() {
        // The case a little-endian or single-byte prefix would get wrong, and the one
        // that shows up in practice with EDNS and long names.
        val long = ByteArray(300) { (it and 0xFF).toByte() }
        val framed = TorSocksWire.frameDnsQuery(long)

        assertEquals(1, framed[0].toInt() and 0xFF)   // 300 = 0x012C
        assertEquals(0x2C, framed[1].toInt() and 0xFF)
        assertEquals(302, framed.size)
    }

    // ------------------------------------------------------------- the reply

    @Test
    fun `the reply carries the client header back verbatim`() {
        val name = "dns.example.org".toByteArray(Charsets.US_ASCII)
        val packet = udpRequest(TorSocksWire.ATYP_DOMAIN, name, 53, dnsQuery)
        val request = TorSocksWire.parseUdpRequest(packet, packet.size)!!
        val answer = byteArrayOf(0x12, 0x34, (0x81).toByte(), (0x80).toByte(), 0x00, 0x01)

        val reply = TorSocksWire.buildUdpReply(packet, request.headerLength, answer)

        assertArrayEquals(
            packet.copyOfRange(0, request.headerLength),
            reply.copyOfRange(0, request.headerLength),
        )
        assertArrayEquals(answer, reply.copyOfRange(request.headerLength, reply.size))
        // Re-parsing the reply must yield the same header shape the client sent.
        val echoed = TorSocksWire.parseUdpRequest(reply, reply.size)!!
        assertEquals(request.host, echoed.host)
        assertEquals(request.port, echoed.port)
        assertEquals(request.headerLength, echoed.headerLength)
    }

    @Test
    fun `the reply does not read past the declared header`() {
        // buildUdpReply gets the whole 4096-byte buffer in the real loop; it must
        // copy headerLength bytes from it, not the whole thing.
        val packet = udpRequest(TorSocksWire.ATYP_IPV4, ipv4(1, 1, 1, 1), 53, dnsQuery)
        val buffer = packet.copyOf(4096).also { it.fill(0x55, packet.size, it.size) }
        val answer = byteArrayOf(1, 2, 3, 4)

        val reply = TorSocksWire.buildUdpReply(buffer, 10, answer)

        assertEquals(14, reply.size)
        assertArrayEquals(answer, reply.copyOfRange(10, 14))
    }
}
