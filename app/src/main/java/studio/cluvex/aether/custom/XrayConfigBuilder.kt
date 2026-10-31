package studio.cluvex.aether.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Writes the complete Xray config one custom-config hop runs with.
 *
 * ```
 *   inbound   SOCKS5 127.0.0.1:<listenPort>  (UDP on, loopback only)
 *   outbound  "proxy"     the user's config
 *   outbound  "chain-up"  SOCKS5 127.0.0.1:<dialVia>   (only when the config is
 *                         the LAST hop: the proxy dials its server through the
 *                         engine's listener, Xray's `sockopt.dialerProxy`)
 *   outbound  "direct"    freedom, used by Xray's own DNS only
 * ```
 *
 * Two details decide whether a session works on Android at all:
 *
 *  * The SOCKS inbound must answer `UDP ASSOCIATE` (`udp: true`): hev hands it
 *    every UDP flow, and with it every DNS query the device makes.
 *  * A server given by NAME must not be resolved through Go's resolver, which on
 *    Android has no `/etc/resolv.conf` to read. When the config dials out
 *    directly, Xray's own DNS resolves it (`sockopt.domainStrategy: UseIP`),
 *    asking the network's resolvers first. When it dials through the engine, the
 *    name is handed to the engine's SOCKS listener unresolved, so the lookup
 *    happens at the far end of the tunnel and never on the local network.
 */
object XrayConfigBuilder {

    const val PROXY_TAG = "proxy"
    const val CHAIN_TAG = "chain-up"
    const val INBOUND_TAG = "socks-in"
    const val DNS_TAG = "dns-internal"

    /** Used only when the network's own resolvers are unknown. */
    val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")

    fun build(
        outbound: String,
        listenPort: Int,
        dialVia: Int?,
        networkDns: List<String> = emptyList(),
        logLevel: String = "warning",
        muxConcurrency: Int = 0,
    ): String {
        val user = outbound.toJsonObjectOrNull()
            ?: throw IllegalArgumentException("stored config is not valid JSON")
        val proxy = withMux(withSockopt(ConfigParser.sanitizeOutbound(user), dialVia), muxConcurrency)
        val plainDns = (networkDns.map { it.trim() }.filter { DNS_IP.matches(it) } + FALLBACK_DNS).distinct()
        // Dialled through the engine, Xray's own lookups ride TCP: Tor carries no
        // UDP, and a TCP query works through every hop the engine can put first.
        // `localhost` (the system resolver) last, for a network that blocks
        // plain DNS to public resolvers but answers its own.
        val dns = if (dialVia != null) FALLBACK_DNS.map { "tcp://$it:53" } else plainDns + "localhost"

        return buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", logLevel)
                put("access", "none")
            })
            put("dns", buildJsonObject {
                put("tag", DNS_TAG)
                put("queryStrategy", "UseIP")
                put("servers", buildJsonArray { dns.forEach { add(JsonPrimitive(it)) } })
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("tag", INBOUND_TAG)
                    put("listen", LOOPBACK)
                    put("port", listenPort)
                    put("protocol", "socks")
                    put("settings", buildJsonObject {
                        put("auth", "noauth")
                        put("udp", true)
                        put("ip", LOOPBACK)
                        put("userLevel", 8)
                    })
                    // Sniffing stays OFF. With `destOverride: tls` Xray swaps the
                    // address a connection was opened to for the SNI it reads from
                    // the ClientHello. Tor's link handshake to a relay carries a
                    // made-up SNI (`www.<random>.com`), so every guard/relay dial
                    // the engine pushed through this hop became a lookup of a name
                    // that does not exist: "tls handshake eof", "Could not connect
                    // to guard", Tor stuck at 93 % - the Config -> Tor chain stall.
                    // Domain-fronted transports (Psiphon's meek/fronted TLS, meek
                    // bridges) are broken the same way. Nothing here routes by
                    // domain, and every hop that hands us a name already sends it.
                    put("sniffing", buildJsonObject { put("enabled", false) })
                })
            })
            put("outbounds", buildJsonArray {
                add(proxy)
                if (dialVia != null) {
                    add(buildJsonObject {
                        put("tag", CHAIN_TAG)
                        put("protocol", "socks")
                        put("settings", buildJsonObject {
                            put("servers", buildJsonArray {
                                add(buildJsonObject {
                                    put("address", LOOPBACK)
                                    put("port", dialVia)
                                })
                            })
                        })
                    })
                }
                add(buildJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                    put("settings", buildJsonObject { put("domainStrategy", "UseIP") })
                })
            })
            // ---- 1.5.0-r2 ROOT CAUSE: the chain timeouts were desktop-shaped ----
            //
            // Field log, `Npvt -> Tor`: every relay dial the engine pushed through
            // this hop died as "Network IO error, or TLS error, in TLS negotiation
            // ... tls handshake eof" or "Channel for [relay] timed out", Tor sat at
            // 93 %, and the session never left Verifying. Sniffing was already off
            // (1.5.0), so the address was right - the connection was being CUT.
            //
            // `handshake` is the budget Xray gives ONE connection to finish being
            // set up, and in a chain that budget pays for: the config's own TLS /
            // REALITY handshake to its server, plus the far end's dial to the real
            // destination. Tor's link handshake to a guard on the other side of the
            // world routinely needs more than the 8 s this had, and Xray closing the
            // socket mid-handshake is read by arti as exactly "tls handshake eof".
            //
            // `uplinkOnly`/`downlinkOnly` are the half-close budgets (seconds a
            // connection survives after ONE direction has ended). They are set to
            // real grace periods below. `connIdle` at 300 s reaps an idle-but-live Tor channel that
            // the engine still has circuits on, which shows up as a stall the moment
            // the user comes back to the app.
            //
            // These are now chain-shaped: a generous handshake, a half-close grace
            // long enough for a response to finish, and an idle window longer than
            // Tor's own channel lifetime.
            put("policy", buildJsonObject {
                put("levels", buildJsonObject {
                    put("8", buildJsonObject {
                        put("handshake", HANDSHAKE_SECONDS)
                        put("connIdle", CONN_IDLE_SECONDS)
                        // Half-close grace. In Xray 0 does NOT mean "never": it
                        // closes the connection the instant one direction ends
                        // (signal.ActivityTimer.SetTimeout(0) finishes at once).
                        // That cut short every response whose request side was
                        // already half-closed (directory fetches, HTTP/1.0 style
                        // probes). Real grace periods instead.
                        put("uplinkOnly", UPLINK_ONLY_SECONDS)
                        put("downlinkOnly", DOWNLINK_ONLY_SECONDS)
                    })
                })
            })
            put("routing", buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add(JsonPrimitive(DNS_TAG)) })
                        put("outboundTag", if (dialVia != null) CHAIN_TAG else "direct")
                    })
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add(JsonPrimitive(INBOUND_TAG)) })
                        put("outboundTag", PROXY_TAG)
                    })
                })
            })
        }.toString()
    }

    /**
     * The user's outbound exactly as a direct (solo / first-hop) session dials it,
     * re-tagged as [tag]. Used by the real-delay test so that a config is measured
     * with the very outbound the session would run, not an approximation of it.
     */
    internal fun directOutbound(outbound: String, tag: String): JsonObject {
        val user = outbound.toJsonObjectOrNull()
            ?: throw IllegalArgumentException("stored config is not valid JSON")
        val proxy = withSockopt(ConfigParser.sanitizeOutbound(user), dialVia = null).toMutableMap()
        proxy["tag"] = JsonPrimitive(tag)
        return JsonObject(proxy)
    }

    /** The resolvers a direct session gives Xray: the network's own, public ones, then the system's. */
    internal fun directDns(networkDns: List<String>): List<String> =
        (networkDns.map { it.trim() }.filter { DNS_IP.matches(it) } + FALLBACK_DNS).distinct() + "localhost"

    /** Tags the user's outbound and points its dialer either at the engine or at Xray's DNS. */
    private fun withSockopt(o: JsonObject, dialVia: Int?): JsonObject {
        val protocol = o.str("protocol")
        val stream = (o["streamSettings"] as? JsonObject) ?: JsonObject(emptyMap())
        val sockopt = (stream["sockopt"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (dialVia != null) {
            sockopt["dialerProxy"] = JsonPrimitive(CHAIN_TAG)
            sockopt.remove("domainStrategy")
        } else if (protocol != "wireguard") {
            // IPv4 first, IPv6 only when the name has no A record: a CDN-fronted
            // config resolves to both, and many mobile networks have no IPv6 route.
            sockopt["domainStrategy"] = JsonPrimitive("UseIPv4v6")
        }
        val newStream = JsonObject(stream + ("sockopt" to JsonObject(sockopt)))
        val out = o.toMutableMap()
        out["tag"] = JsonPrimitive(PROXY_TAG)
        if (protocol == "wireguard") {
            // WireGuard has no streamSettings of its own in Xray; only the dialer applies.
            out["streamSettings"] = JsonObject(mapOf("sockopt" to JsonObject(sockopt)))
            if (dialVia == null) {
                val settings = (o["settings"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
                settings["domainStrategy"] = JsonPrimitive("ForceIPv4v6")
                out["settings"] = JsonObject(settings)
            }
        } else {
            out["streamSettings"] = newStream
        }
        return JsonObject(out)
    }

    /**
     * Turns Mux on for a config that is dialled THROUGH a slow first hop.
     *
     * ROOT CAUSE this answers (field log, `Tor -> Npvt`): the chain connected and
     * then crawled. Without Mux every single flow the device opens becomes its own
     * `CONNECT` through Tor - a fresh Tor stream, often a fresh circuit, and then a
     * full TLS/REALITY handshake to the config's server on top of it. A page with
     * forty sub-resources pays forty of those, and the ones Tor refuses
     * (`SOCKS5 reply 1`, dozens of them in the log) are paid twice. Mux carries all
     * of them inside ONE already-open connection to the server, so only the first
     * flow pays the chain's setup cost.
     *
     * [concurrency] <= 1 leaves Mux off, which is what every direct (non-chained)
     * session gets: there the per-flow cost is one local TCP dial and Mux would
     * only add head-of-line blocking.
     *
     * Mux is skipped - never silently broken - for the cases the core itself
     * refuses or degrades:
     *  * XTLS flows (`flow: xtls-rprx-vision`): Xray rejects Mux on them outright.
     *  * `wireguard` and `hysteria`: not stream proxies, there is nothing to mux.
     */
    private fun withMux(o: JsonObject, concurrency: Int): JsonObject {
        if (concurrency <= 1) return o
        val protocol = o.str("protocol")?.lowercase()
        if (protocol == "wireguard" || protocol == "hysteria" || protocol == "hysteria2") return o
        if (hasXtlsFlow(o)) return o
        val mux = buildJsonObject {
            put("enabled", true)
            put("concurrency", concurrency.coerceIn(2, 128))
            // UDP keeps its own path: a muxed UDP/443 lane would put QUIC behind
            // the same head-of-line queue as every TCP flow on the hop.
            put("xudpConcurrency", 0)
            put("xudpProxyUDP443", "reject")
        }
        return JsonObject(o + ("mux" to mux))
    }

    /** True when any user in this outbound asks for an XTLS flow, which Mux cannot carry. */
    internal fun hasXtlsFlow(o: JsonObject): Boolean {
        val settings = o["settings"] as? JsonObject ?: return false
        val holders = (settings["vnext"] ?: settings["servers"]) as? JsonArray ?: return false
        return holders.any { holder ->
            val users = (holder as? JsonObject)?.get("users") as? JsonArray ?: return@any false
            users.any { u ->
                ((u as? JsonObject)?.get("flow") as? JsonPrimitive)?.content?.isNotBlank() == true
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    /** True when every outbound server in [outbound] can be reached by Xray (used by tests). */
    internal fun hasServer(outbound: JsonObject): Boolean =
        ((outbound["settings"] as? JsonObject)?.let { it["vnext"] ?: it["servers"] ?: it["peers"] } as? JsonArray)
            ?.isNotEmpty() == true

    /**
     * Seconds one connection gets to finish being set up through this hop.
     *
     * Sized for a CHAIN, not for a direct dial: see the policy block in [build].
     */
    internal const val HANDSHAKE_SECONDS = 30

    /** Seconds an idle-but-live connection is kept. Longer than a Tor channel's own lifetime. */
    internal const val CONN_IDLE_SECONDS = 1_800

    /** Seconds the uplink is kept after the downlink has closed. */
    internal const val UPLINK_ONLY_SECONDS = 10

    /** Seconds the downlink is kept after the uplink has closed (a response still arriving). */
    internal const val DOWNLINK_ONLY_SECONDS = 30

    /**
     * Mux concurrency used when the config is dialled through a slow hop (Tor,
     * Psiphon). Eight is v2rayNG's own default and is a good trade between
     * reusing the expensive hop and head-of-line blocking.
     */
    const val SLOW_CHAIN_MUX = 8

    private const val LOOPBACK = "127.0.0.1"
    private val DNS_IP = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
}