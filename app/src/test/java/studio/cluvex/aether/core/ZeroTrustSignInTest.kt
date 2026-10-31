package studio.cluvex.aether.core

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The pre-connect e-mail sign-in and the service-token check, end to end, against
 * a scripted Cloudflare.
 *
 * No network: [ZeroTrustSignIn.Transport] is replaced by [Script], which answers
 * each exchange from a table and records what was sent. That makes the parts that
 * matter checkable - the exact requests, the order, where the nonce comes from,
 * which host sees which cookie, what is and is not followed - none of which a
 * device test could pin down.
 */
class ZeroTrustSignInTest {

    private val host = "https://acme.cloudflareaccess.com"
    private val verify = "$host/cdn-cgi/access/verify-code/acme.cloudflareaccess.com?kid=abc"
    private val landing = "<html><form action='$verify' method=\"post\" id=\"totp-form\">" +
        "<input name=\"email\"></form></html>"
    private val jwt = TestJwt.make("""{"exp":9999999999}""")

    private fun response(
        code: Int,
        url: String,
        body: String = "",
        headers: Map<String, List<String>> = emptyMap(),
    ) = ZeroTrustSignIn.Response(code, url, headers, body)

    private fun redirect(url: String, to: String, code: Int = 302, cookie: String? = null) =
        response(
            code,
            url,
            headers = buildMap {
                put("location", listOf(to))
                if (cookie != null) put("set-cookie", listOf(cookie))
            },
        )

    /** Answers from [answer]; remembers every request as it arrived. */
    private class Script(
        private val answer: (ZeroTrustSignIn.Request) -> ZeroTrustSignIn.Response,
    ) : ZeroTrustSignIn.Transport {
        val seen = mutableListOf<ZeroTrustSignIn.Request>()
        override fun exchange(request: ZeroTrustSignIn.Request): ZeroTrustSignIn.Response {
            seen += request
            return answer(request)
        }
    }

    private fun ZeroTrustSignIn.Request.header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    private fun ZeroTrustSignIn.Request.field(name: String): String? =
        form?.firstOrNull { it.first == name }?.second

    private fun <T> ZeroTrustSignIn.Result<T>.ok(): T = when (this) {
        is ZeroTrustSignIn.Result.Ok -> value
        is ZeroTrustSignIn.Result.Failed -> {
            fail("expected success, got $failure")
            throw AssertionError()
        }
    }

    private fun ZeroTrustSignIn.Result<*>.failure(): ZeroTrustSignIn.Failure = when (this) {
        is ZeroTrustSignIn.Result.Failed -> failure
        is ZeroTrustSignIn.Result.Ok -> {
            fail("expected a failure, got $value")
            throw AssertionError()
        }
    }

    /** The happy Cloudflare: page, mail, callback with an app-scheme redirect. */
    private fun cloudflare(callback: (ZeroTrustSignIn.Request) -> ZeroTrustSignIn.Response = {
        redirect(it.url, "com.cloudflare.warp://acme.cloudflareaccess.com/auth?token=$jwt")
    }) = Script { r ->
        when {
            r.method == "GET" && r.url == "$host/warp" ->
                response(200, r.url, landing, mapOf("set-cookie" to listOf("CF_Session=s1; Path=/; Secure")))
            r.method == "POST" && r.url == verify ->
                redirect(r.url, "/cdn-cgi/access/login?nonce=N1", 302)
            r.method == "GET" && r.url == "$host/cdn-cgi/access/login?nonce=N1" ->
                response(200, r.url, "<html>check your inbox</html>")
            r.method == "POST" && r.url == "$host/cdn-cgi/access/callback" -> callback(r)
            else -> response(404, r.url)
        }
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun `the whole flow yields the token and sends what the engine sends`() {
        val net = cloudflare()
        val signIn = ZeroTrustSignIn(net)

        val session = signIn.requestCode("https://Acme.cloudflareaccess.com/warp", " me@example.com ").ok()
        assertEquals("acme", session.team)
        assertEquals("me@example.com", session.email)

        val outcome = signIn.submitCode(session, " 123456 ").ok()
        assertEquals(ZeroTrustSignIn.CodeOutcome.Token(jwt), outcome)

        val (page, mail, inbox, callback) = net.seen
        assertEquals("GET", page.method)
        assertEquals("$host/warp", page.url)

        assertEquals("POST", mail.method)
        assertEquals(verify, mail.url)
        assertEquals(
            listOf("email", "client_id", "connector_id", "connector_type", "redirect_url"),
            mail.form?.map { it.first },
        )
        assertEquals("me@example.com", mail.field("email"))
        assertEquals("CF_Session=s1", mail.header("Cookie"))

        // 302 -> GET without a body, like reqwest.
        assertEquals("GET", inbox.method)
        assertNull(inbox.form)

        assertEquals("POST", callback.method)
        assertEquals("123456", callback.field("code"))
        assertEquals("N1", callback.field("nonce"))
        assertEquals("CF_Session=s1", callback.header("Cookie"))
        assertEquals(4, net.seen.size)
    }

    @Test
    fun `a token in the callback page itself is found too`() {
        val net = cloudflare { response(200, it.url, "<a href=\"x?token=$jwt\">continue</a>") }
        val signIn = ZeroTrustSignIn(net)
        val session = signIn.requestCode("acme", "me@example.com").ok()
        assertEquals(ZeroTrustSignIn.CodeOutcome.Token(jwt), signIn.submitCode(session, "1").ok())
    }

    @Test
    fun `the nonce falls back to the enrolment page's own url, as in the engine`() {
        val net = Script { r ->
            when {
                r.url == "$host/warp" -> redirect(r.url, "$host/cdn-cgi/access/login/acme?nonce=L1")
                r.url == "$host/cdn-cgi/access/login/acme?nonce=L1" -> response(200, r.url, landing)
                r.url == verify -> response(200, r.url, "<html>sent</html>")
                else -> response(404, r.url)
            }
        }
        val session = ZeroTrustSignIn(net).requestCode("acme", "me@example.com").ok()
        assertEquals("L1", session.nonce)
    }

    // --------------------------------------------------------------- codes

    @Test
    fun `a wrong code is rejected with cloudflare's status and nothing else`() {
        val net = cloudflare { response(200, it.url, "<html>That code is not valid</html>") }
        val signIn = ZeroTrustSignIn(net)
        val session = signIn.requestCode("acme", "me@example.com").ok()
        assertEquals(ZeroTrustSignIn.CodeOutcome.Rejected(200), signIn.submitCode(session, "000000").ok())
    }

    @Test
    fun `an empty code is rejected without asking cloudflare`() {
        val net = cloudflare()
        val signIn = ZeroTrustSignIn(net)
        val session = signIn.requestCode("acme", "me@example.com").ok()
        val before = net.seen.size
        assertEquals(ZeroTrustSignIn.CodeOutcome.Rejected(0), signIn.submitCode(session, "   ").ok())
        assertEquals(before, net.seen.size)
    }

    @Test
    fun `a resent code replaces the nonce`() {
        var round = 0
        val net = Script { r ->
            when {
                r.url == "$host/warp" -> response(200, r.url, landing)
                r.url == verify -> {
                    round++
                    redirect(r.url, "$host/sent?nonce=R$round")
                }
                r.url.startsWith("$host/sent") -> response(200, r.url)
                else -> response(404, r.url)
            }
        }
        val signIn = ZeroTrustSignIn(net)
        val session = signIn.requestCode("acme", "me@example.com").ok()
        assertEquals("R1", session.nonce)
        signIn.resendCode(session).ok()
        assertEquals("R2", session.nonce)
        assertEquals("me@example.com", net.seen.last { it.url == verify }.field("email"))
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `input that cannot work never reaches the network`() {
        val net = cloudflare()
        val signIn = ZeroTrustSignIn(net)
        assertEquals(ZeroTrustSignIn.Failure.BadTeam, signIn.requestCode("not a team", "me@example.com").failure())
        assertEquals(ZeroTrustSignIn.Failure.BadEmail, signIn.requestCode("acme", "me").failure())
        assertTrue(net.seen.isEmpty())
    }

    @Test
    fun `a team without e-mail codes is named as such`() {
        val net = Script { response(200, it.url, "<html>pick an identity provider</html>") }
        assertEquals(
            ZeroTrustSignIn.Failure.NoEmailCode,
            ZeroTrustSignIn(net).requestCode("acme", "me@example.com").failure(),
        )
    }

    @Test
    fun `refusals carry the status`() {
        val page = Script { response(403, it.url, "forbidden") }
        assertEquals(
            ZeroTrustSignIn.Failure.Refused(403),
            ZeroTrustSignIn(page).requestCode("acme", "me@example.com").failure(),
        )
        val mail = Script { r ->
            if (r.url == verify) response(429, r.url) else response(200, r.url, landing)
        }
        assertEquals(
            ZeroTrustSignIn.Failure.Refused(429),
            ZeroTrustSignIn(mail).requestCode("acme", "me@example.com").failure(),
        )
    }

    @Test
    fun `a sent code without any nonce is reported, not guessed`() {
        val net = Script { r -> if (r.url == verify) response(200, r.url) else response(200, r.url, landing) }
        assertEquals(
            ZeroTrustSignIn.Failure.NoNonce,
            ZeroTrustSignIn(net).requestCode("acme", "me@example.com").failure(),
        )
    }

    @Test
    fun `no network is a network failure`() {
        val net = Script { throw IOException("unreachable") }
        val failure = ZeroTrustSignIn(net).requestCode("acme", "me@example.com").failure()
        assertTrue(failure is ZeroTrustSignIn.Failure.Network)
        assertTrue((failure as ZeroTrustSignIn.Failure.Network).detail.contains("unreachable"))
    }

    // ------------------------------------------------------ redirect policy

    @Test
    fun `cookies never leave the team's host`() {
        val net = Script { r ->
            when {
                r.url == "$host/warp" ->
                    response(200, r.url, landing, mapOf("set-cookie" to listOf("CF_Session=s1")))
                r.url == verify -> redirect(r.url, "https://elsewhere.example/landing?nonce=E1")
                else -> response(200, r.url)
            }
        }
        val session = ZeroTrustSignIn(net).requestCode("acme", "me@example.com").ok()
        val away = net.seen.single { it.url.startsWith("https://elsewhere.example/") }
        assertNull(away.header("Cookie"))
        assertEquals("E1", session.nonce)
    }

    @Test
    fun `a cookie deleted by cloudflare is not sent again`() {
        val jar = ZeroTrustSignIn.CookieJar("acme.cloudflareaccess.com")
        jar.remember("$host/a", listOf("A=1", "B=2"))
        assertEquals("A=1; B=2", jar.header("$host/b"))
        jar.remember("$host/a", listOf("A=gone; Max-Age=0"))
        assertEquals("B=2", jar.header("$host/b"))
        // Never over plain http, never to another host, never stored from one.
        assertNull(jar.header("http://acme.cloudflareaccess.com/b"))
        assertNull(jar.header("https://evil.example/"))
        jar.remember("https://evil.example/", listOf("C=3"))
        assertEquals("B=2", jar.header("$host/b"))
    }

    @Test
    fun `a redirect to plain http is never followed`() {
        val net = Script { r ->
            when {
                r.url == "$host/warp" -> response(200, r.url, landing)
                r.url == verify -> redirect(r.url, "http://acme.cloudflareaccess.com/sent?nonce=Z")
                else -> response(200, r.url)
            }
        }
        val failure = ZeroTrustSignIn(net).requestCode("acme", "me@example.com").failure()
        assertEquals(ZeroTrustSignIn.Failure.Refused(302), failure)
        assertTrue(net.seen.none { it.url.startsWith("http://") })
    }

    @Test
    fun `a redirect loop ends after reqwest's limit`() {
        val net = Script { redirect(it.url, "$host/warp") }
        val failure = ZeroTrustSignIn(net).requestCode("acme", "me@example.com").failure()
        assertEquals(ZeroTrustSignIn.Failure.Network("too many redirects"), failure)
        assertEquals(ZeroTrustSignIn.MAX_REDIRECTS + 1, net.seen.size)
    }

    @Test
    fun `307 repeats the post with its form`() {
        val moved = "$host/cdn-cgi/access/verify-code/v2"
        val net = Script { r ->
            when {
                r.url == "$host/warp" -> response(200, r.url, landing)
                r.url == verify -> redirect(r.url, moved, 307)
                r.url == moved -> redirect(r.url, "$host/sent?nonce=M1")
                else -> response(200, r.url)
            }
        }
        val session = ZeroTrustSignIn(net).requestCode("acme", "me@example.com").ok()
        val repeated = net.seen.single { it.url == moved }
        assertEquals("POST", repeated.method)
        assertEquals("me@example.com", repeated.field("email"))
        assertEquals("M1", session.nonce)
    }

    // --------------------------------------------------------- service token

    @Test
    fun `a service token is exchanged exactly like the engine does it`() {
        val net = Script { r ->
            redirect(r.url, "$host/somewhere", cookie = "CF_Authorization=$jwt; Path=/; Secure")
        }
        val token = ZeroTrustSignIn(net).testServiceToken("acme", " id.access ", " secret ").ok()
        assertEquals(jwt, token)
        val only = net.seen.single()
        assertEquals("GET", only.method)
        assertEquals("$host/warp", only.url)
        assertEquals("id.access", only.header("CF-Access-Client-Id"))
        assertEquals("secret", only.header("CF-Access-Client-Secret"))
    }

    @Test
    fun `a refused service token reports the status`() {
        val net = Script { response(403, it.url, "<html>Forbidden</html>") }
        assertEquals(
            ZeroTrustSignIn.Failure.NoToken(403),
            ZeroTrustSignIn(net).testServiceToken("acme", "id", "secret").failure(),
        )
    }

    @Test
    fun `forms are encoded as utf-8 urlencoded`() {
        assertEquals(
            "email=a+b%40c.d&x=%C3%A9%26",
            ZeroTrustSignIn.encodeForm(listOf("email" to "a b@c.d", "x" to "é&")),
        )
    }
}
