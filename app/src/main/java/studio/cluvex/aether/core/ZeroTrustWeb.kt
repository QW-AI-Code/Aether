package studio.cluvex.aether.core

/**
 * The pure-text half of a Cloudflare Access sign-in: names, URLs and the few
 * things that have to be read off Cloudflare's pages.
 *
 * ## Why these are ports and not fresh inventions
 *
 * Every function here mirrors the engine function of the same name in
 * `native/aether/aether/src/zerotrust.rs`, rule for rule, and the unit tests are
 * the engine's own cases carried over. That is the point: the pre-connect sign-in
 * ([ZeroTrustSignIn]) produces the token the engine then consumes through
 * `AETHER_ACCESS_TOKEN`, so the app must agree with the engine on what a team
 * name is, what a token looks like and where on the page it sits. Two parsers
 * that disagree would give "signed in" on this screen and "not a jwt" in the
 * engine.
 *
 * Two deliberate differences, both in [queryValue] and both noted there: a query
 * pair without `=` is skipped instead of abandoning the whole URL, and a
 * `#fragment` is not read as part of the last value.
 *
 * No Android imports, so all of it runs under plain JUnit.
 */
object ZeroTrustWeb {

    /** `zerotrust::TEAM_SUFFIX`. */
    const val TEAM_SUFFIX = "cloudflareaccess.com"

    /** `zerotrust::ENROLL_PATH`. */
    private const val ENROLL_PATH = "/warp"

    /**
     * `zerotrust::normalize_team`: "acme", "acme.cloudflareaccess.com",
     * "https://Acme.cloudflareaccess.com/warp" all become "acme". Null when
     * nothing usable is left.
     */
    fun normalizeTeam(raw: String): String? {
        var value = raw.trim().lowercase(java.util.Locale.ROOT)
        if (value.isEmpty()) return null
        // Sequential, like the engine's loop over the two prefixes.
        for (prefix in listOf("https://", "http://")) {
            if (value.startsWith(prefix)) value = value.removePrefix(prefix)
        }
        value = value.trimEnd('/')
        value = value.substringBefore('/')
        if (value.endsWith(TEAM_SUFFIX)) {
            value = value.removeSuffix(TEAM_SUFFIX).trimEnd('.')
        }
        if (value.isEmpty()) return null
        if (!value.all { it.isAsciiAlnum() || it == '-' || it == '_' }) return null
        return value
    }

    /** `zerotrust::team_domain`. */
    fun teamDomain(team: String): String = "https://$team.$TEAM_SUFFIX"

    /** The host part of [teamDomain], for cookie scoping. */
    fun teamHost(team: String): String = "$team.$TEAM_SUFFIX"

    /** `TeamSettings::login_url`: the device-enrolment page. */
    fun loginUrl(team: String): String = teamDomain(team) + ENROLL_PATH

    /** Where the e-mailed code is confirmed. Same path the engine posts to. */
    fun callbackUrl(team: String): String = teamDomain(team) + "/cdn-cgi/access/callback"

    /**
     * `zerotrust::extract_jwt_from_html`: the token after the first `token=`,
     * up to a quote, `&`, `<` or whitespace, if it passes the engine's JWT shape
     * check. Works on any text, so it is also used on a `Location` header.
     */
    fun extractJwtFromHtml(html: String): String? {
        val marker = "token="
        val at = html.indexOf(marker)
        if (at < 0) return null
        val rest = html.substring(at + marker.length)
        val end = rest.indexOfFirst {
            it == '"' || it == '\'' || it == '&' || it == '<' || it.isWhitespace()
        }.let { if (it < 0) rest.length else it }
        val candidate = rest.substring(0, end).trim()
        return candidate.takeIf { AccessToken.looksLikeJwt(it) }
    }

    /**
     * `zerotrust::extract_jwt_from_cookie`, for one `Set-Cookie` header.
     *
     * Faithful to the engine including its shape: it gives up at the first part
     * that is not the authorization cookie, which in practice means it reads the
     * cookie's `name=value` and ignores the attributes after it.
     */
    fun extractJwtFromCookie(header: String): String? {
        for (part in header.split(';')) {
            val entry = part.trim()
            val value = when {
                entry.startsWith("CF_Authorization=") -> entry.removePrefix("CF_Authorization=")
                entry.startsWith("cf_authorization=") -> entry.removePrefix("cf_authorization=")
                else -> return null
            }
            if (AccessToken.looksLikeJwt(value)) return value
        }
        return null
    }

    /**
     * `zerotrust::extract_totp_form_action`: the `action` of the form with
     * `id="totp-form"` - the "e-mail me a code" form on the enrolment page -
     * entity-decoded, and only if it is an absolute https URL. A team that has
     * not enabled one-time PINs has no such form, and this returns null.
     */
    fun extractTotpFormAction(html: String): String? {
        val anchor = html.indexOf("id=\"totp-form\"")
        if (anchor < 0) return null
        val formStart = html.substring(0, anchor).lastIndexOf("<form")
        if (formStart < 0) return null
        val tagEnd = html.indexOf('>', formStart)
        if (tagEnd < 0) return null
        val tag = html.substring(formStart, tagEnd)

        val key = "action="
        val keyAt = tag.indexOf(key)
        if (keyAt < 0) return null
        val rest = tag.substring(keyAt + key.length).trimStart()
        val quote = rest.firstOrNull() ?: return null
        if (quote != '\'' && quote != '"') return null
        val body = rest.substring(1)
        val end = body.indexOf(quote)
        if (end < 0) return null
        val action = decodeEntities(body.substring(0, end))
        return action.takeIf { it.startsWith("https://") }
    }

    /** `zerotrust::decode_entities`: named, decimal and hex entities; others kept. */
    fun decodeEntities(raw: String): String {
        val out = StringBuilder(raw.length)
        var rest = raw
        while (true) {
            val at = rest.indexOf('&')
            if (at < 0) break
            out.append(rest, 0, at)
            rest = rest.substring(at)

            val semi = rest.indexOf(';')
            if (semi < 0 || semi > 8) {
                out.append('&')
                rest = rest.substring(1)
                continue
            }
            val entity = rest.substring(1, semi)
            val decoded: String? = when (entity) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos", "#39" -> "'"
                else -> numericEntity(entity)
            }
            if (decoded != null) {
                out.append(decoded)
                rest = rest.substring(semi + 1)
            } else {
                out.append('&')
                rest = rest.substring(1)
            }
        }
        out.append(rest)
        return out.toString()
    }

    private fun numericEntity(entity: String): String? {
        val code: Int? = when {
            entity.startsWith("#x") || entity.startsWith("#X") ->
                entity.substring(2).toIntOrNull(16)
            entity.startsWith("#") -> entity.substring(1).toIntOrNull()
            else -> null
        }
        if (code == null || !Character.isValidCodePoint(code)) return null
        // Rust's char::from_u32 refuses surrogates; so does this.
        if (code in 0xD800..0xDFFF) return null
        return String(Character.toChars(code))
    }

    /**
     * `zerotrust::query_value`: the percent-decoded value of [key] in [url]'s
     * query, or null.
     *
     * Two intentional divergences. The engine abandons the whole URL at the
     * first pair without `=`; this skips that pair - Cloudflare does not send
     * such pairs, and a bare flag in front of `nonce` must not hide the nonce.
     * And a `#fragment` ends the query here (RFC 3986), where the engine would
     * glue it onto the last value; reqwest never hands the engine a fragment, so
     * for every URL the engine can actually see, both agree.
     */
    fun queryValue(url: String, key: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        val query = url.substring(q + 1).substringBefore('#')
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            if (pair.substring(0, eq) == key) return percentDecode(pair.substring(eq + 1))
        }
        return null
    }

    /** `zerotrust::percent_decode`, including `+` as a space. */
    fun percentDecode(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            when {
                b == '%'.code.toByte() && i + 2 < bytes.size -> {
                    val hi = hexDigit(bytes[i + 1])
                    val lo = hexDigit(bytes[i + 2])
                    if (hi >= 0 && lo >= 0) {
                        val hex = (hi shl 4) or lo
                        out.write(hex)
                        i += 3
                    } else {
                        out.write(b.toInt())
                        i += 1
                    }
                }
                b == '+'.code.toByte() -> {
                    out.write(' '.code)
                    i += 1
                }
                else -> {
                    out.write(b.toInt())
                    i += 1
                }
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * A plausible e-mail address to ask Cloudflare to write to. Deliberately
     * loose - one `@` with something on both sides, no whitespace - because the
     * organization decides who may sign in, not this check. It only exists so a
     * half-typed address fails here instead of as a Cloudflare error page.
     */
    fun plausibleEmail(raw: String): Boolean {
        val email = raw.trim()
        if (email.length !in 3..254) return false
        if (email.any { it.isWhitespace() || it.code < 0x20 }) return false
        val at = email.indexOf('@')
        return at > 0 && at == email.lastIndexOf('@') && at < email.length - 1
    }

    /** 0..15 for an ASCII hex digit, -1 otherwise (no sign, no whitespace). */
    private fun hexDigit(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun Char.isAsciiAlnum(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
