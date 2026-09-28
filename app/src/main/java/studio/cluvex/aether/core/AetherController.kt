package studio.cluvex.aether.core

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.vpn.AetherVpnService

/**
 * App-wide singleton that (a) publishes the live [ConnectionState] to the UI and
 * (b) sends connect/disconnect intents to [AetherVpnService].
 */
object AetherController {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Epoch millis of when the current session became Connected, or null. */
    private val _connectedSince = MutableStateFlow<Long?>(null)
    val connectedSince: StateFlow<Long?> = _connectedSince.asStateFlow()

    /** IP + country shown in the UI (exit server when connected, operator when not). */
    private val _ipInfo = MutableStateFlow<IpEndpoint?>(null)
    val ipInfo: StateFlow<IpEndpoint?> = _ipInfo.asStateFlow()

    /** True while an IP lookup is in flight (drives the “…” placeholder). */
    private val _ipLoading = MutableStateFlow(false)
    val ipLoading: StateFlow<Boolean> = _ipLoading.asStateFlow()

    /** Called by the service to broadcast state changes. */
    fun setState(newState: ConnectionState) {
        _state.value = newState
        when (newState) {
            is ConnectionState.Connected ->
                if (_connectedSince.value == null) _connectedSince.value = System.currentTimeMillis()
            is ConnectionState.Reconnecting -> {
                // Keep the running timer during a transient reconnect.
            }
            else -> _connectedSince.value = null
        }
    }

    fun setIpInfo(info: IpEndpoint?) {
        _ipInfo.value = info
    }

    /**
     * Sets the tunnel exit IP only when the badge doesn't already show a
     * tunnel IP for this session.
     */
    fun offerTunnelIpInfo(info: IpEndpoint) {
        if (_ipInfo.value?.viaTunnel == true) return
        _ipInfo.value = info
    }

    fun setIpLoading(loading: Boolean) {
        _ipLoading.value = loading
    }

    /**
     * Returns a consent Intent if the user must still grant VPN permission,
     * or null if permission was already granted.
     */
    fun prepare(context: Context): Intent? = VpnService.prepare(context)

    fun connect(context: Context, profile: ConnectionProfile) {
        val intent = Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_CONNECT
            putExtra(AetherVpnService.EXTRA_PROFILE, ProfileCodec.encode(profile))
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun disconnect(context: Context) {
        val intent = Intent(context, AetherVpnService::class.java).apply {
            action = AetherVpnService.ACTION_DISCONNECT
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

/**
 * Serialises a [ConnectionProfile] into a compact `key=value` list (one pair per
 * line) for Intent transport. This format is forward/backward tolerant: unknown
 * keys are ignored and missing keys fall back to the model defaults, so old and
 * new builds can decode each other's payloads without crashing.
 */
object ProfileCodec {
    fun encode(p: ConnectionProfile): String = buildList {
        add("backend=${p.backend.name}")
        add("exitRegion=${p.exitRegion}")
        add("protocol=${p.protocol.name}")
        add("scan=${p.scanMode.name}")
        add("ip=${p.ipVersion.name}")
        add("quick=${p.quickReconnect}")
        add("h2=${p.masqueHttp2}")
        add("share=${p.lanShare}")
        add("noize=${p.noize.name}")
        add("endpoint=${p.endpointMode.name}")
        add("peer=${p.manualPeer}")
        add("range=${p.manualRange}")
        add("keepalive=${p.keepalive}")
        add("fragment=${p.fragment}")
        add("ech=${p.ech}")
        add("echConfig=${p.echConfig}")
        add("mtu=${p.mtu}")
        add("proxy=${p.proxyMode}")
        add("split=${p.splitMode.name}")
        add("splitApps=${p.splitApps.joinToString(",")}")
        // Added in 1.2.4 (feature parity)
        add("kill=${p.killSwitch}")
        add("strictKill=${p.strictKillSwitch}")
        add("v6leak=${p.ipv6LeakProtection}")
        add("smartRe=${p.smartReconnect}")
        add("reLimit=${p.reconnectRetryLimit}")
        add("fSize=${p.fragmentSize}")
        add("fDelay=${p.fragmentDelay}")
        add("noDataCheck=${p.noDataCheck}")
        add("tlsGroups=${p.tlsGroups}")
        add("valSecs=${p.validateSecs}")
        add("recSecs=${p.reconnectSecs}")
        add("noProfRetry=${p.noProfileRetry}")
        add("coreLog=${p.coreLogLevel.name}")
        add("blockedApps=${p.blockedApps.joinToString(",")}")
        // ---------------------------------------------------------------
        // 1.2.7 BUG FIX: everything below was ALREADY in the settings screen,
        // already persisted, already turned into engine flags by
        // ConnectionProfile.toArgs()/toEnv() - and never reached the engine,
        // because the service reconstructs its profile from THIS codec and the
        // codec did not carry any of it. So the in-tunnel DNS servers, both
        // routing-rule lists, domain sniffing, the upstream proxy, identity
        // replacement and the whole Zero Trust section were silently ignored at
        // runtime: the user set them, the UI kept them, and the engine was
        // launched without a single one of the corresponding flags.
        //
        // The two Zero Trust SECRETS are deliberately still absent. They live in
        // the hardware-backed SecretStore and the service reads them from there
        // itself (see AetherVpnService.hydrateSecrets), so a credential never
        // travels inside an Intent.
        // ---------------------------------------------------------------
        add("dns=${p.dnsServers}")
        add("routeBlock=${p.routeBlock}")
        add("routeDirect=${p.routeDirect}")
        add("bypassIran=${p.bypassIran}")
        add("blockAds=${p.blockAds}")
        add("sniff=${p.routeSniff}")
        add("sniffMs=${p.routeSniffMs}")
        add("upstream=${p.upstreamProxy}")
        add("reprovision=${p.autoReprovision}")
        add("fastEndpoint=${p.fastEndpointOnly}")
        add("teamAuth=${p.teamAuth.name}")
        add("team=${p.team}")
        add("accessId=${p.accessClientId}")
        add("accessEmail=${p.accessEmail}")
        add("gateway=${p.gateway}")
    }.joinToString("\n")

    fun decode(raw: String?): ConnectionProfile {
        if (raw.isNullOrBlank()) return ConnectionProfile()
        // Backward compatibility: the 1.0/1.1 codec used a single pipe-delimited
        // line with no keys. Detect and decode that legacy shape.
        if (!raw.contains('=') && raw.contains('|')) return decodeLegacy(raw)

        val map = raw.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()
        val d = ConnectionProfile()
        return runCatching {
            ConnectionProfile(
                // fromStoredName, NOT enumOr: it migrates the retired backend
                // names. The single-hop PSIPHON becomes the chained mode so an
                // existing profile keeps the exit the user chose; the Tor names
                // removed with the backend resolve to plain Aether.
                backend = TransportBackend.fromStoredName(map["backend"]) ?: d.backend,
                exitRegion = map["exitRegion"] ?: d.exitRegion,
                protocol = map["protocol"]?.let { enumOr<Protocol>(it) } ?: d.protocol,
                scanMode = map["scan"]?.let { enumOr<ScanMode>(it) } ?: d.scanMode,
                ipVersion = map["ip"]?.let { enumOr<IpVersion>(it) } ?: d.ipVersion,
                quickReconnect = map["quick"]?.toBooleanStrictOrNull() ?: d.quickReconnect,
                masqueHttp2 = map["h2"]?.toBooleanStrictOrNull() ?: d.masqueHttp2,
                lanShare = map["share"]?.toBooleanStrictOrNull() ?: d.lanShare,
                noize = map["noize"]?.let { enumOr<Noize>(it) } ?: d.noize,
                endpointMode = map["endpoint"]?.let { enumOr<EndpointMode>(it) } ?: d.endpointMode,
                manualPeer = map["peer"] ?: d.manualPeer,
                manualRange = map["range"] ?: d.manualRange,
                keepalive = map["keepalive"]?.toIntOrNull() ?: d.keepalive,
                fragment = map["fragment"]?.toBooleanStrictOrNull() ?: d.fragment,
                ech = map["ech"]?.toBooleanStrictOrNull() ?: d.ech,
                echConfig = map["echConfig"] ?: d.echConfig,
                mtu = map["mtu"]?.toIntOrNull()
                    ?.coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX)
                    ?: d.mtu,
                proxyMode = map["proxy"]?.toBooleanStrictOrNull() ?: d.proxyMode,
                splitMode = map["split"]?.let { enumOr<SplitMode>(it) } ?: d.splitMode,
                splitApps = map["splitApps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: d.splitApps,
                killSwitch = map["kill"]?.toBooleanStrictOrNull() ?: d.killSwitch,
                strictKillSwitch = map["strictKill"]?.toBooleanStrictOrNull() ?: d.strictKillSwitch,
                ipv6LeakProtection = map["v6leak"]?.toBooleanStrictOrNull() ?: d.ipv6LeakProtection,
                smartReconnect = map["smartRe"]?.toBooleanStrictOrNull() ?: d.smartReconnect,
                reconnectRetryLimit = map["reLimit"]?.toIntOrNull() ?: d.reconnectRetryLimit,
                fragmentSize = map["fSize"] ?: d.fragmentSize,
                fragmentDelay = map["fDelay"] ?: d.fragmentDelay,
                noDataCheck = map["noDataCheck"]?.toBooleanStrictOrNull() ?: d.noDataCheck,
                tlsGroups = map["tlsGroups"] ?: d.tlsGroups,
                validateSecs = map["valSecs"]?.toIntOrNull() ?: d.validateSecs,
                reconnectSecs = map["recSecs"]?.toIntOrNull() ?: d.reconnectSecs,
                noProfileRetry = map["noProfRetry"]?.toBooleanStrictOrNull() ?: d.noProfileRetry,
                coreLogLevel = map["coreLog"]?.let { enumOr<CoreLogLevel>(it) } ?: d.coreLogLevel,
                blockedApps = map["blockedApps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    ?: d.blockedApps,
                // See the note in encode(): these used to be dropped here.
                dnsServers = map["dns"] ?: d.dnsServers,
                routeBlock = map["routeBlock"] ?: d.routeBlock,
                routeDirect = map["routeDirect"] ?: d.routeDirect,
                bypassIran = map["bypassIran"]?.toBooleanStrictOrNull() ?: d.bypassIran,
                blockAds = map["blockAds"]?.toBooleanStrictOrNull() ?: d.blockAds,
                routeSniff = map["sniff"]?.toBooleanStrictOrNull() ?: d.routeSniff,
                routeSniffMs = map["sniffMs"]?.toIntOrNull() ?: d.routeSniffMs,
                upstreamProxy = map["upstream"] ?: d.upstreamProxy,
                autoReprovision = map["reprovision"]?.toBooleanStrictOrNull() ?: d.autoReprovision,
                fastEndpointOnly = map["fastEndpoint"]?.toBooleanStrictOrNull() ?: d.fastEndpointOnly,
                teamAuth = map["teamAuth"]?.let { enumOr<TeamAuth>(it) } ?: d.teamAuth,
                team = map["team"] ?: d.team,
                accessClientId = map["accessId"] ?: d.accessClientId,
                accessEmail = map["accessEmail"] ?: d.accessEmail,
                gateway = map["gateway"]?.toBooleanStrictOrNull() ?: d.gateway,
            )
        }.getOrDefault(d)
    }

    private fun decodeLegacy(raw: String): ConnectionProfile {
        val parts = raw.split("|")
        if (parts.size < 5) return ConnectionProfile()
        return runCatching {
            ConnectionProfile(
                protocol = Protocol.valueOf(parts[0]),
                scanMode = ScanMode.valueOf(parts[1]),
                ipVersion = IpVersion.valueOf(parts[2]),
                quickReconnect = parts[3].toBoolean(),
                masqueHttp2 = parts[4].toBoolean(),
                lanShare = parts.getOrNull(5)?.toBoolean() ?: false,
            )
        }.getOrDefault(ConnectionProfile())
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String): T? =
        runCatching { enumValueOf<T>(name) }.getOrNull()
}
