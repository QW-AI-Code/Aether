package studio.cluvex.aether.core

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * 1.4.0-r5: the zero-IP-leak rules of VPN sharing, as pure, unit-tested Kotlin.
 *
 * Same discipline as [LanGuard]: no Android type in here, and no function in
 * this file ever performs a DNS lookup (every address below is parsed from a
 * literal), so every rule is covered by plain JVM tests (`ShareLeakGuardTest`).
 *
 * ## Where a shared connection could leak the real IP (r4 and earlier)
 *
 *  1. **Direct (bypass) routing rules.** The engine executes the user's
 *     `routeDirect` list for EVERY SOCKS client - including the share bridge.
 *     A laptop on the share asking for a domain in that list went straight out
 *     of the phone's own uplink: the site saw the user's real IP. Fixed at the
 *     root: while LAN sharing is on, the engine is started with NO direct rules
 *     at all ([studio.cluvex.aether.model.ConnectionProfile.toArgs]), and the
 *     bridge refuses LAN clients if the running engine still has them.
 *  2. **SOCKS5 UDP ASSOCIATE.** The upstream answered with a loopback relay
 *     address that a LAN device cannot reach, so UDP (QUIC, WebRTC, games,
 *     DNS) silently failed and clients fell back to sending it DIRECTLY from
 *     their own connection. The bridge now runs a real UDP relay on the share
 *     interface and carries every datagram through the tunnel.
 *  3. **Non-public destinations.** Through the bridge every LAN client looks
 *     like `127.0.0.1` to the engine, so it could reach the phone's loopback
 *     services, or - on mobile data - carrier-internal `10.x` hosts that see the
 *     subscriber identity. [destinationAllowed] refuses loopback, private,
 *     link-local, CGNAT, multicast and numeric-obfuscated hosts for LAN clients.
 *  4. **Identity headers** (`X-Forwarded-For`, `Forwarded`, `Via`, ...) are
 *     removed from plain-HTTP requests ([isIdentityHeader]).
 *  5. **Fallback-to-DIRECT PAC files.** The PAC served by the bridge never
 *     contains `DIRECT` for a public destination ([pacScript]): if the phone is
 *     unreachable, the client's browser fails closed instead of going direct.
 *
 * What the phone CANNOT do (and the UI says so): force a device that is NOT
 * configured to use the share through the tunnel. Android gives an unrooted app
 * no control over tethered traffic, so the client must point its proxy (or a
 * TUN-mode client with "strict route") at the share.
 */
object ShareLeakGuard {

    /** The two sharing modes the UI offers. */
    enum class Mode { HOME_WIFI, MOBILE_HOTSPOT }

    /** What a network interface is, judged by its kernel name. */
    enum class IfaceKind { WIFI_STATION, ETHERNET, HOTSPOT, USB_TETHER, BT_TETHER, OTHER }

    /**
     * Classifies an interface by name.
     *
     * Vendor naming, collected from Pixel / Samsung / Qualcomm / MediaTek /
     * Unisoc builds: the Wi-Fi hotspot is `ap0`, `swlan0`, `softap0`, `wlan1+`
     * or `wigig0`; USB tethering is `rndis0` / `usb0` / `ncm0`; Bluetooth PAN is
     * `bt-pan`. `wlan0` is the station (the phone as a Wi-Fi client) everywhere.
     */
    fun classifyInterface(name: String): IfaceKind {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n == "wlan0" -> IfaceKind.WIFI_STATION
            HOTSPOT_NAME.matches(n) -> IfaceKind.HOTSPOT
            USB_NAME.matches(n) -> IfaceKind.USB_TETHER
            n.startsWith("bt-pan") || n.startsWith("bnep") -> IfaceKind.BT_TETHER
            n.startsWith("eth") -> IfaceKind.ETHERNET
            n.startsWith("wlan") -> IfaceKind.WIFI_STATION
            else -> IfaceKind.OTHER
        }
    }

    /**
     * 1.4.0-r6: what Android itself says an interface is used for, taken from
     * `ConnectivityManager` (see `ShareBridge.upstreamRoles`). An interface that
     * backs a [android.net.Network] is an UPSTREAM of this phone (its own Wi-Fi,
     * its mobile data, the VPN); a tethering interface backs none.
     */
    enum class UpstreamRole { WIFI, ETHERNET, CELLULAR, VPN, OTHER }

    /**
     * 1.4.0-r6: classification that does not depend on vendor naming.
     *
     * r5 decided "is this the hotspot?" from the interface NAME alone. That list
     * can never be complete - Android 12+ runs a dual-band hotspot on a bridge
     * (`ap_br_wlan2`), several MediaTek / Xiaomi builds use `wlan1`/`wlan2`/`ap0`,
     * some run the access point on `wlan0` itself while the station is off - and a
     * wrong answer meant the share listened on an interface the other device was
     * not on. Now the ROLE decides:
     *
     *  - backs a Wi-Fi / Ethernet network -> the phone's own home connection;
     *  - backs mobile data, the VPN or anything else -> never shared;
     *  - backs NO network, has a private IPv4 and is not a known modem / tunnel
     *    name -> a tethering downstream (hotspot, USB, Bluetooth, Ethernet
     *    tethering), sub-typed by name only for the label shown to the user.
     *
     * With [roles] == null (no context yet, unit tests) the r5 name rules apply.
     */
    fun classifyInterface(name: String, roles: Map<String, UpstreamRole>?): IfaceKind {
        if (roles == null) return classifyInterface(name)
        val n = name.lowercase(Locale.ROOT)
        return when (roles[name]) {
            UpstreamRole.WIFI -> IfaceKind.WIFI_STATION
            UpstreamRole.ETHERNET -> IfaceKind.ETHERNET
            UpstreamRole.CELLULAR, UpstreamRole.VPN, UpstreamRole.OTHER -> IfaceKind.OTHER
            null -> when {
                NEVER_SHARE_NAME.matches(n) -> IfaceKind.OTHER
                USB_NAME.matches(n) || n.startsWith("eth") -> IfaceKind.USB_TETHER
                n.startsWith("bt-pan") || n.startsWith("bnep") -> IfaceKind.BT_TETHER
                // Any other downstream is the Wi-Fi hotspot, whatever the vendor
                // calls it (swlan0, ap0, ap_br_wlan2, wlan1, softap0, wigig0, ...).
                else -> IfaceKind.HOTSPOT
            }
        }
    }

    /**
     * Java reports a nonsense prefix for IPv4 on some Android builds (0, -1, 64,
     * 128). A /24 is what every Android tethering stack hands out, so a value
     * outside 8..30 falls back to it instead of admitting nobody (or everybody).
     */
    fun sanePrefix(prefixLength: Int): Int = if (prefixLength in 8..30) prefixLength else 24

    /** Which interface kinds a mode shares on, best first. */
    fun kindsFor(mode: Mode): List<IfaceKind> = when (mode) {
        Mode.HOME_WIFI -> listOf(IfaceKind.WIFI_STATION, IfaceKind.ETHERNET)
        Mode.MOBILE_HOTSPOT -> listOf(IfaceKind.HOTSPOT, IfaceKind.USB_TETHER, IfaceKind.BT_TETHER)
    }

    /**
     * True when [address] is a PUBLIC unicast address - the only kind a LAN
     * client may reach through the share.
     */
    fun isPublicUnicast(address: InetAddress): Boolean {
        val a = unwrapMapped(address)
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress
        ) {
            return false
        }
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xFF
                val o1 = b[1].toInt() and 0xFF
                !(o0 == 0 || // 0.0.0.0/8 "this network"
                    o0 == 100 && o1 in 64..127 || // 100.64/10 carrier NAT
                    o0 == 192 && o1 == 0 && (b[2].toInt() and 0xFF) == 0 || // 192.0.0/24
                    o0 == 198 && (o1 == 18 || o1 == 19) || // benchmarking
                    o0 >= 240) // reserved + broadcast
            }
            is Inet6Address -> {
                val first = b[0].toInt() and 0xFF
                // fc00::/7 unique-local; 2001:db8::/32 documentation; ::/8 reserved.
                !((first and 0xFE) == 0xFC ||
                    first == 0x20 && (b[1].toInt() and 0xFF) == 0x01 &&
                    (b[2].toInt() and 0xFF) == 0x0D && (b[3].toInt() and 0xFF) == 0xB8 ||
                    first == 0x00)
            }
            else -> false
        }
    }

    /**
     * Parses [host] as an IP literal WITHOUT any DNS lookup, or returns null
     * when it is a name. Accepts dotted-quad IPv4 and (optionally bracketed)
     * IPv6; anything else is a name.
     */
    fun parseIpLiteral(host: String): InetAddress? {
        val h = host.trim().removePrefix("[").removeSuffix("]")
        if (IPV4_DOTTED.matches(h)) {
            val parts = h.split('.').map { it.toInt() }
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
        }
        if (h.contains(':') && IPV6_CHARS.matches(h)) {
            // A literal containing ':' is never resolved by getByName.
            return runCatching { InetAddress.getByName(h) }.getOrNull()
        }
        return null
    }

    /**
     * May a LAN client reach [host] through the share?
     *
     * Names are allowed (they are resolved INSIDE the tunnel by the engine),
     * except the ones that mean "this machine / this LAN". IP literals must be
     * public unicast. Hostnames made only of digits/dots/hex that are not a
     * clean dotted quad (`2130706433`, `0x7f.1`, `127.1`) are refused: those are
     * the classic encodings used to smuggle a loopback address past a filter.
     */
    fun destinationAllowed(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (h.isEmpty() || h.length > 253) return false
        parseIpLiteral(h)?.let { return isPublicUnicast(it) }
        if (h.startsWith("[") || h.contains(':')) return false // malformed IPv6
        if (NUMERIC_LIKE.matches(h)) return false
        if (h == "localhost" || h.endsWith(".localhost") || h == "localhost.localdomain" ||
            h == "ip6-localhost" || h == "ip6-loopback"
        ) {
            return false
        }
        if (LOCAL_SUFFIXES.any { h == it.removePrefix(".") || h.endsWith(it) }) return false
        return true
    }

    /**
     * Destination check for one SOCKS5 UDP datagram (RFC 1928 section 7).
     *
     * @return true when the datagram is well-formed, unfragmented and addressed
     *   to an allowed destination.
     */
    fun udpDatagramAllowed(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 4 + 1 + 2) return false
        if (data[offset].toInt() != 0 || data[offset + 1].toInt() != 0) return false
        if (data[offset + 2].toInt() != 0) return false // FRAG: fragmentation not supported
        return when (data[offset + 3].toInt() and 0xFF) {
            0x01 -> length >= 4 + 4 + 2 &&
                isPublicUnicast(InetAddress.getByAddress(data.copyOfRange(offset + 4, offset + 8)))
            0x04 -> length >= 4 + 16 + 2 &&
                isPublicUnicast(InetAddress.getByAddress(data.copyOfRange(offset + 4, offset + 20)))
            0x03 -> {
                val len = data[offset + 4].toInt() and 0xFF
                length >= 4 + 1 + len + 2 && len > 0 &&
                    destinationAllowed(String(data, offset + 5, len, Charsets.ISO_8859_1))
            }
            else -> false
        }
    }

    /**
     * Request headers that describe the CLIENT rather than the request, and
     * that must never be forwarded into the tunnel. Case-insensitive on the
     * header line.
     */
    fun isIdentityHeader(line: String): Boolean {
        val name = line.substringBefore(':').trim().lowercase(Locale.ROOT)
        return name in IDENTITY_HEADERS
    }

    /** Same-subnet test used to admit only devices on the shared network. */
    fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
        val x = unwrapMapped(a).address
        val y = unwrapMapped(b).address
        if (x.size != y.size) return false
        val bits = prefixLength.coerceIn(0, x.size * 8)
        val full = bits / 8
        for (i in 0 until full) if (x[i] != y[i]) return false
        val rest = bits % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (x[full].toInt() and mask) == (y[full].toInt() and mask)
    }

    /**
     * The proxy auto-config served at `/proxy.pac`.
     *
     * Fail-closed by construction: the only `DIRECT` answers are for plain host
     * names and PRIVATE IP literals (the client's own LAN: printer, router
     * page), which never leave the local network and cannot reveal a public IP.
     * No `dnsResolve()` - that would make the client resolve every name with
     * its own DNS server, which is a DNS leak. Every other destination gets the
     * phone's HTTP proxy and nothing after it, so an unreachable phone means
     * "no connection", never "connected directly".
     */
    fun pacScript(proxyHost: String, httpPort: Int): String = """
        function FindProxyForURL(url, host) {
          host = host.toLowerCase();
          if (isPlainHostName(host) || host == "localhost" ||
              shExpMatch(host, "*.local") ||
              shExpMatch(host, "10.*") || shExpMatch(host, "127.*") ||
              shExpMatch(host, "192.168.*") || shExpMatch(host, "169.254.*") ||
              /^172\.(1[6-9]|2[0-9]|3[01])\./.test(host)) {
            return "DIRECT";
          }
          return "PROXY $proxyHost:$httpPort";
        }
    """.trimIndent() + "\n"

    /** The magic host a client opens to check it is protected (plain HTTP). */
    const val CHECK_HOST = "aether.check"

    /**
     * 1.4.0-r6: the address [CHECK_HOST] resolves to when a shared device asks a
     * DNS server THROUGH the share (v2rayNG / NekoBox / sing-box in VPN mode, or
     * any client that resolves before it connects). From TEST-NET-2 (RFC 5737):
     * never routed on the internet, so a connection to it can only mean "the
     * check page", and the bridge answers it locally.
     */
    const val CHECK_SENTINEL_V4 = "198.51.100.254"
    private val CHECK_SENTINEL_BYTES = byteArrayOf(198.toByte(), 51, 100, 254.toByte())

    /** True for [CHECK_HOST] (any case, optional trailing dot). */
    fun isCheckHost(host: String): Boolean =
        host.trim().trimEnd('.').lowercase(Locale.ROOT) == CHECK_HOST

    /**
     * True when a connection target means "the check page": the magic name, or
     * the sentinel address a shared device got for it from [checkHostDnsReply].
     */
    fun isCheckTarget(host: String): Boolean {
        if (isCheckHost(host)) return true
        val literal = parseIpLiteral(host) ?: return false
        return literal is Inet4Address && literal.address.contentEquals(CHECK_SENTINEL_BYTES)
    }

    /**
     * Length of a SOCKS5 UDP request header (`RSV RSV FRAG ATYP DST.ADDR
     * DST.PORT`, RFC 1928 section 7), or -1 when [length] cannot hold one.
     */
    fun socksUdpHeaderLength(data: ByteArray, offset: Int, length: Int): Int {
        if (length < 4) return -1
        val header = when (data[offset + 3].toInt() and 0xFF) {
            0x01 -> 4 + 4 + 2
            0x04 -> 4 + 16 + 2
            0x03 -> if (length < 5) return -1 else 4 + 1 + (data[offset + 4].toInt() and 0xFF) + 2
            else -> return -1
        }
        return if (header <= length) header else -1
    }

    /**
     * 1.4.0-r6: the DNS half of the `http://aether.check/` fix.
     *
     * A client in VPN / TUN mode (v2rayNG, NekoBox, sing-box, v2rayN) resolves a
     * name BEFORE it connects, through the share's UDP relay. `aether.check` exists
     * nowhere, so the tunnel's resolver answered NXDOMAIN and the browser gave up
     * before the bridge ever saw a request - the "page does not open" report.
     *
     * If [data] is a SOCKS5 UDP datagram carrying a standard DNS query for
     * [CHECK_HOST] to port 53, this returns the complete datagram to send back to
     * the client (same SOCKS header, so the client files it under the server it
     * asked): an authoritative answer with [CHECK_SENTINEL_V4] for `A`, and an
     * empty NOERROR for any other type (so `AAAA` does not stall the lookup).
     * Anything else returns null and is relayed untouched.
     */
    fun checkHostDnsReply(data: ByteArray, offset: Int, length: Int): ByteArray? {
        val header = socksUdpHeaderLength(data, offset, length)
        if (header < 0) return null
        if (data[offset + 2].toInt() != 0) return null // fragmented: not ours
        val port = ((data[offset + header - 2].toInt() and 0xFF) shl 8) or
            (data[offset + header - 1].toInt() and 0xFF)
        if (port != 53) return null
        val answer = checkHostDnsAnswer(data, offset + header, length - header) ?: return null
        return data.copyOfRange(offset, offset + header) + answer
    }

    /** Raw DNS message: the answer for a [CHECK_HOST] query, or null. */
    fun checkHostDnsAnswer(q: ByteArray, off: Int, len: Int): ByteArray? {
        if (len < 12 + 1 + 4) return null
        val flags = u16(q, off + 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        if ((flags shr 11) and 0x0F != 0) return null // only standard QUERY
        if (u16(q, off + 4) != 1) return null // exactly one question
        val end = off + len
        var p = off + 12
        val name = StringBuilder()
        while (true) {
            if (p >= end) return null
            val label = q[p].toInt() and 0xFF
            if (label == 0) {
                p++
                break
            }
            if (label and 0xC0 != 0) return null // no compression inside a question
            if (p + 1 + label > end) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(q, p + 1, label, Charsets.ISO_8859_1))
            if (name.length > 253) return null
            p += 1 + label
        }
        if (p + 4 > end) return null
        if (!isCheckHost(name.toString())) return null
        val qtype = u16(q, p)
        val qclass = u16(q, p + 2)
        val questionEnd = p + 4
        val withAddress = qtype == 1 && qclass == 1

        val out = ByteArrayOutputStream()
        out.write(q, off, 2) // same transaction id
        // QR | AA | copy RD | RA, RCODE 0
        writeU16(out, 0x8000 or 0x0400 or (flags and 0x0100) or 0x0080)
        writeU16(out, 1) // QDCOUNT
        writeU16(out, if (withAddress) 1 else 0) // ANCOUNT
        writeU16(out, 0) // NSCOUNT
        writeU16(out, 0) // ARCOUNT (an EDNS OPT in the query is simply not echoed)
        out.write(q, off + 12, questionEnd - (off + 12))
        if (withAddress) {
            writeU16(out, 0xC00C) // pointer to the question name
            writeU16(out, 1) // TYPE A
            writeU16(out, 1) // CLASS IN
            out.write(byteArrayOf(0, 0, 0, 60)) // TTL 60 s
            writeU16(out, 4)
            out.write(CHECK_SENTINEL_BYTES)
        }
        return out.toByteArray()
    }

    /**
     * 1.4.0-r6: true when a SOCKS5 UDP datagram carries a STUN message (RFC 8489:
     * top two bits zero, magic cookie `0x2112A442`, length consistent). Used only
     * to log, once, that a shared device's WebRTC is travelling THROUGH the
     * tunnel - the positive proof a user can read in the log.
     */
    fun isStunDatagram(data: ByteArray, offset: Int, length: Int): Boolean {
        val header = socksUdpHeaderLength(data, offset, length)
        if (header < 0) return false
        val p = offset + header
        val payload = length - header
        if (payload < 20) return false
        if (data[p].toInt() and 0xC0 != 0) return false
        if (data[p + 4] != 0x21.toByte() || data[p + 5] != 0x12.toByte() ||
            data[p + 6] != 0xA4.toByte() || data[p + 7] != 0x42.toByte()
        ) {
            return false
        }
        val declared = u16(data, p + 2)
        return declared % 4 == 0 && 20 + declared <= payload
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun unwrapMapped(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val raw = address.address
        for (i in 0 until 10) if (raw[i].toInt() != 0) return address
        if (raw[10] != 0xFF.toByte() || raw[11] != 0xFF.toByte()) return address
        return InetAddress.getByAddress(raw.copyOfRange(12, 16))
    }

    // r6: + `ap_br_*` (Android 12+ dual-band hotspot bridge) and `wifi_ap*`.
    private val HOTSPOT_NAME = Regex("^(ap\\d+|ap_br_.*|swlan\\d+|softap.*|wifi_ap.*|wlan[1-9]\\d*|wigig\\d+|p2p-wlan.*)$")

    /**
     * r6: interfaces that are NEVER a share downstream even when Android does not
     * list them as a network (restricted IMS / modem links are invisible to apps):
     * modem data links from every chipset vendor (Qualcomm `rmnet`, MediaTek
     * `ccmni`, Unisoc/Exynos `seth`/`sipa`), 464xlat, tunnels, VPNs, dummies.
     */
    private val NEVER_SHARE_NAME = Regex(
        "^(lo|tun\\d*|tap\\d*|ppp\\d*|rmnet.*|r_rmnet.*|rev_rmnet.*|ccmni.*|ccemni.*|cc2mni.*|" +
            "clat.*|v4-.*|dummy\\d*|ip6tnl\\d*|ip_vti\\d*|ip6_vti\\d*|sit\\d*|gre\\d*|ipsec.*|" +
            "seth.*|sipa.*|pdp.*|wwan.*|umts.*|epdg.*|ims.*|ifb\\d*|bond\\d*|nlmon.*|p2p\\d+|" +
            "aware_.*|nan\\d*|wg\\d*|xfrm.*|mhi.*|usb_rmnet.*)$",
    )
    private val USB_NAME = Regex("^(rndis\\d+|usb\\d+|ncm\\d+)$")
    private val IPV4_DOTTED = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")
    private val NUMERIC_LIKE = Regex("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]+|[0-9]+))*$")
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".intranet")

    private val IDENTITY_HEADERS = setOf(
        "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-server",
        "x-real-ip", "x-client-ip", "client-ip", "true-client-ip", "cf-connecting-ip",
        "x-originating-ip", "x-remote-ip", "x-remote-addr", "x-cluster-client-ip",
        "forwarded", "via",
    )
}
