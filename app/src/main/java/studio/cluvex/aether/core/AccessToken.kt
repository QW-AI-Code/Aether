package studio.cluvex.aether.core

import java.util.Base64

/**
 * Sanity checks for a pasted Zero Trust enrolment token.
 *
 * ## Why the app checks this at all (issue #12)
 *
 * The enrolment token is the one Zero Trust path that works without the app
 * driving a sign-in: the user obtains it in a browser at
 * `https://<team>.cloudflareaccess.com/warp` and pastes it in. Which means the
 * two most likely failures are a paste of the wrong thing and a token that has
 * quietly expired — and both used to surface as a connect that failed with
 * nothing an ordinary user could act on, because the engine's complaint is one
 * line in the diagnostics log.
 *
 * So the same two checks the engine performs (`zerotrust::looks_like_jwt` and
 * `zerotrust::jwt_expired`, which it applies in `store_token` and again in
 * `sign_in`) are mirrored here, where they can be shown next to the field while
 * the value is being pasted.
 *
 * ## Deliberately NOT verification
 *
 * Nothing here is a security check. The signature is not verified — it cannot be,
 * the key belongs to Cloudflare — and a token that passes is not thereby
 * trustworthy. The engine remains the only thing that decides whether a token is
 * accepted. This exists to catch "you pasted the URL instead of the token" and
 * "this expired last week" before a user spends two minutes on a failing connect.
 */
object AccessToken {

    /** What [inspect] concluded. */
    sealed interface Verdict {
        /** Nothing entered yet. */
        data object Empty : Verdict

        /** Not three dot-separated base64url segments with a decodable header. */
        data object Malformed : Verdict

        /** Parsed, and the `exp` claim is in the past. */
        data object Expired : Verdict

        /** Parsed and in date. [daysLeft] is null when there is no `exp` claim. */
        data class Usable(val daysLeft: Long?) : Verdict
    }

    /**
     * Classifies [raw].
     *
     * [nowSeconds] is a parameter rather than read from the clock so the tests can
     * pin it; callers pass nothing.
     */
    fun inspect(raw: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): Verdict {
        val token = raw.trim()
        if (token.isEmpty()) return Verdict.Empty
        if (!looksLikeJwt(token)) return Verdict.Malformed
        val expiry = expirySeconds(token) ?: return Verdict.Usable(null)
        if (expiry <= nowSeconds) return Verdict.Expired
        return Verdict.Usable(daysLeft = (expiry - nowSeconds) / 86_400L)
    }

    /**
     * Three non-empty dot-separated segments whose first one decodes as base64url.
     *
     * Mirrors the engine: it does not look at the header's CONTENT either, because
     * a JWT the app cannot parse is still a JWT and refusing it here would be the
     * app second-guessing Cloudflare.
     */
    fun looksLikeJwt(token: String): Boolean {
        val trimmed = token.trim()
        // r8: the two engine rules this used to skip. `zerotrust::looks_like_jwt`
        // also refuses anything under 32 characters and anything outside the
        // base64url alphabet plus dots. Without them a paste with a space or a
        // stray quote inside passed here as "looks valid" and was then refused by
        // the engine as "not a jwt" - the exact disagreement this class exists to
        // prevent. Now both say what the engine will say.
        if (trimmed.length < MIN_JWT_LENGTH) return false
        val segments = trimmed.split('.')
        if (segments.size != 3) return false
        if (segments.any { it.isEmpty() }) return false
        if (!trimmed.all { it.isJwtChar() }) return false
        return decodeSegment(segments[0]) != null
    }

    /** `zerotrust::looks_like_jwt`'s minimum length. */
    private const val MIN_JWT_LENGTH = 32

    private fun Char.isJwtChar(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' ||
            this == '.' || this == '-' || this == '_'

    /** The `exp` claim in seconds since the epoch, or null when absent/unreadable. */
    fun expirySeconds(token: String): Long? {
        val segments = token.split('.')
        if (segments.size != 3) return null
        val payload = decodeSegment(segments[1])?.toString(Charsets.UTF_8) ?: return null
        // A minimal scan rather than a JSON parser: the payload is attacker-shaped
        // input from the app's point of view, and the only field wanted is a
        // number. Matches `"exp"` followed by a colon and digits, ignoring spaces.
        val match = EXP.find(payload) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private val EXP = Regex("\"exp\"\\s*:\\s*(\\d{1,19})")

    /**
     * base64url, padding optional — the JWT flavour.
     *
     * `java.util.Base64` rather than `android.util.Base64`, deliberately: this
     * class is covered by plain JUnit tests, and the Android one is a stub that
     * throws "not mocked" off-device. It exists from API 26 and `minSdk` here is
     * 26, so nothing is given up.
     *
     * A decode failure is null rather than an exception: being handed something
     * that is not a token is the expected case here, not an error.
     */
    private fun decodeSegment(segment: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(segment)
    }.getOrNull()
}
