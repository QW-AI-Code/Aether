package studio.cluvex.aether.transport

import java.net.InetAddress

/**
 * The byte-level SOCKS5 and DNS framing [TorSocksFront] runs on, with no socket in
 * sight.
 *
 * ## Why this is a separate object
 *
 * Everything here is a place where one wrong byte produces a failure that looks
 * like something else entirely, and none of it can be tested while it lives inside
 * a read loop on a live socket:
 *
 *  * A UDP request header parsed one byte off yields a plausible-looking port. Read
 *    port 53 where the client wrote 443 and the front answers a QUIC attempt with a
 *    DNS reply; read 443 where the client wrote 53 and every name resolution on the
 *    device is silently dropped. Both present as "the tunnel is up and nothing
 *    loads" - the exact symptom the 1.2.7 Tor backend shipped, which is why this is
 *    the last part of the front that should be trusted to review-by-eye.
 *  * The DNS-over-TCP length prefix (RFC 1035 §4.2.2) is two bytes, big-endian,
 *    counting the message only. Get it wrong and the resolver answers nothing at
 *    all: no error, no reply, just a timeout that reads as "Tor is slow".
 *  * The reply must carry the client's own header back verbatim. hev matches
 *    replies against what it sent; a rebuilt header that differs in the address
 *    form makes the answer arrive and be discarded.
 *
 * So the parsing is pure, total (no exception escapes for malformed input - a bad
 * packet returns null and the caller drops it) and covered by
 * `TorSocksWireTest`. [TorSocksFront] keeps the sockets, the threads and the
 * counters; this file keeps the bytes.
 */
internal object TorSocksWire {

    const val ATYP_IPV4 = 1
    const val ATYP_DOMAIN = 3
    const val ATYP_IPV6 = 4

    /** The DNS port. Every other UDP port is dropped, because Tor has no UDP. */
    const val DNS_PORT = 53

    /** QUIC's port, counted separately so a diagnostics dump can explain itself. */
    const val QUIC_PORT = 443

    /**
     * One SOCKS5 UDP request, as hev-socks5-tunnel sends it.
     *
     * ```
     *  +-----+------+------+----------+----------+----------+
     *  | RSV | RSV  | FRAG | ATYP     | DST.ADDR | DST.PORT | DATA
     *  |  0  |  0   |  0   | 1 byte   | variable | 2 bytes  |
     *  +-----+------+------+----------+----------+----------+
     * ```
     *
     * [headerLength] is where DATA starts, kept so the reply can echo the exact
     * bytes the client sent rather than a reconstruction of them.
     */
    data class UdpRequest(
        val host: String,
        val port: Int,
        val headerLength: Int,
        val payload: ByteArray,
    ) {
        // Generated equals/hashCode would compare the payload by identity, which
        // makes every assertEquals in the test suite pass or fail by accident.
        override fun equals(other: Any?): Boolean =
            other is UdpRequest &&
                host == other.host &&
                port == other.port &&
                headerLength == other.headerLength &&
                payload.contentEquals(other.payload)

        override fun hashCode(): Int =
            (((host.hashCode() * 31 + port) * 31 + headerLength) * 31) + payload.contentHashCode()
    }

    /**
     * Parses a SOCKS5 UDP request, or returns null when the datagram is not one.
     *
     * Null covers every malformed case on purpose: a truncated header, a fragment
     * (`FRAG != 0`, which this front does not reassemble and must not treat as a
     * whole message), an unknown address type, a domain length that runs past the
     * end of the packet. The caller's only correct response to any of them is to
     * drop the packet, so they do not need to be distinguished.
     */
    fun parseUdpRequest(data: ByteArray, length: Int): UdpRequest? {
        // Smallest possible: 3 header bytes + ATYP + 4-byte IPv4 + 2-byte port.
        if (length < 10 || length > data.size) return null
        // FRAG. A non-zero value means the client is fragmenting a datagram, which
        // needs reassembly this front deliberately does not do.
        if (data[2] != 0.toByte()) return null

        var cursor = 3
        val host: String = when (data[cursor++].toInt() and 0xFF) {
            ATYP_IPV4 -> {
                if (cursor + 4 > length) return null
                val raw = data.copyOfRange(cursor, cursor + 4)
                cursor += 4
                InetAddress.getByAddress(raw).hostAddress ?: return null
            }
            ATYP_DOMAIN -> {
                val len = data[cursor++].toInt() and 0xFF
                if (len == 0 || cursor + len > length) return null
                val name = String(data, cursor, len, Charsets.US_ASCII)
                cursor += len
                name
            }
            ATYP_IPV6 -> {
                if (cursor + 16 > length) return null
                val raw = data.copyOfRange(cursor, cursor + 16)
                cursor += 16
                InetAddress.getByAddress(raw).hostAddress ?: return null
            }
            else -> return null
        }
        if (cursor + 2 > length) return null
        val port = ((data[cursor].toInt() and 0xFF) shl 8) or (data[cursor + 1].toInt() and 0xFF)
        cursor += 2
        return UdpRequest(
            host = host,
            port = port,
            headerLength = cursor,
            payload = data.copyOfRange(cursor, length),
        )
    }

    /**
     * Frames a DNS message for TCP: the two-byte big-endian length, then the
     * message (RFC 1035 §4.2.2).
     */
    fun frameDnsQuery(query: ByteArray): ByteArray {
        val out = ByteArray(query.size + 2)
        out[0] = ((query.size shr 8) and 0xFF).toByte()
        out[1] = (query.size and 0xFF).toByte()
        query.copyInto(out, 2)
        return out
    }

    /**
     * Builds the datagram sent back to the client: its own header, then the answer.
     *
     * Takes the ORIGINAL packet and the header length rather than a rebuilt header,
     * because "the same bytes back" is the property hev checks.
     */
    fun buildUdpReply(request: ByteArray, headerLength: Int, answer: ByteArray): ByteArray {
        val out = ByteArray(headerLength + answer.size)
        request.copyInto(out, 0, 0, headerLength)
        answer.copyInto(out, headerLength)
        return out
    }

    /** True when this request is a DNS query the front can answer over Tor. */
    fun isDnsQuery(request: UdpRequest): Boolean = request.port == DNS_PORT
}
