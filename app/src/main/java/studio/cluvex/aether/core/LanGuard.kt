package studio.cluvex.aether.core

import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The access-control primitives of the LAN sharing bridge (audit 1.2.9-r3, F-5).
 *
 * Deliberately pure Kotlin + `java.*`: no Android type appears here, so every rule
 * below is covered by ordinary JVM unit tests (`LanGuardTest`) instead of being
 * "reviewed and believed". An authentication check that is only exercised by
 * hand on one phone is an authentication check that regresses.
 *
 * ## What the bridge got wrong
 *
 * With sharing on, `ShareBridge` bound SOCKS5 and HTTP proxies on `0.0.0.0` with
 * **no credential and no source restriction**. Any device on the same Wi-Fi -
 * every guest on a cafe network, every other subscriber on a shared hotspot -
 * could use the tunnel, and every byte they sent was attributed to this user's
 * exit IP. For a censorship-circumvention tool that is not just abuse of
 * bandwidth: it is someone else's traffic leaving under the user's identity.
 *
 * ## The two rules
 *
 *  1. **Where from.** A remote client must come from an address that is actually
 *     local: RFC1918 / RFC4193 / link-local / loopback. Carrier-grade NAT
 *     (`100.64.0.0/10`) and every public address are refused, because a phone on
 *     mobile data can be reachable from other subscribers inside the same CGNAT
 *     pool, and nothing about that is a "local network".
 *  2. **Who.** A remote client must authenticate: SOCKS5 username/password
 *     (RFC 1929) or HTTP `Proxy-Authorization: Basic`. Loopback is exempt, which
 *     keeps on-device apps and the app's own proxy-mode self-test working exactly
 *     as before - the credential exists to gate the LAN, not the phone itself.
 */
object LanGuard {

    /**
     * True when [address] belongs to a genuinely local scope.
     *
     * `isSiteLocalAddress` covers `10/8`, `172.16/12`, `192.168/16` (and the
     * deprecated IPv6 `fec0::/10`); IPv6 unique-local `fc00::/7` is checked
     * explicitly because the JDK has no predicate for it. IPv4-mapped IPv6
     * (`::ffff:192.168.1.5`, which is what a dual-stack listener reports for an
     * IPv4 peer) is unwrapped first, otherwise every IPv4 client on the LAN would
     * be classified by the wrong family.
     */
    fun isLocalNetworkAddress(address: InetAddress?): Boolean {
        val addr = unwrapMapped(address ?: return false)
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) return true
        if (addr is Inet6Address) {
            val first = addr.address.firstOrNull()?.toInt()?.and(0xFE) ?: return false
            // fc00::/7 -> unique local addresses.
            return first == 0xFC
        }
        return false
    }

    /** `::ffff:a.b.c.d` -> `a.b.c.d`; anything else is returned untouched. */
    private fun unwrapMapped(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val raw = address.address
        if (raw.size != 16) return address
        val mappedPrefix = raw.copyOfRange(0, 12)
        val expected = ByteArray(12).also { it[10] = 0xFF.toByte(); it[11] = 0xFF.toByte() }
        if (!mappedPrefix.contentEquals(expected)) return address
        return runCatching { InetAddress.getByAddress(raw.copyOfRange(12, 16)) }.getOrDefault(address)
    }

    /** True for the one address family/scope that never needs a credential. */
    fun isLoopback(address: InetAddress?): Boolean {
        val addr = address ?: return false
        return unwrapMapped(addr).isLoopbackAddress
    }

    /** The value an HTTP client must send in `Proxy-Authorization`. */
    fun basicAuthValue(user: String, password: String): String =
        "Basic " + Base64.getEncoder()
            .encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    /**
     * Parses a `Proxy-Authorization` header value into user + password.
     *
     * Returns null for anything that is not a well-formed `Basic` credential -
     * a different scheme, broken base64, or a payload with no colon in it - so a
     * malformed header can only ever mean "not authenticated".
     */
    fun parseBasicCredential(headerValue: String): Pair<String, String>? {
        val trimmed = headerValue.trim()
        if (!trimmed.regionMatches(0, "Basic", 0, 5, ignoreCase = true)) return null
        val encoded = trimmed.substring(5).trim().takeIf { it.isNotEmpty() } ?: return null
        val decoded = runCatching {
            String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val split = decoded.indexOf(':')
        if (split < 0) return null
        return decoded.substring(0, split) to decoded.substring(split + 1)
    }

    /**
     * Length-independent constant-time comparison.
     *
     * Compares SHA-256 digests rather than the strings themselves:
     * `MessageDigest.isEqual` is only constant-time for equal-length inputs, and
     * comparing raw passwords would leak the length of the expected one through
     * timing. Both operands are fixed-size here, so the comparison tells an
     * attacker nothing but the answer.
     */
    fun secretEquals(expected: String, offered: String): Boolean {
        if (expected.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val a = digest.digest(expected.toByteArray(Charsets.UTF_8))
        val b = MessageDigest.getInstance("SHA-256").digest(offered.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(a, b)
    }

    /**
     * A shareable password: [length] characters from an alphabet with no
     * look-alikes (no `0/O`, no `1/l/I`), because this is a string a human reads
     * off one screen and types into another device.
     *
     * 16 characters over a 57-symbol alphabet is ~93 bits - far past anything a
     * LAN attacker can grind against a proxy that also rate-limits by refusing
     * non-local sources.
     */
    fun randomPassword(length: Int = 16): String {
        val random = SecureRandom()
        val builder = StringBuilder(length)
        repeat(length) { builder.append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]) }
        return builder.toString()
    }

    /** Hex SHA-256, lowercase, no separators. Used for signer fingerprints. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private const val PASSWORD_ALPHABET =
        // No 0/O/o, no 1/l/I: this password gets read off one screen and typed on
        // another device, and 'o' next to '0' is exactly the pair that makes a
        // shared-LAN password look broken when it is merely mistyped. 'o' was
        // still in this alphabet until the unit test drew it (1.3.0).
        "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
}
