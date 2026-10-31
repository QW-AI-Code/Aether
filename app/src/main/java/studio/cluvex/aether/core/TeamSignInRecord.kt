package studio.cluvex.aether.core

/**
 * The result of a pre-connect Zero Trust sign-in, as it is kept on the device.
 *
 * ## Bound to a team AND an address
 *
 * The token is only ever handed to the engine for the team and the e-mail address
 * it was obtained for ([matches]). Change either field in Settings and the stored
 * token is ignored - never sent to a different organization, never used to enrol
 * as somebody the screen no longer names - and the screen asks for a new sign-in.
 *
 * ## Where it lives
 *
 * [encode]d as one string and sealed as ONE value in
 * [studio.cluvex.aether.data.SecretStore] (AES-256-GCM under a non-exportable
 * Android Keystore key), so the binding is inside the ciphertext: nobody can
 * re-point a sealed token at another team by editing a plain preference next to
 * it. See [studio.cluvex.aether.data.TeamSignInStore].
 *
 * Pure Kotlin, unit-tested.
 */
data class TeamSignInRecord(
    /** Normalised team name ([ZeroTrustWeb.normalizeTeam]). */
    val team: String,
    /** The address the code was mailed to, as typed (trimmed). */
    val email: String,
    /** The device-enrolment JWT Cloudflare issued. */
    val token: String,
    /** When it was obtained, seconds since the epoch. Informational. */
    val signedInAtSeconds: Long,
) {
    /**
     * True when this record belongs to [rawTeam] and [rawEmail] as they are now
     * in Settings. The team compares normalised (so "acme" and
     * "acme.cloudflareaccess.com" are one team); the address compares
     * case-insensitively, as mailbox providers treat it.
     */
    fun matches(rawTeam: String, rawEmail: String): Boolean {
        val team = ZeroTrustWeb.normalizeTeam(rawTeam) ?: return false
        return team == this.team && rawEmail.trim().equals(email, ignoreCase = true)
    }

    /** The engine's view of the token, for the screen and for [usableToken]. */
    fun verdict(nowSeconds: Long = System.currentTimeMillis() / 1000L): AccessToken.Verdict =
        AccessToken.inspect(token, nowSeconds)

    /**
     * Seconds until the token's `exp`, or null when it carries none. Negative
     * once it has expired. Cloudflare's enrolment tokens are short-lived, so the
     * screen needs finer steps than [AccessToken.Verdict.Usable.daysLeft].
     */
    fun secondsLeft(nowSeconds: Long = System.currentTimeMillis() / 1000L): Long? =
        AccessToken.expirySeconds(token)?.let { it - nowSeconds }

    /**
     * The token to hand the engine, or null when it must not be used: another
     * team or address, expired, or not a token at all. On null the connect falls
     * back to the in-connect code prompt, which is exactly what happened before
     * any sign-in existed.
     */
    fun usableToken(
        rawTeam: String,
        rawEmail: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String? {
        if (!matches(rawTeam, rawEmail)) return null
        return token.trim().takeIf { verdict(nowSeconds) is AccessToken.Verdict.Usable }
    }

    /**
     * Never the token. A data class prints every property by default, and this one
     * is a live organization credential: one stray `"$record"` in a log line would
     * put it into the diagnostics log that users paste into public issues.
     */
    override fun toString(): String =
        "TeamSignInRecord(team=$team, email=$email, token=<${token.length} chars>, " +
            "signedInAtSeconds=$signedInAtSeconds)"

    /** One line per field; none of them can contain a newline (see [decode]). */
    fun encode(): String = listOf(VERSION, team, email, signedInAtSeconds.toString(), token)
        .joinToString("\n")

    companion object {
        private const val VERSION = "zt-signin-v1"

        /**
         * Builds a record, or null if any field could not survive a round trip:
         * a team that does not normalise, an address or token with a line break.
         */
        fun of(rawTeam: String, email: String, token: String, nowSeconds: Long): TeamSignInRecord? {
            val team = ZeroTrustWeb.normalizeTeam(rawTeam) ?: return null
            val mail = email.trim()
            val jwt = token.trim()
            if (mail.isEmpty() || jwt.isEmpty()) return null
            if (mail.any { it == '\n' || it == '\r' } || jwt.any { it == '\n' || it == '\r' }) return null
            return TeamSignInRecord(team, mail, jwt, nowSeconds)
        }

        /** The inverse of [encode]; null for anything else, including "" (unset). */
        fun decode(raw: String): TeamSignInRecord? {
            val lines = raw.split('\n')
            if (lines.size != 5 || lines[0] != VERSION) return null
            val at = lines[3].toLongOrNull() ?: return null
            return of(lines[1], lines[2], lines[4], at)?.takeIf { it.team == lines[1] }
        }
    }
}
