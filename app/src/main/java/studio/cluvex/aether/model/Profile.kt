package studio.cluvex.aether.model


/** Transport protocol, mapped 1:1 to the desktop app's CLI flags. */
enum class Protocol { AUTO, MASQUE, WIREGUARD, GOOL }

/** Endpoint scanning strategy. IRONCLAD added in engine v1.3.0. */
enum class ScanMode { TURBO, BALANCED, THOROUGH, STEALTH, IRONCLAD }

/** IP family preference. */
enum class IpVersion { V4, V6, BOTH }

/**
 * Anti-DPI obfuscation profile ("Amnezia"-style). Maps to the engine's
 * `--noize <profile>` option (see aethernoize.rs / noize.rs in the engine).
 * The engine injects junk packets + fake handshake signatures so WireGuard /
 * MASQUE traffic no longer looks like a fixed fingerprint to DPI boxes.
 */
enum class Noize { OFF, LIGHT, FIREWALL, BALANCED, GFW, AGGRESSIVE }

/**
 * Where the engine gets its endpoint from:
 *  - AUTO         : engine scans the clean (non-Iranian) WARP edge ranges.
 *  - MANUAL_PEER  : user pins one endpoint `ip:port`; the engine skips scanning.
 *  - MANUAL_RANGE : user types their own IP range(s); the engine scans ONLY those.
 *
 * Whatever is chosen here, the exit is still verified end-to-end before the
 * session is accepted.
 */
enum class EndpointMode { AUTO, MANUAL_PEER, MANUAL_RANGE }

/** Per-app tunneling policy (split tunneling). */
enum class SplitMode { OFF, INCLUDE, EXCLUDE }

/**
 * How the device enrols into a Cloudflare Zero Trust ("WARP for teams")
 * organization. New in engine v1.5.0 (see zerotrust.rs).
 *  - OFF            : consumer WARP, no organization (default).
 *  - SERVICE_TOKEN  : headless enrolment with an Access service token id+secret.
 *  - EMAIL          : one-time code sent to a work e-mail address.
 *  - TOKEN          : an enrolment JWT the user already obtained in a browser
 *                     at https://<team>.cloudflareaccess.com/warp.
 */
enum class TeamAuth { OFF, SERVICE_TOKEN, EMAIL, TOKEN }

/** Engine core log verbosity (1.2.4). Mapped to the engine's AETHER_LOG_LEVEL. */
enum class CoreLogLevel(val raw: String) { OFF("off"), ERROR("error"), WARN("warn"), INFO("info"), DEBUG("debug") }

/**
 * User-tunable connection profile. Knows how to turn itself into the engine's
 * CLI arguments and environment variables.
 */
data class ConnectionProfile(
    val protocol: Protocol = Protocol.AUTO,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val ipVersion: IpVersion = IpVersion.V4,
    val quickReconnect: Boolean = true,
    val masqueHttp2: Boolean = false,
    /**
     * Share the tunnel with other devices on the same Wi-Fi / hotspot via the
     * in-app proxy bridge (see [studio.cluvex.aether.core.ShareBridge]).
     * UI-side option only — it never reaches the engine's CLI args.
     */
    val lanShare: Boolean = false,

    // ---- Added in 1.2.0 (engine v1.3.0 feature parity) ----

    /** Anti-DPI obfuscation ("Amnezia"). */
    val noize: Noize = Noize.OFF,
    /** Endpoint selection strategy. */
    val endpointMode: EndpointMode = EndpointMode.AUTO,
    /** `ip:port` used when [endpointMode] is MANUAL_PEER. */
    val manualPeer: String = "",
    /**
     * Comma-separated IP range(s) used when [endpointMode] is MANUAL_RANGE,
     * e.g. "8.6.112.x" or "188.114.96.0/24, 162.159.192.0/24". The engine
     * scans exactly these ranges (see AETHER_SCAN_CIDRS in prober.rs), minus
     * anything the no-Iran filter rejects.
     */
    val manualRange: String = "",
    /** WireGuard persistent keepalive, seconds. 0 = engine default (5). */
    val keepalive: Int = 0,
    /** Fragment the TLS ClientHello on the HTTP/2 transport (anti-DPI). */
    val fragment: Boolean = false,
    /** Enable Encrypted Client Hello (hides the real SNI). */
    val ech: Boolean = false,

    // ---- App-side only (never reach the engine CLI) ----

    /** TUN interface MTU. 1280 is the safe default for Iranian mobile/DPI. */
    val mtu: Int = DEFAULT_MTU,
    /**
     * Proxy mode: run the engine + local SOCKS5/HTTP proxy WITHOUT capturing
     * the whole device through a system VPN/TUN. Lets apps that support SOCKS5
     * natively (e.g. Telegram) use the tunnel selectively.
     */
    val proxyMode: Boolean = false,
    /** Split-tunneling policy. */
    val splitMode: SplitMode = SplitMode.OFF,
    /** Package names the split policy applies to. */
    val splitApps: List<String> = emptyList(),

    // ---- Added in 1.2.3 (engine v1.5.0 feature parity) ----

    /**
     * Resolvers used INSIDE the tunnel (engine `--dns`). Blank = engine default
     * (1.1.1.1, 1.0.0.1). Comma separated; a bare IP implies port 53.
     */
    val dnsServers: String = "",

    /**
     * Zero Trust organization ("team") name, e.g. "acme" for
     * acme.cloudflareaccess.com. Blank = consumer WARP.
     */
    val team: String = "",
    /** Which Zero Trust enrolment method to use. */
    val teamAuth: TeamAuth = TeamAuth.OFF,
    /** Access service-token client id (used when [teamAuth] is SERVICE_TOKEN). */
    val accessClientId: String = "",
    /**
     * Access service-token client secret. SECURITY: never emitted as a CLI
     * argument (argv is world-readable via /proc on rooted devices) — it is
     * handed to the engine through its environment instead.
     */
    val accessClientSecret: String = "",
    /** Work e-mail for the one-time-code flow (used when [teamAuth] is EMAIL). */
    val accessEmail: String = "",
    /** Pre-obtained enrolment JWT (used when [teamAuth] is TOKEN). Env-only. */
    val accessToken: String = "",
    /**
     * Route http/https through the organization's Gateway proxy so its
     * filtering and logging apply. Off by default: it adds a hop inside the
     * tunnel AND makes the organization able to log browsing.
     */
    val gateway: Boolean = false,

    /** Destinations that must never reach the network at all (engine `--route-block`). */
    val routeBlock: String = "",
    /** Destinations sent straight out, bypassing the tunnel (engine `--route-direct`). */
    val routeDirect: String = "",

    // ---- Added in 1.2.4 (feature parity) ----

    /** Kill switch: if the tunnel drops, keep a blocking blackhole TUN up so nothing leaks direct. */
    val killSwitch: Boolean = false,
    /** Strict kill switch: stay in lockdown even after a MANUAL disconnect until the user lifts it. */
    val strictKillSwitch: Boolean = false,
    /** Route IPv6 through the tunnel as well (prevents IPv6 leaks). On by default. */
    val ipv6LeakProtection: Boolean = true,
    /** Stop and report an error after [reconnectRetryLimit] failed engine restarts. */
    val smartReconnect: Boolean = true,
    /** Max automatic engine restarts when [smartReconnect] is on. */
    val reconnectRetryLimit: Int = 5,
    /** TLS ClientHello fragment chunk-size range, e.g. "16-32" (engine `--fragment-size`). */
    val fragmentSize: String = "",
    /** Inter-fragment delay range in ms, e.g. "2-10" (engine `--fragment-delay`). */
    val fragmentDelay: String = "",
    /** Skip the engine's end-to-end data check after connect (engine env). */
    val noDataCheck: Boolean = false,
    /** Restrict TLS curve groups, e.g. "X25519:P-256" (engine `--tls-groups`). */
    val tlsGroups: String = "",
    /** Endpoint validation window, seconds; 0 = engine default. */
    val validateSecs: Int = 0,
    /** Delay between engine-level reconnects, seconds; 0 = engine default. */
    val reconnectSecs: Int = 0,
    /** Do not fall back to alternate WireGuard profiles (engine `--no-profile-retry`). */
    val noProfileRetry: Boolean = false,
    /** Engine core log verbosity. */
    val coreLogLevel: CoreLogLevel = CoreLogLevel.WARN,
    /** Apps that get NO internet at all while the VPN is on (UID-filtering bridge). */
    val blockedApps: List<String> = emptyList(),

    // ---- Added in 1.2.6 (engine v1.7.0 feature parity) ----

    /**
     * Chain the engine through a proxy that is ALREADY running on this phone
     * (engine `--upstream` / `AETHER_UPSTREAM`, new in core 1.7.0). Accepts
     * `socks5://[user:pass@]host:port`, `http://[user:pass@]host:port` or a bare
     * `host:port` (read as SOCKS5). Blank = dial out directly.
     *
     * The endpoint scan, the registration calls and the ECH lookup all travel
     * through it too; a destination matched by [routeDirect] does not, because
     * that rule exists precisely to bypass the tunnel.
     */
    val upstreamProxy: String = "",
    /**
     * Decide the domain rules in [routeBlock] / [routeDirect] from the name in
     * the first bytes (TLS server name, or the HTTP `Host` header) instead of
     * only from the address (core 1.7.0, `AETHER_ROUTE_SNIFF`).
     *
     * This matters far more on Android than on a desktop: the app is ALWAYS a
     * tun front end, so by the time a flow reaches the engine it has already
     * lost its name and every domain rule would silently do nothing. On by
     * default, exactly like the engine.
     */
    val routeSniff: Boolean = true,
    /** How long to wait for those first bytes, ms. 0 = engine default (400). */
    val routeSniffMs: Int = 0,
    /**
     * Register a fresh device identity when Cloudflare stops accepting the saved
     * one (core 1.7.0, `AETHER_REPROVISION`). Off means the tunnel still
     * handshakes but carries no traffic, which is impossible to diagnose from
     * the UI, so this stays on by default.
     */
    val autoReprovision: Boolean = true,

) {
    /** True when a Zero Trust organization is configured and usable. */
    val hasTeam: Boolean
        get() = teamAuth != TeamAuth.OFF && team.isNotBlank()

    /** True when the user pinned one specific gateway by hand. */
    val hasManualPeer: Boolean
        get() = endpointMode == EndpointMode.MANUAL_PEER && manualPeer.isNotBlank()

    /** Command-line arguments passed to the `aether` engine binary. */
    fun toArgs(): List<String> {
        val args = mutableListOf<String>()

        when (protocol) {
            // AUTO no longer reaches the engine: Smart Auto (core/SmartAuto.kt)
            // fingerprints the network's DPI and resolves AUTO to a concrete,
            // tuned protocol BEFORE launch. Kept only for exhaustiveness.
            Protocol.AUTO -> { /* resolved by SmartAuto before launch */ }
            Protocol.MASQUE -> args += "--masque"
            Protocol.WIREGUARD -> args += "--wg"
            Protocol.GOOL -> args += "--gool"
        }

        // A pinned peer makes scan mode irrelevant, so only emit it otherwise.
        if (!hasManualPeer) {
            when (scanMode) {
                ScanMode.TURBO -> args += "--turbo"
                ScanMode.BALANCED -> args += "--balanced"
                ScanMode.THOROUGH -> args += "--thorough"
                ScanMode.STEALTH -> args += "--stealth"
                ScanMode.IRONCLAD -> args += "--ironclad"
            }
        }

        when (ipVersion) {
            IpVersion.V4 -> args += "-4"
            IpVersion.V6 -> args += "-6"
            IpVersion.BOTH -> args += "--dual"
        }

        args += if (quickReconnect) "--quick-reconnect" else "--no-quick-reconnect"

        // Anti-DPI obfuscation.
        if (noize != Noize.OFF) {
            args += "--noize"
            args += noize.name.lowercase()
        }

        // Manual endpoint pins one gateway and skips scanning entirely.
        if (hasManualPeer) {
            args += "--peer"
            args += manualPeer.trim()
        }

        if (fragment) args += "--fragment"
        if (ech) { args += "--ech"; args += "auto" }
        if (keepalive > 0) { args += "--keepalive"; args += keepalive.toString() }

        // ---- engine v1.5.0 ----

        // In-tunnel resolvers. Sanitised so a malformed entry can never inject
        // a second CLI token (the engine itself also re-validates each entry).
        sanitizedDns().takeIf { it.isNotEmpty() }?.let {
            args += "--dns"
            args += it.joinToString(",")
        }

        // Zero Trust: only the non-secret team name travels via argv. The id,
        // secret, token and e-mail go through the environment (see toEnv).
        if (hasTeam) {
            args += "--team"
            args += team.trim()
            if (gateway) args += "--gateway"
        }

        // Split routing rules. Block is evaluated before direct by the engine.
        sanitizedRules(routeBlock).takeIf { it.isNotEmpty() }?.let {
            args += "--route-block"
            args += it.joinToString(",")
        }
        sanitizedRules(routeDirect).takeIf { it.isNotEmpty() }?.let {
            args += "--route-direct"
            args += it.joinToString(",")
        }

        // ---- 1.2.4 engine tuning ----
        if (fragment) {
            sanitizedRange(fragmentSize)?.let { args += "--fragment-size"; args += it }
            sanitizedRange(fragmentDelay)?.let { args += "--fragment-delay"; args += it }
        }
        sanitizedTlsGroups()?.let { args += "--tls-groups"; args += it }
        if (validateSecs > 0) { args += "--validate-secs"; args += validateSecs.coerceIn(1, 3600).toString() }
        if (reconnectSecs > 0) { args += "--reconnect-secs"; args += reconnectSecs.coerceIn(1, 600).toString() }
        if (noProfileRetry) args += "--no-profile-retry"

        return args
    }

    /** Environment variables for the engine process. */
    fun toEnv(): Map<String, String> = buildMap {
        // An HTTP CONNECT upstream cannot carry UDP, so MASQUE has to ride
        // HTTP/2 over TCP whenever the user chained the engine behind an
        // http:// proxy. Forcing it here turns "connects but nothing loads"
        // into a working session the user never has to debug.
        val httpUpstream = sanitizedUpstream()?.startsWith("http://") == true
        put("AETHER_MASQUE_HTTP2", if (masqueHttp2 || httpUpstream) "1" else "0")

        // Which addresses the engine's scanner may consider.
        //
        // Only what the user pinned in Settings. With nothing pinned the
        // engine uses its own built-in WARP ranges and picks an endpoint
        // itself, which is the natural behaviour of the core.
        val userRange = manualRange.trim()
        if (endpointMode == EndpointMode.MANUAL_RANGE && userRange.isNotBlank()) {
            // prober.rs reads AETHER_MASQUE_CIDRS then AETHER_SCAN_CIDRS;
            // wg_prober.rs reads AETHER_WG_CIDRS then AETHER_SCAN_CIDRS.
            // Both scanners honour this from 1.2.6 on: until then only the
            // WireGuard side carried the app patch, so a pinned range was
            // silently ignored on MASQUE and gool.
            put("AETHER_SCAN_CIDRS", userRange)
            put("AETHER_MASQUE_CIDRS", userRange)
            put("AETHER_WG_CIDRS", userRange)
        }

        // ---- Zero Trust credentials (engine v1.5.0) ----
        //
        // SECURITY: these are passed as environment variables, NOT as CLI
        // arguments. On Android every local app can read /proc/<pid>/cmdline
        // of a process it can see, but the environment block is only readable
        // by the process owner. The engine reads exactly these names in
        // zerotrust.rs::TeamSettings::from_env().
        if (hasTeam) {
            when (teamAuth) {
                TeamAuth.SERVICE_TOKEN -> {
                    accessClientId.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_CLIENT_ID", it) }
                    accessClientSecret.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_CLIENT_SECRET", it) }
                }
                TeamAuth.EMAIL ->
                    accessEmail.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_EMAIL", it) }
                TeamAuth.TOKEN ->
                    accessToken.trim().takeIf { it.isNotEmpty() }
                        ?.let { put("AETHER_ACCESS_TOKEN", it) }
                TeamAuth.OFF -> Unit
            }
        }

        // ---- 1.2.4 engine tuning ----
        if (noDataCheck) {
            put("AETHER_MASQUE_NO_DATA_CHECK", "1")
            put("AETHER_WG_NO_DATA_CHECK", "1")
        }
        if (validateSecs > 0) {
            put("AETHER_MASQUE_VALIDATE_SECS", validateSecs.coerceIn(1, 3600).toString())
        }
        if (reconnectSecs > 0) {
            put("AETHER_MASQUE_RECONNECT_SECS", reconnectSecs.coerceIn(1, 600).toString())
            put("AETHER_WG_RECONNECT_SECS", reconnectSecs.coerceIn(1, 600).toString())
        }
        if (noProfileRetry) put("AETHER_WG_NO_PROFILE_RETRY", "1")
        sanitizedTlsGroups()?.let { put("AETHER_TLS_GROUPS", it) }
        if (coreLogLevel != CoreLogLevel.WARN) put("AETHER_LOG_LEVEL", coreLogLevel.raw)

        // ---- engine v1.7.0 (1.2.6) ----
        //
        // Both of these are ON in the engine by default, so the variable is
        // only sent when the user deviates from that: fewer moving parts, and
        // the engine keeps owning its own defaults.
        if (!routeSniff) put("AETHER_ROUTE_SNIFF", "0")
        if (routeSniff && routeSniffMs > 0) {
            put("AETHER_ROUTE_SNIFF_MS", routeSniffMs.coerceIn(50, 5_000).toString())
        }
        if (!autoReprovision) put("AETHER_REPROVISION", "0")

        // SECURITY: an upstream proxy URL can carry a username and password, so
        // it is handed over through the environment and NEVER as the `--upstream`
        // CLI argument: any local app can read /proc/<pid>/cmdline of a process
        // it can see, but not that process's environment block.
        sanitizedUpstream()?.let { put("AETHER_UPSTREAM", it) }
    }

    /**
     * Validated resolver list for `--dns`. Accepts `1.1.1.1` or `1.1.1.1:53`
     * and drops anything else, so a stray space or shell metacharacter in the
     * settings field can never become a separate engine argument.
     */
    fun sanitizedDns(): List<String> = dnsServers
        .split(',', ' ', ';', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && DNS_ENTRY.matches(it) }
        .distinct()
        .take(MAX_DNS_SERVERS)

    /**
     * Validated routing-rule list. Mirrors the grammar documented by the
     * engine (`example.com`, `full:`, `keyword:`, `regexp:`, CIDR, `port:`,
     * `private`) and rejects entries containing a comma, whitespace or a shell
     * metacharacter, which would otherwise split into extra arguments.
     */
    fun sanitizedRules(raw: String): List<String> = raw
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && RULE_ENTRY.matches(it) }
        .distinct()
        .take(MAX_ROUTE_RULES)

    /**
     * Validated upstream proxy URL for `AETHER_UPSTREAM`. Accepts an explicit
     * `socks5://` or `http://` scheme, optional `user:pass@` credentials, and a
     * host or bracketed IPv6 address with a port; a bare `host:port` is read as
     * SOCKS5 by the engine. Anything else is dropped rather than passed on, so a
     * typo can never turn into a second engine token or a silent direct dial.
     */
    fun sanitizedUpstream(): String? = upstreamProxy.trim()
        .takeIf { it.length in 1..200 && UPSTREAM_ENTRY.matches(it) }

    /**
     * How long to wait for the engine to open the local SOCKS5 port before
     * giving up. This MUST comfortably exceed the engine's own endpoint-scan
     * budget for the chosen mode; otherwise we abort while the engine is still
     * legitimately scanning. A pinned peer connects almost immediately.
     */
    fun connectTimeoutMs(): Long {
        if (hasManualPeer) return 45_000L
        return when (scanMode) {
            ScanMode.TURBO -> 60_000L
            ScanMode.BALANCED -> 150_000L
            ScanMode.STEALTH -> 240_000L
            ScanMode.THOROUGH -> 300_000L
            ScanMode.IRONCLAD -> 360_000L
        }
    }

    /** Accepts `500` or `16-32` style ranges; anything else is dropped. */
    private fun sanitizedRange(raw: String): String? =
        raw.trim().takeIf { it.matches(Regex("^\\d{1,5}(-\\d{1,5})?$")) }

    private fun sanitizedTlsGroups(): String? =
        tlsGroups.trim().takeIf { it.matches(Regex("^[A-Za-z0-9:_-]{1,64}$")) }

    companion object {
        /** Safe default TUN MTU for Iranian mobile networks / aggressive DPI. */
        const val DEFAULT_MTU = 1280
        /** Presets offered in the UI. */
        val MTU_PRESETS = listOf(1280, 1380, 1420, 1500, 8500)
        /** Keepalive presets offered in the UI (0 = engine default). */
        val KEEPALIVE_PRESETS = listOf(0, 10, 25, 45)

        /** Hard caps so a pasted blob can't build a gigantic argv. */
        const val MAX_DNS_SERVERS = 8
        const val MAX_ROUTE_RULES = 256

        /** `1.1.1.1` or `1.1.1.1:53` (IPv4, or bracketed IPv6 with a port). */
        private val DNS_ENTRY =
            Regex("^(?:\\d{1,3}(?:\\.\\d{1,3}){3}|\\[[0-9A-Fa-f:]+])(?::\\d{1,5})?$")

        /** `socks5://user:pass@host:port`, `http://host:port` or a bare `host:port`. */
        private val UPSTREAM_ENTRY = Regex(
            "^(?:(?:socks5|http)://)?" +
                "(?:[^\\s:@/]{1,64}(?::[^\\s:@/]{0,64})?@)?" +
                "(?:\\[[0-9A-Fa-f:]{2,45}]|[A-Za-z0-9._-]{1,253}):\\d{1,5}$"
        )

        /** One routing-rule token: no comma, no whitespace, no shell metacharacters. */
        private val RULE_ENTRY = Regex("^[A-Za-z0-9_.:/*\\-\\[\\]^\$+?()|{}\\\\]{1,200}$")
    }
}
