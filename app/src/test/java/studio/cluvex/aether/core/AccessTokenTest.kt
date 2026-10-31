package studio.cluvex.aether.core

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app is allowed to conclude about a pasted Zero Trust enrolment token.
 *
 * Written because this is the one Zero Trust path a user drives by hand: they copy
 * a value out of a browser and paste it into a text field. The two mistakes that
 * path invites — pasting the wrong thing, and pasting something that expired — used
 * to surface as a connect that failed with the reason buried in the diagnostics
 * log. Every case below is one of those mistakes, or a shape that must NOT be
 * rejected, because a false rejection of a good token is worse than no check.
 */
class AccessTokenTest {

    /** A JWT with the given payload, base64url and unpadded, as they arrive. */
    private fun jwt(payload: String, header: String = """{"alg":"HS256","typ":"JWT"}"""): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString(header.toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + "." +
            "c2lnbmF0dXJl"
    }

    // ---------------------------------------------------------------- shape

    @Test
    fun `an empty or blank value is simply empty, not malformed`() {
        assertEquals(AccessToken.Verdict.Empty, AccessToken.inspect(""))
        assertEquals(AccessToken.Verdict.Empty, AccessToken.inspect("   "))
        assertEquals(AccessToken.Verdict.Empty, AccessToken.inspect("\n\t "))
    }

    @Test
    fun `the enrolment URL pasted instead of the token is malformed`() {
        // The likeliest wrong paste of all: the whole address bar.
        assertEquals(
            AccessToken.Verdict.Malformed,
            AccessToken.inspect("https://acme.cloudflareaccess.com/warp?token=abc"),
        )
    }

    @Test
    fun `two segments or four segments are malformed`() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val head = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        assertEquals(AccessToken.Verdict.Malformed, AccessToken.inspect("$head.payload"))
        assertEquals(AccessToken.Verdict.Malformed, AccessToken.inspect("$head.a.b.c"))
    }

    @Test
    fun `an empty segment is malformed even with the right number of dots`() {
        assertEquals(AccessToken.Verdict.Malformed, AccessToken.inspect("..'"))
        assertEquals(AccessToken.Verdict.Malformed, AccessToken.inspect("a..c"))
    }

    @Test
    fun `a header that is not base64url is malformed`() {
        assertFalse(AccessToken.looksLikeJwt("not+valid/base64url==.payload.sig"))
    }

    @Test
    fun `whitespace around a good token is tolerated`() {
        // Clipboard pastes routinely carry a trailing newline.
        val token = jwt("""{"exp":4000000000}""")
        val verdict = AccessToken.inspect("  $token\n", nowSeconds = 1_000_000_000L)
        assertTrue(verdict is AccessToken.Verdict.Usable)
    }

    // ---------------------------------------------------------------- expiry

    @Test
    fun `a token whose exp is in the past is expired`() {
        val token = jwt("""{"exp":1500000000}""")
        assertEquals(AccessToken.Verdict.Expired, AccessToken.inspect(token, nowSeconds = 1_600_000_000L))
    }

    @Test
    fun `exp exactly now counts as expired`() {
        // The boundary belongs to the safe side: a token expiring this second is
        // not something to start a connect with.
        val token = jwt("""{"exp":1600000000}""")
        assertEquals(AccessToken.Verdict.Expired, AccessToken.inspect(token, nowSeconds = 1_600_000_000L))
    }

    @Test
    fun `a token in date reports the whole days left`() {
        val now = 1_600_000_000L
        val token = jwt("""{"exp":${now + 3 * 86_400 + 500}}""")
        assertEquals(AccessToken.Verdict.Usable(3L), AccessToken.inspect(token, nowSeconds = now))
    }

    @Test
    fun `a token expiring within the day reports zero days, not expired`() {
        val now = 1_600_000_000L
        val token = jwt("""{"exp":${now + 3_600}}""")
        assertEquals(AccessToken.Verdict.Usable(0L), AccessToken.inspect(token, nowSeconds = now))
    }

    @Test
    fun `no exp claim is usable with an unknown expiry rather than rejected`() {
        // Cloudflare's tokens carry exp, but a token the app cannot date is still
        // a token the engine may well accept. Refusing it would be the app
        // overruling the only party that can actually decide.
        val token = jwt("""{"sub":"someone@example.com"}""")
        assertEquals(AccessToken.Verdict.Usable(null), AccessToken.inspect(token, nowSeconds = 1L))
        assertNull(AccessToken.expirySeconds(token))
    }

    @Test
    fun `a payload that is not json is usable with an unknown expiry`() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val token = enc.encodeToString("""{"alg":"none"}""".toByteArray()) +
            "." + enc.encodeToString("not json at all".toByteArray()) + ".sig"
        assertEquals(AccessToken.Verdict.Usable(null), AccessToken.inspect(token, nowSeconds = 1L))
    }

    @Test
    fun `exp is read with spaces around the colon`() {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val token = enc.encodeToString("""{"alg":"none"}""".toByteArray()) +
            "." + enc.encodeToString("""{ "exp" :  4000000000 }""".toByteArray()) + ".sig"
        assertEquals(4_000_000_000L, AccessToken.expirySeconds(token))
    }

    @Test
    fun `an absurdly long exp does not blow up the parse`() {
        // 25 digits: the regex caps at 19, so this must not be read as a number
        // rather than overflowing one.
        val enc = Base64.getUrlEncoder().withoutPadding()
        val token = enc.encodeToString("""{"alg":"none"}""".toByteArray()) +
            "." + enc.encodeToString("""{"exp":12345678901234567890123456}""".toByteArray()) + ".sig"
        // Whatever it reads, it must not throw and must not report expired.
        val verdict = AccessToken.inspect(token, nowSeconds = 1_600_000_000L)
        assertTrue(verdict is AccessToken.Verdict.Usable)
    }
}
