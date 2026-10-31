package studio.cluvex.aether.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** A config the importer could not accept, with a reason the UI can show as-is. */
class ConfigImportException(message: String) : IllegalArgumentException(message)

/** Thrown for a password-protected NapsternetV file when no (or a wrong) password was given. */
class NpvtPasswordException(val wrong: Boolean) : IllegalArgumentException(
    if (wrong) "wrong password for this npvt config" else "this npvt config is password protected",
)

/**
 * Turns whatever the user pasted, uploaded or scanned into saved configs.
 *
 * Accepted input, auto-detected:
 *  * share links: `vless://`, `vmess://` (v2rayN base64 JSON and URI form),
 *    `trojan://`, `ss://` (SIP002 and legacy), `socks://` / `socks5://`,
 *    `wireguard://` / `wg://` - one per line, any number of them;
 *  * a base64 subscription body holding such links;
 *  * an Xray / V2Ray JSON config (its proxy outbound is used) or a bare outbound;
 *  * a NapsternetV `.npvt` file (`NPVT1...`), also as `npvt://<base64 or text>`.
 *
 * Pure Kotlin on purpose (no Android types), so every rule here is covered by
 * plain JVM unit tests.
 */
object ConfigParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Lines this importer understands as share links. */
    private val LINK_SCHEMES = listOf(
        "vless://", "vmess://", "trojan://", "ss://", "socks://", "socks5://", "wireguard://", "wg://",
    )

    fun isShareLink(text: String): Boolean {
        val t = cleanInput(text).lowercase()
        return LINK_SCHEMES.any { t.startsWith(it) }
    }

    /**
     * Characters that are invisible on screen but break a link: the RLM/LRM and
     * bidi isolates RTL messengers wrap around Latin text, zero-width spaces, the
     * word joiner, a BOM, soft hyphens. ZWNJ/ZWJ are kept - they are part of
     * Persian words and emoji sequences in a config's name.
     */
    private val INVISIBLE = Regex("[\u00AD\u061C\u180E\u200B\u200E\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF]")

    /**
     * Normalises pasted, scanned or downloaded text before anything looks at it:
     * line endings unified, invisible marks removed, no-break spaces made plain.
     */
    fun cleanInput(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(INVISIBLE, "")
            .replace('\u00A0', ' ')
            .replace('\u202F', ' ')
            .trim()

    /** Where a share link starts inside a line; not inside another scheme (`vless://` holds `ss://`). */
    private val LINK_START = Regex("(?i)(?<![A-Za-z0-9+.\\-])(?:vless|vmess|trojan|ss|socks5?|wireguard|wg)://")

    /**
     * Every share link in one line of text.
     *
     * A link copied from a channel post usually arrives with text around it
     * ("Config: vless://... 🔥"), several links can share a line, and messengers
     * wrap links in quotes or brackets. A link ends where the next one starts;
     * before its `#name` it also ends at the first whitespace (a name may hold
     * spaces, a URI may not).
     */
    internal fun extractLinks(line: String): List<String> {
        val starts = LINK_START.findAll(line).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts.mapIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] else line.length
            var link = line.substring(start, end).trim()
            val hash = link.indexOf('#')
            val space = link.indexOfFirst { it.isWhitespace() }
            if (space >= 0 && (hash < 0 || space < hash)) link = link.substring(0, space)
            link = if ('#' in link) link.trimEnd('"', '\'', '`') else link.trimEnd(*LINK_TRAILERS)
            link
        }.filter { it.substringAfter("://").isNotBlank() }
    }

    /** Punctuation that closes a sentence or a quote around a link, never part of it. */
    private val LINK_TRAILERS = charArrayOf('"', '\'', '`', ')', ']', '}', '>', ',', ';', '.', '،', '؛', '»', '”', '’')

    /**
     * Parses [input]. Returns at least one config or throws [ConfigImportException]
     * (or [NpvtPasswordException]). Lines that are not configs are skipped as long
     * as at least one line was one; the count of skipped lines is not an error.
     */
    fun parse(
        input: String,
        source: ConfigSource,
        npvtTables: () -> NpvtCodec.Tables,
        npvtPassword: String? = null,
        now: Long = System.currentTimeMillis(),
    ): List<CustomConfig> {
        val text = cleanInput(input)
        if (text.isEmpty()) throw ConfigImportException("empty input")

        if (text.lowercase().startsWith("npvt://")) {
            val body = text.substring("npvt://".length).trim()
            val decoded = decodeLoose(body)?.takeIf { NpvtCodec.looksLike(it) }
                ?: runCatching { URLDecoder.decode(body, "UTF-8") }.getOrNull()?.takeIf { NpvtCodec.looksLike(it) }
                ?: throw ConfigImportException("npvt link does not contain an npvt config")
            return parseNpvt(decoded, source, npvtTables(), npvtPassword, now)
        }
        // Named before anything else tries to read it as a subscription URL, so
        // an MTProto proxy gets the explanation in [MTPROTO_UNSUPPORTED] rather
        // than a download that returns a web page.
        if (looksLikeMtProto(text)) throw ConfigImportException(MTPROTO_UNSUPPORTED)
        if (NpvtCodec.looksLike(text)) return parseNpvt(text, source, npvtTables(), npvtPassword, now)
        if (NpvoCodec.looksLike(text)) return parseNpvo(text, source, npvtPassword, now)
        if (text.startsWith("{") || text.startsWith("[")) return parseJson(text, source, now)

        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val links = lines.flatMap { extractLinks(it) }
        if (links.isEmpty()) {
            // A subscription body: the whole text is base64 of a list of links.
            val decoded = decodeLoose(text.filterNot { it.isWhitespace() })
            if (decoded != null && decoded != text && (LINK_SCHEMES.any { decoded.contains(it, ignoreCase = true) } ||
                    NpvtCodec.looksLike(decoded) || NpvoCodec.looksLike(decoded) || decoded.trimStart().startsWith("{"))
            ) {
                return parse(decoded, source, npvtTables, npvtPassword, now)
            }
            throw ConfigImportException("no supported config found")
        }
        val out = ArrayList<CustomConfig>()
        var firstError: String? = null
        for (link in links) {
            try {
                out += parseLink(link, source, now)
            } catch (e: IllegalArgumentException) {
                if (firstError == null) firstError = e.message
            }
        }
        if (out.isEmpty()) throw ConfigImportException(firstError ?: "no supported config found")
        return out
    }

    // ------------------------------------------------------------------ links

    /**
     * Why an MTProto proxy cannot be imported here.
     *
     * Deliberately a full sentence rather than "unsupported": users are given
     * MTProto proxies alongside VLESS links and reasonably expect them to be the
     * same kind of thing. They are not - an MTProto proxy relays Telegram's own
     * protocol to Telegram's data centres only. It cannot carry web traffic, a
     * download, or another hop of a chain, so there is nothing this app could do
     * with one even as a first hop.
     */
    const val MTPROTO_UNSUPPORTED: String =
        "MTProto proxies carry Telegram traffic only - they cannot route web traffic or downloads, " +
            "so they cannot be used as a tunnel or as a hop in a chain. Use it inside Telegram's own " +
            "proxy settings instead."

    /** `t.me/proxy?...&secret=...` and the `https://` form of the same thing. */
    internal fun looksLikeMtProto(link: String): Boolean {
        val lower = link.trim().lowercase()
        if (lower.startsWith("tg://proxy") || lower.startsWith("tg://socks")) return true
        val isTelegramHost = lower.startsWith("https://t.me/") || lower.startsWith("http://t.me/") ||
            lower.startsWith("t.me/") || lower.contains("telegram.me/proxy")
        return isTelegramHost && lower.contains("proxy") && lower.contains("secret=")
    }

    /** One share link -> one config. */
    fun parseLink(link: String, source: ConfigSource, now: Long = System.currentTimeMillis()): CustomConfig {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://").lowercase()
        val built: Built = when (scheme) {
            "vless" -> vless(UriParts.of(trimmed))
            "vmess" -> vmess(trimmed)
            "trojan" -> trojan(UriParts.of(trimmed))
            "ss" -> shadowsocks(trimmed)
            "socks", "socks5" -> socks(UriParts.of(trimmed))
            "wireguard", "wg" -> wireguard(UriParts.of(trimmed))
            // 1.5.0-r2: say WHY, instead of "unsupported link type: tg://".
            // An MTProto proxy is not a general proxy at all: it speaks
            // Telegram's own MTProto framing to Telegram's data centres and has
            // no way to carry an arbitrary TCP connection, which is why Xray
            // (and sing-box) refuse these links outright. Importing one here
            // could only ever produce a tunnel that carries nothing.
            "tg" -> throw ConfigImportException(MTPROTO_UNSUPPORTED)
            else -> {
                if (looksLikeMtProto(trimmed)) throw ConfigImportException(MTPROTO_UNSUPPORTED)
                throw ConfigImportException("unsupported link type: $scheme://")
            }
        }
        return CustomConfig(
            id = UUID.randomUUID().toString(),
            name = built.name.ifBlank { "${built.protocol.short} ${built.host}" }.take(MAX_NAME),
            protocol = built.protocol,
            format = ConfigFormat.URI,
            source = source,
            outbound = built.outbound.toString(),
            raw = trimmed,
            server = hostPort(built.host, built.port),
            addedAt = now,
        )
    }

    private data class Built(
        val protocol: ConfigProtocol,
        val outbound: JsonObject,
        val host: String,
        val port: Int,
        val name: String,
    )

    /** `scheme://userinfo@host:port?query#fragment`, split without java.net.URI's strictness. */
    internal data class UriParts(
        val user: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
        val name: String,
    ) {
        fun q(vararg keys: String): String =
            keys.firstNotNullOfOrNull { k -> query[k]?.takeIf { it.isNotEmpty() } } ?: ""

        companion object {
            fun of(link: String, needUser: Boolean = true): UriParts {
                var rest = link.substringAfter("://")
                val name = if ('#' in rest) decode(rest.substringAfter('#')) else ""
                rest = rest.substringBefore('#')
                val queryText = if ('?' in rest) rest.substringAfter('?') else ""
                rest = rest.substringBefore('?').trimEnd('/')
                val at = rest.lastIndexOf('@')
                if (needUser && at < 0) throw ConfigImportException("link has no user id / password")
                val user = if (at >= 0) decode(rest.substring(0, at)) else ""
                val (host, port) = splitHostPort(if (at >= 0) rest.substring(at + 1) else rest)
                val query = LinkedHashMap<String, String>()
                for (pair in queryText.split('&')) {
                    if (pair.isEmpty()) continue
                    val k = decode(pair.substringBefore('=')).trim()
                    val v = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                    if (k.isNotEmpty() && k !in query) query[k] = v
                }
                return UriParts(user, host, port, query, name)
            }
        }
    }

    private fun vless(u: UriParts): Built {
        requireUuidLike(u.user)
        val user = buildJsonObject {
            put("id", u.user)
            put("encryption", u.q("encryption").ifEmpty { "none" })
            u.q("flow").takeIf { it.isNotEmpty() }?.let { put("flow", it) }
            put("level", 8)
        }
        val outbound = buildJsonObject {
            put("protocol", "vless")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", u.host)
                        put("port", u.port)
                        put("users", buildJsonArray { add(user) })
                    })
                })
            })
            put("streamSettings", stream(u.query, u.host, defaultSecurity = "none"))
        }
        return Built(ConfigProtocol.VLESS, outbound, u.host, u.port, u.name)
    }

    private fun trojan(u: UriParts): Built {
        if (u.user.isEmpty()) throw ConfigImportException("trojan link has no password")
        val outbound = buildJsonObject {
            put("protocol", "trojan")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", u.host)
                        put("port", u.port)
                        put("password", u.user)
                        u.q("flow").takeIf { it.isNotEmpty() }?.let { put("flow", it) }
                        put("level", 8)
                    })
                })
            })
            put("streamSettings", stream(u.query, u.host, defaultSecurity = "tls"))
        }
        return Built(ConfigProtocol.TROJAN, outbound, u.host, u.port, u.name)
    }

    private fun vmess(link: String): Built {
        val body = link.substringAfter("://").trim()
        val decoded = decodeLoose(body.substringBefore('#'))
        if (decoded == null || !decoded.trimStart().startsWith("{")) {
            // URI form: vmess://uuid@host:port?type=ws&security=tls...
            val u = UriParts.of(link)
            requireUuidLike(u.user)
            return vmessOutbound(u.user, u.host, u.port, u.q("alterId", "aid").toIntOrNull() ?: 0,
                u.q("encryption", "scy").ifEmpty { "auto" }, u.query, u.name)
        }
        val o = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            ?: throw ConfigImportException("vmess link is not valid JSON")
        fun s(key: String): String = o[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val host = s("add")
        val port = s("port").toIntOrNull() ?: throw ConfigImportException("vmess link has no port")
        if (host.isEmpty()) throw ConfigImportException("vmess link has no address")
        val id = s("id")
        requireUuidLike(id)
        val q = LinkedHashMap<String, String>()
        q["type"] = s("net").ifEmpty { "tcp" }
        q["headerType"] = s("type")
        q["host"] = s("host")
        q["path"] = s("path")
        q["security"] = s("tls")
        q["sni"] = s("sni")
        q["alpn"] = s("alpn")
        q["fp"] = s("fp")
        q["allowInsecure"] = s("allowInsecure").ifEmpty { s("insecure") }
        if (q["type"] == "grpc") {
            q["serviceName"] = s("path")
            q["mode"] = if (s("type") == "multi") "multi" else "gun"
        }
        if (q["type"] == "kcp") q["seed"] = s("path")
        if (q["type"] == "xhttp" || q["type"] == "splithttp") q["mode"] = s("type").ifEmpty { "auto" }
        return vmessOutbound(id, host, port, s("aid").toIntOrNull() ?: 0, s("scy").ifEmpty { "auto" }, q, s("ps"))
    }

    private fun vmessOutbound(
        id: String, host: String, port: Int, aid: Int, scy: String, q: Map<String, String>, name: String,
    ): Built {
        val outbound = buildJsonObject {
            put("protocol", "vmess")
            put("settings", buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", id)
                                put("alterId", aid)
                                put("security", scy)
                                put("level", 8)
                            })
                        })
                    })
                })
            })
            put("streamSettings", stream(q, host, defaultSecurity = "none"))
        }
        return Built(ConfigProtocol.VMESS, outbound, host, port, name)
    }

    private fun shadowsocks(link: String): Built {
        var body = link.substringAfter("://")
        val name = if ('#' in body) decode(body.substringAfter('#')) else ""
        body = body.substringBefore('#')
        val query = if ('?' in body) body.substringAfter('?') else ""
        body = body.substringBefore('?').trimEnd('/')
        val method: String
        val password: String
        val hostPortText: String
        if ('@' in body) {
            // SIP002: base64(method:password)@host:port, or plain method:password@host:port.
            val userinfo = body.substring(0, body.lastIndexOf('@'))
            hostPortText = body.substring(body.lastIndexOf('@') + 1)
            val plain = decode(userinfo)
            val creds = if (':' in plain && !looksBase64(plain)) plain else decodeLoose(userinfo) ?: plain
            method = creds.substringBefore(':')
            password = creds.substringAfter(':', "")
        } else {
            // Legacy: base64(method:password@host:port).
            val whole = decodeLoose(body) ?: throw ConfigImportException("shadowsocks link is not valid")
            val creds = whole.substring(0, whole.lastIndexOf('@').coerceAtLeast(0))
            hostPortText = whole.substring(whole.lastIndexOf('@') + 1)
            method = creds.substringBefore(':')
            password = creds.substringAfter(':', "")
        }
        if (method.isEmpty() || password.isEmpty()) throw ConfigImportException("shadowsocks link has no method or password")
        val plugin = query.split('&').firstOrNull { it.startsWith("plugin=") }?.let { decode(it.substringAfter('=')) }
        if (!plugin.isNullOrBlank() && !plugin.startsWith("none")) {
            throw ConfigImportException("shadowsocks plugins ($plugin) are not supported")
        }
        val (host, port) = splitHostPort(hostPortText)
        val outbound = buildJsonObject {
            put("protocol", "shadowsocks")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", host)
                        put("port", port)
                        put("method", method.lowercase())
                        put("password", password)
                        put("level", 8)
                    })
                })
            })
            put("streamSettings", buildJsonObject { put("network", "raw") })
        }
        return Built(ConfigProtocol.SHADOWSOCKS, outbound, host, port, name)
    }

    private fun socks(u0: UriParts): Built {
        var user = ""
        var pass = ""
        if (u0.user.isNotEmpty()) {
            val creds = if (':' in u0.user) u0.user else decodeLoose(u0.user) ?: u0.user
            user = creds.substringBefore(':')
            pass = creds.substringAfter(':', "")
        }
        val outbound = buildJsonObject {
            put("protocol", "socks")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", u0.host)
                        put("port", u0.port)
                        if (user.isNotEmpty()) {
                            put("users", buildJsonArray {
                                add(buildJsonObject { put("user", user); put("pass", pass); put("level", 8) })
                            })
                        }
                    })
                })
            })
        }
        return Built(ConfigProtocol.SOCKS, outbound, u0.host, u0.port, u0.name)
    }

    private fun wireguard(u: UriParts): Built {
        if (u.user.isEmpty()) throw ConfigImportException("wireguard link has no private key")
        val peerKey = u.q("publickey", "publicKey", "public_key")
        if (peerKey.isEmpty()) throw ConfigImportException("wireguard link has no peer public key")
        val addresses = u.q("address", "ip").ifEmpty { "10.0.0.2/32" }
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val outbound = buildJsonObject {
            put("protocol", "wireguard")
            put("settings", buildJsonObject {
                put("secretKey", u.user)
                put("address", buildJsonArray { addresses.forEach { add(JsonPrimitive(it)) } })
                put("mtu", u.q("mtu").toIntOrNull() ?: 1280)
                u.q("reserved").takeIf { it.isNotEmpty() }?.let { r ->
                    put("reserved", buildJsonArray {
                        r.split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { add(JsonPrimitive(it)) }
                    })
                }
                put("peers", buildJsonArray {
                    add(buildJsonObject {
                        put("publicKey", peerKey)
                        u.q("presharedkey", "preSharedKey").takeIf { it.isNotEmpty() }?.let { put("preSharedKey", it) }
                        put("endpoint", hostPort(u.host, u.port))
                        put("allowedIPs", buildJsonArray { add(JsonPrimitive("0.0.0.0/0")); add(JsonPrimitive("::/0")) })
                    })
                })
            })
        }
        return Built(ConfigProtocol.WIREGUARD, outbound, u.host, u.port, u.name)
    }

    /**
     * `streamSettings` from the de-facto share-link parameters (v2rayN / Xray
     * VLESS share standard): `type`, `security`, `sni`, `fp`, `alpn`, `host`,
     * `path`, `serviceName`, `mode`, `headerType`, `pbk`, `sid`, `spx`, `pqv`,
     * `allowInsecure` / `insecure`, `seed`, `extra`, `authority`.
     */
    internal fun stream(q: Map<String, String>, address: String, defaultSecurity: String): JsonObject {
        fun v(vararg k: String) = k.firstNotNullOfOrNull { key -> q[key]?.trim()?.takeIf { it.isNotEmpty() } } ?: ""
        val net = when (val t = v("type", "net").lowercase().ifEmpty { "tcp" }) {
            "tcp", "raw" -> "raw"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "kcp", "mkcp" -> "kcp"
            "h2", "http" -> throw ConfigImportException("the HTTP/2 (h2) transport was removed from Xray; ask for an XHTTP config")
            "quic" -> throw ConfigImportException("the QUIC transport was removed from Xray")
            else -> throw ConfigImportException("unsupported transport: $t")
        }
        val security = v("security").lowercase().ifEmpty { defaultSecurity }.let { if (it == "xtls") "tls" else it }
        val host = v("host")
        val path = v("path")
        val sni = v("sni", "serverName", "peer").ifEmpty {
            host.substringBefore(',').ifEmpty { address.takeIf { isDomain(it) } ?: "" }
        }
        val insecure = v("allowInsecure", "insecure").lowercase() in setOf("1", "true", "yes")
        val alpn = v("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return buildJsonObject {
            put("network", net)
            when (security) {
                "tls" -> {
                    put("security", "tls")
                    put("tlsSettings", buildJsonObject {
                        if (sni.isNotEmpty()) put("serverName", sni)
                        put("allowInsecure", insecure)
                        v("pinnedPeerCertSha256").takeIf { it.isNotEmpty() }?.let { pin ->
                            require(pin.split(',').all { token -> token.trim().replace(":", "").matches(Regex("[0-9a-fA-F]{64}")) }) {
                                "invalid TLS certificate pin"
                            }
                            put("pinnedPeerCertSha256", pin)
                        }
                        v("fp", "fingerprint").takeIf { it.isNotEmpty() }?.let { put("fingerprint", it) }
                        if (alpn.isNotEmpty()) put("alpn", buildJsonArray { alpn.forEach { add(JsonPrimitive(it)) } })
                    })
                }
                "reality" -> {
                    val pbk = v("pbk", "publicKey")
                    if (pbk.isEmpty()) throw ConfigImportException("reality link has no public key (pbk)")
                    put("security", "reality")
                    put("realitySettings", buildJsonObject {
                        if (sni.isNotEmpty()) put("serverName", sni)
                        put("fingerprint", v("fp", "fingerprint").ifEmpty { "chrome" })
                        put("publicKey", pbk)
                        put("shortId", v("sid", "shortId"))
                        put("spiderX", v("spx", "spiderX"))
                        v("pqv", "mldsa65Verify").takeIf { it.isNotEmpty() }?.let { put("mldsa65Verify", it) }
                    })
                }
                "none", "" -> put("security", "none")
                else -> throw ConfigImportException("unsupported security: $security")
            }
            when (net) {
                "raw" -> if (v("headerType").lowercase() == "http") {
                    put("rawSettings", buildJsonObject {
                        put("header", buildJsonObject {
                            put("type", "http")
                            put("request", buildJsonObject {
                                put("path", buildJsonArray {
                                    path.ifEmpty { "/" }.split(',').forEach { add(JsonPrimitive(it.trim())) }
                                })
                                if (host.isNotEmpty()) {
                                    put("headers", buildJsonObject {
                                        put("Host", buildJsonArray { host.split(',').forEach { add(JsonPrimitive(it.trim())) } })
                                    })
                                }
                            })
                        })
                    })
                }
                "ws" -> put("wsSettings", buildJsonObject {
                    put("path", path.ifEmpty { "/" })
                    if (host.isNotEmpty()) put("host", host)
                })
                "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
                    put("path", path.ifEmpty { "/" })
                    if (host.isNotEmpty()) put("host", host)
                })
                "grpc" -> put("grpcSettings", buildJsonObject {
                    put("serviceName", v("serviceName").ifEmpty { path })
                    put("multiMode", v("mode").lowercase() == "multi")
                    v("authority").takeIf { it.isNotEmpty() }?.let { put("authority", it) }
                })
                "xhttp" -> put("xhttpSettings", buildJsonObject {
                    put("path", path.ifEmpty { "/" })
                    if (host.isNotEmpty()) put("host", host)
                    put("mode", v("mode").ifEmpty { "auto" })
                    v("extra").takeIf { it.isNotEmpty() }?.let { extra ->
                        runCatching { json.parseToJsonElement(extra).jsonObject }.getOrNull()?.let { put("extra", it) }
                    }
                })
                "kcp" -> put("kcpSettings", buildJsonObject {
                    put("header", buildJsonObject { put("type", v("headerType").ifEmpty { "none" }) })
                    v("seed").takeIf { it.isNotEmpty() }?.let { put("seed", it) }
                })
            }
        }
    }

    // ------------------------------------------------------------------ JSON

    /** An Xray / V2Ray JSON config, a list of them, or a bare outbound. */
    private fun parseJson(text: String, source: ConfigSource, now: Long): List<CustomConfig> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: throw ConfigImportException("the JSON could not be read")
        val configs: List<JsonObject> = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        val out = configs.mapNotNull { cfg ->
            val outbound = pickOutbound(cfg) ?: return@mapNotNull null
            val remarks = (cfg["remarks"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fromOutbound(outbound, remarks, ConfigFormat.XRAY_JSON, source, raw = cfg.toString(), now = now)
        }
        if (out.isEmpty()) throw ConfigImportException("the JSON has no proxy outbound")
        return out
    }

    /** The proxy outbound of a full config (tag `proxy` first), or [cfg] itself when it IS an outbound. */
    internal fun pickOutbound(cfg: JsonObject): JsonObject? {
        if (cfg["protocol"] is JsonPrimitive && cfg["outbounds"] == null) {
            return cfg.takeIf { isProxyProtocol(it) }
        }
        val outbounds = (cfg["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val proxies = outbounds.filter { isProxyProtocol(it) }
        return proxies.firstOrNull { (it["tag"] as? JsonPrimitive)?.contentOrNull == "proxy" } ?: proxies.firstOrNull()
    }

    private fun isProxyProtocol(o: JsonObject): Boolean {
        val p = (o["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return false
        return p !in setOf("freedom", "blackhole", "dns", "loopback")
    }

    /**
     * Cleans an outbound taken from someone else's config so it can sit inside
     * the config this app writes: its tag, any `dialerProxy` / `proxySettings`
     * pointing at outbounds that do not exist here, and the client-only `show`
     * flag are dropped. Everything else - transport, TLS/REALITY, mux - is kept
     * exactly as the author wrote it.
     */
    internal fun sanitizeOutbound(o: JsonObject): JsonObject {
        val stream = (o["streamSettings"] as? JsonObject)?.let { s ->
            JsonObject(s.mapNotNull { (k, v) ->
                when (k) {
                    "sockopt" -> (v as? JsonObject)?.let { so ->
                        JsonObject(so.filterKeys { it != "dialerProxy" && it != "mark" && it != "interface" })
                            .takeIf { it.isNotEmpty() }?.let { k to it }
                    }
                    "tlsSettings" -> (v as? JsonObject)?.let { t -> k to JsonObject(t.filterKeys { it != "show" }) }
                    "realitySettings" -> (v as? JsonObject)?.let { t -> k to JsonObject(t.filterKeys { it != "show" }) }
                    else -> k to v
                }
            }.toMap())
        }
        return JsonObject(buildMap {
            o.forEach { (k, v) ->
                when (k) {
                    "tag", "proxySettings", "sendThrough" -> Unit
                    "streamSettings" -> stream?.let { put(k, it) }
                    else -> put(k, v)
                }
            }
        })
    }

    private fun fromOutbound(
        outbound: JsonObject,
        name: String,
        format: ConfigFormat,
        source: ConfigSource,
        raw: String,
        now: Long,
        locked: Boolean = false,
        note: String = "",
        expiresAt: Long = 0L,
        mobileOnly: Boolean = false,
    ): CustomConfig {
        val clean = sanitizeOutbound(outbound)
        val protocol = ConfigProtocol.fromXray((clean["protocol"] as? JsonPrimitive)?.contentOrNull)
        val (host, port) = serverOf(clean)
        return CustomConfig(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "${protocol.short} $host" }.trim().take(MAX_NAME),
            protocol = protocol,
            format = format,
            source = source,
            outbound = clean.toString(),
            raw = if (locked) "" else raw,
            server = if (locked) "" else hostPort(host, port),
            locked = locked,
            note = note.take(MAX_NOTE),
            expiresAt = expiresAt,
            mobileOnly = mobileOnly,
            addedAt = now,
        )
    }

    /** The first server address and port named in an outbound, for display. */
    internal fun serverOf(o: JsonObject): Pair<String, Int> {
        val settings = o["settings"] as? JsonObject ?: return "" to 0
        val entry = ((settings["vnext"] ?: settings["servers"]) as? JsonArray)?.firstOrNull() as? JsonObject
        if (entry != null) {
            val host = (entry["address"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val port = (entry["port"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0
            return host to port
        }
        // The flat form newer Xray accepts: settings.address / settings.port.
        (settings["address"] as? JsonPrimitive)?.contentOrNull?.let { host ->
            val port = (settings["port"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0
            return host to port
        }
        val peer = (settings["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
        val endpoint = (peer?.get("endpoint") as? JsonPrimitive)?.contentOrNull ?: return "" to 0
        return runCatching { splitHostPort(endpoint) }.getOrDefault(endpoint to 0)
    }

    // ------------------------------------------------------------------ npvt

    fun parseBytes(bytes: ByteArray, source: ConfigSource, npvtTables: () -> NpvtCodec.Tables,
        npvsTables: () -> NpvtCodec.Tables, password: String? = null,
        now: Long = System.currentTimeMillis()): List<CustomConfig> {
        require(bytes.size <= 2 * 1024 * 1024) { "config file is too large" }
        if (!NpvtCodec.isBinary(bytes)) return parse(NpvtCodec.decodeFileText(bytes), source, npvtTables, password, now)
        val decoded = try { NpvsCodec.decode(bytes, npvsTables(), password)
        } catch (e: NpvtPasswordException) { throw e
        } catch (e: IllegalArgumentException) { throw ConfigImportException(e.message ?: "invalid NPVS file") }
        return parseDecodedNpvt(decoded, "", source, password, now)
    }

    private fun parseNpvo(text: String, source: ConfigSource, password: String?, now: Long): List<CustomConfig> {
        val decoded = NpvoCodec.decode(text)
        // Each editable record owns its own normalized source. Editing #2 must not re-import #1.
        return Json.parseToJsonElement(decoded.servers).jsonArray.flatMap { server ->
            val single = JsonArray(listOf(server)).toString()
            val raw = "NPVO1\n" + buildJsonObject { put("configs", JsonArray(listOf(server))) }
            parseDecodedNpvt(NpvtCodec.Decoded(1, single, "{}"), raw, source, password, now)
        }
    }

    private fun parseNpvt(
        text: String,
        source: ConfigSource,
        tables: NpvtCodec.Tables,
        password: String?,
        now: Long,
    ): List<CustomConfig> {
        val decoded = try {
            NpvtCodec.decode(text, tables)
        } catch (e: IllegalArgumentException) {
            throw ConfigImportException(e.message ?: "npvt file could not be read")
        }
        return parseDecodedNpvt(decoded, text, source, password, now)
    }

    private fun parseDecodedNpvt(decoded: NpvtCodec.Decoded, text: String, source: ConfigSource,
        password: String?, now: Long): List<CustomConfig> {
        val servers = runCatching { json.parseToJsonElement(decoded.servers).jsonArray }.getOrNull()
            ?: throw ConfigImportException("npvt file could not be decrypted")
        val globalLock = runCatching { json.parseToJsonElement(decoded.lock).jsonObject }.getOrNull()
        val out = ArrayList<CustomConfig>()
        var skipped: String? = null
        servers.forEachIndexed { index, el ->
            val server = el as? JsonObject ?: return@forEachIndexed
            val lock = NpvtLock.of(server["lockConfig"] as? JsonObject ?: globalLock)
            lock.check(password, now)
            val type = (server["type"] as? JsonPrimitive)?.contentOrNull?.uppercase().orEmpty()
            if (type != "V2RAY") {
                skipped = "npvt server type $type is not supported (only V2Ray/Xray servers are)"
                return@forEachIndexed
            }
            val profile = server["v2rayProfile"] as? JsonObject ?: return@forEachIndexed
            val outbound = npvtOutbound(profile) ?: run {
                skipped = "an npvt server could not be converted"
                return@forEachIndexed
            }
            val name = (server["name"] as? JsonPrimitive)?.contentOrNull
                ?: (profile["remarks"] as? JsonPrimitive)?.contentOrNull ?: ""
            val numbered = if (servers.size > 1) "${name.ifBlank { "Npvt" }} #${index + 1}" else name
            out += fromOutbound(
                outbound = outbound,
                name = numbered,
                format = ConfigFormat.NPVT,
                source = source,
                raw = text.trim(),
                now = now,
                locked = lock.locked,
                note = lock.message,
                expiresAt = lock.expiresAt,
                mobileOnly = lock.mobileOnly,
            )
        }
        if (out.isEmpty()) throw ConfigImportException(skipped ?: "the npvt file holds no servers")
        return out
    }

    /** The outbound of an npvt V2Ray server: its embedded Xray JSON when present, otherwise rebuilt from its fields. */
    private fun npvtOutbound(p: JsonObject): JsonObject? {
        fun s(key: String) = (p[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        s("v2rayJson").takeIf { it.isNotEmpty() }?.let { embedded ->
            runCatching { json.parseToJsonElement(embedded).jsonObject }.getOrNull()
                ?.let { pickOutbound(it) }?.let { return it }
        }
        val host = s("server")
        val port = s("serverPort").toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        val q = LinkedHashMap<String, String>()
        q["type"] = s("network").ifEmpty { "tcp" }
        q["security"] = s("security")
        q["host"] = s("host")
        q["path"] = s("path")
        q["sni"] = s("sni")
        q["fp"] = s("fingerPrint")
        q["alpn"] = s("alpn")
        q["flow"] = s("flow")
        q["pbk"] = s("publicKey")
        q["sid"] = s("shortId")
        q["spx"] = s("spiderX")
        q["headerType"] = s("headerType")
        q["serviceName"] = s("serviceName")
        q["mode"] = s("xhttpMode").ifEmpty { s("mode") }
        q["extra"] = s("xhttpExtra")
        q["seed"] = s("seed")
        q["authority"] = s("authority")
        q["pinnedPeerCertSha256"] = s("pinnedPeerCertSha256")
        q["allowInsecure"] = if ((p["tlsAllowInsecure"] as? JsonPrimitive)?.contentOrNull
            ?.let { it == "true" || it == "1" } ?: ((p["insecure"] as? JsonPrimitive)?.contentOrNull == "true")) "1" else "0"
        val id = s("password")
        // v2rayNG's EConfigType numbering, which NapsternetV's export keeps.
        val built = when (s("configType").toIntOrNull()) {
            1 -> vmessOutbound(id, host, port, s("alterId").toIntOrNull() ?: 0, s("method").ifEmpty { "auto" }, q, "")
            3 -> return shadowsocks("ss://${s("method")}:${id}@${hostPort(host, port)}").outbound
            5 -> vless(UriParts(id, host, port, q + ("encryption" to s("method").ifEmpty { "none" }), ""))
            6 -> trojan(UriParts(id, host, port, q, ""))
            else -> return null
        }
        return built.outbound
    }

    // ------------------------------------------------------------------ helpers

    internal fun splitHostPort(text: String): Pair<String, Int> {
        val t = text.trim()
        val (host, portText) = if (t.startsWith("[")) {
            t.substring(1, t.indexOf(']').takeIf { it > 0 } ?: throw ConfigImportException("bad IPv6 address")) to
                t.substringAfter("]:", "")
        } else {
            t.substringBeforeLast(':') to t.substringAfterLast(':', "")
        }
        val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw ConfigImportException("link has no valid port")
        if (host.isBlank()) throw ConfigImportException("link has no server address")
        return host.trim() to port
    }

    internal fun hostPort(host: String, port: Int): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    private fun isDomain(host: String): Boolean =
        host.any { it.isLetter() } && !host.contains(':')

    private fun requireUuidLike(id: String) {
        if (id.isBlank()) throw ConfigImportException("link has no user id")
    }

    private fun looksBase64(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }

    /** Decodes standard or URL-safe base64 with or without padding; null when it is not base64 text. */
    internal fun decodeLoose(text: String): String? {
        val clean = text.trim().filterNot { it.isWhitespace() }
        if (clean.isEmpty() || !looksBase64(clean)) return null
        val padded = clean.trimEnd('=') + "=".repeat((4 - clean.trimEnd('=').length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded.replace('-', '+').replace('_', '/')) }.getOrNull()
            ?: return null
        val s = String(bytes, Charsets.UTF_8)
        // Reject binary garbage: real configs are text.
        if (s.any { it == '\uFFFD' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }) return null
        return s
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private const val MAX_NAME = 80
    private const val MAX_NOTE = 600
}

/** A NapsternetV author's lock settings, enforced by the importer. */
data class NpvtLock(
    val locked: Boolean,
    val password: String,
    val expiresAt: Long,
    val deviceIds: String,
    val mobileOnly: Boolean,
    val message: String,
) {
    /** Refuses a config this device may not use, exactly as the author's lock says. */
    fun check(given: String?, now: Long) {
        if (deviceIds.isNotBlank()) {
            throw ConfigImportException("this npvt config is bound to specific devices by its author")
        }
        if (expiresAt in 1 until now) throw ConfigImportException("this npvt config has expired")
        if (password.isNotEmpty()) {
            if (given.isNullOrEmpty()) throw NpvtPasswordException(wrong = false)
            if (given != password) throw NpvtPasswordException(wrong = true)
        }
    }

    companion object {
        fun of(o: JsonObject?): NpvtLock {
            fun s(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            fun b(k: String) = s(k).lowercase() == "true"
            return NpvtLock(
                locked = b("isLocked"),
                password = s("password"),
                expiresAt = parseExpiry(s("expiryDate")),
                deviceIds = s("deviceIds"),
                mobileOnly = b("onlyMobileNetwork"),
                message = s("message"),
            )
        }

        /** Epoch millis, epoch seconds, or an ISO date / date-time; 0 when empty or unreadable. */
        internal fun parseExpiry(text: String): Long {
            if (text.isBlank()) return 0L
            text.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "yyyy/MM/dd")) {
                val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                runCatching { f.parse(text.take(pattern.replace("'", "").length)) }.getOrNull()?.let { return it.time }
            }
            return 0L
        }
    }
}

/** Helpers the store and the runner share. */
internal fun String.toJsonObjectOrNull(): JsonObject? =
    runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()

internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull