package studio.cluvex.aether.ai

/**
 * Everything in the app the "AI" icon can explain.
 *
 * ## Why an enum and not just the row's title
 *
 * The obvious implementation of "put an AI icon next to every option" is to send
 * the model the visible label and ask what it means. That produces confident
 * nonsense, because the labels are deliberately short and several are ambiguous
 * out of context: "Noize" is an Amnezia obfuscation profile, not audio; "Scan
 * mode" scans WARP edge IP ranges, not files; "Fragment" is TLS ClientHello
 * fragmentation, not disk fragmentation; "Endpoint" is a WireGuard peer address,
 * not an API endpoint. A general model asked about "Fragment size range" in a VPN
 * app will invent something plausible.
 *
 * So each topic carries a [detail]: a factual, English, one-or-two-sentence
 * description of what THIS setting does in THIS app, written from the engine's
 * actual behaviour. The model's job is then reduced to explaining and translating
 * something true, which is what it is good at, instead of guessing, which is what
 * it is dangerous at.
 *
 * [profileKey] is the corresponding key in [AiPatch], so the explanation can be
 * given about the value the user is actually looking at. Topics with no
 * profile-backed value (a whole screen, a language switch) leave it null.
 */
enum class AiTopic(
    val id: String,
    /** Short English name, used in the prompt and as the sheet's title fallback. */
    val label: String,
    /** Ground truth about the setting. The single most important field here. */
    val detail: String,
    /** Key in [AiPatch.WRITABLE]/[AiPatch.read], when this topic has a value. */
    val profileKey: String? = null,
) {
    // ---- connection ------------------------------------------------------
    BACKEND(
        "backend",
        "Network backend",
        "Chooses the network stack, and with it the address the internet sees. " +
            "'Aether' is one hop through the bundled Aether/WARP engine (fastest, " +
            "exit = a Cloudflare WARP address). 'Aether -> Psiphon' brings Aether up " +
            "first as a local SOCKS5 proxy and dials Psiphon through it, so the exit " +
            "belongs to Psiphon while the only leg a local censor sees is Aether's " +
            "obfuscated transport; sites and AI services that refuse WARP addresses " +
            "work in this mode. The three Tor modes use the Tor implementation built " +
            "into the engine: 'Aether -> Tor' carries Tor inside the tunnel, so a " +
            "network that blocks Tor never sees it and the exit is a Tor exit node - " +
            "this is the Tor mode to choose on a censored network. 'Tor' is Tor alone, " +
            "which has to reach the Tor network by itself and uses bridges when it is " +
            "blocked. 'Tor -> Psiphon' dials Psiphon through Tor, so the exit is a " +
            "Psiphon address reached from a Tor one. 'Tor -> Aether' is the reverse " +
            "chain: Tor first, the tunnel built inside it, so the exit is a WARP address " +
            "like plain Aether while the local network sees only Tor and cannot tell a " +
            "VPN is in use - the mode for a network that blocks or throttles " +
            "Cloudflare/WARP itself while Tor still gets through. It is fixed to MASQUE " +
            "over HTTP/2, because Tor carries TCP only and WARP's WireGuard endpoints " +
            "answer on UDP alone. Every Tor mode is slower than the others by design. In " +
            "the two modes whose EXIT is Tor the app answers DNS over TCP inside Tor and " +
            "drops other UDP, including QUIC, which apps retry over TCP; the reverse " +
            "chain carries normal UDP, because the device's traffic travels inside the " +
            "WARP tunnel.",
        "backend",
    ),
    TOR_BRIDGES(
        "torBridges",
        "Tor bridges",
        "How Tor reaches the Tor network when it has to do so by itself, which is " +
            "the case in every mode except 'Aether -> Tor': so in 'Tor', " +
            "'Tor -> Psiphon' and the reverse chain 'Tor -> Aether'. Automatic tries a direct " +
            "connection briefly and then asks bridgedb for bridges suited to the " +
            "country you appear to be in, running them through the obfs4/webtunnel " +
            "transport shipped in the app - nothing to paste in, no CAPTCHA. Always " +
            "skips the direct attempt, which is faster on a network already known to " +
            "block Tor. Off never uses bridges. In 'Aether -> Tor' this setting does " +
            "nothing, because Tor is dialled through the tunnel and the local network " +
            "never sees it.",
        "torBridges",
    ),
    TOR_COUNTRY(
        "torCountry",
        "Which country bridgedb should hand out bridges for, when Tor asks it for " +
            "some. Left blank the engine works it out by asking Cloudflare where it " +
            "is - which is exactly the request that fails, or answers wrongly, on the " +
            "networks where bridges are needed in the first place. Naming your country " +
            "skips that guess and asks for bridges that are known to work there. Two " +
            "letters, like 'ir' or 'ru'. It changes nothing in 'Aether -> Tor', where " +
            "Tor is dialled through the tunnel and needs no bridge at all.",
        "torCountry",
    ),
    TOR_DIRECT_SECS(
        "torDirectSecs",
        "How long Tor tries to reach the network plainly before it falls back to " +
            "bridges, in seconds. 'Automatic' leaves the engine's 75 seconds alone, " +
            "which is right when you do not know whether Tor is blocked where you are. " +
            "Shorten it when you know it is blocked: those seconds are then spent " +
            "waiting for something that cannot work. Lengthen it on a network that is " +
            "merely slow, where a direct connection would succeed if it were given the " +
            "time - a direct Tor connection is faster and simpler than a bridge.",
        "torDirectSecs",
    ),
    TOR_CHECK(
        "torCheck",
        "The address Tor must be able to reach before the app accepts that the " +
            "bootstrap worked. The engine's default is check.torproject.org, which is " +
            "itself blocked on some networks - and then a perfectly good Tor circuit " +
            "fails this one test and the app reports a bootstrap failure for something " +
            "that was working. If you see that, point this at any ordinary host:port " +
            "you know is up. It is a reachability test, not a privacy setting: the " +
            "connection to it runs through Tor like everything else.",
        "torCheck",
    ),
    TOR_BRIDGE_LINES(
        "torBridgeLines",
        "Own bridge lines",
        "Bridge lines you obtained yourself, one per line, used instead of the ones " +
            "the app fetches. The format is the one bridges.torproject.org hands out, " +
            "e.g. 'obfs4 192.0.2.55:38114 <FINGERPRINT> cert=... iat-mode=0'. A line " +
            "whose first word is not a transport the app ships (obfs4, meek_lite, " +
            "webtunnel, snowflake) is ignored rather than passed on. Leave empty " +
            "unless you have a bridge that is known to work on your network.",
        "torBridgeLines",
    ),
    EXIT_REGION(
        "exitRegion",
        "Exit country",
        "Preferred exit country for the chained mode. Psiphon treats it as a HARD " +
            "filter: if it cannot establish a tunnel there, the app retries with an " +
            "automatic exit. Plain Aether ignores it and picks its own anycast exit.",
        "exitRegion",
    ),
    PROTOCOL(
        "protocol",
        "Protocol",
        "The transport the Aether engine uses: Smart (tries strategies and keeps what " +
            "works), MASQUE (QUIC/HTTP-based, usually best through DPI), WireGuard " +
            "(fastest when it is not blocked), WARP*2/gool (WARP inside WARP - two " +
            "hops, slower, works on networks where one hop does not) and MASQUE*2/mim " +
            "(two MASQUE hops, new in engine core 2.0.0, for an exit address in a " +
            "different range than a single hop gives). In the 'Tor' and " +
            "'Tor -> Psiphon' modes this setting does nothing: those bring up no WARP " +
            "tunnel at all. In 'Tor -> Aether' it is overridden to MASQUE over HTTP/2, " +
            "which is the only carrier Tor can hold - the engine refuses WireGuard and " +
            "WARP*2 through Tor.",
        "protocol",
    ),
    SCAN_MODE(
        "scanMode",
        "Scan mode",
        "How hard the engine hunts for a reachable WARP edge endpoint before " +
            "connecting. Turbo is fastest and gives up soonest; Thorough and Ironclad " +
            "test many more candidates and take longer but find a working edge on " +
            "hostile networks. Stealth paces the probing so the scan itself is less " +
            "conspicuous to DPI.",
        "scanMode",
    ),
    IP_VERSION(
        "ipVersion",
        "IP version",
        "Which address family the engine uses to reach the endpoint. V4 is the safe " +
            "default on Iranian mobile networks; V6 can be faster where the operator " +
            "supports it properly; Both lets the engine choose.",
        "ipVersion",
    ),

    // ---- transport / anti-DPI -------------------------------------------
    NOIZE(
        "noize",
        "Obfuscation (Amnezia)",
        "Anti-DPI obfuscation strength. The engine injects junk packets and fake " +
            "handshake signatures so the WireGuard/MASQUE handshake no longer matches a " +
            "fixed fingerprint. Higher profiles (GFW, Aggressive) defeat more " +
            "aggressive inspection but add overhead and slow the handshake.",
        "noize",
    ),
    ENDPOINT_MODE(
        "endpointMode",
        "Endpoint selection",
        "Where the engine gets its peer address: Auto scan (the engine scans clean " +
            "WARP edge ranges), Manual IP (pin one ip:port and skip scanning) or " +
            "Custom range (scan only the ranges you type).",
        "endpointMode",
    ),
    MANUAL_PEER(
        "manualPeer",
        "Manual endpoint",
        "A single WARP edge address as ip:port, e.g. 162.159.192.1:443. The engine " +
            "connects straight to it and does no scanning, which is fast when you know " +
            "a good edge and fails outright when that edge is blocked.",
    ),
    MANUAL_RANGE(
        "manualRange",
        "Custom scan ranges",
        "Comma-separated IP ranges the engine may scan, e.g. 188.114.96.0/24. Accepts " +
            "8.6.112.x, CIDR, or a single IP. The engine scans ONLY these, minus " +
            "anything the no-Iran filter rejects.",
    ),
    KEEPALIVE(
        "keepalive",
        "Keepalive",
        "WireGuard persistent-keepalive interval in seconds. Short intervals hold the " +
            "session open through aggressive carrier NAT timeouts at the cost of a " +
            "little battery and background traffic. 0 uses the engine default.",
        "keepalive",
    ),
    MTU(
        "mtu",
        "MTU",
        "Largest packet size the tunnel interface will carry. 1280 is the safe value " +
            "on Iranian mobile data: an oversized MTU causes path-MTU and " +
            "fragmentation failures that look like 'connected but some sites and " +
            "Telegram will not open'. Higher values are faster only on links that " +
            "genuinely support them.",
        "mtu",
    ),
    FRAGMENT(
        "fragment",
        "TLS fragmentation",
        "Splits the TLS ClientHello across several packets so a DPI box cannot read " +
            "the whole handshake (and the SNI inside it) in one go. Very effective " +
            "against SNI-based blocking, slightly slower to connect.",
        "fragment",
    ),
    FRAGMENT_SIZE(
        "fragmentSize",
        "Fragment size range",
        "Size range, in bytes, of each TLS handshake fragment, written as min-max " +
            "(e.g. 16-32). The engine picks randomly inside the range so the split " +
            "pattern is not itself a fingerprint. Empty means the engine default.",
        "fragmentSize",
    ),
    FRAGMENT_DELAY(
        "fragmentDelay",
        "Fragment delay range",
        "Delay range, in milliseconds, between TLS handshake fragments, as min-max " +
            "(e.g. 2-10). Larger delays defeat DPI boxes that reassemble within a " +
            "short window, at the cost of a slower handshake.",
        "fragmentDelay",
    ),
    ECH(
        "ech",
        "Encrypted Client Hello",
        "Encrypts the ClientHello, so the destination hostname (SNI) is never sent in " +
            "the clear. Hides which site is being reached from anyone inspecting the " +
            "handshake - only works where the far side supports ECH.",
        "ech",
    ),
    MASQUE_HTTP2(
        "masqueHttp2",
        "MASQUE over HTTP/2",
        "Carries the MASQUE transport inside HTTP/2 instead of QUIC. Useful where UDP " +
            "or QUIC is throttled or blocked outright, because the traffic then looks " +
            "like ordinary HTTPS. Required when chaining through an http:// upstream " +
            "proxy.",
        "masqueHttp2",
    ),
    QUICK_RECONNECT(
        "quickReconnect",
        "Quick reconnect",
        "Reuses the last endpoint that worked instead of scanning again after a drop, " +
            "so reconnecting takes seconds rather than a full scan.",
        "quickReconnect",
    ),
    FAST_ENDPOINT(
        "fastEndpointOnly",
        "Only reuse a fast endpoint",
        "Makes quick reconnect check the cached endpoint for SPEED as well as " +
            "reachability, and throw it away for a fresh scan if it is slow. Without " +
            "this, one slow cached edge can halve throughput for a whole session.",
        "fastEndpointOnly",
    ),

    // ---- DNS + routing ---------------------------------------------------
    DNS(
        "dnsServers",
        "DNS inside the tunnel",
        "Resolvers the engine uses for names INSIDE the tunnel. Comma separated; a " +
            "bare IP means port 53. Empty uses the engine default (1.1.1.1, 1.0.0.1). " +
            "These queries travel inside the tunnel, so the operator cannot see them.",
        "dnsServers",
    ),
    ROUTE_BLOCK(
        "routeBlock",
        "Never connect (block)",
        "Destinations the tunnel refuses outright. Comma separated; supports " +
            "example.com, full:, keyword:, regexp:, CIDR like 10.0.0.0/8, port:25 and " +
            "the keyword 'private'. Checked before the direct list.",
    ),
    ROUTE_DIRECT(
        "routeDirect",
        "Bypass tunnel (direct)",
        "Destinations that skip the tunnel and go out on the normal connection. " +
            "Useful for local or domestic services that refuse foreign IPs - and a " +
            "privacy decision, because anything listed here is visible to the operator " +
            "again.",
    ),
    ROUTE_SNIFF(
        "routeSniff",
        "Match domain rules behind the tunnel",
        "Reads the site name out of the TLS/HTTP handshake so the domain rules above " +
            "work on Android at all: without it the engine only ever sees an IP " +
            "address and a rule written as a domain never matches.",
        "routeSniff",
    ),
    ROUTE_SNIFF_MS(
        "routeSniffMs",
        "Name wait window",
        "How long, in milliseconds, the engine waits for the handshake to reveal the " +
            "hostname before giving up and routing by IP. Too short and domain rules " +
            "miss; too long and every new connection stalls by that much. 0 uses the " +
            "default.",
        "routeSniffMs",
    ),

    // ---- upstream / zero trust ------------------------------------------
    UPSTREAM(
        "upstreamProxy",
        "Upstream proxy",
        "Dials out through a proxy already running on this phone. socks5://host:port " +
            "carries every protocol; http://host:port only carries MASQUE over HTTP/2, " +
            "which is switched on automatically. Credentials may be included as " +
            "user:pass@host:port and are passed to the engine privately, never on its " +
            "command line.",
        "upstreamProxy",
    ),
    ZERO_TRUST(
        "teamAuth",
        "Zero Trust enrolment",
        "Joins a Cloudflare Zero Trust ('WARP for Teams') organization instead of " +
            "consumer WARP, using a service token, an e-mail code or an enrolment " +
            "token. With the e-mail code the user can sign in beforehand on this page, " +
            "so connecting needs no code; skipped, the code is asked for while " +
            "connecting. For company devices - leave it off for normal personal use.",
    ),
    GATEWAY(
        "gateway",
        "Organization Gateway",
        "Sends HTTP/HTTPS through the organization's filtering proxy. Adds a hop and " +
            "lets the organization log browsing, so it should stay off unless the " +
            "organization requires it.",
    ),

    // ---- apps + security -------------------------------------------------
    PROXY_MODE(
        "proxyMode",
        "Proxy mode",
        "Runs the engine and a local SOCKS5/HTTP proxy WITHOUT capturing the whole " +
            "device through a system VPN. Only apps that you point at the proxy (for " +
            "example Telegram) use the tunnel; everything else goes out normally.",
        "proxyMode",
    ),
    SPLIT_MODE(
        "splitMode",
        "Split tunneling",
        "Decides which apps the VPN applies to: off (all of them), only the selected " +
            "ones, or all except the selected ones.",
        "splitMode",
    ),
    BLOCKED_APPS(
        "blockedApps",
        "Blocked apps",
        "Apps that get no internet at all while the VPN is on, enforced by the " +
            "built-in filter bridge. Different from split tunneling, which only " +
            "decides whether an app's traffic uses the tunnel.",
    ),
    KILL_SWITCH(
        "killSwitch",
        "Kill switch",
        "Blocks all traffic if the tunnel drops unexpectedly, so nothing leaks onto " +
            "the normal connection while it is down.",
        "killSwitch",
    ),
    STRICT_KILL(
        "strictKillSwitch",
        "Strict kill switch",
        "Keeps blocking even after a MANUAL disconnect, until it is lifted " +
            "explicitly. Nothing reaches the network outside the tunnel, ever, by " +
            "accident.",
    ),
    IPV6_LEAK(
        "ipv6LeakProtection",
        "IPv6 leak protection",
        "Routes IPv6 through the tunnel as well. Without it, a dual-stack network can " +
            "carry IPv6 traffic outside the tunnel and reveal the real address.",
        "ipv6LeakProtection",
    ),
    REPROVISION(
        "autoReprovision",
        "Replace a refused identity",
        "If Cloudflare stops accepting the saved device identity, register a fresh one " +
            "automatically instead of holding a tunnel that connects but carries no " +
            "traffic.",
        "autoReprovision",
    ),
    SMART_RECONNECT(
        "smartReconnect",
        "Smart reconnect",
        "Stops retrying and reports an error after a limited number of failed " +
            "restarts, instead of reconnecting forever in the background.",
        "smartReconnect",
    ),
    RECONNECT_LIMIT(
        "reconnectRetryLimit",
        "Max reconnect attempts",
        "How many consecutive failed restarts are allowed before the app gives up and " +
            "shows an error.",
        "reconnectRetryLimit",
    ),

    // ---- engine tuning ---------------------------------------------------
    TLS_GROUPS(
        "tlsGroups",
        "TLS groups",
        "Key-exchange groups offered in the TLS handshake, e.g. X25519:P-256. Changing " +
            "them changes the handshake's fingerprint, which occasionally slips past a " +
            "DPI box tuned for the default list. Empty uses the engine default.",
        "tlsGroups",
    ),
    VALIDATE_SECS(
        "validateSecs",
        "Validate seconds",
        "How long the engine waits while validating a freshly built tunnel before " +
            "declaring it good. 0 uses the engine default.",
        "validateSecs",
    ),
    RECONNECT_SECS(
        "reconnectSecs",
        "Reconnect seconds",
        "How long the engine waits before retrying after a failure. 0 uses the engine " +
            "default.",
        "reconnectSecs",
    ),
    NO_DATA_CHECK(
        "noDataCheck",
        "No data check",
        "Skips the engine's own end-to-end data probe after connecting. Connects " +
            "faster, at the cost of the check that catches a tunnel which comes up but " +
            "carries nothing.",
        "noDataCheck",
    ),
    NO_PROFILE_RETRY(
        "noProfileRetry",
        "No profile retry",
        "Stops the engine falling back to alternate WireGuard profiles when the first " +
            "one fails.",
        "noProfileRetry",
    ),
    CORE_LOG(
        "coreLogLevel",
        "Core log level",
        "How much the engine writes to the diagnostics log. Debug is what makes a bug " +
            "report useful; it is also noisier and slightly slower, and the log holds " +
            "more about the connection while it is set that high.",
        "coreLogLevel",
    ),

    // ---- app-level -------------------------------------------------------
    LANGUAGE(
        "language",
        "Language",
        "Switches the whole app between English and Persian, including the " +
            "notification, the Quick Settings tile and the home-screen widget. Persian " +
            "also flips the layout right-to-left.",
    ),
    SHARE(
        "share",
        "Share VPN",
        "Turns this phone into a proxy for other devices on the same Wi-Fi or hotspot, " +
            "exposing a local HTTP and SOCKS5 address they can be pointed at. While it " +
            "is on, anyone on that network can use the tunnel, so it is for trusted " +
            "networks only.",
    ),
    DIAGNOSTICS(
        "diagnostics",
        "Diagnostics and logs",
        "The live status of every stage of the tunnel - engine process, in-process " +
            "tunnel, VpnService lifecycle and the end-to-end self-tests - plus the log " +
            "they write. This is what identifies WHICH stage fails when the app says " +
            "connected but nothing loads.",
    ),
    RESET(
        "reset",
        "Reset all settings",
        "Puts every tunnel setting back to its default, including endpoint ranges, " +
            "routing rules and enrolment details. It does not touch the Gemini API key.",
    ),

    // ---- the AI feature explaining itself --------------------------------
    AI_KEY(
        "aiKey",
        "Gemini API key",
        "A free API key from Google AI Studio (aistudio.google.com/apikey). It is " +
            "stored sealed with a hardware-backed key on this device, sent only to " +
            "Google, and only ever through the tunnel.",
    ),
    AI_MODEL(
        "aiModel",
        "Gemini model",
        "Which Gemini model answers. The list is discovered from your own key and " +
            "limited to supported Flash models. Default: Gemini 3.1 Flash-Lite, which has " +
            "the highest free daily request limit and suits continuous use. Gemini 3.8 " +
            "Flash, 3.7 Flash and 3.5 Flash-Lite answer faster but reach the daily limit " +
            "sooner.",
    ),
    AI_AUTO_OPTIMIZE(
        "aiAutoOptimize",
        "Analyse the log on every connect",
        "Off by default. When on: after each successful connect, sends a redacted " +
            "excerpt of the connection log to Gemini, which reports what the operator's " +
            "DPI appears to be doing and proposes matching settings; and the AI chat " +
            "receives the current redacted log with every question, so it can check the " +
            "log itself. Nothing is changed without a tap unless automatic apply is also on.",
    ),
    AI_AUTO_APPLY(
        "aiAutoApply",
        "Apply proposals automatically",
        "Writes a proposal straight into the settings instead of waiting for you to " +
            "review it. Off by default: the proposal comes from a remote model, and " +
            "settings that change on their own are indistinguishable from a bug.",
    ),
    AI_HINTS(
        "aiHints",
        "Show AI icons next to options",
        "Adds the small AI icon beside individual settings. Tapping one asks Gemini to " +
            "explain that specific setting, what it is for and how to use it.",
    ),
    AI_CHAT(
        "aiChat",
        "Aether AI chat",
        "A chat with Gemini inside the app. It knows this app's settings and can " +
            "propose changes to the tuning options; it cannot change anything that " +
            "decides which traffic is protected.",
    ),
    ;

    companion object {
        fun byId(id: String): AiTopic? = entries.firstOrNull { it.id == id }
    }
}
