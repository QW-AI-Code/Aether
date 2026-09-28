package studio.cluvex.aether.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
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
    }

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
