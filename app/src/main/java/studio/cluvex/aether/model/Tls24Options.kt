package studio.cluvex.aether.model

/**
 * Engine core 2.4.0 TLS / API options (the "TLS fingerprint & API" block of the
 * Windows build, ported to the phone).
 *
 * This file is only the VALUE layer: it validates what the settings screen
 * collected and turns it into the `AETHER_*` environment variables the core
 * already reads (see `aether/src/cli.rs`, "environment" table). It does not
 * touch the engine. Every rule below mirrors `safe_opt` / `tls24_env` of the
 * Windows build (`src-tauri/src/profile.rs`): a value with a character outside
 * the allowed set, or longer than the limit, is DROPPED instead of being handed
 * to the engine, because a value the engine cannot parse stops it at startup.
 * An empty field means "core default" and sends nothing.
 */
object Tls24Options {

    /** Allowed `--tls-cert-compression` algorithms, in the order the UI offers them. */
    val CERT_COMPRESSION = listOf("brotli", "zlib", "zstd")

    /** Allowed `--tls-alps` values; blank (off) is also valid and sends nothing. */
    val ALPS = listOf("new", "old")

    const val MAX_PADDING = 65_535

    /**
     * Trimmed [raw] if it is non-empty, at most [max] long and made only of ASCII
     * letters, digits and the characters in [extra]; otherwise null.
     */
    fun safeOpt(raw: String, extra: String, max: Int): String? {
        val v = raw.trim()
        if (v.isEmpty() || v.length > max) return null
        val ok = v.all { c ->
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || extra.indexOf(c) >= 0
        }
        return if (ok) v else null
    }

    fun masqueSni(raw: String): String? = safeOpt(raw, ".-_", 253)
    fun apiSni(raw: String): String? = safeOpt(raw, ".-_", 253)
    fun apiHost(raw: String): String? = safeOpt(raw, ".-_", 253)
    fun tls13Ciphers(raw: String): String? = safeOpt(raw, "_:", 512)
    fun keyShares(raw: String): String? = safeOpt(raw, "-_:", 256)
    fun sigalgs(raw: String): String? = safeOpt(raw, "_:", 1024)

    /** Colon-separated list of zlib / brotli / zstd. */
    fun certCompression(raw: String): String? =
        safeOpt(raw, ":", 64)?.takeIf { v ->
            v.split(':').all { it in CERT_COMPRESSION }
        }

    fun alps(raw: String): String? = raw.trim().takeIf { it in ALPS }

    /** Dotted numeric IDs separated by a colon, e.g. `11129.9.1:11129.9.4`. */
    fun trustAnchors(raw: String): String? =
        safeOpt(raw, ".:", 512)?.takeIf { v ->
            v.split(':').all { id ->
                id.isNotEmpty() && id.split('.').all { n -> n.isNotEmpty() && n.all { it in '0'..'9' } }
            }
        }

    /** Server padding in bytes, 0 to 65535. */
    fun serverPadding(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty() || !v.all { it in '0'..'9' } || v.length > 10) return null
        val n = v.toLongOrNull() ?: return null
        return n.takeIf { it in 0..MAX_PADDING }?.toString()
    }

    /** The 2.4.0 environment for [p]; only options the user actually set. */
    fun env(p: ConnectionProfile): Map<String, String> = buildMap {
        fun opt(k: String, v: String?) { if (v != null) this.put(k, v) }
        opt("AETHER_API_SNI", apiSni(p.apiSni))
        opt("AETHER_API_HOST", apiHost(p.apiHost))
        opt("AETHER_MASQUE_SNI", masqueSni(p.masqueSni))
        opt("AETHER_TLS13_CIPHERS", tls13Ciphers(p.tls13Ciphers))
        opt("AETHER_TLS_KEY_SHARES", keyShares(p.tlsKeyShares))
        opt("AETHER_TLS_SIGALGS", sigalgs(p.tlsSigalgs))
        opt("AETHER_TLS_CERT_COMPRESSION", certCompression(p.tlsCertCompression))
        opt("AETHER_TLS_ALPS", alps(p.tlsAlps))
        opt("AETHER_TLS_TRUST_ANCHORS", trustAnchors(p.tlsTrustAnchors))
        opt("AETHER_TLS_SERVER_PADDING", serverPadding(p.tlsServerPadding))
        if (p.enableGreaseSigalgs) put("AETHER_ENABLE_GREASE_SIGALGS", "1")
        if (p.enableEchGrease) put("AETHER_ENABLE_ECH_GREASE", "1")
        if (p.disableOcspStapling) put("AETHER_DISABLE_OCSP_STAPLING", "1")
        if (p.disableSct) put("AETHER_DISABLE_SCT", "1")
        if (p.disableSessionTicket) put("AETHER_DISABLE_SESSION_TICKET", "1")
        if (p.disableExtensionPermutation) put("AETHER_DISABLE_EXTENSION_PERMUTATION", "1")
    }

    /** True when a non-blank field would be dropped by its sanitizer (UI error state). */
    fun invalid(raw: String, sanitized: String?): Boolean = raw.isNotBlank() && sanitized == null
}
