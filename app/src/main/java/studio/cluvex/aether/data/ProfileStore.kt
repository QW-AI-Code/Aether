package studio.cluvex.aether.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.GoolMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TorBridges
import studio.cluvex.aether.model.TransportBackend

private val Context.dataStore by preferencesDataStore(name = "aether_profile")

/** Persists the last-used [ConnectionProfile] with Jetpack DataStore. */
class ProfileStore(private val context: Context) {
    private object Keys {
        val backend = stringPreferencesKey("transportBackend")
        // Added in 1.3.0 (engine core 2.0.0: Tor)
        val torBridges = stringPreferencesKey("torBridges")
        val torBridgeLines = stringPreferencesKey("torBridgeLines")
        val torCountry = stringPreferencesKey("torCountry")
        val torDirectSecs = intPreferencesKey("torDirectSecs")
        val torCheck = stringPreferencesKey("torCheck")
        val exitRegion = stringPreferencesKey("exitRegion")
        val protocol = stringPreferencesKey("protocol")
        val scan = stringPreferencesKey("scan")
        val ip = stringPreferencesKey("ip")
        val quick = booleanPreferencesKey("quick")
        val verifyConnection = booleanPreferencesKey("verifyConnection")
        val h2 = booleanPreferencesKey("h2")
        val share = booleanPreferencesKey("share")
        // Added in 1.2.0
        val noize = stringPreferencesKey("noize")
        val endpoint = stringPreferencesKey("endpoint")
        val peer = stringPreferencesKey("peer")
        val range = stringPreferencesKey("range")
        val keepalive = intPreferencesKey("keepalive")
        val fragment = booleanPreferencesKey("fragment")
        val ech = booleanPreferencesKey("ech")
        val echConfig = stringPreferencesKey("echConfig")
        val mtu = intPreferencesKey("mtu")
        val proxy = booleanPreferencesKey("proxy")
        val split = stringPreferencesKey("split")
        val splitApps = stringPreferencesKey("splitApps")
        // Added in 1.2.3 (engine v1.5.0)
        val dns = stringPreferencesKey("dns")
        val gameMode = booleanPreferencesKey("gameMode")
        val gameDns = stringPreferencesKey("gameDns")
        val team = stringPreferencesKey("team")
        val teamAuth = stringPreferencesKey("teamAuth")
        val accessId = stringPreferencesKey("accessId")
        val accessEmail = stringPreferencesKey("accessEmail")
        val gateway = booleanPreferencesKey("gateway")
        val routeBlock = stringPreferencesKey("routeBlock")
        val routeDirect = stringPreferencesKey("routeDirect")
        // Added in 1.4.0 (smart routing)
        val bypassIran = booleanPreferencesKey("bypassIran")
        val blockAds = booleanPreferencesKey("blockAds")
        // Added in 1.2.4 (feature parity)
        val killSwitch = booleanPreferencesKey("killSwitch")
        val strictKillSwitch = booleanPreferencesKey("strictKillSwitch")
        val ipv6Leak = booleanPreferencesKey("ipv6Leak")
        val smartReconnect = booleanPreferencesKey("smartReconnect")
        val reconnectRetryLimit = intPreferencesKey("reconnectRetryLimit")
        val fragmentSize = stringPreferencesKey("fragmentSize")
        val fragmentDelay = stringPreferencesKey("fragmentDelay")
        val noDataCheck = booleanPreferencesKey("noDataCheck")
        val tlsGroups = stringPreferencesKey("tlsGroups")
        // Added in 1.5.0 (engine core 2.3.0)
        val tlsCiphers = stringPreferencesKey("tlsCiphers")
        val disableGrease = booleanPreferencesKey("disableGrease")
        val tlsVerify = booleanPreferencesKey("tlsVerify")
        val apiAddress = stringPreferencesKey("apiAddress")
        // Core 2.4.0 TLS / API options
        val masqueSni = stringPreferencesKey("masqueSni")
        val apiSni = stringPreferencesKey("apiSni")
        val apiHost = stringPreferencesKey("apiHost")
        val tls13Ciphers = stringPreferencesKey("tls13Ciphers")
        val tlsKeyShares = stringPreferencesKey("tlsKeyShares")
        val tlsSigalgs = stringPreferencesKey("tlsSigalgs")
        val tlsCertCompression = stringPreferencesKey("tlsCertCompression")
        val tlsAlps = stringPreferencesKey("tlsAlps")
        val tlsTrustAnchors = stringPreferencesKey("tlsTrustAnchors")
        val tlsServerPadding = stringPreferencesKey("tlsServerPadding")
        val enableGreaseSigalgs = booleanPreferencesKey("enableGreaseSigalgs")
        val enableEchGrease = booleanPreferencesKey("enableEchGrease")
        val disableOcspStapling = booleanPreferencesKey("disableOcspStapling")
        val disableSct = booleanPreferencesKey("disableSct")
        val disableSessionTicket = booleanPreferencesKey("disableSessionTicket")
        val disableExtensionPermutation = booleanPreferencesKey("disableExtensionPermutation")
        val goolMode = stringPreferencesKey("goolMode")
        val validateSecs = intPreferencesKey("validateSecs")
        val reconnectSecs = intPreferencesKey("reconnectSecs")
        val noProfileRetry = booleanPreferencesKey("noProfileRetry")
        val coreLogLevel = stringPreferencesKey("coreLogLevel")
        val blockedApps = stringPreferencesKey("blockedApps")
        // Added in 1.2.6 (engine v1.7.0)
        val upstreamProxy = stringPreferencesKey("upstreamProxy")
        val routeSniff = booleanPreferencesKey("routeSniff")
        val routeSniffMs = intPreferencesKey("routeSniffMs")
        val autoReprovision = booleanPreferencesKey("autoReprovision")
        val fastEndpointOnly = booleanPreferencesKey("fastEndpointOnly")
    }

    /**
     * Zero Trust secrets (service-token secret + enrolment JWT) are NOT kept in
     * the DataStore preferences file. That file is plain protobuf inside the app
     * sandbox, so a device backup or an adb dump on a rooted phone would expose
     * a long-lived organization credential. They live in [SecretStore] instead,
     * sealed with a hardware-backed AES-GCM key from the Android Keystore.
     */
    private val secrets = SecretStore(context)

    val profile: Flow<ConnectionProfile> = context.dataStore.data.map { prefs ->
        val d = ConnectionProfile()
        ConnectionProfile(
            // fromStoredName migrates the retired backend names (see
            // TransportBackend): the single-hop PSIPHON of 1.2.6 becomes the
            // chained mode, so a saved profile keeps the exit the user picked,
            // while the Tor names, removed with the backend, resolve to Aether
            // instead of silently re-routing through a different provider.
            backend = TransportBackend.fromStoredName(prefs[Keys.backend]) ?: TransportBackend.AETHER,
            exitRegion = prefs[Keys.exitRegion] ?: "",
            torBridges = prefs[Keys.torBridges]
                ?.let { runCatching { TorBridges.valueOf(it) }.getOrNull() } ?: TorBridges.AUTO,
            torBridgeLines = prefs[Keys.torBridgeLines] ?: "",
            torCountry = prefs[Keys.torCountry] ?: "",
            torDirectSecs = prefs[Keys.torDirectSecs] ?: 0,
            torCheck = prefs[Keys.torCheck] ?: "",
            protocol = prefs[Keys.protocol]
                ?.let { runCatching { Protocol.valueOf(it) }.getOrNull() } ?: Protocol.AUTO,
            scanMode = prefs[Keys.scan]
                ?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() } ?: ScanMode.BALANCED,
            ipVersion = prefs[Keys.ip]
                ?.let { runCatching { IpVersion.valueOf(it) }.getOrNull() } ?: IpVersion.V4,
            quickReconnect = prefs[Keys.quick] ?: true,
            verifyConnection = prefs[Keys.verifyConnection] ?: true,
            masqueHttp2 = prefs[Keys.h2] ?: false,
            lanShare = prefs[Keys.share] ?: false,
            noize = prefs[Keys.noize]
                ?.let { runCatching { Noize.valueOf(it) }.getOrNull() } ?: Noize.OFF,
            endpointMode = prefs[Keys.endpoint]
                ?.let { runCatching { EndpointMode.valueOf(it) }.getOrNull() } ?: EndpointMode.AUTO,
            manualPeer = prefs[Keys.peer] ?: "",
            manualRange = prefs[Keys.range] ?: "",
            keepalive = prefs[Keys.keepalive] ?: 0,
            fragment = prefs[Keys.fragment] ?: false,
            ech = prefs[Keys.ech] ?: false,
            echConfig = prefs[Keys.echConfig] ?: "",
            // r8: clamped on the way in. The field only ever commits 1280..9000,
            // but a value stored by an older build or edited outside the UI would
            // otherwise reach the tunnel, and anything under 1280 breaks the ::/0
            // route this app always installs (IPv6's minimum link MTU).
            mtu = (prefs[Keys.mtu] ?: d.mtu)
                .coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX),
            proxyMode = prefs[Keys.proxy] ?: false,
            splitMode = prefs[Keys.split]
                ?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() } ?: SplitMode.OFF,
            splitApps = prefs[Keys.splitApps]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            dnsServers = prefs[Keys.dns] ?: "",
            gameMode = prefs[Keys.gameMode] ?: false,
            gameDnsServers = prefs[Keys.gameDns] ?: "",
            team = prefs[Keys.team] ?: "",
            teamAuth = prefs[Keys.teamAuth]
                ?.let { runCatching { TeamAuth.valueOf(it) }.getOrNull() } ?: TeamAuth.OFF,
            accessClientId = prefs[Keys.accessId] ?: "",
            accessClientSecret = secrets.read(SecretStore.ACCESS_SECRET),
            accessEmail = prefs[Keys.accessEmail] ?: "",
            accessToken = secrets.read(SecretStore.ACCESS_TOKEN),
            gateway = prefs[Keys.gateway] ?: false,
            routeBlock = prefs[Keys.routeBlock] ?: "",
            routeDirect = prefs[Keys.routeDirect] ?: "",
            bypassIran = prefs[Keys.bypassIran] ?: false,
            blockAds = prefs[Keys.blockAds] ?: false,
            // AUDIT F-2: default ON. Only applies when the key was never written,
            // so an explicit user "off" (which writes false) survives the update.
            killSwitch = prefs[Keys.killSwitch] ?: true,
            strictKillSwitch = prefs[Keys.strictKillSwitch] ?: false,
            ipv6LeakProtection = prefs[Keys.ipv6Leak] ?: true,
            smartReconnect = prefs[Keys.smartReconnect] ?: true,
            reconnectRetryLimit = prefs[Keys.reconnectRetryLimit] ?: 5,
            fragmentSize = prefs[Keys.fragmentSize] ?: "",
            fragmentDelay = prefs[Keys.fragmentDelay] ?: "",
            noDataCheck = prefs[Keys.noDataCheck] ?: false,
            tlsGroups = prefs[Keys.tlsGroups] ?: "",
            // 1.5.0: absent keys (every profile saved before this build) fall back
            // to the engine defaults, i.e. exactly what those profiles launched with.
            tlsCiphers = prefs[Keys.tlsCiphers] ?: "",
            disableGrease = prefs[Keys.disableGrease] ?: false,
            tlsVerify = prefs[Keys.tlsVerify] ?: false,
            apiAddress = prefs[Keys.apiAddress] ?: "",
            masqueSni = prefs[Keys.masqueSni] ?: "",
            apiSni = prefs[Keys.apiSni] ?: "",
            apiHost = prefs[Keys.apiHost] ?: "",
            tls13Ciphers = prefs[Keys.tls13Ciphers] ?: "",
            tlsKeyShares = prefs[Keys.tlsKeyShares] ?: "",
            tlsSigalgs = prefs[Keys.tlsSigalgs] ?: "",
            tlsCertCompression = prefs[Keys.tlsCertCompression] ?: "",
            tlsAlps = prefs[Keys.tlsAlps] ?: "",
            tlsTrustAnchors = prefs[Keys.tlsTrustAnchors] ?: "",
            tlsServerPadding = prefs[Keys.tlsServerPadding] ?: "",
            enableGreaseSigalgs = prefs[Keys.enableGreaseSigalgs] ?: false,
            enableEchGrease = prefs[Keys.enableEchGrease] ?: false,
            disableOcspStapling = prefs[Keys.disableOcspStapling] ?: false,
            disableSct = prefs[Keys.disableSct] ?: false,
            disableSessionTicket = prefs[Keys.disableSessionTicket] ?: false,
            disableExtensionPermutation = prefs[Keys.disableExtensionPermutation] ?: false,
            goolMode = prefs[Keys.goolMode]
                ?.let { runCatching { GoolMode.valueOf(it) }.getOrNull() } ?: GoolMode.MODERN,
            validateSecs = prefs[Keys.validateSecs] ?: 0,
            reconnectSecs = prefs[Keys.reconnectSecs] ?: 0,
            noProfileRetry = prefs[Keys.noProfileRetry] ?: false,
            coreLogLevel = prefs[Keys.coreLogLevel]
                ?.let { runCatching { CoreLogLevel.valueOf(it) }.getOrNull() } ?: CoreLogLevel.WARN,
            blockedApps = prefs[Keys.blockedApps]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            upstreamProxy = prefs[Keys.upstreamProxy] ?: "",
            routeSniff = prefs[Keys.routeSniff] ?: true,
            routeSniffMs = prefs[Keys.routeSniffMs] ?: 0,
            autoReprovision = prefs[Keys.autoReprovision] ?: true,
            // Defaults ON: reusing a slow cached endpoint is what halved
            // throughput on chained sessions (see ConnectionProfile).
            // 1.3.1: was `?: true`. The default had to change in BOTH places -
            // this fallback is what a fresh install reads, and leaving it true
            // here would have kept the old behaviour for every new user while the
            // model said otherwise. A user's explicit choice is written to disk
            // and still wins; the new default only applies where the key was never
            // written. See ConnectionProfile.fastEndpointOnly for why.
            fastEndpointOnly = prefs[Keys.fastEndpointOnly] ?: false,
        )
    }.flowOn(Dispatchers.IO)

    suspend fun save(profile: ConnectionProfile) {
        context.dataStore.edit { prefs ->
            prefs[Keys.backend] = profile.backend.name
            prefs[Keys.exitRegion] = profile.exitRegion.uppercase()
            prefs[Keys.torBridges] = profile.torBridges.name
            prefs[Keys.torBridgeLines] = profile.torBridgeLines
            prefs[Keys.torCountry] = profile.torCountry
            prefs[Keys.torDirectSecs] = profile.torDirectSecs
            prefs[Keys.torCheck] = profile.torCheck
            prefs[Keys.protocol] = profile.protocol.name
            prefs[Keys.scan] = profile.scanMode.name
            prefs[Keys.ip] = profile.ipVersion.name
            prefs[Keys.quick] = profile.quickReconnect
            prefs[Keys.verifyConnection] = profile.verifyConnection
            prefs[Keys.h2] = profile.masqueHttp2
            prefs[Keys.share] = profile.lanShare
            prefs[Keys.noize] = profile.noize.name
            prefs[Keys.endpoint] = profile.endpointMode.name
            prefs[Keys.peer] = profile.manualPeer
            prefs[Keys.range] = profile.manualRange
            prefs[Keys.keepalive] = profile.keepalive
            prefs[Keys.fragment] = profile.fragment
            prefs[Keys.ech] = profile.ech
            prefs[Keys.echConfig] = profile.echConfig
            prefs[Keys.mtu] = profile.mtu
            prefs[Keys.proxy] = profile.proxyMode
            prefs[Keys.split] = profile.splitMode.name
            prefs[Keys.splitApps] = profile.splitApps.joinToString(",")
            prefs[Keys.dns] = profile.dnsServers
            prefs[Keys.gameMode] = profile.gameMode
            prefs[Keys.gameDns] = profile.gameDnsServers
            prefs[Keys.team] = profile.team
            prefs[Keys.teamAuth] = profile.teamAuth.name
            prefs[Keys.accessId] = profile.accessClientId
            prefs[Keys.accessEmail] = profile.accessEmail
            prefs[Keys.gateway] = profile.gateway
            prefs[Keys.routeBlock] = profile.routeBlock
            prefs[Keys.routeDirect] = profile.routeDirect
            prefs[Keys.bypassIran] = profile.bypassIran
            prefs[Keys.blockAds] = profile.blockAds
            prefs[Keys.killSwitch] = profile.killSwitch
            prefs[Keys.strictKillSwitch] = profile.strictKillSwitch
            prefs[Keys.ipv6Leak] = profile.ipv6LeakProtection
            prefs[Keys.smartReconnect] = profile.smartReconnect
            prefs[Keys.reconnectRetryLimit] = profile.reconnectRetryLimit
            prefs[Keys.fragmentSize] = profile.fragmentSize
            prefs[Keys.fragmentDelay] = profile.fragmentDelay
            prefs[Keys.noDataCheck] = profile.noDataCheck
            prefs[Keys.tlsGroups] = profile.tlsGroups
            prefs[Keys.tlsCiphers] = profile.tlsCiphers
            prefs[Keys.disableGrease] = profile.disableGrease
            prefs[Keys.tlsVerify] = profile.tlsVerify
            prefs[Keys.apiAddress] = profile.apiAddress
            prefs[Keys.masqueSni] = profile.masqueSni
            prefs[Keys.apiSni] = profile.apiSni
            prefs[Keys.apiHost] = profile.apiHost
            prefs[Keys.tls13Ciphers] = profile.tls13Ciphers
            prefs[Keys.tlsKeyShares] = profile.tlsKeyShares
            prefs[Keys.tlsSigalgs] = profile.tlsSigalgs
            prefs[Keys.tlsCertCompression] = profile.tlsCertCompression
            prefs[Keys.tlsAlps] = profile.tlsAlps
            prefs[Keys.tlsTrustAnchors] = profile.tlsTrustAnchors
            prefs[Keys.tlsServerPadding] = profile.tlsServerPadding
            prefs[Keys.enableGreaseSigalgs] = profile.enableGreaseSigalgs
            prefs[Keys.enableEchGrease] = profile.enableEchGrease
            prefs[Keys.disableOcspStapling] = profile.disableOcspStapling
            prefs[Keys.disableSct] = profile.disableSct
            prefs[Keys.disableSessionTicket] = profile.disableSessionTicket
            prefs[Keys.disableExtensionPermutation] = profile.disableExtensionPermutation
            prefs[Keys.goolMode] = profile.goolMode.name
            prefs[Keys.validateSecs] = profile.validateSecs
            prefs[Keys.reconnectSecs] = profile.reconnectSecs
            prefs[Keys.noProfileRetry] = profile.noProfileRetry
            prefs[Keys.coreLogLevel] = profile.coreLogLevel.name
            prefs[Keys.blockedApps] = profile.blockedApps.joinToString(",")
            prefs[Keys.upstreamProxy] = profile.upstreamProxy
            prefs[Keys.routeSniff] = profile.routeSniff
            prefs[Keys.routeSniffMs] = profile.routeSniffMs
            prefs[Keys.autoReprovision] = profile.autoReprovision
            prefs[Keys.fastEndpointOnly] = profile.fastEndpointOnly
        }
        // Secrets go to the Keystore-sealed store, never to the prefs file.
        secrets.write(SecretStore.ACCESS_SECRET, profile.accessClientSecret)
        secrets.write(SecretStore.ACCESS_TOKEN, profile.accessToken)
    }

    /** Wipes the sealed Zero Trust secrets (used by "Reset settings"). */
    fun clearSecrets() = secrets.clear()
}
