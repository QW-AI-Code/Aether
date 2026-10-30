package studio.cluvex.aether.model

/** Custom and ordinary sessions have independent default-on initial gates. Not a liveness switch. */
object VerificationPolicy {
    fun enabled(profile: ConnectionProfile, customVerify: Boolean?): Boolean =
        customVerify ?: profile.verifyConnection
}
