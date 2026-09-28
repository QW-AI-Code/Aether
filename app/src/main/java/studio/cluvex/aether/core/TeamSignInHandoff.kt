package studio.cluvex.aether.core

/**
 * Decides, right before a connect, whether the pre-connect Zero Trust sign-in is
 * handed to the engine (1.4.0-r9, issue #12).
 *
 * ## Why this is one function
 *
 * Two places have to reach the same answer: the VPN service, which puts the token
 * into the engine's environment (`AetherVpnService.hydrateSecrets`), and the
 * Settings screen, which tells the user beforehand what the next connect will do.
 * If they decided separately, the screen could say "signed in, nothing to do" while
 * the service quietly fell back to the in-connect code prompt. So both call
 * [decide], and the pure part is covered by JUnit.
 *
 * ## The order, and why
 *
 *  1. **No sign-in** - nothing stored: the e-mail method works as it always did.
 *  2. **Another profile** - the stored sign-in names a different team or address
 *     than Settings does now. It is kept (the user may switch back) but never sent:
 *     a token must not enrol this device into an organization, or as a person, the
 *     screen no longer names.
 *  3. **Already enrolled** - the engine has an identity for this team, so it will
 *     load that and never look at a token (`load_or_provision_*` only calls
 *     `resolve_token` when it has to provision). The sign-in was spent by the
 *     connect that enrolled; it is discarded rather than kept as a dormant
 *     credential.
 *  4. **Unusable** - expired, or not a token: discarded, and the connect falls back
 *     to the code prompt, which is exactly the behaviour without a sign-in.
 *  5. **Use** it.
 */
object TeamSignInHandoff {

    sealed interface Decision {
        /** Nothing stored. */
        data object NoSignIn : Decision

        /** Stored for a different team or address; kept, not used. */
        data object OtherProfile : Decision

        /** The device already holds an identity for this team; discard. */
        data object AlreadyEnrolled : Decision

        /** Expired or malformed; discard. */
        data object Unusable : Decision

        /** Hand [token] to the engine as `AETHER_ACCESS_TOKEN`. */
        class Use(val token: String, val record: TeamSignInRecord) : Decision {
            /** Never the token itself; see [TeamSignInRecord.toString]. */
            override fun toString(): String = "Use($record)"
        }
    }

    /**
     * [isEnrolled] is asked with the NORMALISED team name
     * ([TeamSignInRecord.team]); production passes
     * `{ TeamMembership.isEnrolled(filesDir, it) }`.
     */
    fun decide(
        record: TeamSignInRecord?,
        rawTeam: String,
        rawEmail: String,
        isEnrolled: (String) -> Boolean,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Decision {
        if (record == null) return Decision.NoSignIn
        if (!record.matches(rawTeam, rawEmail)) return Decision.OtherProfile
        if (isEnrolled(record.team)) return Decision.AlreadyEnrolled
        val token = record.usableToken(rawTeam, rawEmail, nowSeconds) ?: return Decision.Unusable
        return Decision.Use(token, record)
    }
}
