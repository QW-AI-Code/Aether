package studio.cluvex.aether.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Signs in to a Cloudflare Zero Trust organization BEFORE a connect (issue #12).
 *
 * ## Why the app can do this itself
 *
 * `docs/ZERO_TRUST.md` used to say a pre-connect sign-in needs the engine loaded
 * as a library, because the engine's sign-in lives behind its C ABI. It does not:
 * the e-mail flow is four plain HTTPS exchanges with
 * `https://<team>.cloudflareaccess.com`, and what comes out is a device-enrolment
 * JWT - exactly what the engine already accepts through `AETHER_ACCESS_TOKEN`.
 * This class performs those four exchanges the way `zerotrust.rs` does
 * (`begin_email_signin`, `request_email_code`, `EmailSignIn::submit_code`,
 * `fetch_token_with_service_token`), in the same order, with the same form
 * fields and the same User-Agent, and hands the token to the engine on the next
 * connect. No JNI, no engine change, no second copy of the engine in memory.
 *
 *  1. `GET /warp` - the enrolment page. Its `id="totp-form"` form's `action` is
 *     the "e-mail me a code" endpoint ([ZeroTrustWeb.extractTotpFormAction]).
 *  2. `POST <action>` with the address. Cloudflare mails the code and redirects
 *     to a page whose URL carries the `nonce` that ties the code to this session.
 *  3. `POST /cdn-cgi/access/callback` with `code` and `nonce`.
 *  4. The answer (after redirects) carries `token=<jwt>` - in the page, or in a
 *     redirect to the `com.cloudflare.warp://` app scheme, which is not followed
 *     but read.
 *
 * ## Deliberate limits
 *
 *  * **Direct network only.** This runs while the tunnel is DOWN (the settings are
 *    only editable then), from the app's own process. It does not go through the
 *    upstream proxy the engine may be configured with. Where Cloudflare Access is
 *    unreachable directly, the in-connect code prompt still works, because that
 *    one is performed by the engine, which honours the upstream proxy.
 *  * **Cookies never leave the team's host.** The jar only stores and only sends
 *    cookies for `<team>.cloudflareaccess.com`. A redirect anywhere else gets no
 *    cookies.
 *  * **Only https is followed**, at most [MAX_REDIRECTS] hops.
 *  * **Nothing secret is logged.** Not the code, not the nonce, not the token,
 *    not the service-token secret. The address is logged, as the engine does.
 *
 * Blocking I/O: call from a background dispatcher. The network is behind
 * [Transport] so the flow is covered by plain JUnit tests without a network.
 */
class ZeroTrustSignIn(private val transport: Transport = UrlConnectionTransport()) {

    // ------------------------------------------------------------------ types

    /** One HTTP request as this flow needs it. */
    class Request(
        val method: String,
        val url: String,
        val headers: List<Pair<String, String>> = emptyList(),
        val form: List<Pair<String, String>>? = null,
    )

    /**
     * One HTTP response. [headers] keys are lower-case; [url] is the URL this
     * response came from (the transport does NOT follow redirects - this class
     * does, so it can scope cookies and read app-scheme redirects).
     */
    class Response(
        val code: Int,
        val url: String,
        val headers: Map<String, List<String>>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
        fun all(name: String): List<String> = headers[name.lowercase()].orEmpty()
    }

    /** The network, one exchange at a time. */
    fun interface Transport {
        @Throws(IOException::class)
        fun exchange(request: Request): Response
    }

    /** Why a step failed, in terms the UI can put into words. */
    sealed interface Failure {
        /** The team name does not normalise to anything usable. */
        data object BadTeam : Failure

        /** The address is not an address. */
        data object BadEmail : Failure

        /** The team's enrolment page has no e-mail code form (OTP not enabled). */
        data object NoEmailCode : Failure

        /** Cloudflare answered, but not with success. */
        data class Refused(val status: Int) : Failure

        /** The code was sent but no nonce came back; the flow changed shape. */
        data object NoNonce : Failure

        /** The service token was not exchanged for an enrolment token. */
        data class NoToken(val status: Int) : Failure

        /** No answer at all: DNS, TLS, timeout, blocked. */
        data class Network(val detail: String) : Failure
    }

    /** A step's outcome. */
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Failed(val failure: Failure) : Result<Nothing>
    }

    /** What confirming a code produced. */
    sealed interface CodeOutcome {
        data class Token(val token: String) : CodeOutcome

        /** Cloudflare did not issue a token for that code. */
        data class Rejected(val status: Int) : CodeOutcome
    }

    /**
     * A code has been e-mailed and not yet confirmed.
     *
     * Holds the cookies of this one conversation, so it must not be shared or
     * reused for another team or address. Discard it when done.
     */
    class Session internal constructor(
        val team: String,
        val email: String,
        internal val verifyUrl: String,
        internal var nonce: String,
        internal val jar: CookieJar,
    )

    // ------------------------------------------------------------------ flow

    /** Steps 1 and 2: open the enrolment page and ask Cloudflare to mail a code. */
    fun requestCode(rawTeam: String, rawEmail: String): Result<Session> {
        val team = ZeroTrustWeb.normalizeTeam(rawTeam) ?: return Result.Failed(Failure.BadTeam)
        val email = rawEmail.trim()
        if (!ZeroTrustWeb.plausibleEmail(email)) return Result.Failed(Failure.BadEmail)
        val jar = CookieJar(ZeroTrustWeb.teamHost(team))

        DiagnosticsLog.i(TAG, "Opening the device enrolment page for team $team.")
        val landing = when (val r = follow(Request("GET", ZeroTrustWeb.loginUrl(team)), jar)) {
            is Result.Ok -> r.value.response
            is Result.Failed -> return r
        }
        if (landing.code !in 200..299) return Result.Failed(Failure.Refused(landing.code))
        val verifyUrl = ZeroTrustWeb.extractTotpFormAction(landing.body)
            ?: return Result.Failed(Failure.NoEmailCode)

        val nonce = when (val r = postEmail(verifyUrl, email, jar)) {
            is Result.Ok -> r.value ?: ZeroTrustWeb.queryValue(landing.url, "nonce")
            is Result.Failed -> return r
        } ?: return Result.Failed(Failure.NoNonce)

        return Result.Ok(Session(team, email, verifyUrl, nonce, jar))
    }

    /** Step 2 again, same session. A fresh nonce replaces the old one when sent. */
    fun resendCode(session: Session): Result<Unit> =
        when (val r = postEmail(session.verifyUrl, session.email, session.jar)) {
            is Result.Ok -> {
                r.value?.let { session.nonce = it }
                Result.Ok(Unit)
            }
            is Result.Failed -> r
        }

    /** Steps 3 and 4: confirm [code] and collect the enrolment token. */
    fun submitCode(session: Session, code: String): Result<CodeOutcome> {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return Result.Ok(CodeOutcome.Rejected(0))
        val request = Request(
            method = "POST",
            url = ZeroTrustWeb.callbackUrl(session.team),
            form = listOf("code" to trimmed, "nonce" to session.nonce),
        )
        return when (val r = follow(request, session.jar)) {
            is Result.Failed -> r
            is Result.Ok -> {
                val token = r.value.appToken ?: ZeroTrustWeb.extractJwtFromHtml(r.value.response.body)
                if (token != null) {
                    DiagnosticsLog.i(TAG, "Signed in to team ${session.team} with the e-mail code.")
                    Result.Ok(CodeOutcome.Token(token))
                } else {
                    Result.Ok(CodeOutcome.Rejected(r.value.response.code))
                }
            }
        }
    }

    /**
     * `fetch_token_with_service_token`, as a check the user can run from Settings.
     *
     * Exactly the engine's request (no redirects, the two `CF-Access-Client-*`
     * headers), so a pass here means the engine's own exchange will pass too.
     * The token is returned for its expiry only; the engine fetches its own on
     * connect, and this one is not stored.
     */
    fun testServiceToken(rawTeam: String, clientId: String, clientSecret: String): Result<String> {
        val team = ZeroTrustWeb.normalizeTeam(rawTeam) ?: return Result.Failed(Failure.BadTeam)
        DiagnosticsLog.i(TAG, "Testing the service token against team $team.")
        val response = try {
            transport.exchange(
                Request(
                    method = "GET",
                    url = ZeroTrustWeb.loginUrl(team),
                    headers = listOf(
                        "CF-Access-Client-Id" to clientId.trim(),
                        "CF-Access-Client-Secret" to clientSecret.trim(),
                    ),
                ),
            )
        } catch (e: IOException) {
            return Result.Failed(Failure.Network(describe(e)))
        }
        val token = response.all("set-cookie").firstNotNullOfOrNull { ZeroTrustWeb.extractJwtFromCookie(it) }
            ?: ZeroTrustWeb.extractJwtFromHtml(response.body)
        return if (token != null) {
            DiagnosticsLog.i(TAG, "The service token was accepted by team $team.")
            Result.Ok(token)
        } else {
            DiagnosticsLog.w(TAG, "Team $team did not issue an enrolment token for the service token (status ${response.code}).")
            Result.Failed(Failure.NoToken(response.code))
        }
    }

    // --------------------------------------------------------------- plumbing

    /** Step 2's POST. Ok(nonce-or-null) on a successful answer. */
    private fun postEmail(verifyUrl: String, email: String, jar: CookieJar): Result<String?> {
        DiagnosticsLog.i(TAG, "Asking Cloudflare to e-mail a login code to $email.")
        val request = Request(
            method = "POST",
            url = verifyUrl,
            // The exact field set the enrolment page's own form posts, and the
            // engine with it.
            form = listOf(
                "email" to email,
                "client_id" to "",
                "connector_id" to "",
                "connector_type" to "",
                "redirect_url" to "",
            ),
        )
        val sent = when (val r = follow(request, jar)) {
            is Result.Ok -> r.value.response
            is Result.Failed -> return r
        }
        if (sent.code !in 200..299) {
            DiagnosticsLog.w(TAG, "Cloudflare refused to send a login code (status ${sent.code}).")
            return Result.Failed(Failure.Refused(sent.code))
        }
        return Result.Ok(ZeroTrustWeb.queryValue(sent.url, "nonce"))
    }

    /** The final response, plus a token found in an app-scheme redirect, if any. */
    private class Followed(val response: Response, val appToken: String?)

    /**
     * Sends [first] and follows redirects like reqwest's default policy, which is
     * what the engine uses: 301/302/303 become a GET without a body, 307/308
     * repeat the request. Cookies are recorded and replayed through [jar] on
     * every hop.
     */
    private fun follow(first: Request, jar: CookieJar): Result<Followed> {
        var request = first
        repeat(MAX_REDIRECTS + 1) {
            val withCookies = jar.header(request.url)
                ?.let { Request(request.method, request.url, request.headers + ("Cookie" to it), request.form) }
                ?: request
            val response = try {
                transport.exchange(withCookies)
            } catch (e: IOException) {
                DiagnosticsLog.w(TAG, "Cloudflare Access could not be reached: ${describe(e)}")
                return Result.Failed(Failure.Network(describe(e)))
            }
            jar.remember(response.url, response.all("set-cookie"))

            val location = response.header("location")
            if (response.code !in REDIRECTS || location.isNullOrBlank()) {
                return Result.Ok(Followed(response, null))
            }
            val next = resolve(response.url, location)
            if (next == null || !next.startsWith("https://")) {
                // The WARP client's own scheme (com.cloudflare.warp://...?token=)
                // or anything else that is not https: read, never followed.
                return Result.Ok(Followed(response, ZeroTrustWeb.extractJwtFromHtml(location)))
            }
            request = when (response.code) {
                307, 308 -> Request(request.method, next, request.headers, request.form)
                else -> Request("GET", next, request.headers, null)
            }
        }
        return Result.Failed(Failure.Network("too many redirects"))
    }

    private fun resolve(base: String, location: String): String? = runCatching {
        URL(URL(base), location.trim()).toString()
    }.getOrNull() ?: location.trim().takeIf { ':' in it }

    /** A one-line, secret-free description of an I/O failure. */
    private fun describe(e: IOException): String =
        (e::class.java.simpleName + (e.message?.let { ": $it" } ?: "")).take(160)

    // ------------------------------------------------------------ cookie jar

    /**
     * The smallest cookie store that does the job: name -> value, for ONE host.
     *
     * reqwest's `cookie_store(true)` is what carries Cloudflare's session from
     * the enrolment page to the callback in the engine; this is its equivalent,
     * narrowed on purpose to the team's own host so nothing Cloudflare sets is
     * ever sent anywhere else. Attributes other than expiry are not needed for a
     * four-request conversation on one host and are ignored.
     */
    class CookieJar(private val host: String) {
        private val cookies = LinkedHashMap<String, String>()

        @Synchronized
        fun remember(url: String, setCookies: List<String>) {
            if (!sameHost(url)) return
            for (header in setCookies) {
                val parts = header.split(';')
                val pair = parts.first().trim()
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                val expired = parts.drop(1).any {
                    val attr = it.trim()
                    attr.startsWith("max-age=", ignoreCase = true) &&
                        (attr.substringAfter('=').trim().toLongOrNull() ?: 1L) <= 0L
                }
                if (expired || value.isEmpty()) cookies.remove(name) else cookies[name] = value
            }
        }

        /** The `Cookie` header for [url], or null when there is nothing to send. */
        @Synchronized
        fun header(url: String): String? {
            if (!sameHost(url) || cookies.isEmpty()) return null
            return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        private fun sameHost(url: String): Boolean =
            runCatching { URL(url).host.equals(host, ignoreCase = true) && url.startsWith("https://") }
                .getOrDefault(false)
    }

    // ------------------------------------------------------------- transport

    /**
     * The production [Transport]: `HttpsURLConnection`, https only, no automatic
     * redirects, no caches, no global cookie handler, bounded timeouts and body.
     */
    class UrlConnectionTransport : Transport {
        override fun exchange(request: Request): Response {
            val url = URL(request.url)
            if (url.protocol != "https") throw IOException("refusing a non-https URL")
            val conn = url.openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.requestMethod = request.method
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                for ((name, value) in request.headers) conn.setRequestProperty(name, value)
                val form = request.form
                if (form != null) {
                    val payload = encodeForm(form).toByteArray(Charsets.UTF_8)
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.setFixedLengthStreamingMode(payload.size)
                    conn.outputStream.use { it.write(payload) }
                }
                val code = conn.responseCode
                val headers = HashMap<String, List<String>>()
                for ((name, values) in conn.headerFields) {
                    if (name != null) headers[name.lowercase()] = values
                }
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                val body = stream?.use { readCapped(it) }.orEmpty()
                return Response(code, request.url, headers, body)
            } finally {
                conn.disconnect()
            }
        }

        private fun readCapped(input: InputStream): String {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            while (out.size() < MAX_BODY_BYTES) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, minOf(n, MAX_BODY_BYTES - out.size()))
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    companion object {
        private const val TAG = "zerotrust"

        /** reqwest's default redirect limit, which the engine runs with. */
        const val MAX_REDIRECTS = 10

        /** `zerotrust::AUTH_TIMEOUT`. */
        private const val TIMEOUT_MS = 20_000

        /** An enrolment page is a few KB; anything this big is not one. */
        private const val MAX_BODY_BYTES = 512 * 1024

        /** `consts::UA_REGISTER`: Cloudflare sees the same client either way. */
        const val USER_AGENT = "WARP for Android"

        private val REDIRECTS = setOf(301, 302, 303, 307, 308)

        /** `application/x-www-form-urlencoded`, UTF-8. */
        fun encodeForm(form: List<Pair<String, String>>): String =
            form.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
    }
}
