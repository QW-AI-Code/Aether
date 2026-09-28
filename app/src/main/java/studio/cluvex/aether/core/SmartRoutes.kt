package studio.cluvex.aether.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

/**
 * 1.4.0 smart routing: the app-side twin of the engine's `routing.rs`.
 *
 * ## Why the app needs its own copy (#66)
 *
 * In `Aether -> Psiphon` the path is `tun2socks -> PsiphonSocksFront -> Psiphon
 * -> engine`. The engine only ever sees Psiphon's own connections to its
 * servers, never the user's destination, so `--route-direct` / `--route-block`
 * can not match anything the user typed, whatever the syntax or the sniff
 * window. The rules have to be applied where the destination is still visible:
 * in [studio.cluvex.aether.transport.PsiphonSocksFront]. This class is that
 * rule engine.
 *
 * Same grammar, same precedence (block before direct), same indexing as the
 * engine, so one routes file means the same thing in both places:
 *
 *  * `example.com` / `domain:` / `suffix:` - the name and every name under it;
 *  * `full:` / `exact:` - only that name; `keyword:`; `regexp:`;
 *  * `1.2.3.0/24`, `ip:` / `cidr:`, a bare address; `port:25`, `port:3000-3010`;
 *  * `private` / `geoip:private` - LAN, loopback, link-local, CGNAT.
 *
 * Pure JVM: no Android type, no DNS lookup anywhere (every address is parsed
 * from a literal), so it is covered by plain unit tests (`SmartRoutesTest`).
 */
class RouteRules private constructor(
    private val block: Rules,
    private val direct: Rules,
) {
    enum class Verdict { PROXY, DIRECT, BLOCK }

    val isEmpty: Boolean get() = block.count == 0 && direct.count == 0
    val hasDomainRules: Boolean get() = block.hasDomainRules || direct.hasDomainRules
    val hasDirectRules: Boolean get() = direct.count > 0
    val blockCount: Int get() = block.count
    val directCount: Int get() = direct.count

    /** Verdict for a destination known only by [name] (a domain) or only by [ip]. */
    fun decide(name: String?, ip: InetAddress?, port: Int): Verdict {
        if (name != null) {
            val byName = decideOne(name, null, port)
            if (byName != Verdict.PROXY || ip == null) return byName
        }
        return decideOne(null, ip, port)
    }

    private fun decideOne(name: String?, ip: InetAddress?, port: Int): Verdict {
        if (block.matches(name, ip, port)) return Verdict.BLOCK
        if (direct.matches(name, ip, port)) return Verdict.DIRECT
        return Verdict.PROXY
    }

    /** True when the BLOCK rules alone cover [name] (used with a remembered name). */
    fun blocksName(name: String, port: Int): Boolean = block.matches(name, null, port)

    /**
     * Verdict for one TCP flow at the Psiphon front, in exactly the order the
     * engine's `handle_connect` uses (#66):
     *
     *  * a HOSTNAME target (proxy mode) is decided by that name alone;
     *  * an address target is decided by the name it announced ([sniffed], the
     *    TLS SNI or HTTP Host) first and by the address second;
     *  * a flow that DID send bytes ([sentBytes]) but announced no name is then
     *    checked against the BLOCK rules through the name its address was last
     *    resolved from ([recall]). Block only: that memory is a hint (CDN
     *    addresses are shared), and a wrong "direct" would send a foreign site
     *    out of the real uplink.
     */
    fun decideTcp(
        host: String,
        sniffed: String?,
        sentBytes: Boolean,
        port: Int,
        recall: (InetAddress) -> String? = NameMemory::recall,
    ): Verdict {
        val ip = Net.literal(host) ?: return decide(host, null, port)
        val verdict = decide(sniffed, ip, port)
        if (verdict != Verdict.PROXY || sniffed != null || !sentBytes || !hasDomainRules) return verdict
        val remembered = recall(ip) ?: return verdict
        return if (blocksName(remembered, port)) Verdict.BLOCK else verdict
    }

    /**
     * Verdict for one UDP datagram at the Psiphon front: the address (or, for a
     * hostname target, the name), then - block only - the remembered DNS name.
     * Mirrors `decide_udp` in the engine. A dropped QUIC datagram makes the app
     * fall back to TCP, where the real server name decides.
     */
    fun decideUdp(
        ip: InetAddress?,
        name: String?,
        port: Int,
        recall: (InetAddress) -> String? = NameMemory::recall,
    ): Verdict {
        val verdict = if (ip != null) decide(null, ip, port) else decide(name, null, port)
        if (verdict != Verdict.PROXY || ip == null || !hasDomainRules) return verdict
        val remembered = recall(ip) ?: return verdict
        return if (blocksName(remembered, port)) Verdict.BLOCK else verdict
    }

    /**
     * DNS sinkhole: an `NXDOMAIN` answer for a one-question query whose name the
     * BLOCK rules cover, or null to carry the query untouched. Byte-identical to
     * `RuleSet::dns_block_reply` in the engine.
     */
    fun dnsBlockReply(query: ByteArray): ByteArray? {
        if (block.count == 0) return null
        val question = Dns.question(query) ?: return null
        if (!block.matches(question.first, null, 53)) return null
        val reply = query.copyOf(question.second)
        reply[2] = ((query[2].toInt() and 0x01) or 0x80).toByte()
        reply[3] = 0x83.toByte()
        for (i in 6 until 12) reply[i] = 0
        return reply
    }

    companion object {
        val EMPTY = RouteRules(Rules.build(emptyList()), Rules.build(emptyList()))

        fun parse(block: String, direct: String): RouteRules =
            RouteRules(Rules.build(splitEntries(block)), Rules.build(splitEntries(direct)))

        /** Splits a routes file into its `[block]` and `[direct]` sections. */
        fun splitSections(text: String): Pair<String, String> {
            val block = StringBuilder()
            val direct = StringBuilder()
            var current: StringBuilder? = null
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val lowered = trimmed.lowercase(Locale.ROOT)
                when {
                    lowered == "[block]" -> current = block
                    lowered == "[direct]" -> current = direct
                    lowered.startsWith("[") -> current = null
                    else -> current?.append(trimmed)?.append('\n')
                }
            }
            return block.toString() to direct.toString()
        }

        private fun splitEntries(raw: String): List<String> =
            raw.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        /**
         * The server name a flow announces in its first bytes: the TLS
         * ClientHello SNI, or the HTTP `Host` header. Null for anything else.
         */
        fun sniffHost(head: ByteArray): String? = Sniff.host(head)

        /** Parses an IP literal WITHOUT any DNS lookup; null if [text] is not one. */
        fun literal(text: String): InetAddress? = Net.literal(text)
    }
}

/** One indexed rule list (block or direct). */
private class Rules(
    val suffixes: HashSet<String>,
    val fulls: HashSet<String>,
    val keywords: List<String>,
    val regexes: List<Regex>,
    val v4Start: LongArray,
    val v4End: LongArray,
    val v6: List<Pair<ByteArray, Int>>,
    val ports: List<IntRange>,
    val private: Boolean,
    val count: Int,
) {
    val hasDomainRules: Boolean
        get() = suffixes.isNotEmpty() || fulls.isNotEmpty() || keywords.isNotEmpty() || regexes.isNotEmpty()

    fun matches(name: String?, ip: InetAddress?, port: Int): Boolean {
        if (count == 0) return false
        if (ports.any { port in it }) return true
        if (name != null) {
            val lowered = name.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (lowered.isEmpty()) return false
            if (private && lowered == "localhost") return true
            if (fulls.contains(lowered)) return true
            if (suffixes.isNotEmpty()) {
                var rest = lowered
                while (true) {
                    if (suffixes.contains(rest)) return true
                    val dot = rest.indexOf('.')
                    if (dot < 0) break
                    rest = rest.substring(dot + 1)
                }
            }
            if (keywords.any { lowered.contains(it) }) return true
            if (regexes.any { it.containsMatchIn(name) }) return true
            return false
        }
        if (ip != null) {
            if (private && Net.isPrivate(ip)) return true
            if (ip is Inet4Address) {
                if (v4Start.isEmpty()) return false
                val x = Net.v4ToLong(ip.address)
                // partition point: first start > x
                var lo = 0
                var hi = v4Start.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (v4Start[mid] <= x) lo = mid + 1 else hi = mid
                }
                return lo > 0 && v4End[lo - 1] >= x
            }
            if (ip is Inet6Address) {
                val raw = ip.address
                return v6.any { (prefix, bits) -> Net.prefixMatches(raw, prefix, bits) }
            }
        }
        return false
    }

    companion object {
        fun build(entries: List<String>): Rules {
            val suffixes = HashSet<String>()
            val fulls = HashSet<String>()
            val keywords = ArrayList<String>()
            val regexes = ArrayList<Regex>()
            val v4 = ArrayList<LongArray>()
            val v6 = ArrayList<Pair<ByteArray, Int>>()
            val ports = ArrayList<IntRange>()
            var private = false
            var count = 0

            for (entry in entries) {
                val colon = entry.indexOf(':')
                val kindRaw = if (colon > 0) entry.substring(0, colon) else ""
                val isKind = colon > 0 && !kindRaw.contains('.') && !kindRaw.contains('/') &&
                    Net.literalNet(entry) == null
                val kind = if (isKind) kindRaw.trim().lowercase(Locale.ROOT) else ""
                val value = if (isKind) entry.substring(colon + 1).trim() else entry.trim()
                var accepted = true
                when (kind) {
                    "domain", "suffix" -> normalize(value)?.let { suffixes += it } ?: run { accepted = false }
                    "full", "exact" -> normalize(value)?.let { fulls += it } ?: run { accepted = false }
                    "keyword" -> value.lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
                        ?.let { keywords += it } ?: run { accepted = false }
                    "regexp", "regex" -> runCatching { Regex(value) }.getOrNull()
                        ?.let { regexes += it } ?: run { accepted = false }
                    "ip", "cidr" -> accepted = addNet(value, v4, v6)
                    "port" -> Net.ports(value)?.let { ports += it } ?: run { accepted = false }
                    "geoip", "geosite" ->
                        if (value.equals("private", ignoreCase = true)) private = true else accepted = false
                    "" -> when {
                        value.equals("private", ignoreCase = true) -> private = true
                        addNet(value, v4, v6) -> {}
                        else -> normalize(value)?.let { suffixes += it } ?: run { accepted = false }
                    }
                    else -> accepted = false
                }
                if (accepted) count++
            }

            v4.sortWith(compareBy<LongArray> { it[0] }.thenBy { it[1] })
            val starts = ArrayList<Long>(v4.size)
            val ends = ArrayList<Long>(v4.size)
            for (range in v4) {
                val last = ends.size - 1
                if (last >= 0 && range[0] <= ends[last] + 1) {
                    if (range[1] > ends[last]) ends[last] = range[1]
                } else {
                    starts += range[0]
                    ends += range[1]
                }
            }
            return Rules(
                suffixes, fulls, keywords, regexes,
                starts.toLongArray(), ends.toLongArray(), v6, ports, private, count,
            )
        }

        private fun addNet(value: String, v4: MutableList<LongArray>, v6: MutableList<Pair<ByteArray, Int>>): Boolean {
            val (address, bits) = Net.literalNet(value) ?: return false
            if (address is Inet4Address) {
                val base = Net.v4ToLong(address.address)
                val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
                val start = base and mask
                val end = start or (mask.inv() and 0xFFFFFFFFL)
                v4 += longArrayOf(start, end)
            } else {
                v6 += address.address to bits
            }
            return true
        }

        private fun normalize(value: String): String? {
            val cleaned = value.trim().trimStart('*').trimStart('.').trimEnd('.').lowercase(Locale.ROOT)
            return cleaned.takeIf { it.isNotEmpty() }
        }
    }
}

/** Address helpers. Nothing in here ever resolves a name. */
internal object Net {
    private val V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val V6 = Regex("^[0-9A-Fa-f:.]+$")

    fun literal(text: String): InetAddress? {
        val t = text.trim().removePrefix("[").removeSuffix("]")
        V4.matchEntire(t)?.let { m ->
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val octet = m.groupValues[i + 1].toInt()
                if (octet > 255) return null
                bytes[i] = octet.toByte()
            }
            return InetAddress.getByAddress(bytes)
        }
        if (t.contains(':') && V6.matches(t)) {
            // Parsed by hand: on Android InetAddress.getByName() falls back to a
            // DNS lookup for anything it cannot read as a literal.
            val bytes = parseV6(t) ?: return null
            return InetAddress.getByAddress(bytes)
        }
        return null
    }

    /** Strict RFC 4291 text form (with `::` and an optional dotted IPv4 tail). */
    private fun parseV6(text: String): ByteArray? {
        var t = text
        val tail = ArrayList<Int>()
        val lastColon = t.lastIndexOf(':')
        if (t.indexOf('.') > lastColon && lastColon >= 0) {
            val v4 = literal(t.substring(lastColon + 1)) as? Inet4Address ?: return null
            val b = v4.address
            tail += ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
            tail += ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
            t = t.substring(0, lastColon + 1) + "0"
            // "0" is a placeholder group replaced by the two tail groups below.
        }
        val doubleAt = t.indexOf("::")
        if (doubleAt >= 0 && t.indexOf("::", doubleAt + 1) >= 0) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                if (g.isEmpty() || g.length > 4) return null
                g.toIntOrNull(16) ?: return null
            }
        }
        var head: List<Int>
        var rest: List<Int>
        if (doubleAt >= 0) {
            head = groups(t.substring(0, doubleAt)) ?: return null
            rest = groups(t.substring(doubleAt + 2)) ?: return null
        } else {
            head = groups(t) ?: return null
            rest = emptyList()
        }
        if (tail.isNotEmpty()) {
            if (rest.isNotEmpty()) rest = rest.dropLast(1) + tail else head = head.dropLast(1) + tail
        }
        val total = head.size + rest.size
        if (doubleAt < 0 && total != 8) return null
        if (doubleAt >= 0 && total > 7) return null
        val all = head + List(8 - total) { 0 } + rest
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[2 * i] = (all[i] shr 8).toByte()
            out[2 * i + 1] = all[i].toByte()
        }
        return out
    }

    /** `address[/bits]` -> the address and its prefix length. */
    fun literalNet(text: String): Pair<InetAddress, Int>? {
        val t = text.trim()
        val slash = t.indexOf('/')
        val address = literal(if (slash >= 0) t.substring(0, slash) else t) ?: return null
        val max = if (address is Inet4Address) 32 else 128
        val bits = if (slash >= 0) t.substring(slash + 1).trim().toIntOrNull() ?: return null else max
        if (bits !in 0..max) return null
        return address to bits
    }

    fun v4ToLong(b: ByteArray): Long =
        ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
            ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)

    fun prefixMatches(raw: ByteArray, prefix: ByteArray, bits: Int): Boolean {
        if (raw.size != prefix.size) return false
        var remaining = bits
        var i = 0
        while (remaining > 0) {
            val take = minOf(8, remaining)
            val mask = (0xFF shl (8 - take)) and 0xFF
            if ((raw[i].toInt() and mask) != (prefix[i].toInt() and mask)) return false
            remaining -= take
            i++
        }
        return true
    }

    fun ports(value: String): IntRange? {
        val v = value.trim()
        val dash = v.indexOf('-')
        return if (dash >= 0) {
            val lo = v.substring(0, dash).trim().toIntOrNull() ?: return null
            val hi = v.substring(dash + 1).trim().toIntOrNull() ?: return null
            if (lo !in 0..65535 || hi !in 0..65535) return null
            if (hi < lo) hi..lo else lo..hi
        } else {
            val single = v.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
            single..single
        }
    }

    fun isPrivate(ip: InetAddress): Boolean {
        val b = ip.address
        if (ip is Inet4Address) {
            val a0 = b[0].toInt() and 0xFF
            val a1 = b[1].toInt() and 0xFF
            return a0 == 10 || a0 == 127 || a0 == 0 ||
                (a0 == 172 && a1 in 16..31) ||
                (a0 == 192 && a1 == 168) ||
                (a0 == 169 && a1 == 254) ||
                (a0 == 100 && a1 in 64..127) ||
                (a0 == 255 && a1 == 255 && (b[2].toInt() and 0xFF) == 255 && (b[3].toInt() and 0xFF) == 255) ||
                (a0 == 192 && a1 == 0 && (b[2].toInt() and 0xFF) == 2) ||
                (a0 == 198 && a1 == 51 && (b[2].toInt() and 0xFF) == 100) ||
                (a0 == 203 && a1 == 0 && (b[2].toInt() and 0xFF) == 113)
        }
        val s0 = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        return ip.isLoopbackAddress || ip.isAnyLocalAddress ||
            (s0 and 0xfe00) == 0xfc00 || (s0 and 0xffc0) == 0xfe80
    }
}

/** DNS wire helpers shared by the sinkhole and [NameMemory]. */
internal object Dns {
    /** The single question of a QUERY: lowercase name, offset past QTYPE/QCLASS. */
    fun question(msg: ByteArray): Pair<String, Int>? {
        if (msg.size < 17 || (msg[2].toInt() and 0x80) != 0) return null
        if (u16(msg, 4) != 1) return null
        val (name, end) = plainName(msg, 12) ?: return null
        val after = end + 4
        if (after > msg.size) return null
        return name to after
    }

    fun plainName(msg: ByteArray, start: Int): Pair<String, Int>? {
        val name = StringBuilder()
        var at = start
        var labels = 0
        while (true) {
            if (at >= msg.size) return null
            val len = msg[at].toInt() and 0xFF
            if (len == 0) {
                at++
                break
            }
            if (len and 0xC0 != 0 || labels > 127) return null
            if (at + 1 + len > msg.size) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(msg, at + 1, len, Charsets.ISO_8859_1).lowercase(Locale.ROOT))
            at += 1 + len
            labels++
        }
        if (name.isEmpty()) return null
        return name.toString() to at
    }

    fun skipName(msg: ByteArray, start: Int): Int? {
        var at = start
        var hops = 0
        while (true) {
            if (at >= msg.size) return null
            val len = msg[at].toInt() and 0xFF
            if (len and 0xC0 == 0xC0) return at + 2
            if (len == 0) return at + 1
            at += 1 + len
            if (++hops > 128) return null
        }
    }

    fun u16(msg: ByteArray, at: Int): Int =
        ((msg[at].toInt() and 0xFF) shl 8) or (msg[at + 1].toInt() and 0xFF)
}

/**
 * Address -> name memory filled from the DNS answers that pass through the
 * front. A HINT, never an authority: CDN addresses are shared by many names, so
 * it is only ever used to BLOCK (a dropped datagram or a refused flow makes the
 * app retry, and a TLS retry is then decided by its real server name), never to
 * send anything direct.
 */
object NameMemory {
    private const val MAX = 8192
    private const val MIN_TTL_S = 60L
    private const val MAX_TTL_S = 3600L

    private val names = object : LinkedHashMap<String, Pair<String, Long>>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?): Boolean =
            size > MAX
    }

    /** Records every A/AAAA answer in a DNS RESPONSE under the name that was asked for. */
    fun learn(msg: ByteArray) {
        try {
            if (msg.size < 12 || (msg[2].toInt() and 0x80) == 0 || (msg[3].toInt() and 0x0F) != 0) return
            if (Dns.u16(msg, 4) != 1) return
            val answers = Dns.u16(msg, 6)
            if (answers == 0) return
            val (name, questionEnd) = Dns.plainName(msg, 12) ?: return
            var at = questionEnd + 4
            val now = System.nanoTime()
            synchronized(names) {
                repeat(minOf(answers, 64)) {
                    val afterName = Dns.skipName(msg, at) ?: return
                    if (afterName + 10 > msg.size) return
                    val type = Dns.u16(msg, afterName)
                    val ttl = ((msg[afterName + 4].toLong() and 0xFF) shl 24) or
                        ((msg[afterName + 5].toLong() and 0xFF) shl 16) or
                        ((msg[afterName + 6].toLong() and 0xFF) shl 8) or
                        (msg[afterName + 7].toLong() and 0xFF)
                    val rdlen = Dns.u16(msg, afterName + 8)
                    val rdata = afterName + 10
                    if (rdata + rdlen > msg.size) return
                    val ip = when {
                        type == 1 && rdlen == 4 -> InetAddress.getByAddress(msg.copyOfRange(rdata, rdata + 4))
                        type == 28 && rdlen == 16 -> InetAddress.getByAddress(msg.copyOfRange(rdata, rdata + 16))
                        else -> null
                    }
                    if (ip != null) {
                        val keep = ttl.coerceIn(MIN_TTL_S, MAX_TTL_S)
                        names[ip.hostAddress ?: ""] = name to (now + keep * 1_000_000_000L)
                    }
                    at = rdata + rdlen
                }
            }
        } catch (_: Exception) {
            // A malformed answer teaches nothing; it must never hurt the relay.
        }
    }

    /** The name [ip] was last resolved from, while that answer is still fresh. */
    fun recall(ip: InetAddress): String? = synchronized(names) {
        val key = ip.hostAddress ?: return null
        val (name, expires) = names[key] ?: return null
        if (expires - System.nanoTime() <= 0) {
            names.remove(key)
            null
        } else {
            name
        }
    }

    fun clear() = synchronized(names) { names.clear() }
}

/** TLS SNI / HTTP Host extraction from the first bytes of a flow. */
internal object Sniff {
    private val METHODS = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ")

    fun host(head: ByteArray): String? {
        if (head.isEmpty()) return null
        return (if (head[0].toInt() == 0x16) sni(head) else httpHost(head))
            ?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)?.takeIf { plausible(it) }
    }

    private fun plausible(name: String): Boolean =
        name.isNotEmpty() && name.length <= 253 && name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    private fun sni(d: ByteArray): String? {
        try {
            // record header (5) + handshake type (1) + length (3)
            if (d.size < 9 || d[5].toInt() != 0x01) return null
            var p = 9
            p += 2 + 32 // client version + random
            if (p >= d.size) return null
            p += 1 + (d[p].toInt() and 0xFF) // session id
            if (p + 2 > d.size) return null
            p += 2 + Dns.u16(d, p) // cipher suites
            if (p >= d.size) return null
            p += 1 + (d[p].toInt() and 0xFF) // compression methods
            if (p + 2 > d.size) return null
            val end = minOf(d.size, p + 2 + Dns.u16(d, p))
            p += 2
            while (p + 4 <= end) {
                val type = Dns.u16(d, p)
                val len = Dns.u16(d, p + 2)
                p += 4
                if (type == 0) {
                    // server_name_list: list length (2), then type (1), length (2), name
                    if (p + 5 > end) return null
                    val nameType = d[p + 2].toInt() and 0xFF
                    val nameLen = Dns.u16(d, p + 3)
                    if (nameType != 0 || p + 5 + nameLen > d.size) return null
                    return String(d, p + 5, nameLen, Charsets.US_ASCII)
                }
                p += len
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun httpHost(d: ByteArray): String? {
        val text = String(d, 0, minOf(d.size, 4096), Charsets.ISO_8859_1)
        if (METHODS.none { text.startsWith(it) }) return null
        for (line in text.split("\r\n").drop(1)) {
            if (line.isEmpty()) break
            if (line.regionMatches(0, "host:", 0, 5, ignoreCase = true)) {
                val value = line.substring(5).trim()
                return if (value.startsWith("[")) {
                    value.substringAfter('[').substringBefore(']')
                } else {
                    value.substringBefore(':')
                }
            }
        }
        return null
    }
}
