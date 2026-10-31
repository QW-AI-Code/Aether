package studio.cluvex.aether.ai

import studio.cluvex.aether.core.DiagnosticsLog
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The one network path the AI layer is allowed to use: HTTPS to Google, dialled
 * through the tunnel's own local SOCKS5 proxy.
 *
 * ## Why this is hand-rolled instead of `HttpsURLConnection`
 *
 * Three reasons, in order of how badly each one would bite:
 *
 *  1. **Our own sockets bypass the tunnel.** The VpnService excludes this app's
 *     package from the VPN on purpose, so a stock HTTP client would leave on the
 *     operator's network however connected the phone looks. See [AiGate].
 *  2. **`Proxy(SOCKS)` resolves DNS locally.** `java.net.Proxy` with a SOCKS
 *     address hands the proxy an already-resolved IP, so the device would have to
 *     resolve `generativelanguage.googleapis.com` itself, on the operator's
 *     resolver, off-tunnel - a DNS query that both fails and advertises intent.
 *     A hand-written SOCKS5 CONNECT can send the destination as a DOMAIN
 *     (`ATYP=0x03`) and let the exit resolve it, which is what a browser tab
 *     inside the tunnel does. This is the same technique, and the same reasoning,
 *     as [studio.cluvex.aether.core.NetProbe].
 *  3. **No new dependency.** The app ships no HTTP library, and adding OkHttp for
 *     one endpoint would grow the APK for something ~200 lines already do.
 *
 * TLS is terminated on the device, over the proxied socket, with hostname
 * verification enforced explicitly (see [tlsWrap]) - so neither the operator nor
 * the exit node can read or alter the conversation, and the API key is protected
 * end-to-end.
 */
internal object GeminiHttp {

    const val HOST = "generativelanguage.googleapis.com"
    private const val PORT = 443

    /** A completed HTTP exchange. [code] is 0 when nothing was ever received. */
    data class Response(
        val code: Int,
        val body: String,
        /**
         * `Retry-After`, in seconds, when the server sent one.
         *
         * Parsed here rather than in the client because it is a transport-level
         * header and the client should not have to know how headers are framed.
         * Google sends it on some 429s and on 503s, and honouring it is the
         * difference between one polite wait and the five-requests-in-ten-seconds
         * burst that a naive retry loop turns a rate limit into.
         */
        val retryAfterSeconds: Double? = null,
    ) {
        val ok: Boolean get() = code in 200..299
    }

    /**
     * Performs one request and returns the whole response.
     *
     * @param socksPort the local SOCKS5 port to dial through - see
     *   [AiAvailability.socksPort]. Never a direct socket.
     * @param apiKey sent as `x-goog-api-key`, never as a `?key=` query parameter:
     *   a request line ends up in far more logs than a header does.
     */
    fun request(
        method: String,
        path: String,
        socksPort: Int,
        apiKey: String,
        jsonBody: String? = null,
        timeoutMs: Int = 60_000,
    ): Response {
        socks5Connect("127.0.0.1", socksPort, HOST, PORT, timeoutMs).use { raw ->
            tlsWrap(raw, HOST, PORT, timeoutMs).use { tls ->
                val payload = jsonBody?.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(HOST).append("\r\n")
                    append("x-goog-api-key: ").append(apiKey).append("\r\n")
                    // No app name, no version (F-5 follow-up). This request runs
                    // inside TLS and through the tunnel, so the header was never
                    // operator-visible - but it announced the app to Google, and it
                    // still said "1.2.9" in a 1.3.0 build, so it was wrong as well
                    // as unnecessary. Nothing in the Gemini API depends on it.
                    append("User-Agent: Mozilla/5.0\r\n")
                    append("Accept: application/json\r\n")
                    // identity: we never want a compressed body, because this
                    // client does not implement gzip and a silently gzipped
                    // response would look like a parse failure.
                    append("Accept-Encoding: identity\r\n")
                    if (payload != null) {
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ").append(payload.size).append("\r\n")
                    }
                    // Close-delimited: the reply is read until EOF, so no
                    // keep-alive framing has to be tracked across requests.
                    append("Connection: close\r\n\r\n")
                }
                val out = tls.getOutputStream()
                // ISO_8859_1 for the head: header bytes are latin-1 by
                // definition, and an API key is ASCII. The BODY is UTF-8, which
                // is why it is written separately and its length is measured in
                // bytes rather than characters - a Persian prompt has more bytes
                // than characters, and a Content-Length counted in characters
                // truncates the request into a hang.
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                if (payload != null) out.write(payload)
                out.flush()

                val bytes = tls.getInputStream().readBytes()
                return parse(bytes)
            }
        }
    }

    // ---- response parsing ------------------------------------------------

    private fun parse(raw: ByteArray): Response {
        val split = indexOfHeaderEnd(raw)
        if (split < 0) return Response(0, "")
        val headText = String(raw, 0, split, Charsets.ISO_8859_1)
        val bodyBytes = raw.copyOfRange(split + 4, raw.size)
        val code = headText.substringBefore("\r\n")
            .split(' ')
            .getOrNull(1)
            ?.toIntOrNull() ?: 0
        val chunked = headText.lineSequence().any {
            it.startsWith("transfer-encoding", ignoreCase = true) &&
                it.contains("chunked", ignoreCase = true)
        }
        val body = if (chunked) dechunk(bodyBytes) else bodyBytes
        val retryAfter = headText.lineSequence()
            .firstOrNull { it.startsWith("retry-after", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            // Only the delta-seconds form. The HTTP-date form is legal and Google
            // does not use it here; parsing a date badly would be worse than
            // falling back to our own backoff.
            ?.toDoubleOrNull()
            ?.takeIf { it >= 0 }
        return Response(code, String(body, Charsets.UTF_8), retryAfter)
    }

    /** Index of the CRLFCRLF that ends the header block, or -1. */
    private fun indexOfHeaderEnd(raw: ByteArray): Int {
        val cr = 13.toByte()
        val lf = 10.toByte()
        var i = 0
        while (i + 3 < raw.size) {
            if (raw[i] == cr && raw[i + 1] == lf && raw[i + 2] == cr && raw[i + 3] == lf) return i
            i++
        }
        return -1
    }

    /**
     * Decodes `Transfer-Encoding: chunked`.
     *
     * On BYTES, not on a decoded string, and that is the whole point: a chunk
     * boundary may fall in the middle of a multi-byte UTF-8 sequence, so
     * decoding the body to text first and then stripping chunk headers corrupts
     * every Persian answer that happens to be longer than one chunk. Frame
     * first, decode once, at the end.
     */
    private fun dechunk(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(src.size)
        val cr = 13.toByte()
        val lf = 10.toByte()
        var i = 0
        while (i < src.size) {
            var end = i
            while (end + 1 < src.size && !(src[end] == cr && src[end + 1] == lf)) end++
            if (end + 1 >= src.size) break
            val sizeText = String(src, i, end - i, Charsets.ISO_8859_1)
                .trim()
                .substringBefore(';')
            val size = sizeText.toIntOrNull(16) ?: break
            i = end + 2
            if (size <= 0) break
            if (i + size > src.size) {
                out.write(src, i, src.size - i)
                break
            }
            out.write(src, i, size)
            // Skip the chunk's own trailing CRLF.
            i += size + 2
        }
        return out.toByteArray()
    }

    // ---- SOCKS5 + TLS ----------------------------------------------------

    /**
     * Opens a TCP connection to [destHost]:[destPort] through the SOCKS5 proxy,
     * sending the destination as a DOMAIN so the EXIT resolves it.
     */
    private fun socks5Connect(
        socksHost: String,
        socksPort: Int,
        destHost: String,
        destPort: Int,
        timeoutMs: Int,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(socksHost, socksPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = timeoutMs
            // Nagle off: the request is one small write followed by a read, so
            // waiting to coalesce it only adds latency to every answer.
            socket.tcpNoDelay = true
            val out = socket.getOutputStream()
            val input = DataInputStream(socket.getInputStream())

            // Greeting: VER=5, one method, NO-AUTH.
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            input.readFully(greeting)
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                throw IOException("SOCKS5 auth negotiation failed on port $socksPort")
            }

            val hostBytes = destHost.toByteArray(Charsets.US_ASCII)
            val req = ByteArrayOutputStream()
            req.write(0x05) // VER
            req.write(0x01) // CMD = CONNECT
            req.write(0x00) // RSV
            req.write(0x03) // ATYP = DOMAINNAME -> resolved at the exit
            req.write(hostBytes.size)
            req.write(hostBytes)
            req.write((destPort ushr 8) and 0xFF)
            req.write(destPort and 0xFF)
            out.write(req.toByteArray())
            out.flush()

            val head = ByteArray(4)
            input.readFully(head)
            if (head[1].toInt() != 0x00) {
                throw IOException(
                    "SOCKS5 CONNECT rejected (rep=${head[1].toInt() and 0xFF})",
                )
            }
            val skip = when (head[3].toInt() and 0xFF) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> {
                    val len = ByteArray(1)
                    input.readFully(len)
                    len[0].toInt() and 0xFF
                }
                else -> throw IOException("SOCKS5 bad ATYP in reply")
            }
            input.readFully(ByteArray(skip))
            input.readFully(ByteArray(2)) // BND.PORT
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    /**
     * Wraps the proxied socket in TLS and verifies the certificate belongs to
     * [host].
     *
     * The verification is explicit because `startHandshake()` validates the chain
     * against the system trust store but does NOT check the name on it - the exact
     * gap [studio.cluvex.aether.core.NetProbe] documents. Here it matters more
     * than it does for a geolocation probe: without the check, anyone holding a
     * valid certificate for ANY domain could sit on this connection and read the
     * user's API key and every prompt they type.
     */
    private fun tlsWrap(socket: Socket, host: String, port: Int, timeoutMs: Int): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(socket, host, port, true) as SSLSocket
        ssl.soTimeout = timeoutMs
        ssl.startHandshake()
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        if (!verifier.verify(host, ssl.session)) {
            runCatching { ssl.close() }
            DiagnosticsLog.e("ai", "TLS hostname verification failed for $host")
            throw SSLPeerUnverifiedException("Certificate does not match $host (possible MitM)")
        }
        return ssl
    }

    /**
     * How long to wait for the LOCAL proxy to accept a connection.
     *
     * Short and separate from the request timeout on purpose: 127.0.0.1 either
     * answers immediately or is not listening, so a long timeout here would only
     * make "the tunnel is down" take a minute to say.
     */
    private const val CONNECT_TIMEOUT_MS = 5_000
}
