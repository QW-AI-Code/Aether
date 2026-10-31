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
    CUSTOM_CONFIGS("customConfigs", "Custom configs, tabs and imports",
        "Aether 1.6.0 imports VLESS, VMess, Trojan, Shadowsocks, Xray JSON and NPVT/NPVS files via link, text, file or QR. " +
        "All, V2Ray and Npvt are automatic tabs with stable internal keys; names can be edited. User tabs support create, rename, move and delete. " +
        "The selected tab persists across navigation and restart; automatic selection is restricted to it. Tab deletion is confirmed and removes its members. Locked NPVT authors' credentials and endpoints must stay hidden. " +
        "Never ask for private config links, passwords or API keys in chat. Explain steps; you cannot mutate the custom vault."),
    CUSTOM_CHAIN("customChain", "Custom chain order",
        "Arrows are traffic order from the phone to the internet. Config -> Aether starts Xray first, then carries Aether MASQUE HTTP/2 TCP through that local SOCKS proxy; exit IP is WARP. " +
        "Aether -> Config starts Aether first then dials the config through it; the final local Xray port feeds TUN and exit-IP probes. " +
        "A healthy ordinary HTTPS ping does not prove that a remote config permits WARP gateway connections. No direct fallback is allowed."),
    CUSTOM_PING("customPing", "Custom ping and automatic selection",
        "Numeric real delay is measured through each actual config. Below 150 ms is green, 150 through 300 ms amber, above 300 ms red. " +
        "Failures are muted and labelled; they are not latency values. Successful entries can sort by increasing or decreasing delay; failures stay last. " +
        "Auto-select tests eligible configs and periodic health checks use the configured interval. A failed probe alone cannot prove censorship."),
    CUSTOM_VERIFY("customVerify", "Verify custom connection",
        "Enabled by default. This optional bounded HTTP readiness check does not measure upload speed. Turning it off skips only the initial check, " +
        "not core startup, TLS authentication, tunnel supervision or exit-IP lookup. It does not make an unreachable server work."),
    CLEAN_IP_SCANNER("cleanIpScanner", "Clean-IP scanner for custom servers",
        "This UI scanner is separate from the engine WARP endpoint scan. It scans supported, unlocked TLS custom servers only, while disconnected. " +
        "It preserves credentials, SNI and transport; a candidate must pass actual proxy traffic and three stability confirmations before being applied. " +
        "Group scan is limited to the selected tab. Opening settings or asking AI never starts a scan. Scan results depend on this network, not country or censorship guarantees."),
    CLEAN_IP_PROFILE("cleanIpProfile", "Clean-IP scan depth",
        "Fast, Balanced (default) and Deep trade search budget and repeat measurements against time. " +
        "Fast: 120 candidates, 3500 ms timeout, 1 probe/IP, parallelism 60. Balanced: 300, 5000 ms, 2, 50. Deep: 600, 6000 ms, 3, 40. " +
        "These are per-profile search budgets, not promises of total duration or real-delay limits. All profiles still require final proxy validation."),
    CLEAN_IP_FAST("cleanIpFast", "Fast clean-IP scan",
        "120 candidates, 3500 ms per-probe timeout, 1 probe/IP, 60 concurrent probes and 6 neighbours per hit. " +
        "Quick first attempt; may miss healthy IPs on slow networks. Final real-delay validation still uses three confirmations."),
    CLEAN_IP_BALANCED("cleanIpBalanced", "Balanced clean-IP scan",
        "Default: 300 candidates, 5000 ms timeout, 2 probes/IP, 50 concurrent probes and 12 neighbours per hit. " +
        "A compromise between time and stability; use Deep if it finds nothing. Final proxy validation is still required."),
    CLEAN_IP_DEEP("cleanIpDeep", "Deep clean-IP scan",
        "600 candidates, 6000 ms timeout, 3 probes/IP, 40 concurrent probes and 16 neighbours per hit. " +
        "Broader, slower search for difficult networks; no success guarantee. Final proxy validation is still required."),
    CLEAN_IP_LIMIT("cleanIpLimit", "Manual real-delay ceiling",
        "Separate from the scan timeout: optional integer 50..5000 ms, saved default 2000 ms. " +
        "Manual mode accepts the first candidate passing the ceiling AND three actual proxy confirmations. " +
        "Automatic mode ranks the passing measured candidates by latency and confirms the fastest stable one without the manual ceiling. " +
        "Failed probes never count as low ping. AI can explain these separate preferences but cannot change them or start a scan."),
    VERIFY_CONNECTION("verifyConnection", "Initial connection verification",
        "Disabling skips only the optional initial HTTP readiness check, not startup, TLS, supervision or exit-IP lookup. It cannot make an unreachable server work."),
    GAME_DNS("gameDns", "Game DNS override",
        "When game mode is on, valid comma-separated game DNS IPs replace the regular tunnel resolvers. Empty or invalid input falls back to regular DNS. It does not guarantee lower game latency."),
    BYPASS_IRAN("bypassIran", "Bypass Iranian destinations",
        "Uses the bundled or updated Iranian domain/CIDR lists to route those destinations directly outside the tunnel. This exposes their traffic to the operator. Suspended while LAN sharing is enabled."),
    BLOCK_ADS("blockAds", "Block advertising domains",
        "Adds the bundled or updated advertising domain list to blocked routes. Domain rules may require hostname sniffing. Not every ad can be blocked; custom block rules still apply."),
    CONNECTION_SOUND("connectionSound", "Connection sounds",
        "Device-local connection/disconnection feedback, enabled by default. Respects the phone's silent/vibrate mode. Does not change the network or tunnel."),
    BATTERY("battery", "Background battery exemption",
        "Opens Android battery-optimisation settings so the user can allow background VPN work. The app cannot grant this itself. Vendor power saving can otherwise interrupt the tunnel."),
    ANNOUNCEMENTS("announcements", "Release announcements",
        "Reopens this app's bundled version announcements. These are release notes, not real-time network status or security advice from a remote server."),
    ABOUT("about", "About Aether and credits",
        "Shows version, core/build provenance, signature information and source links. Cluvex Studio develops the engine; QW-AI-Code develops this Android UI and its UI-side scanner, tabs and real-delay presentation. Do not attribute engine work to the UI author."),
    SHARE_MODE("shareMode", "Share over Wi-Fi or mobile hotspot",
        "Home Wi-Fi requires both devices on the same router. Mobile hotspot covers the phone's hotspot or USB/Bluetooth tethering. " +
        "Select the mode matching the actual local link and use that link's displayed proxy address on the other device. It does not enable Android tethering itself."),
    SHARE_AUTH("shareAuth", "Sharing proxy authentication",
        "Optional username/password protection for the local HTTP/SOCKS sharing proxy, recommended on shared Wi-Fi. " +
        "Enable, edit or rotate credentials here and configure them on clients. These are NOT Gemini credentials. Never paste proxy passwords into AI chat."),
    SHARE_WEBRTC("shareWebrtc", "Sharing and WebRTC leak checks",
        "A browser can send WebRTC traffic outside its configured proxy. The local sharing setup page provides leak checks and instructions/locks. " +
        "A configured proxy does not guarantee that every application or browser channel uses it. Check the client before trusting the setup."),
    PRESETS("presets", "Saved connection presets",
        "Save, select, rename, delete, import or export connection setting presets from the home selector. Default restores clean tunnel settings even when already selected. " +
        "Presets are distinct from custom-server tabs. Changing a preset while busy is locked. Exported settings may contain private values: never paste their contents into AI chat."),
    SETTINGS("settings", "Aether settings",
        "Settings are grouped into connection, custom configs, clean-IP scanner, transport, DNS/routing, upstream, apps, security, sharing and app/AI pages. Tunnel controls are locked while connected; some device-only preferences remain available."),
    AI_PRIVACY("aiPrivacy", "AI privacy and limits",
        "Requests go to Google only through the final connected SOCKS endpoint. Topic name, factual help and safe current settings may be sent; log analysis uses a redacted excerpt when enabled. Never ask users to paste keys, passwords, tokens, one-time codes or private config links. Offline topic help needs no API key."),
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
    GOOL_MODE(
        "goolMode",
        "Gool mode",
        "Which WARP*2/gool the engine builds (engine core 2.3.0). 'Modern' is the core's " +
            "default --gool: a WireGuard WARP tunnel carried inside a MASQUE one, with its " +
            "own identity registered from inside WARP, so the session leaves from a " +
            "foreign address. 'Classic' is --gool-classic, the older gool: WireGuard " +
            "tunneled inside WireGuard. Only used when the protocol is WARP*2, or when " +
            "Smart picks it.",
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
    TLS_CIPHERS(
        "tlsCiphers",
        "TLS 1.2 ciphers",
        "The TLS 1.2 cipher suites the engine's ClientHello offers, as a BoringSSL " +
            "cipher string with names separated by ':' (engine --tls-ciphers, core " +
            "2.3.0). They are listed after the TLS 1.3 suites, which stay as they are, " +
            "and HTTP/3 lists none because QUIC offers TLS 1.3 alone, so this only " +
            "affects MASQUE over HTTP/2, the WARP API calls and the ECH lookup. Empty " +
            "uses Chrome's own rule, ALL:!aPSK:!ECDSA+SHA1:!3DES. A name the engine " +
            "does not know stops it as it starts.",
    ),
    DISABLE_GREASE(
        "disableGrease",
        "Disable GREASE",
        "Leaves the GREASE values (RFC 8701) out of the cipher suites, extensions, " +
            "groups, key shares and versions of the engine's ClientHello (engine " +
            "--disable-grease, core 2.3.0). Chrome sends GREASE, so this is off by " +
            "default; turning it on makes the handshake stop looking like Chrome's, " +
            "which only helps against a middlebox that chokes on GREASE.",
    ),
    TLS_VERIFY(
        "tlsVerify",
        "Verify gateway certificate",
        "Holds the MASQUE gateway's certificate to the pins the engine carries " +
            "(engine --tls-verify, core 2.4.0). Off by default; the engine turns it " +
            "on by itself with a Zero Trust team. Turn it on if a middlebox might " +
            "be intercepting TLS.",
    ),
    API_ADDRESS(
        "apiAddress",
        "WARP API address",
        "Where the calls that register and enroll the keys go (engine " +
            "--api-address, core 2.4.0; it replaces --enroll-address). An IP address " +
            "or name, with an optional port, e.g. 162.159.137.105 or " +
            "[2606:4700:7::a29f:8969]:443. Empty uses the engine's default.",
    ),
    MASQUE_SNI(
        "masqueSni",
        "MASQUE server name (SNI)",
        "The server name the engine puts in the ClientHello of its MASQUE handshakes (engine --masque-sni, core 2.4.0). Empty uses the engine default. Only affects how the handshake looks to the network; the gateway is still the one chosen by the endpoint.",
    ),
    API_SNI(
        "apiSni",
        "WARP API server name (SNI)",
        "The server name sent in the ClientHello of the calls to the WARP API (engine --api-sni, core 2.4.0), e.g. to front the calls behind another name. Empty uses the default. Used together with the API address and API host.",
    ),
    API_HOST(
        "apiHost",
        "WARP API host",
        "The HTTP Host of the calls to the WARP API (engine --api-host, core 2.4.0). Empty uses the default api.cloudflareclient.com. Normally only changed together with the API address / API server name.",
    ),
    TLS_TRUST_ANCHORS(
        "tlsTrustAnchors",
        "TLS trust anchor IDs",
        "Trust anchor IDs the ClientHello announces (engine --tls-trust-anchors, core 2.4.0): dotted numeric IDs separated by a colon, e.g. 11129.9.1:11129.9.4. Empty announces none.",
    ),
    TLS_CERT_COMPRESSION(
        "tlsCertCompression",
        "Certificate compression",
        "Which certificate compression algorithm the ClientHello offers (engine --tls-cert-compression, core 2.4.0): brotli, zlib or zstd; Off offers the engine default.",
    ),
    TLS13_CIPHERS(
        "tls13Ciphers",
        "TLS 1.3 cipher suites",
        "The TLS 1.3 cipher suites of the engine ClientHello, in order, as standard names separated by a colon (engine --tls13-ciphers, core 2.4.0), e.g. TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256. Empty uses Chrome's order. An unknown name stops the engine at startup.",
    ),
    TLS_KEY_SHARES(
        "tlsKeyShares",
        "TLS key shares",
        "The groups that get a key share in the ClientHello (engine --tls-key-shares, core 2.4.0), e.g. X25519MLKEM768:X25519. Each must be one of the TLS groups. Empty uses Chrome's choice.",
    ),
    TLS_SIGALGS(
        "tlsSigalgs",
        "TLS signature algorithms",
        "The signature algorithms of the ClientHello as BoringSSL names separated by a colon (engine --tls-sigalgs, core 2.4.0), e.g. ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256. Empty uses the engine default.",
    ),
    TLS_ALPS(
        "tlsAlps",
        "ALPS",
        "Whether the ClientHello offers ALPS (application-layer protocol settings) and with which codepoint: Off, New or Old (engine --tls-alps, core 2.4.0). Off offers none.",
    ),
    TLS_SERVER_PADDING(
        "tlsServerPadding",
        "Server padding",
        "Bytes of padding the ClientHello asks of the server, 0 to 65535 (engine --tls-server-padding, core 2.4.0). Empty asks for none.",
    ),
    GREASE_SIGALGS(
        "enableGreaseSigalgs",
        "GREASE signature algorithm",
        "Puts a GREASE value first among the signature algorithms, as Chrome does (engine --enable-grease-sigalgs, core 2.4.0). Off by default.",
    ),
    ECH_GREASE(
        "enableEchGrease",
        "ECH GREASE",
        "Sends a GREASE ECH extension when no real ECH key is offered (engine --enable-ech-grease, core 2.4.0). Off by default.",
    ),
    DISABLE_OCSP_STAPLING(
        "disableOcspStapling",
        "Disable OCSP stapling",
        "Leaves the status_request extension out of the ClientHello (engine --disable-ocsp-stapling, core 2.4.0). Off by default; Chrome sends it.",
    ),
    DISABLE_SCT(
        "disableSct",
        "Disable SCT",
        "Leaves the signed_certificate_timestamp extension out of the ClientHello (engine --disable-sct, core 2.4.0). Off by default; Chrome sends it.",
    ),
    DISABLE_SESSION_TICKET(
        "disableSessionTicket",
        "Disable session tickets",
        "Leaves the session_ticket extension out of the ClientHello (engine --disable-session-ticket, core 2.4.0). Off by default.",
    ),
    DISABLE_EXTENSION_PERMUTATION(
        "disableExtensionPermutation",
        "Disable extension permutation",
        "Keeps one fixed order of the ClientHello extensions instead of Chrome's per-handshake shuffle (engine --disable-extension-permutation, core 2.4.0). Off by default.",
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

    fun offlineDetail(persian: Boolean): String {
        if (!persian) return detail
        return when (this) {
            CLEAN_IP_SCANNER -> "این اسکنر رابط کاربری با اسکن ورودی WARP موتور متفاوت است. فقط سرورهای سفارشی TLS پشتیبانی‌شده و قفل‌نشده، در حالت قطع اتصال و در تب انتخابی بررسی می‌شوند. هر مقصد باید ترافیک واقعی پروکسی و سه تأیید پایداری را پاس کند. باز کردن راهنما یا تنظیمات اسکن را شروع نمی‌کند."
            CLEAN_IP_PROFILE -> "سریع: 120 نامزد، مهلت 3500 ms و یک پروب برای هر IP. متعادل (پیش‌فرض): 300 نامزد، 5000 ms و دو پروب. عمیق: 600 نامزد، 6000 ms و سه پروب؛ کندتر و گسترده‌تر است. مهلت پروب با سقف پینگ واقعی یا زمان کل اسکن یکی نیست. همهٔ حالت‌ها تأیید نهایی پروکسی دارند."
            CLEAN_IP_FAST -> "برای تلاش اول و سریع: 120 نامزد، مهلت هر پروب 3500 ms، یک پروب برای هر IP و 60 پروب هم‌زمان؛ 6 همسایه برای هر نتیجهٔ سالم. ممکن است IP سالم در شبکهٔ کند پیدا نشود؛ سه تأیید نهایی ترافیک واقعی همچنان لازم است."
            CLEAN_IP_BALANCED -> "حالت پیش‌فرض: 300 نامزد، مهلت 5000 ms، دو پروب برای هر IP، 50 پروب هم‌زمان و 12 همسایه برای هر نتیجهٔ سالم. تعادل زمان و دقت؛ اگر نتیجه‌ای پیدا نشد عمیق را امتحان کنید. تأیید واقعی پروکسی همچنان الزامی است."
            CLEAN_IP_DEEP -> "جست‌وجوی گسترده‌تر و کندتر: 600 نامزد، مهلت 6000 ms، سه پروب برای هر IP، 40 پروب هم‌زمان و 16 همسایه برای هر نتیجهٔ سالم. مناسب شبکهٔ دشوار، بدون تضمین موفقیت و با تأیید نهایی پروکسی."
            CLEAN_IP_LIMIT -> "سقف دستی عددی بین 50 تا 5000 ms است؛ مقدار ذخیره‌شدهٔ پیش‌فرض 2000 ms. این سقف با مهلت پروب فرق دارد. حالت دستی اولین نامزد زیر سقف را پس از سه تأیید واقعی می‌پذیرد؛ خودکار، نامزدهای اندازه‌گیری‌شده را مرتب و سریع‌ترین مورد پایدار را تأیید می‌کند، بدون سقف دستی. دستیار فقط راهنمایی می‌کند و تنظیمات اسکن یا شروع آن را تغییر نمی‌دهد."
            else -> detail
        }
    }

    companion object {
        fun byId(id: String): AiTopic? = entries.firstOrNull { it.id == id }
    }
}
