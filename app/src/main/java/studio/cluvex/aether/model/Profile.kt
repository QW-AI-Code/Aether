package studio.cluvex.aether.model

import androidx.compose.runtime.Immutable
import studio.cluvex.aether.core.PortLease
import studio.cluvex.aether.core.TunnelConfig

/**
 * Transport protocol, mapped 1:1 to the desktop app's CLI flags.
 *
 * MIM (`--mim`, MASQUE-in-MASQUE) is new in core 2.0.0: two MASQUE hops, for an
 * exit address in a different range than a single hop gives. It is to MASQUE what
 * gool is to WireGuard, and it is appended rather than inserted because
 * [studio.cluvex.aether.data.ProfileStore] persists the NAME, not the ordinal.

 */
enum class Protocol { AUTO, MASQUE, WIREGUARD, GOOL, MIM }

/**
 * Whether Tor should reach the network through bridges (core 2.0.0).
 *
 * [AUTO] is the engine's own behaviour: try Tor plainly for a moment and turn to
 * bridges fetched from bridgedb when that gets nowhere. [ALWAYS] skips the plain
 * attempt (`--tor-bridges`), which is the right choice on a network already known
 * to block Tor. [OFF] never uses bridges (`--no-tor-bridges`).
 *
 * This has no effect in the `Aether -> Tor` mode and the app says so: Tor's
 * guards are dialled through the tunnel there, so the local network never sees
 * Tor and there is nothing for a bridge to hide from.
 */
enum class TorBridges { AUTO, ALWAYS, OFF }

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
 *
 * UI-SPEED: annotated [Immutable], and it genuinely is - every field is a `val`
 * and the two `List<String>` members are only ever replaced through `copy()`,
 * never mutated in place. Without the annotation the Compose compiler infers
 * this class as UNSTABLE (a `List` interface could be a mutable implementation),
 * so it cannot skip ANY composable that takes a profile: one keystroke in one
 * text field recomposed every control on the settings screen, which is a large
 * part of why the settings menu felt slow to open and sluggish to use. With the
 * annotation, equality is trusted and only the rows whose values actually changed
 * recompose.
 */
@Immutable
data class ConnectionProfile(
    /** Selects plain Aether or the chained Aether -> Psiphon network backend. */
    val backend: TransportBackend = TransportBackend.AETHER,
    /** ISO-3166 alpha-2 preferred exit; blank means automatic. */
    val exitRegion: String = "",
    val protocol: Protocol = Protocol.AUTO,
    val scanMode: ScanMode = ScanMode.BALANCED,
    val ipVersion: IpVersion = IpVersion.V4,
    val quickReconnect: Boolean = true,
    val masqueHttp2: Boolean = false,
    /**
     * Share the tunnel with other devices on the same Wi-Fi / hotspot via the
     * in-app proxy bridge (see [studio.cluvex.aether.core.ShareBridge]).
     * 1.4.0-r5: it now also affects the engine's CLI args - while it is on,
     * direct (bypass) rules are not passed, so nothing a shared device sends
     * can leave outside the tunnel (see [shareSuspendsDirectRules]).
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
    /**
     * A base64 ECHConfigList to use instead of letting the engine fetch one
     * (issue #44). Blank means `--ech auto`, which is the previous behaviour.
     *
     * ## What was asked for, and what this is
     *
     * The request was v2rayNG's shape: name a DIFFERENT domain, say
     * `gitlab.io+https://8.8.8.8/dns-query`, so the DPI sees a handshake for a
     * host it does not filter. The engine cannot do that. `--ech` takes
     * `auto | <base64>` and nothing else: `auto` fetches the ECHConfigList for the
     * hosts in `dns::ECH_HOSTS` (`cloudflare-ech.com`, `crypto.cloudflare.com`),
     * and the alternative is a config the caller already holds. Naming an
     * arbitrary domain would mean the engine resolving an HTTPS RR for it, which
     * is engine work, not app work.
     *
     * So this exposes the half that exists: paste a config obtained elsewhere.
     * On a network where fetching the default one is what fails - and the field
     * logs show exactly that, `fetched ECHConfigList ... via 1.1.x.x:53` being the
     * step before a stall - that is the difference between ECH working and not.
     *
     * Validated as base64 before it is sent, because the engine's own answer to a
     * bad value is `[-] bad AETHER_ECH: ...; continuing without ECH`, one line in
     * a log nobody reads, and the user is left believing ECH is on.
     */
    val echConfig: String = "",

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
     * 1.4.0-r9: the enrolment JWT from the pre-connect e-mail sign-in in Settings
     * (used when [teamAuth] is EMAIL). Env-only, like [accessToken].
     *
     * NEVER persisted with the profile and NEVER carried by [studio.cluvex.aether.core.ProfileCodec]: it is
     * not something the user typed, it lives sealed in
     * [studio.cluvex.aether.data.TeamSignInStore], and only the VPN service puts
     * it here - in `hydrateSecrets`, right before the engine is started, and only
     * when the stored sign-in belongs to exactly this team and address, has not
     * expired, and the device is not already enrolled. Blank everywhere else, in
     * which case the e-mail method behaves exactly as before: the engine mails a
     * code during the connect and [studio.cluvex.aether.core.LoginCodePrompt]
     * asks for it.
     */
    val accessSignInToken: String = "",
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

    // ---- 1.4.0 smart routing (core/SmartLists.kt) ----

    /**
     * Send Iranian sites straight out instead of through the tunnel: every `.ir`
     * name, well-known Iranian services on other domains, and every Iranian IPv4
     * block. Off by default. A DIRECT rule, so it is suspended while LAN sharing
     * is on, exactly like [routeDirect].
     */
    val bypassIran: Boolean = false,
    /**
     * Block ads and trackers: a DNS sinkhole plus a block rule for the HaGeZi
     * "Light" list (refreshed weekly through the tunnel). Off by default.
     */
    val blockAds: Boolean = false,

    // ---- Added in 1.2.4 (feature parity) ----

    /**
     * Kill switch: if the tunnel drops, keep a blocking blackhole TUN up so nothing leaks direct.
     *
     * AUDIT F-2: on by default since 1.3.0. On a circumvention tool the answer to
     * "the tunnel just died" must be "no traffic", not "traffic in the clear" -
     * the leak happens in the seconds before the user notices the icon changed.
     * A user who explicitly turned it off keeps it off: [ProfileStore] only falls
     * back to this default when the key was never written.
     */
    val killSwitch: Boolean = true,
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

    // ---- Added in 1.2.7 ----

    /**
     * Only reuse the cached ("quick reconnect") endpoint while it is still FAST,
     * instead of merely still alive. **Off by default since 1.3.1.**
     *
     * ## What it is for
     *
     * The engine skips its scan for any cached endpoint that still answers. A
     * 1.2.7 field log showed it reusing one at `rtt 472ms` while 100-143ms edges
     * had just been measured on the same network, which roughly halves throughput
     * - and in the chained mode that cost is paid on both hops. With this ON, an
     * over-budget cached endpoint is ignored and a normal scan looks for a faster
     * one. See `AETHER_QUICK_RECONNECT_MAX_RTT_MS` in [toEnv].
     *
     * ## Why the default is now OFF (1.3.1)
     *
     * It was on, and on the networks this app exists for it threw the cache away
     * every single time. The budgets were calibrated against "100-150ms edges on
     * Iranian mobile"; the field logs that came in for 1.3.0 do not contain a
     * single measurement in that range:
     *
     *  * a cached WireGuard endpoint rejected at `rtt 1.272s` against the 180ms
     *    chained budget;
     *  * a cached MASQUE gateway verified as WORKING, then rejected at
     *    `handshake 2.778s` against the 1.4s budget - after which the rescan found
     *    no gateway at all in 300 seconds and the connect FAILED. The session
     *    ended with nothing, having held a working gateway seconds earlier;
     *  * a full `thorough` scan whose every accepted candidate sat between 727ms
     *    and 1.8s.
     *
     * So the feature was not choosing the faster of two endpoints. It was
     * discarding the only one there was, and paying a full scan on every single
     * reconnect - which is the "it takes forever to find a working IP, and the
     * moment it drops that is thrown away" report.
     *
     * Unset, the engine is back to its own behaviour: reuse a cached endpoint that
     * answers. Throughput on a good network is the thing being traded away, and
     * that is the right way round - a slow tunnel is usable and a missing one is
     * not. Anyone on a fast link can still switch it on, and the budgets it sends
     * are no longer calibrated for a network nobody reported having.
     */
    val fastEndpointOnly: Boolean = false,

    // ---- Added in 1.3.0 (engine core 2.0.0: Tor) ----

    /**
     * Whether Tor uses bridges. Only consulted when the backend actually needs
     * Tor to reach the network on its own, i.e. in the two `--tor-only` modes.
     */
    val torBridges: TorBridges = TorBridges.AUTO,

    /**
     * Bridge lines the user pasted in by hand, one per line, instead of the ones
     * the engine fetches from bridgedb.
     *
     * A line looks like
     * `obfs4 192.0.2.55:38114 <FINGERPRINT> cert=... iat-mode=0`. Anything that
     * does not start with a known transport name is dropped by [sanitizedBridges]
     * rather than passed on, because a malformed line would otherwise become a
     * second engine argument.
     */
    val torBridgeLines: String = "",

    /**
     * Two-letter country code handed to bridgedb, or blank to let the engine work
     * it out.
     *
     * Worth overriding, because the engine's own detection (`detect_country` in
     * `bridges.rs`) asks Cloudflare's trace endpoint where it is - and on the
     * networks where bridges matter most that request is exactly what fails or
     * answers with the wrong location. Naming the country skips the guess and asks
     * bridgedb for bridges that work there.
     *
     * The engine requires exactly two characters and lowercases them; anything else
     * is ignored by [sanitizedTorCountry] rather than sent, since a rejected value
     * would silently fall back to detection and look like the setting did nothing.
     */
    val torCountry: String = "",

    /**
     * How long Tor may try to reach the network plainly before bridges are brought
     * in, in seconds. 0 keeps the engine's own 75.
     *
     * Raising it helps where the direct path is slow but not blocked; lowering it
     * helps where it is definitely blocked and those seconds are pure waiting.
     */
    val torDirectSecs: Int = 0,

    /**
     * `host:port` Tor must be able to reach before the engine accepts the bootstrap
     * as working. Blank keeps the engine's `check.torproject.org:443`.
     *
     * The default is itself a censorship target: a network that blocks
     * check.torproject.org makes a perfectly working Tor circuit fail this proof,
     * and the app reports a bootstrap failure for a Tor that was fine. Pointing it
     * at something ordinary that is up locally removes that false negative.
     */
    val torCheck: String = "",

    /**
     * True only for the Aether hop of a chained session, set by the VPN service.
     *
     * Never persisted and never shown: it exists so [toEnv] can tighten the
     * cached-endpoint budget for a hop whose latency the user pays TWICE.
     */
    val chainedStage: Boolean = false,

    /**
     * Local SOCKS5 port this engine must bind, overriding [PortLease.socks].
     *
     * Zero means "the session's port", which is every ordinary connect. It is set
     * only for the throwaway engines of a Smart Plus race
     * ([studio.cluvex.aether.core.SmartPlusPlan]), where two engines run at the
     * same time and each needs a listener of its own.
     *
     * Never persisted: a port borrowed for one race must not come back on the next
     * connect. [studio.cluvex.aether.data.ProfileStore] does not write it and
     * [studio.cluvex.aether.core.AetherController]'s wire format does not carry it.
     */
    /**
     * 1.4.0: run the ENGINE's Psiphon (core 2.1.0, `--psiphon`) chained behind this
     * engine's exit. Transient, set only on a chained stage-1 copy; never persisted.
     */
    val enginePsiphon: Boolean = false,
    val bindOverride: Int = 0,

) {
    /** The local SOCKS5 port this profile's engine will listen on. */
    val bindPort: Int
        get() = if (bindOverride > 0) bindOverride else PortLease.socks

    /** True when Tor is asked to reach the network through bridges of any kind. */
    val hasCustomBridges: Boolean
        get() = torBridgeLines.isNotBlank() && sanitizedBridges().isNotEmpty()

    /** True when a Zero Trust organization is configured and usable. */
    val hasTeam: Boolean
        get() = teamAuth != TeamAuth.OFF && team.isNotBlank()

    /** True when the user pinned one specific gateway by hand. */
    val hasManualPeer: Boolean
        get() = endpointMode == EndpointMode.MANUAL_PEER && manualPeer.isNotBlank()

    /**
     * The protocol the engine is actually asked for.
     *
     * Differs from [protocol] in exactly one case: the reverse chain. Tor carries
     * TCP only and WARP's WireGuard endpoints answer on UDP alone, so core 2.0.0
     * runs `--tor-reverse` over MASQUE/HTTP-2 and **refuses `--wg` and `--gool`**.
     * Sending the user's WireGuard selection anyway would make the engine exit
     * immediately - which, from the app's side, is indistinguishable from a blocked
     * network, and would be diagnosed as one. So the override happens here, once,
     * where every argv is built, instead of in each caller; the UI says the same
     * thing next to the disabled protocol selector.
     */
    val effectiveProtocol: Protocol
        get() = if (backend.torMode == TorMode.REVERSE) Protocol.MASQUE else protocol

    /** Validated bridge lines for `--tor-bridge`, one entry per line. */
    /**
     * The country code in the form the engine accepts, or null.
     *
     * Two ASCII letters, lowercased. Everything else returns null and nothing is
     * sent: the engine would ignore it anyway, and a variable that is present but
     * ignored is harder to diagnose than one that was never set.
     */
    fun sanitizedTorCountry(): String? = torCountry.trim().lowercase()
        .takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }

    /**
     * The reachability target as `host:port`, or null when it is unusable.
     *
     * Kept strict on purpose. The engine parses this with `rsplit_once(':')` and
     * falls back to port 443 on a bad port, so a typo would silently become a
     * different target rather than an error - which is the one thing this setting
     * must not do, since its whole job is telling a working Tor from a broken one.
     */
    fun sanitizedTorCheck(): String? {
        val raw = torCheck.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        val (host, port) = raw.rsplitPortOrNull() ?: return null
        if (host.isEmpty() || host.length > 253) return null
        // A bare host is legal for the engine (it assumes 443), but only allow the
        // characters a host name or literal can contain.
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' }) return null
        return if (port == null) host else "$host:$port"
    }

    /** Splits a trailing `:port` off, or returns the whole string with no port. */
    private fun String.rsplitPortOrNull(): Pair<String, Int?>? {
        val cut = lastIndexOf(':')
        if (cut < 0) return this to null
        val tail = substring(cut + 1)
        // An IPv6 literal has colons of its own and no port here.
        val port = tail.toIntOrNull() ?: return (this to null).takeIf { count { c -> c == ':' } > 1 }
        if (port !in 1..65_535) return null
        return substring(0, cut) to port
    }

    fun sanitizedBridges(): List<String> = torBridgeLines
        .split('\n', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() && BRIDGE_LINE.matches(it) }
        .distinct()
        .take(MAX_BRIDGE_LINES)

    /**
     * Whether [text] is plausibly base64 - the only check worth making on a
     * pasted ECHConfigList (issue #44).
     *
     * Not a decode: the app has no idea what a valid ECHConfigList looks like and
     * has no business deciding. This catches the paste that is obviously not one -
     * a URL, a domain name, a sentence - so it can fall back to `auto` rather than
     * handing the engine something it will silently drop.
     */
    private fun looksLikeBase64(text: String): Boolean =
        text.length >= 8 && text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }

    /** The Psiphon exit country as the engine accepts it (two letters), or null for automatic. */
    fun sanitizedPsiphonRegion(): String? = exitRegion.trim().uppercase()
        .takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }

    /** Command-line arguments passed to the `aether` engine binary. */
    fun toArgs(): List<String> {
        val args = mutableListOf<String>()

        // ---- Local listener (issues #27, #52) ----------------------------
        //
        // Emitted ALWAYS, and first, even when it names the engine's own default.
        // The engine binds 127.0.0.1:1819 unless told otherwise, and that fixed
        // number is what made a second instance in an Android Private Space fail
        // with "Address already in use (os error 98)" before any tunnel was
        // attempted - the two profiles share the network namespace. [PortLease]
        // picks the preferred port when it is free and the next free one when it
        // is not, so this is 1819 on a normal install and something else only when
        // 1819 is genuinely taken. Passing it unconditionally also means the log
        // states the port rather than leaving it implied by a default.
        args += "--bind"
        args += "${TunnelConfig.SOCKS_HOST}:${bindPort}"

        // ---- Tor (engine core 2.0.0) -------------------------------------
        //
        // Emitted FIRST because in the `--tor-only` modes it decides that most of
        // what follows must not be emitted at all: there is no tunnel, so there is
        // no endpoint to scan, no transport to obfuscate and no WARP identity to
        // provision. Sending those flags anyway would ask the engine to do work
        // whose result nothing reads.
        when (backend.torMode) {
            null -> Unit
            TorMode.CHAIN -> {
                args += "--tor"
                args += "--tor-bind"
                args += "${TunnelConfig.SOCKS_HOST}:${PortLease.torSocks}"
            }
            TorMode.ONLY -> args += "--tor-only"
            TorMode.REVERSE -> {
                args += "--tor-reverse"
                args += "--tor-bind"
                args += "${TunnelConfig.SOCKS_HOST}:${PortLease.torSocks}"
            }
        }
        if (backend.usesTor) {
            // Bridges are only meaningful when Tor has to reach the network by
            // itself, which is both modes where Tor faces the local network:
            // `--tor-only` and `--tor-reverse`. In the chained mode Tor is dialled
            // through the tunnel, so there is nothing for a bridge to hide from.
            if (backend.torMode != TorMode.CHAIN) {
                when (torBridges) {
                    TorBridges.AUTO -> Unit
                    TorBridges.ALWAYS -> args += "--tor-bridges"
                    TorBridges.OFF -> args += "--no-tor-bridges"
                }
                sanitizedBridges().forEach {
                    args += "--tor-bridge"
                    args += it
                }
            }
        }
        // ---- Psiphon (engine core 2.1.0, app 1.4.0) ------------------------
        //
        // Psiphon lives in the engine now. `--psiphon` chains it behind this
        // engine's own listener (WARP, or Tor with `--tor-only` via the app's
        // tor-only-psiphon-chain engine patch). Emitted before the tor-only early
        // return below, because `Tor -> Psiphon` needs it too.
        if (enginePsiphon) {
            args += "--psiphon"
            args += "--psiphon-bind"
            args += "${TunnelConfig.SOCKS_HOST}:${PortLease.psiphon}"
            sanitizedPsiphonRegion()?.let {
                args += "--psiphon-region"
                args += it
            }
        }
        if (!backend.usesWarp) {
            // Plain Tor: the resolvers still apply (they are what the engine's own
            // SOCKS front hands out), nothing else here does.
            sanitizedDns().takeIf { it.isNotEmpty() }?.let {
                args += "--dns"
                args += it.joinToString(",")
            }
            return args
        }

        when (effectiveProtocol) {
            // AUTO never reaches the engine: Smart (core/SmartAuto.kt), and the
            // race in core/SmartPlusPlan.kt where the backend allows it, resolve it
            // to a concrete tuned protocol BEFORE launch. Kept for exhaustiveness.
            Protocol.AUTO -> { /* resolved before launch */ }
            Protocol.MASQUE -> args += "--masque"
            Protocol.WIREGUARD -> args += "--wg"
            Protocol.GOOL -> args += "--gool"
            Protocol.MIM -> args += "--mim"
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
        if (ech) {
            args += "--ech"
            // A pasted config wins over `auto`; see [echConfig]. Trimmed because a
            // clipboard paste routinely carries a newline, and the engine would
            // reject the value for it.
            val pasted = echConfig.trim()
            args += if (pasted.isNotEmpty() && looksLikeBase64(pasted)) pasted else "auto"
        }
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
        // 1.4.0-r5 ZERO-LEAK SHARING: while LAN sharing is on, the engine gets NO
        // direct (bypass) rules. The engine applies them to every SOCKS client,
        // the share bridge included, so a shared device asking for a "direct"
        // destination went out of the phone's own uplink with the user's real
        // IP. Suspending them for the session is the only fully provable fix;
        // the Share card tells the user when this is happening. See
        // core/ShareLeakGuard.kt, point 1.
        if (!lanShare) {
            sanitizedRules(routeDirect).takeIf { it.isNotEmpty() }?.let {
                args += "--route-direct"
                args += it.joinToString(",")
            }
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
        // The reverse chain has no choice here: Tor carries TCP only, so MASQUE
        // over HTTP/2 is the only carrier it can hold, and core 2.0.0 runs that
        // mode over h2 regardless. Forced so the engine's carrier and the app's
        // own reported state cannot disagree.
        put(
            "AETHER_MASQUE_HTTP2",
            if (masqueHttp2 || httpUpstream || backend.torMode == TorMode.REVERSE) "1" else "0",
        )

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
                TeamAuth.EMAIL -> {
                    // 1.4.0-r9: signed in beforehand -> hand over the token and
                    // NOT the address. `zerotrust::sign_in` would prefer the token
                    // anyway, but leaving the address out makes it impossible for
                    // this connect to fall into the in-connect code prompt: with a
                    // token the engine either enrols or fails with a named reason.
                    // Not signed in -> the address, and the prompt, as before.
                    val signedIn = accessSignInToken.trim()
                    if (signedIn.isNotEmpty()) {
                        put("AETHER_ACCESS_TOKEN", signedIn)
                    } else {
                        accessEmail.trim().takeIf { it.isNotEmpty() }
                            ?.let { put("AETHER_ACCESS_EMAIL", it) }
                    }
                }
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

        // ---- engine core 2.0.0 (1.3.0): Tor tuning ----
        //
        // Only sent when the backend actually runs Tor, and only when the user
        // deviated from the engine's default. Sending them always would mean the
        // app owns values the engine should keep owning.
        if (backend.usesTor) {
            sanitizedTorCheck()?.let { put("AETHER_TOR_CHECK", it) }
            if (backend.torMode != TorMode.CHAIN) {
                // Both only matter while Tor faces the network itself: bridgedb is
                // not consulted in the chained mode, and the direct probe there runs
                // inside the tunnel where it is not the thing that stalls.
                sanitizedTorCountry()?.let { put("AETHER_TOR_COUNTRY", it) }
                if (torDirectSecs > 0) {
                    put("AETHER_TOR_DIRECT_SECS", torDirectSecs.coerceIn(5, 600).toString())
                }
            }
            // The engine's Tor log understands info/debug/trace only, and already
            // derives itself from AETHER_LOG_LEVEL - but only for debug and trace.
            // At the app's DEBUG level the bootstrap detail is the point, so it is
            // asked for explicitly; every quieter level is left to the engine, which
            // then logs Tor at info.
            if (coreLogLevel == CoreLogLevel.DEBUG) put("AETHER_TOR_LOG", "debug")
        }

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

        // ---- 1.2.7: quick-reconnect endpoint budget ----
        //
        // Only sent when the user leaves the option on, so the engine keeps
        // owning its own default (reuse anything that answers) when it is off.
        // The chained hop gets a tighter budget because a chained session pays
        // stage 1's latency on every packet AND again inside Psiphon's own hop.
        if (fastEndpointOnly) {
            put(
                "AETHER_QUICK_RECONNECT_MAX_RTT_MS",
                if (chainedStage) CHAINED_RTT_BUDGET_MS else DIRECT_RTT_BUDGET_MS,
            )
            put(
                "AETHER_QUICK_RECONNECT_MAX_HANDSHAKE_MS",
                if (chainedStage) CHAINED_HANDSHAKE_BUDGET_MS else DIRECT_HANDSHAKE_BUDGET_MS,
            )
            // 1.2.8-r4: the SAME budget now also gates the SCAN's own result.
            // Until this existed the engine enforced the budget on the cached
            // endpoint only, so it would reject a 397 ms cache as too slow and
            // then commit the session to the first thing that answered the scan -
            // 475 ms, 79 ms WORSE than what it had just thrown away, while
            // 104-115 ms edges had been measured on those ranges in the same
            // second. See `good_rtt_budget` in the engine's `wg_prober.rs`.
            put(
                "AETHER_SCAN_GOOD_RTT_MS",
                if (chainedStage) CHAINED_RTT_BUDGET_MS else DIRECT_RTT_BUDGET_MS,
            )
        }

        // SECURITY: an upstream proxy URL can carry a username and password, so
        // it is handed over through the environment and NEVER as the `--upstream`
        // CLI argument: any local app can read /proc/<pid>/cmdline of a process
        // it can see, but not that process's environment block.
        // 1.4.0: in `Tor -> Psiphon` the engine's Psiphon starts dialling through
        // Tor before Tor has finished bootstrapping, so its own 180 s ready budget
        // must cover the bootstrap as well.
        if (enginePsiphon && backend.torMode == TorMode.ONLY) {
            put("AETHER_PSIPHON_READY_SECS", PSIPHON_OVER_TOR_READY_SECS)
        }

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
    /**
     * 1.4.0-r5: true when LAN sharing is on AND the user has direct rules, i.e.
     * those rules are suspended for this session to keep the real IP hidden.
     */
    val shareSuspendsDirectRules: Boolean
        get() = lanShare && (sanitizedRules(routeDirect).isNotEmpty() || bypassIran)

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

        /**
         * Bounds for the free MTU entry added in 1.3.1 (issue #33).
         *
         * 1280 is the floor because it is IPv6's minimum link MTU (RFC 8200 §5):
         * below it the TUN cannot carry v6 at all, and this app routes `::/0`
         * unconditionally in its chained and lockdown modes. 9000 is the ceiling
         * because it is the practical jumbo-frame limit and the existing 8500
         * preset has to remain reachable by hand.
         *
         * A value outside this range is not committed to the profile at all - see
         * the field in SettingsScreen. The engine would otherwise be handed a TUN
         * it cannot build, which surfaces as a connect that succeeds and carries
         * nothing.
         */
        const val MTU_MIN = 1280
        const val MTU_MAX = 9000
        /** Keepalive presets offered in the UI (0 = engine default). */
        val KEEPALIVE_PRESETS = listOf(0, 10, 25, 45)

        // Cached-endpoint budgets, milliseconds (see [fastEndpointOnly]).
        //
        // The WireGuard probe measures one data-plane round trip, so the budget
        // is an RTT. The MASQUE probe is a whole QUIC/TLS handshake and costs
        // several round trips, hence the separate, larger number.
        //
        // 1.3.1 RECALIBRATION. These were 320 / 1400 / 180 / 900, chosen "above
        // the good edges observed on Iranian mobile (100-150ms)". The 1.3.0 field
        // logs contain no such edges: accepted scan candidates run 727ms-1.8s,
        // a cached WireGuard endpoint measured 1.272s and a cached MASQUE gateway
        // 2.778s. Against the old numbers every cached endpoint on those networks
        // was rejected, so the option could only ever cost a full rescan - see the
        // long note on [fastEndpointOnly].
        //
        // The numbers now sit above what those logs actually show, so the option
        // does what it was meant to do - reject an endpoint that has become
        // genuinely bad - instead of rejecting every endpoint that exists. They
        // are still budgets, not timeouts: over them the engine scans, and with
        // the 1.3.1 engine patch it falls back to the cached peer if that scan
        // finds nothing.
        private const val DIRECT_RTT_BUDGET_MS = "2000"
        private const val DIRECT_HANDSHAKE_BUDGET_MS = "5000"
        private const val CHAINED_RTT_BUDGET_MS = "1200"
        private const val CHAINED_HANDSHAKE_BUDGET_MS = "3500"
        private const val PSIPHON_OVER_TOR_READY_SECS = "900"

        /** Hard caps so a pasted blob can't build a gigantic argv. */
        const val MAX_DNS_SERVERS = 8
        const val MAX_ROUTE_RULES = 256
        const val MAX_BRIDGE_LINES = 12

        /**
         * One Tor bridge line: a known transport name, an address, a fingerprint
         * and any number of `key=value` parameters.
         *
         * Deliberately strict about the FIRST token. A bridge line is passed to
         * the engine as one argv entry, so nothing here can inject a second one -
         * but a line that names a transport the app ships no binary for would fail
         * at connect time with a message about the transport rather than about the
         * line, which is the sort of error nobody can act on.
         */
        private val BRIDGE_LINE = Regex(
            "^(?:obfs4|meek_lite|webtunnel|snowflake|scramblesuit|obfs3)\\s+[^\\s]{3,120}" +
                "(?:\\s+[0-9A-Fa-f]{40})?(?:\\s+[A-Za-z0-9_.=/+:,\\-]{1,400})*$"
        )

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
