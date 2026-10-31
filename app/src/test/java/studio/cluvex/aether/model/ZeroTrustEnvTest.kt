package studio.cluvex.aether.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the engine is told for each Zero Trust method (1.4.0-r9).
 *
 * The new case is the e-mail method with a pre-connect sign-in: the token goes
 * in, the address stays out, so the connect cannot fall into the code prompt.
 * The other cases pin that nothing else changed.
 */
class ZeroTrustEnvTest {

    private val base = ConnectionProfile(team = "acme", accessEmail = "me@example.com")

    @Test
    fun `e-mail without a sign-in hands over the address, as before`() {
        val env = base.copy(teamAuth = TeamAuth.EMAIL).toEnv()
        assertEquals("me@example.com", env["AETHER_ACCESS_EMAIL"])
        assertNull(env["AETHER_ACCESS_TOKEN"])
    }

    @Test
    fun `e-mail with a sign-in hands over the token and not the address`() {
        val env = base.copy(teamAuth = TeamAuth.EMAIL, accessSignInToken = " jwt.value.here ").toEnv()
        assertEquals("jwt.value.here", env["AETHER_ACCESS_TOKEN"])
        assertFalse(env.containsKey("AETHER_ACCESS_EMAIL"))
    }

    @Test
    fun `a pasted token left over from the token method is not used by the e-mail method`() {
        val env = base.copy(teamAuth = TeamAuth.EMAIL, accessToken = "pasted.old.token").toEnv()
        assertNull(env["AETHER_ACCESS_TOKEN"])
        assertEquals("me@example.com", env["AETHER_ACCESS_EMAIL"])
    }

    @Test
    fun `the sign-in token is ignored by every other method`() {
        val signIn = "signed.in.token"
        assertNull(base.copy(teamAuth = TeamAuth.OFF, accessSignInToken = signIn).toEnv()["AETHER_ACCESS_TOKEN"])
        assertNull(
            base.copy(teamAuth = TeamAuth.SERVICE_TOKEN, accessSignInToken = signIn).toEnv()["AETHER_ACCESS_TOKEN"],
        )
        assertEquals(
            "pasted.token.value",
            base.copy(teamAuth = TeamAuth.TOKEN, accessToken = "pasted.token.value", accessSignInToken = signIn)
                .toEnv()["AETHER_ACCESS_TOKEN"],
        )
    }

    @Test
    fun `no team means no credentials at all`() {
        val env = base.copy(team = "", teamAuth = TeamAuth.EMAIL, accessSignInToken = "signed.in.token").toEnv()
        assertNull(env["AETHER_ACCESS_TOKEN"])
        assertNull(env["AETHER_ACCESS_EMAIL"])
    }
}
