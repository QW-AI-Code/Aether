package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored sign-in: what it is bound to, when it may be used, how it round-trips,
 * and that the decision the service acts on is the one the screen shows.
 */
class TeamSignInRecordTest {

    private val now = 1_760_000_000L
    private val live = TestJwt.make("""{"exp":${now + 3_600}}""")
    private val dead = TestJwt.make("""{"exp":${now - 1}}""")

    private fun record(token: String = live) =
        TeamSignInRecord.of("https://Acme.cloudflareaccess.com/warp", " Me@Example.com ", token, now)!!

    // ------------------------------------------------------------- binding

    @Test
    fun `a record is normalised and bound to its team and address`() {
        val r = record()
        assertEquals("acme", r.team)
        assertEquals("Me@Example.com", r.email)
        assertTrue(r.matches("acme", "me@example.com"))
        assertTrue(r.matches("acme.cloudflareaccess.com", "  ME@EXAMPLE.COM"))
        assertFalse(r.matches("acme-eu", "me@example.com"))
        assertFalse(r.matches("acme", "someone@example.com"))
        assertFalse(r.matches("", "me@example.com"))
    }

    @Test
    fun `the token is only usable for the same team and address, and only in date`() {
        assertEquals(live, record().usableToken("acme", "me@example.com", now))
        assertNull(record().usableToken("other", "me@example.com", now))
        assertNull(record().usableToken("acme", "you@example.com", now))
        assertNull(record(dead).usableToken("acme", "me@example.com", now))
        assertNull(record().usableToken("acme", "me@example.com", now + 3_600))
    }

    @Test
    fun `seconds left follow the exp claim`() {
        assertEquals(3_600L, record().secondsLeft(now))
        assertEquals(-1L, record(dead).secondsLeft(now))
        assertNull(record(TestJwt.make("""{"sub":"x"}""")).secondsLeft(now))
    }

    // --------------------------------------------------------- round trip

    @Test
    fun `encode and decode round trip`() {
        val r = record()
        assertEquals(r, TeamSignInRecord.decode(r.encode()))
    }

    @Test
    fun `anything that is not a record decodes to null`() {
        assertNull(TeamSignInRecord.decode(""))
        assertNull(TeamSignInRecord.decode("garbage"))
        assertNull(TeamSignInRecord.decode("zt-signin-v0\nacme\nme@example.com\n1\n$live"))
        assertNull(TeamSignInRecord.decode("zt-signin-v1\nacme\nme@example.com\nnot-a-number\n$live"))
        // A team that is not already normalised was not written by encode().
        assertNull(TeamSignInRecord.decode("zt-signin-v1\nACME\nme@example.com\n1\n$live"))
    }

    @Test
    fun `fields that would break the encoding are refused up front`() {
        assertNull(TeamSignInRecord.of("acme", "me@example.com\nx", live, now))
        assertNull(TeamSignInRecord.of("acme", "me@example.com", "a\nb", now))
        assertNull(TeamSignInRecord.of("acme", "  ", live, now))
        assertNull(TeamSignInRecord.of("not a team", "me@example.com", live, now))
    }

    @Test
    fun `the token never appears in toString`() {
        val printed = record().toString()
        assertFalse(printed.contains(live))
        assertTrue(printed.contains("acme"))
    }

    // ----------------------------------------------------------- hand-off

    private fun decide(r: TeamSignInRecord?, enrolled: Boolean = false, email: String = "me@example.com") =
        TeamSignInHandoff.decide(r, "acme", email, { enrolled }, now)

    @Test
    fun `nothing stored means the old behaviour`() {
        assertEquals(TeamSignInHandoff.Decision.NoSignIn, decide(null))
    }

    @Test
    fun `a sign-in for someone else is kept but not used`() {
        assertEquals(TeamSignInHandoff.Decision.OtherProfile, decide(record(), email = "you@example.com"))
    }

    @Test
    fun `an enrolled device does not need the token any more`() {
        assertEquals(TeamSignInHandoff.Decision.AlreadyEnrolled, decide(record(), enrolled = true))
    }

    @Test
    fun `an expired sign-in is unusable`() {
        assertEquals(TeamSignInHandoff.Decision.Unusable, decide(record(dead)))
    }

    @Test
    fun `a live matching sign-in is used`() {
        val use = decide(record()) as TeamSignInHandoff.Decision.Use
        assertEquals(live, use.token)
        assertFalse(use.toString().contains(live))
    }

    @Test
    fun `enrolment is asked with the normalised team`() {
        var asked: String? = null
        TeamSignInHandoff.decide(record(), "https://ACME.cloudflareaccess.com", "me@example.com", {
            asked = it
            false
        }, now)
        assertNotNull(asked)
        assertEquals("acme", asked)
    }
}
