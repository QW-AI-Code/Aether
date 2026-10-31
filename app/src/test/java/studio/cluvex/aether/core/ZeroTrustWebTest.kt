package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ZeroTrustWeb] against the engine's own cases.
 *
 * Every test named after a `zerotrust.rs` test carries that test's inputs and
 * expectations over unchanged: the pre-connect sign-in only works if the app and
 * the engine agree on what a team is and where a token sits on a page. The rest
 * pin the two documented divergences and the app-only helpers.
 */
class ZeroTrustWebTest {

    private val jwt = TestJwt.make("""{"exp":9999999999}""")

    // ------------------------------------------------ zerotrust.rs, carried over

    @Test
    fun `a bare team name is kept as is`() {
        assertEquals("acme", ZeroTrustWeb.normalizeTeam("acme"))
        assertEquals("acme", ZeroTrustWeb.normalizeTeam("  ACME  "))
    }

    @Test
    fun `a full team domain is reduced to the name`() {
        assertEquals("acme", ZeroTrustWeb.normalizeTeam("acme.cloudflareaccess.com"))
        assertEquals("acme", ZeroTrustWeb.normalizeTeam("https://acme.cloudflareaccess.com"))
        assertEquals("acme", ZeroTrustWeb.normalizeTeam("https://acme.cloudflareaccess.com/"))
    }

    @Test
    fun `an enrolment url is reduced to the name`() {
        assertEquals("acme", ZeroTrustWeb.normalizeTeam("https://Acme.cloudflareaccess.com/warp"))
        assertEquals("my-team_1", ZeroTrustWeb.normalizeTeam("http://my-team_1.cloudflareaccess.com/warp?x=1"))
    }

    @Test
    fun `an empty or malformed team is rejected`() {
        assertNull(ZeroTrustWeb.normalizeTeam(""))
        assertNull(ZeroTrustWeb.normalizeTeam("   "))
        assertNull(ZeroTrustWeb.normalizeTeam("cloudflareaccess.com"))
        assertNull(ZeroTrustWeb.normalizeTeam("ac me"))
        assertNull(ZeroTrustWeb.normalizeTeam("acme.example.com"))
    }

    @Test
    fun `the team domain and login url follow cloudflare's shape`() {
        assertEquals("https://acme.cloudflareaccess.com", ZeroTrustWeb.teamDomain("acme"))
        assertEquals("https://acme.cloudflareaccess.com/warp", ZeroTrustWeb.loginUrl("acme"))
        assertEquals("acme.cloudflareaccess.com", ZeroTrustWeb.teamHost("acme"))
        assertEquals(
            "https://acme.cloudflareaccess.com/cdn-cgi/access/callback",
            ZeroTrustWeb.callbackUrl("acme"),
        )
    }

    @Test
    fun `a token is pulled out of the meta refresh`() {
        val page = "<meta http-equiv=\"refresh\" content=\"0;url=com.cloudflare.warp://acme/auth?token=$jwt\">"
        assertEquals(jwt, ZeroTrustWeb.extractJwtFromHtml(page))
    }

    @Test
    fun `a page without a token yields nothing`() {
        assertNull(ZeroTrustWeb.extractJwtFromHtml("<html>nothing here</html>"))
        assertNull(ZeroTrustWeb.extractJwtFromHtml("token=not-a-jwt"))
    }

    @Test
    fun `a token is pulled out of the authorization cookie`() {
        assertEquals(jwt, ZeroTrustWeb.extractJwtFromCookie("CF_Authorization=$jwt; Path=/; Secure"))
        assertEquals(jwt, ZeroTrustWeb.extractJwtFromCookie("cf_authorization=$jwt"))
    }

    @Test
    fun `an unrelated cookie is ignored`() {
        assertNull(ZeroTrustWeb.extractJwtFromCookie("CF_Session=abc; Path=/"))
        // Faithful to the engine: it stops at the first part that is not the
        // authorization cookie.
        assertNull(ZeroTrustWeb.extractJwtFromCookie("other=1; CF_Authorization=$jwt"))
    }

    @Test
    fun `the verify code action is read off the real enrolment page`() {
        val page = "<html><body><form class=\"AuthFormLogin\" " +
            "action='https:&#x2F;&#x2F;example-team.cloudflareaccess.com&#x2F;cdn-cgi&#x2F;access" +
            "&#x2F;verify-code&#x2F;example-team.cloudflareaccess.com?kid&#x3D;abc&amp;meta&#x3D;xyz" +
            "&amp;redirect_url&#x3D;%2Fwarp' method=\"post\" id=\"totp-form\">" +
            "<input name=\"email\"></form></body></html>"
        assertEquals(
            "https://example-team.cloudflareaccess.com/cdn-cgi/access/verify-code/" +
                "example-team.cloudflareaccess.com?kid=abc&meta=xyz&redirect_url=%2Fwarp",
            ZeroTrustWeb.extractTotpFormAction(page),
        )
    }

    @Test
    fun `a page without the email form has no action`() {
        assertNull(ZeroTrustWeb.extractTotpFormAction("<html>pick an idp</html>"))
        assertNull(ZeroTrustWeb.extractTotpFormAction("<form action='/relative' id=\"totp-form\">"))
    }

    @Test
    fun `html entities are decoded including hex escapes`() {
        assertEquals("a/b", ZeroTrustWeb.decodeEntities("a&#x2F;b"))
        assertEquals("x&y", ZeroTrustWeb.decodeEntities("x&amp;y"))
        assertEquals("k=v", ZeroTrustWeb.decodeEntities("k&#x3D;v"))
        assertEquals("plain", ZeroTrustWeb.decodeEntities("plain"))
        assertEquals("a & b", ZeroTrustWeb.decodeEntities("a & b"))
    }

    @Test
    fun `a query parameter is read and percent decoded`() {
        val url = "https://team.cloudflareaccess.com/x?nonce=abc123&redirect_url=%2Fwarp"
        assertEquals("abc123", ZeroTrustWeb.queryValue(url, "nonce"))
        assertEquals("/warp", ZeroTrustWeb.queryValue(url, "redirect_url"))
        assertNull(ZeroTrustWeb.queryValue(url, "missing"))
        assertNull(ZeroTrustWeb.queryValue("https://team/x", "nonce"))
    }

    // ------------------------------------------------- documented divergences

    @Test
    fun `a bare flag in front of the nonce does not hide it`() {
        assertEquals("n1", ZeroTrustWeb.queryValue("https://t/x?flag&nonce=n1", "nonce"))
    }

    @Test
    fun `a fragment is not part of the last value`() {
        assertEquals("n1", ZeroTrustWeb.queryValue("https://t/x?nonce=n1#top", "nonce"))
    }

    // ----------------------------------------------------------- app helpers

    @Test
    fun `invalid numeric entities are kept as text`() {
        assertEquals("&#xD800;", ZeroTrustWeb.decodeEntities("&#xD800;"))
        assertEquals("&#x110000;", ZeroTrustWeb.decodeEntities("&#x110000;"))
        assertEquals("&unknown;", ZeroTrustWeb.decodeEntities("&unknown;"))
        assertEquals("A", ZeroTrustWeb.decodeEntities("&#65;"))
    }

    @Test
    fun `percent decoding handles plus, bad escapes and utf-8`() {
        assertEquals("a b", ZeroTrustWeb.percentDecode("a+b"))
        assertEquals("%zz!", ZeroTrustWeb.percentDecode("%zz!"))
        assertEquals("é!", ZeroTrustWeb.percentDecode("%C3%A9!"))
    }

    @Test
    fun `a plausible address has one at sign and no whitespace`() {
        assertTrue(ZeroTrustWeb.plausibleEmail("me@example.com"))
        assertTrue(ZeroTrustWeb.plausibleEmail("  me@example.com "))
        assertFalse(ZeroTrustWeb.plausibleEmail("me"))
        assertFalse(ZeroTrustWeb.plausibleEmail("@example.com"))
        assertFalse(ZeroTrustWeb.plausibleEmail("me@"))
        assertFalse(ZeroTrustWeb.plausibleEmail("me@a@b"))
        assertFalse(ZeroTrustWeb.plausibleEmail("m e@example.com"))
    }
}
