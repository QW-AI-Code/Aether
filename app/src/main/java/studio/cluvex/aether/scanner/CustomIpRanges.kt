package studio.cluvex.aether.scanner

import java.util.Locale

/**
 * User-supplied Cloudflare IPv4 ranges for the clean-IP scanner.
 *
 * Accepted entries (separated by newline, space, comma or semicolon):
 *  * CIDR       `104.16.0.0/20`  (prefix 8..32; network/broadcast are skipped up to /30)
 *  * single IP  `104.16.1.5`
 *  * range      `104.16.1.1-104.16.1.80`
 *
 * Only public unicast IPv4 space is accepted. Loopback, private (RFC 1918), link-local,
 * "this network" and multicast/reserved space are rejected, so a typo can never turn the
 * scanner into a probe of a local network.
 */
internal object CustomIpRanges {

    data class Range(val start: Long, val end: Long) {
        val size: Long get() = end - start + 1
        operator fun contains(value: Long): Boolean = value in start..end
    }

    data class Parsed(val ranges: List<Range>, val invalid: List<String>)

    const val MAX_ENTRIES = 100

    /** Spaces at or below this total size are enumerated; larger ones are sampled randomly. */
    const val ENUMERATE_LIMIT = 50_000L

    private val SEPARATORS = Regex("[\\s,;\u060C]+")

    private val RESERVED = listOf(
        Range(0x00000000L, 0x00FFFFFFL), // 0.0.0.0/8
        Range(0x0A000000L, 0x0AFFFFFFL), // 10.0.0.0/8
        Range(0x7F000000L, 0x7FFFFFFFL), // 127.0.0.0/8
        Range(0xA9FE0000L, 0xA9FEFFFFL), // 169.254.0.0/16
        Range(0xAC100000L, 0xAC1FFFFFL), // 172.16.0.0/12
        Range(0xC0A80000L, 0xC0A8FFFFL), // 192.168.0.0/16
        Range(0xE0000000L, 0xFFFFFFFFL), // 224.0.0.0/3 multicast + reserved
    )

    fun parse(text: String): Parsed {
        val tokens = normalizeDigits(text).split(SEPARATORS).filter { it.isNotEmpty() }
        val ranges = ArrayList<Range>()
        val invalid = ArrayList<String>()
        for (token in tokens.take(MAX_ENTRIES)) {
            val range = parseEntry(token)
            if (range == null) invalid += token else ranges += range
        }
        if (tokens.size > MAX_ENTRIES) invalid += tokens.drop(MAX_ENTRIES)
        return Parsed(merge(ranges), invalid)
    }

    /** Valid ranges of [text]; empty when nothing usable was entered. */
    fun rangesOf(text: String): List<Range> = parse(text).ranges

    private fun parseEntry(token: String): Range? {
        val range = when {
            '/' in token -> parseCidr(token)
            '-' in token -> parseSpan(token)
            else -> ipToLong(token)?.let { Range(it, it) }
        } ?: return null
        return range.takeIf { r -> RESERVED.none { it.start <= r.end && r.start <= it.end } }
    }

    private fun parseCidr(token: String): Range? {
        val parts = token.split('/')
        if (parts.size != 2) return null
        val base = ipToLong(parts[0]) ?: return null
        val prefix = parts[1].takeIf { it.isNotEmpty() && it.length <= 2 && it.all { c -> c in '0'..'9' } }
            ?.toInt()?.takeIf { it in 8..32 } ?: return null
        val hostBits = 32 - prefix
        val mask = (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val first = base and mask
        val last = first or (0xFFFFFFFFL and mask.inv())
        // Skip the network and broadcast addresses of real subnets.
        return if (prefix <= 30) Range(first + 1, last - 1) else Range(first, last)
    }

    private fun parseSpan(token: String): Range? {
        val parts = token.split('-')
        if (parts.size != 2) return null
        val a = ipToLong(parts[0]) ?: return null
        val b = ipToLong(parts[1]) ?: return null
        return if (a <= b) Range(a, b) else null
    }

    /** Sorted, overlap-free list. */
    private fun merge(input: List<Range>): List<Range> {
        val out = ArrayList<Range>()
        for (r in input.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end + 1) {
                out[out.size - 1] = Range(last.start, maxOf(last.end, r.end))
            } else {
                out += r
            }
        }
        return out
    }

    fun total(ranges: List<Range>): Long = ranges.sumOf { it.size }

    /** Address number [index] (0-based) counted across [ranges]. */
    fun addressAt(ranges: List<Range>, index: Long): Long {
        var left = index
        for (r in ranges) {
            if (left < r.size) return r.start + left
            left -= r.size
        }
        error("index out of range")
    }

    fun ipToLong(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' }) return null
            val n = p.toInt()
            if (n > 255) return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    fun longToIp(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
    }

    /** Persian / Arabic-Indic digits are accepted too. */
    private fun normalizeDigits(text: String): String = buildString(text.length) {
        for (c in text) {
            val d = if (c.code > 127) Character.digit(c, 10) else -1
            append(if (d >= 0) ('0' + d) else c)
        }
    }.lowercase(Locale.ROOT)
}
