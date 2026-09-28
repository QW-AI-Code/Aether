package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * VPN sharing (hotspot / tethering / home Wi-Fi) - REWRITTEN in 1.4.0-r5.
 *
 * Lets other devices use this phone's Aether tunnel as a normal proxy, and
 * also serves on-device apps in proxy mode (loopback).
 *
 * ## Listeners
 *
 *  - **Loopback** `127.0.0.1:[SOCKS_SHARE_PORT]` (SOCKS5) and
 *    `127.0.0.1:[HTTP_SHARE_PORT]` (HTTP) whenever the bridge runs. Loopback
 *    clients keep the original byte-for-byte relay into the engine.
 *  - **LAN** - the same two ports bound to ONE interface address chosen by the
 *    sharing [ShareLeakGuard.Mode]:
 *      - [ShareLeakGuard.Mode.HOME_WIFI]: the phone's Wi-Fi (or Ethernet)
 *        address on the home router's network;
 *      - [ShareLeakGuard.Mode.MOBILE_HOTSPOT]: the phone's own hotspot / USB /
 *        Bluetooth tethering address, while its internet is mobile data.
 *    r4 bound `0.0.0.0`, i.e. EVERY interface - including the mobile-data
 *    interface, where many carriers hand out `10.x` addresses that the old
 *    "is it RFC1918?" admission check accepted as "local". Binding the one
 *    chosen interface, and admitting only peers in THAT interface's subnet,
 *    removes that exposure. A watcher re-binds when the hotspot is toggled or
 *    the Wi-Fi address changes, so the user never has to restart sharing.
 *
 * ## Zero IP leak (the priority of this rewrite)
 *
 * See [ShareLeakGuard] for the full list. In short, for every LAN client:
 *  1. the engine runs with NO direct (bypass) rules while sharing
 *     ([noteEngineRoutes]); if a running engine still has some, LAN clients
 *     are refused until reconnect ([LanStatus.NEEDS_RECONNECT]);
 *  2. every outbound socket this class opens goes to LOOPBACK (the engine or
 *     the chained front) - the bridge itself never dials the internet, so
 *     nothing can bypass the tunnel, and a dead tunnel means a refused
 *     connection, never a direct one (fail closed);
 *  3. SOCKS5 UDP ASSOCIATE is relayed on the share interface and through the
 *     tunnel, so clients do not fall back to sending UDP directly;
 *  4. destinations that are not public (loopback, private, CGNAT, link-local,
 *     numeric-obfuscated, `localhost`, `.local`) are refused;
 *  5. identity headers are stripped from plain HTTP; names are resolved INSIDE
 *     the tunnel (SOCKS domain address / HTTP proxy), never on the phone;
 *  6. the served PAC file has no `DIRECT` fallback for public hosts.
 *
 * ## Authentication (optional since 1.4.0-r5)
 *
 * Off by default, as requested: devices already had to join the user's Wi-Fi
 * or hotspot, and the share only answers peers on that exact subnet. When
 * turned on ([setAuthRequired]), a LAN client must present the username and
 * password - SOCKS5 RFC 1929 or HTTP `Proxy-Authorization: Basic`; a SOCKS
 * client that will not authenticate is refused, never silently downgraded.
 * Loopback never needs it (on-device apps, the proxy-mode self-test).
 *
 * The concurrency cap and the rate-limited refusal log from 1.2.9-r3 remain.
 *
 * ## 1.4.0-r6
 *
 *  - **Mobile hotspot never carried data.** r5 bound ONE interface, chosen from a
 *    list of vendor interface names. The field log shows `interface=swlan0` bound
 *    and then six minutes without a single connection reaching the bridge - not
 *    even a refused one: the other device was on a different downstream (5 GHz
 *    band bridge, USB, a vendor-named AP) than the one listening. Now EVERY
 *    tethering downstream is bound (Wi-Fi hotspot + USB + Bluetooth at once),
 *    downstreams are recognised by what Android says they are NOT (an upstream
 *    network) instead of by name, every address is shown in the Share card, and
 *    the log says which interface the first device came in on - or, after 90 s
 *    of silence, that none has.
 *  - **`http://aether.check` did not open** on SOCKS5 clients, TUN/VPN clients
 *    (which resolve the name first and got NXDOMAIN) and HTTPS-first browsers.
 *    It is now answered on every path: HTTP proxy, HTTP CONNECT, SOCKS5 CONNECT
 *    and the DNS query itself ([ShareLeakGuard.checkHostDnsReply]).
 *  - **WebRTC leak.** See [SharePages]: the check page runs a live WebRTC test
 *    against the tunnel exit and serves one-click browser locks; STUN handed to
 *    the share is carried through the tunnel and logged.
 */
object ShareBridge {

    /**
     * FIXED proxy ports (1.2.2: moved off v2rayNG's 10808/10809). Never change
     * at runtime: users type them once into another device.
     */
    const val SOCKS_SHARE_PORT = 10810
    const val HTTP_SHARE_PORT = 10811

    /** Ports owned by well-known neighbouring tunnels - diagnostics only. */
    private val KNOWN_NEIGHBOUR_PORTS = mapOf(
        10808 to "v2rayNG (SOCKS5)",
        10809 to "v2rayNG (HTTP)",
        7890 to "Clash (mixed)",
        1080 to "Psiphon / generic SOCKS",
        8118 to "Privoxy",
    )

    private const val TAG = "share"
    private const val LOOPBACK = "127.0.0.1"
    private const val MAX_HEADER_BYTES = 64 * 1024
    private const val DIAL_TIMEOUT_MS = 10_000

    /** How long a remote client gets to finish the handshake / authentication. */
    private const val AUTH_TIMEOUT_MS = 10_000

    /** Ceiling on simultaneously relayed connections (incl. UDP associations). */
    private const val MAX_LIVE_CONNECTIONS = 128

    /** At most one refusal line per window, so a probe loop cannot flood the log. */
    private const val REJECT_LOG_INTERVAL_MS = 30_000L

    /** How often the LAN watcher re-checks the share interface. */
    private const val LAN_WATCH_INTERVAL_MS = 3_000L

    /** r6: after this long READY with no device at all, log one hint. */
    private const val IDLE_HINT_AFTER_MS = 90_000L

    /** Largest UDP datagram relayed (SOCKS5 header included). */
    private const val MAX_UDP_DATAGRAM = 65_535

    /** Where the LAN side of sharing stands - drives the Share card. */
    enum class LanStatus {
        /** LAN sharing not requested. */
        OFF,
        /** Requested, but the chosen mode's network (Wi-Fi / hotspot) is not up. */
        WAITING_FOR_NETWORK,
        /** Listening on the LAN; [lanEndpoint] holds the address. */
        READY,
        /** The fixed ports are held by another app on that interface. */
        PORT_BUSY,
        /**
         * The running engine was started with direct (bypass) rules, so LAN
         * clients are refused to keep the real IP hidden. A reconnect fixes it.
         */
        NEEDS_RECONNECT,
    }

    /** The LAN address other devices use, and what kind of network it is. */
    data class LanEndpoint(
        val address: String,
        val interfaceName: String,
        val kind: ShareLeakGuard.IfaceKind,
    )

    /** A candidate share interface found on the device. */
    private data class LanTarget(
        val address: InetAddress,
        val prefixLength: Int,
        val interfaceName: String,
        val kind: ShareLeakGuard.IfaceKind,
    )

    /** r6: the SOCKS5 + HTTP listener pair bound on one share interface. */
    private class LanListener(val target: LanTarget, val socks: ServerSocket, val http: ServerSocket) {
        fun close() {
            runCatching { socks.close() }
            runCatching { http.close() }
        }
    }

    private val _active = MutableStateFlow(false)

    /** True while the loopback listeners (the bridge itself) are running. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    private val _httpPort = MutableStateFlow<Int?>(null)
    val httpPort: StateFlow<Int?> = _httpPort.asStateFlow()

    private val _lanStatus = MutableStateFlow(LanStatus.OFF)
    val lanStatus: StateFlow<LanStatus> = _lanStatus.asStateFlow()

    private val _lanEndpoint = MutableStateFlow<LanEndpoint?>(null)
    val lanEndpoint: StateFlow<LanEndpoint?> = _lanEndpoint.asStateFlow()

    /**
     * r6: EVERY address the share listens on (hotspot, USB, Bluetooth...), the
     * primary [lanEndpoint] first. A device must use the address of the link it
     * is actually on.
     */
    private val _lanEndpoints = MutableStateFlow<List<LanEndpoint>>(emptyList())
    val lanEndpoints: StateFlow<List<LanEndpoint>> = _lanEndpoints.asStateFlow()

    private val _mode = MutableStateFlow(ShareLeakGuard.Mode.HOME_WIFI)
    val mode: StateFlow<ShareLeakGuard.Mode> = _mode.asStateFlow()

    private val _authRequired = MutableStateFlow(false)
    val authRequired: StateFlow<Boolean> = _authRequired.asStateFlow()

    /** Live LAN clients (TCP connections + UDP associations) - for the card. */
    private val _lanClients = MutableStateFlow(0)
    val lanClients: StateFlow<Int> = _lanClients.asStateFlow()

    /**
     * Cumulative byte counters for THIS session (reset on every [startSync]).
     * In proxy mode these are the ONLY source for the traffic meter.
     * upload = client -> tunnel, download = tunnel -> client.
     */
    private val uploadBytesCounter = AtomicLong(0L)
    private val downloadBytesCounter = AtomicLong(0L)

    data class Traffic(val downloadBytes: Long, val uploadBytes: Long)

    fun traffic(): Traffic = Traffic(
        downloadBytes = downloadBytesCounter.get(),
        uploadBytes = uploadBytesCounter.get(),
    )

    private val _proxyUser = MutableStateFlow(DEFAULT_USER)
    val proxyUser: StateFlow<String> = _proxyUser.asStateFlow()

    private val _proxyPassword = MutableStateFlow("")
    val proxyPassword: StateFlow<String> = _proxyPassword.asStateFlow()

    private val liveConnections = AtomicInteger(0)
    private val liveLanClients = AtomicInteger(0)
    private val refusals = AtomicLong(0L)

    @Volatile
    private var lastRefusalLogMs = 0L

    private var loSocks: ServerSocket? = null
    private var loHttp: ServerSocket? = null
    /** r6: one listener pair PER share interface. Guarded by the object lock. */
    private val lanListeners = mutableListOf<LanListener>()

    /** Immutable snapshot of the bound share interfaces, for lock-free admission. */
    @Volatile
    private var lanTargets: List<LanTarget> = emptyList()

    /** r6: application context, for asking Android which interfaces are upstreams. */
    @Volatile
    private var appContext: Context? = null

    /** r6 diagnostics: when LAN became ready, and whether any device ever arrived. */
    @Volatile
    private var lanReadySinceMs = 0L

    @Volatile
    private var lanClientSeen = false

    @Volatile
    private var idleHintLogged = false

    @Volatile
    private var stunNoted = false

    /** The user (or the service) wants the LAN side up. */
    @Volatile
    private var lanRequested = false

    /** Whether the running engine was started with direct (bypass) rules. */
    @Volatile
    private var engineDirectRoutes = false

    /**
     * Monotonic session id; every start/stop bumps it so a stale async stop or
     * watcher can never touch a NEWER session's listeners.
     */
    private var session = 0
    private var watcherSession = -1

    /** Loopback SOCKS5 port every shared connection is relayed INTO. */
    @Volatile
    private var upstreamPort = PortLease.socks

    // ================================================================ control

    /**
     * Turn the bridge on (asynchronously; safe from the UI thread).
     *
     * SECURITY (audit 1.2.7): [localOnly] defaults to TRUE (loopback only); LAN
     * exposure has to be asked for.
     */
    fun start(localOnly: Boolean = true, upstreamPort: Int? = null) {
        thread(name = "share-start", isDaemon = true) { startSync(localOnly, upstreamPort) }
    }

    /**
     * Turn the bridge on and WAIT for the loopback listeners. Returns true when
     * both loopback listeners accept connections (the LAN side follows on its
     * own once its network is up - see [lanStatus]). Background thread only.
     *
     * @param upstreamPort loopback SOCKS5 port to relay into; null keeps what the
     *   running session configured (the UI toggle must never retarget a chain).
     */
    fun startSync(localOnly: Boolean = true, upstreamPort: Int? = null): Boolean = synchronized(this) {
        if (upstreamPort != null) this.upstreamPort = upstreamPort
        lanRequested = !localOnly

        if (_active.value && loSocks?.isClosed == false && loHttp?.isClosed == false) {
            if (lanRequested) {
                ensureLanLocked()
                startWatcherLocked()
            } else {
                closeLanLocked()
                _lanStatus.value = LanStatus.OFF
            }
            return@synchronized true
        }

        session++
        closeAllLocked()
        uploadBytesCounter.set(0L)
        downloadBytesCounter.set(0L)
        liveConnections.set(0)
        liveLanClients.set(0)
        _lanClients.value = 0
        refusals.set(0L)
        lanClientSeen = false
        idleHintLogged = false
        stunNoted = false

        reportNeighbours()

        loSocks = bindWithRetry("SOCKS5", LOOPBACK, SOCKS_SHARE_PORT)
        loHttp = bindWithRetry("HTTP", LOOPBACK, HTTP_SHARE_PORT)
        if (loSocks == null || loHttp == null) {
            DiagnosticsLog.e(
                TAG,
                "Could not open the fixed proxy ports ($SOCKS_SHARE_PORT/$HTTP_SHARE_PORT) - " +
                    "close the app holding them and reconnect.",
            )
            closeAllLocked()
            _active.value = false
            return@synchronized false
        }
        _socksPort.value = loSocks?.localPort
        _httpPort.value = loHttp?.localPort
        loSocks?.let { acceptLoop("share-socks-lo", it, via = null) { c -> serveSocksClient(c) } }
        loHttp?.let { acceptLoop("share-http-lo", it, via = null) { c -> serveHttpClient(c) } }
        _active.value = true
        DiagnosticsLog.i(TAG, "Proxy bridge ON - SOCKS5 :$SOCKS_SHARE_PORT + HTTP :$HTTP_SHARE_PORT (loopback)")

        if (lanRequested) {
            ensureLanLocked()
            startWatcherLocked()
        } else {
            _lanStatus.value = LanStatus.OFF
        }
        true
    }

    /** Turn everything off. Safe from any thread. */
    fun stop() {
        _active.value = false
        lanRequested = false
        _lanStatus.value = LanStatus.OFF
        val stopSession = synchronized(this) { ++session }
        thread(name = "share-stop", isDaemon = true) {
            synchronized(this) {
                if (session == stopSession) {
                    val had = loSocks != null || lanListeners.isNotEmpty()
                    closeAllLocked()
                    if (had) DiagnosticsLog.i(TAG, "Sharing OFF")
                }
            }
        }
    }

    /**
     * Turns the LAN side on for a running bridge, or starts the bridge with it.
     * Used by the Share card's switch.
     */
    fun enableLan() {
        lanRequested = true
        if (!_active.value) {
            start(localOnly = false)
            return
        }
        thread(name = "share-lan-on", isDaemon = true) {
            synchronized(this) {
                if (!_active.value) return@thread
                ensureLanLocked()
                startWatcherLocked()
            }
        }
    }

    /**
     * Turns only the LAN side off, keeping the loopback listeners (proxy mode
     * needs them - r4 stopped the whole bridge here and broke proxy mode).
     */
    fun disableLan() {
        lanRequested = false
        _lanStatus.value = LanStatus.OFF
        thread(name = "share-lan-off", isDaemon = true) {
            synchronized(this) {
                val had = lanListeners.isNotEmpty()
                closeLanLocked()
                if (had) DiagnosticsLog.i(TAG, "LAN sharing OFF (loopback proxy kept)")
            }
        }
    }

    /** Switches between Home Wi-Fi and Mobile-data hotspot sharing. */
    fun setMode(mode: ShareLeakGuard.Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        if (!_active.value || !lanRequested) return
        thread(name = "share-mode", isDaemon = true) {
            synchronized(this) { if (_active.value) ensureLanLocked() }
        }
    }

    /** Turns the optional username/password requirement on or off (new connections). */
    fun setAuthRequired(required: Boolean) {
        val changed = _authRequired.value != required
        _authRequired.value = required
        if (required && _proxyPassword.value.isBlank()) {
            // Fail closed: never "auth on" with an empty password.
            _proxyPassword.value = LanGuard.randomPassword()
        }
        if (changed) DiagnosticsLog.i(TAG, "Share authentication ${if (required) "ON" else "OFF"}")
    }

    /**
     * r6: hands the bridge the application context so it can tell upstream
     * networks (this phone's Wi-Fi, mobile data, the VPN) from tethering
     * downstreams without guessing from interface names. Idempotent; called from
     * [studio.cluvex.aether.data.ShareCredentials.ensure].
     */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Installs the credential used when authentication is on. Takes effect for
     * NEW connections; relaying sockets are untouched.
     */
    fun setCredentials(user: String, password: String) {
        if (user.isNotBlank()) _proxyUser.value = user
        if (password.isNotBlank()) _proxyPassword.value = password
    }

    /**
     * Called by [AetherProcess] with the argv actually given to the engine: if
     * it can route anything directly, LAN clients are refused (zero leak).
     */
    fun noteEngineRoutes(directRulesActive: Boolean) {
        engineDirectRoutes = directRulesActive
        if (directRulesActive) {
            DiagnosticsLog.w(
                TAG,
                "Engine started WITH direct (bypass) rules - LAN sharing stays closed for this " +
                    "session so a shared device can never leave outside the tunnel.",
            )
        }
        if (_active.value && lanRequested) {
            thread(name = "share-routes", isDaemon = true) {
                synchronized(this) { if (_active.value) ensureLanLocked() }
            }
        }
    }

    /** The address other devices would use in the CURRENT mode (null if none). */
    fun lanAddress(): String? = lanAddress(_mode.value)

    fun lanAddress(mode: ShareLeakGuard.Mode): String? = findLanTargets(mode).firstOrNull()?.address?.hostAddress

    /** Which sharing modes currently have a usable network - for the card's hint. */
    fun availableModes(): Set<ShareLeakGuard.Mode> =
        ShareLeakGuard.Mode.entries.filter { findLanTargets(it).isNotEmpty() }.toSet()

    // ============================================================ LAN binding

    /**
     * Brings the LAN listeners in line with the request, the mode and the
     * network. Caller holds the lock. Idempotent - the watcher calls it often.
     */
    private fun ensureLanLocked() {
        if (!lanRequested) {
            closeLanLocked()
            _lanStatus.value = LanStatus.OFF
            return
        }
        if (engineDirectRoutes) {
            closeLanLocked()
            _lanStatus.value = LanStatus.NEEDS_RECONNECT
            return
        }
        val targets = findLanTargets(_mode.value)
        if (targets.isEmpty()) {
            if (lanListeners.isNotEmpty()) DiagnosticsLog.i(TAG, "Share network went away - LAN listeners closed")
            closeLanLocked()
            _lanStatus.value = LanStatus.WAITING_FOR_NETWORK
            return
        }

        // r6: follow the SET of share interfaces, not one. Close what is gone or
        // dead, bind what is new, keep what still works (its clients stay up).
        var changed = false
        val iterator = lanListeners.iterator()
        while (iterator.hasNext()) {
            val listener = iterator.next()
            val stillWanted = targets.any { it.address == listener.target.address }
            if (!stillWanted || listener.socks.isClosed || listener.http.isClosed) {
                listener.close()
                iterator.remove()
                changed = true
            }
        }
        if (_authRequired.value && _proxyPassword.value.isBlank()) {
            _proxyPassword.value = LanGuard.randomPassword()
        }
        var busy = 0
        for (target in targets) {
            if (lanListeners.any { it.target.address == target.address }) continue
            val host = target.address.hostAddress ?: continue
            val label = "(LAN ${target.interfaceName})"
            val socks = bindWithRetry("SOCKS5 $label", host, SOCKS_SHARE_PORT, attempts = 4)
            val http = if (socks != null) bindWithRetry("HTTP $label", host, HTTP_SHARE_PORT, attempts = 4) else null
            if (socks == null || http == null) {
                runCatching { socks?.close() }
                runCatching { http?.close() }
                busy++
                continue
            }
            lanListeners += LanListener(target, socks, http)
            acceptLoop("share-socks-lan", socks, via = target) { c -> serveSocksClient(c) }
            acceptLoop("share-http-lan", http, via = target) { c -> serveHttpClient(c) }
            changed = true
        }

        if (lanListeners.isEmpty()) {
            lanTargets = emptyList()
            _lanEndpoints.value = emptyList()
            _lanEndpoint.value = null
            _lanStatus.value = if (busy > 0) LanStatus.PORT_BUSY else LanStatus.WAITING_FOR_NETWORK
            return
        }

        // Preference order of findLanTargets: the Wi-Fi hotspot first, then USB, BT.
        val order = targets.map { it.address }
        lanListeners.sortBy { listener ->
            order.indexOf(listener.target.address).let { if (it < 0) Int.MAX_VALUE else it }
        }
        lanTargets = lanListeners.map { it.target }
        val endpoints = lanTargets.map {
            LanEndpoint(
                address = it.address.hostAddress.orEmpty().substringBefore('%'),
                interfaceName = it.interfaceName,
                kind = it.kind,
            )
        }
        _lanEndpoints.value = endpoints
        _lanEndpoint.value = endpoints.first()
        val wasReady = _lanStatus.value == LanStatus.READY
        _lanStatus.value = LanStatus.READY
        if (!wasReady) {
            lanReadySinceMs = System.currentTimeMillis()
            idleHintLogged = false
        }
        if (changed || !wasReady) {
            // Addresses are not logged (the log goes into bug reports); interface
            // names, kinds and prefixes are enough to diagnose a mix-up.
            DiagnosticsLog.i(
                TAG,
                "LAN sharing ON - mode=${_mode.value}, interfaces=${describeTargets(lanTargets)}, " +
                    "auth=${if (_authRequired.value) "on" else "off"}, zero-leak guard on",
            )
        }
    }

    private fun describeTargets(targets: List<LanTarget>): String =
        targets.joinToString(", ") { "${it.interfaceName} (${it.kind}, /${it.prefixLength})" }

    /**
     * r6: one warning when the share has been listening for a while and no device
     * has reached it - the exact silence of the r5 hotspot log, now explained in
     * the log itself instead of left for a bug report.
     */
    private fun maybeLogIdleHintLocked() {
        if (_lanStatus.value != LanStatus.READY || lanClientSeen || idleHintLogged) return
        if (System.currentTimeMillis() - lanReadySinceMs < IDLE_HINT_AFTER_MS) return
        idleHintLogged = true
        DiagnosticsLog.w(
            TAG,
            "LAN sharing has been listening for ${IDLE_HINT_AFTER_MS / 1000} s on " +
                "${describeTargets(lanTargets)} and no device has connected yet. On the other device use " +
                "the address the Share card lists for the link it is actually on (Wi-Fi hotspot, USB and " +
                "Bluetooth each have their own) with port $HTTP_SHARE_PORT (HTTP) or $SOCKS_SHARE_PORT " +
                "(SOCKS5), or open http://<that address>:$HTTP_SHARE_PORT/ in its browser to test reachability.",
        )
    }

    /** One watcher per session: follows hotspot toggles and Wi-Fi address changes. */
    private fun startWatcherLocked() {
        if (watcherSession == session) return
        watcherSession = session
        val mySession = session
        thread(name = "share-lan-watch", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(LAN_WATCH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                synchronized(this) {
                    if (session != mySession || !_active.value) return@thread
                    if (lanRequested) ensureLanLocked()
                    maybeLogIdleHintLocked()
                }
            }
        }
    }

    /**
     * Every interface to share on for [mode], best first, IPv4 only (every proxy
     * dialog accepts it; an IPv6 literal confuses most of them).
     *
     * r6: ALL matching interfaces, not the first one, and classified by ROLE
     * ([ShareLeakGuard.classifyInterface] with [upstreamRoles]) rather than by a
     * vendor name list.
     */
    private fun findLanTargets(mode: ShareLeakGuard.Mode): List<LanTarget> = runCatching {
        val wanted = ShareLeakGuard.kindsFor(mode)
        val roles = upstreamRoles()
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                val kind = ShareLeakGuard.classifyInterface(nif.name, roles)
                nif.interfaceAddresses.asSequence()
                    .filter { ia ->
                        val a = ia.address
                        a is Inet4Address && a.isSiteLocalAddress
                    }
                    .map { ia ->
                        LanTarget(
                            address = ia.address,
                            prefixLength = ShareLeakGuard.sanePrefix(ia.networkPrefixLength.toInt()),
                            interfaceName = nif.name,
                            kind = kind,
                        )
                    }
            }
            .filter { it.kind in wanted }
            .distinctBy { it.address }
            .sortedWith(
                compareBy<LanTarget> { wanted.indexOf(it.kind) }
                    // A tethering gateway is almost always x.x.x.1.
                    .thenBy { if (it.address.address.last().toInt() and 0xFF == 1) 0 else 1 }
                    .thenBy { it.interfaceName },
            )
            .toList()
    }.getOrDefault(emptyList())

    /**
     * r6: interface name -> role of the Android network it backs, or null when
     * the bridge has no context yet (then the name rules apply). Interfaces
     * missing from the map back no network: tethering downstreams.
     */
    private fun upstreamRoles(): Map<String, ShareLeakGuard.UpstreamRole>? {
        val context = appContext ?: return null
        return runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
            val roles = HashMap<String, ShareLeakGuard.UpstreamRole>()
            @Suppress("DEPRECATION")
            val networks = cm.allNetworks
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val link = cm.getLinkProperties(network) ?: continue
                val role = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ShareLeakGuard.UpstreamRole.VPN
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ShareLeakGuard.UpstreamRole.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ShareLeakGuard.UpstreamRole.ETHERNET
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ShareLeakGuard.UpstreamRole.CELLULAR
                    else -> ShareLeakGuard.UpstreamRole.OTHER
                }
                link.interfaceName?.let { roles[it] = role }
            }
            roles
        }.getOrNull()
    }

    private fun bind(host: String, port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(host, port), 64)
        }

    /** Binds a FIXED port with a short retry; never moves to another port. */
    private fun bindWithRetry(
        label: String,
        host: String,
        port: Int,
        attempts: Int = 10,
        delayMs: Long = 300,
    ): ServerSocket? {
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return bind(host, port)
            } catch (e: Exception) {
                lastError = e
                if (attempt < attempts - 1) Thread.sleep(delayMs)
            }
        }
        DiagnosticsLog.e(TAG, "$label port $port is busy${describePortHolder(port)}: $lastError")
        return null
    }

    private fun describePortHolder(port: Int): String =
        KNOWN_NEIGHBOUR_PORTS[port]?.let { " - this port belongs to $it" } ?: " (held by another app?)"

    private fun reportNeighbours() {
        val live = KNOWN_NEIGHBOUR_PORTS.filterKeys { port ->
            runCatching {
                Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 120) }
                true
            }.getOrDefault(false)
        }.values.distinct()
        if (live.isEmpty()) return
        DiagnosticsLog.i(
            TAG,
            "Other local proxies detected (${live.joinToString(", ")}) - Aether uses " +
                "$SOCKS_SHARE_PORT/$HTTP_SHARE_PORT, so they can run side by side.",
        )
    }

    private fun closeLanLocked() {
        lanListeners.forEach { it.close() }
        lanListeners.clear()
        lanTargets = emptyList()
        _lanEndpoint.value = null
        _lanEndpoints.value = emptyList()
    }

    private fun closeAllLocked() {
        closeLanLocked()
        runCatching { loSocks?.close() }
        loSocks = null
        runCatching { loHttp?.close() }
        loHttp = null
        _socksPort.value = null
        _httpPort.value = null
    }

    // ============================================================ admission

    /** [via] is the share interface a LAN listener is bound on; null = loopback. */
    private fun acceptLoop(name: String, server: ServerSocket, via: LanTarget?, handler: (Socket) -> Unit) {
        thread(name = name, isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                if (!admit(client, via)) {
                    runCatching { client.close() }
                    continue
                }
                thread(name = "$name-conn", isDaemon = true) {
                    try {
                        client.tcpNoDelay = true
                        handler(client)
                    } catch (_: Exception) {
                        // Per-connection errors are non-fatal by design.
                    } finally {
                        liveConnections.decrementAndGet()
                        if (via != null) _lanClients.value = liveLanClients.decrementAndGet().coerceAtLeast(0)
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    /**
     * Admission: loopback listeners accept loopback only; LAN listeners accept
     * only peers inside the bound interface's subnet (and never while the
     * engine could route directly). Then the concurrency cap.
     */
    private fun admit(client: Socket, via: LanTarget?): Boolean {
        val remote = client.inetAddress
        if (via == null) {
            if (!LanGuard.isLoopback(remote)) {
                noteRefusal("a non-loopback peer on the loopback listener")
                return false
            }
        } else {
            val targets = lanTargets
            if (targets.isEmpty() || remote == null || engineDirectRoutes) {
                noteRefusal("LAN sharing not being ready")
                return false
            }
            // r6: a peer on ANY bound share subnet is admitted on any share
            // listener (a USB laptop may dial the hotspot address - Linux accepts
            // it on either link). Nothing outside those subnets ever is.
            if (!LanGuard.isLocalNetworkAddress(remote) ||
                targets.none { ShareLeakGuard.sameSubnet(remote, it.address, it.prefixLength) }
            ) {
                noteRefusal("a peer outside the shared network")
                return false
            }
        }
        if (liveConnections.get() >= MAX_LIVE_CONNECTIONS) {
            noteRefusal("the $MAX_LIVE_CONNECTIONS simultaneous-connection limit")
            return false
        }
        liveConnections.incrementAndGet()
        if (via != null) {
            _lanClients.value = liveLanClients.incrementAndGet()
            if (!lanClientSeen) {
                lanClientSeen = true
                DiagnosticsLog.i(TAG, "First device reached the share via ${via.interfaceName} (${via.kind}).")
            }
        }
        return true
    }

    /** Counts a refusal; logs a summary at most once per window, never the peer. */
    private fun noteRefusal(reason: String) {
        val total = refusals.incrementAndGet()
        val now = System.currentTimeMillis()
        if (now - lastRefusalLogMs < REJECT_LOG_INTERVAL_MS) return
        lastRefusalLogMs = now
        DiagnosticsLog.w(TAG, "Refused a shared-proxy connection because of $reason ($total refused so far this session).")
    }

    // ================================================================ SOCKS5

    /**
     * SOCKS5.
     *
     * Loopback: original byte-for-byte relay (the engine serves the protocol).
     * LAN: the bridge terminates the negotiation itself - optional RFC 1929
     * authentication, then the request is inspected: CONNECT is checked against
     * [ShareLeakGuard.destinationAllowed] and relayed; UDP ASSOCIATE gets a real
     * relay on the share interface; anything else is refused.
     */
    private fun serveSocksClient(client: Socket) {
        if (LanGuard.isLoopback(client.inetAddress)) {
            relayToLocalSocks(client)
            return
        }
        client.soTimeout = AUTH_TIMEOUT_MS
        val input = client.getInputStream()
        val out = client.getOutputStream()
        if (!negotiateLanSocks(input, out)) return
        val request = readSocksRequest(input) ?: return
        when (request.command) {
            SOCKS_CMD_CONNECT -> {
                // r6: the check page, answered here - it exists nowhere upstream.
                if (ShareLeakGuard.isCheckTarget(request.host)) {
                    serveCheckOverStream(client, request.port, socks = true)
                    return
                }
                if (!ShareLeakGuard.destinationAllowed(request.host)) {
                    noteRefusal("a LAN request for a non-public destination")
                    out.write(socksReply(SOCKS_REP_NOT_ALLOWED))
                    out.flush()
                    return
                }
                client.soTimeout = 0
                val upstream = dialUpstreamGreeted() ?: run {
                    out.write(socksReply(SOCKS_REP_GENERAL_FAILURE))
                    out.flush()
                    return
                }
                try {
                    // Hand the engine the client's own request (domain names stay
                    // names, so DNS is resolved inside the tunnel); its reply and
                    // everything after it flow straight back.
                    upstream.getOutputStream().apply { write(request.raw); flush() }
                    uploadBytesCounter.addAndGet(request.raw.size.toLong())
                    relay(client, upstream)
                } finally {
                    runCatching { upstream.close() }
                }
            }
            SOCKS_CMD_UDP_ASSOCIATE -> serveUdpAssociate(client, request)
            else -> {
                out.write(socksReply(SOCKS_REP_CMD_NOT_SUPPORTED))
                out.flush()
            }
        }
    }

    /**
     * Method negotiation for a LAN client. With authentication ON only 0x02 is
     * accepted (a client that will not authenticate is refused, never
     * downgraded). With it OFF, 0x00 is preferred, and a client configured with
     * a username/password anyway is let through after a formal 0x02 exchange.
     */
    private fun negotiateLanSocks(input: InputStream, out: OutputStream): Boolean {
        val greeting = input.readExact(2) ?: return false
        if (greeting[0] != 5.toByte()) return false
        val methodCount = greeting[1].toInt() and 0xFF
        if (methodCount == 0) return false
        val methods = input.readExact(methodCount) ?: return false
        val offersNoAuth = methods.any { it == 0x00.toByte() }
        val offersUserPass = methods.any { it == 0x02.toByte() }
        val required = _authRequired.value

        if (!required && offersNoAuth) {
            out.write(byteArrayOf(0x05, 0x00))
            out.flush()
            return true
        }
        if (!offersUserPass) {
            out.write(byteArrayOf(0x05, 0xFF.toByte()))
            out.flush()
            noteRefusal("a LAN SOCKS5 client that did not offer username/password")
            return false
        }
        out.write(byteArrayOf(0x05, 0x02))
        out.flush()

        val authHeader = input.readExact(2) ?: return false
        if (authHeader[0] != 0x01.toByte()) return false
        val offeredUser = String(input.readExact(authHeader[1].toInt() and 0xFF) ?: return false, Charsets.UTF_8)
        val passLen = (input.readExact(1) ?: return false)[0].toInt() and 0xFF
        val offeredPass = String(input.readExact(passLen) ?: return false, Charsets.UTF_8)

        val ok = !required || (
            _proxyPassword.value.isNotBlank() &&
                LanGuard.secretEquals(_proxyUser.value, offeredUser) &&
                LanGuard.secretEquals(_proxyPassword.value, offeredPass)
            )
        out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
        out.flush()
        if (!ok) noteRefusal("a wrong shared-proxy username or password")
        return ok
    }

    /** A parsed SOCKS5 request; [raw] is the exact bytes, for forwarding. */
    private class SocksRequest(val command: Int, val host: String, val port: Int, val raw: ByteArray)

    private fun readSocksRequest(input: InputStream): SocksRequest? {
        val head = input.readExact(4) ?: return null
        if (head[0] != 5.toByte()) return null
        val command = head[1].toInt() and 0xFF
        val buffer = ByteArrayOutputStream().apply { write(head) }
        val host: String = when (head[3].toInt() and 0xFF) {
            0x01 -> {
                val a = input.readExact(4) ?: return null
                buffer.write(a)
                InetAddress.getByAddress(a).hostAddress ?: return null
            }
            0x04 -> {
                val a = input.readExact(16) ?: return null
                buffer.write(a)
                InetAddress.getByAddress(a).hostAddress ?: return null
            }
            0x03 -> {
                val len = (input.readExact(1) ?: return null)[0].toInt() and 0xFF
                val name = input.readExact(len) ?: return null
                buffer.write(len)
                buffer.write(name)
                String(name, Charsets.ISO_8859_1)
            }
            else -> return null
        }
        val portBytes = input.readExact(2) ?: return null
        buffer.write(portBytes)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return SocksRequest(command, host, port, buffer.toByteArray())
    }

    /**
     * UDP ASSOCIATE for a LAN client, carried through the tunnel.
     *
     * The upstream (engine / chained front) only offers a LOOPBACK relay, which
     * a LAN device cannot reach - in r4 that made UDP fail and apps fell back to
     * sending it directly, outside the tunnel. Now:
     *
     *   LAN client <-UDP-> [clientSide on the share interface]
     *                        <-> [upstreamSide on loopback] <-UDP-> upstream relay
     *
     * Only datagrams from the client's own IP are accepted, every datagram's
     * destination is checked, fragments are dropped, and the association lives
     * exactly as long as the client's TCP control connection (RFC 1928 s.7).
     */
    private fun serveUdpAssociate(client: Socket, request: SocksRequest) {
        val out = client.getOutputStream()
        val upstream = dialUpstreamGreeted() ?: run {
            out.write(socksReply(SOCKS_REP_GENERAL_FAILURE))
            out.flush()
            return
        }
        var clientSide: DatagramSocket? = null
        var upstreamSide: DatagramSocket? = null
        try {
            // Ask the upstream for ITS relay. DST 0.0.0.0:0 - the upstream sees
            // our loopback datagram socket, not the LAN client.
            upstream.soTimeout = DIAL_TIMEOUT_MS
            upstream.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                flush()
            }
            val upIn = upstream.getInputStream()
            val rep = upIn.readExact(4) ?: return
            if (rep[1] != 0.toByte()) {
                out.write(socksReply(SOCKS_REP_GENERAL_FAILURE))
                out.flush()
                return
            }
            val relayAddr: InetAddress = when (rep[3].toInt() and 0xFF) {
                0x01 -> InetAddress.getByAddress(upIn.readExact(4) ?: return)
                0x04 -> InetAddress.getByAddress(upIn.readExact(16) ?: return)
                0x03 -> {
                    val len = (upIn.readExact(1) ?: return)[0].toInt() and 0xFF
                    upIn.readExact(len) ?: return
                    InetAddress.getByName(LOOPBACK)
                }
                else -> return
            }
            val relayPortBytes = upIn.readExact(2) ?: return
            val relayPort = ((relayPortBytes[0].toInt() and 0xFF) shl 8) or (relayPortBytes[1].toInt() and 0xFF)
            // An upstream that answers 0.0.0.0 means "the address you reached me on".
            // Anything that is not loopback is refused: the bridge must never send
            // a client's UDP anywhere but into the local tunnel.
            val upstreamRelay = InetSocketAddress(
                if (relayAddr.isAnyLocalAddress) InetAddress.getByName(LOOPBACK) else relayAddr,
                relayPort,
            )
            if (!upstreamRelay.address.isLoopbackAddress || relayPort == 0) {
                DiagnosticsLog.w(TAG, "UDP relay refused: upstream offered a non-loopback relay")
                out.write(socksReply(SOCKS_REP_GENERAL_FAILURE))
                out.flush()
                return
            }
            upstream.soTimeout = 0

            // Our two datagram sockets: one on the SAME local address the client
            // reached us on (the share interface), one on loopback.
            val shareAddr = client.localAddress
            val cs = DatagramSocket(InetSocketAddress(shareAddr, 0))
            clientSide = cs
            val us = DatagramSocket(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0))
            upstreamSide = us

            // Tell the client where to send its datagrams.
            out.write(socksReplyBound(shareAddr, cs.localPort))
            out.flush()
            client.soTimeout = 0

            val clientIp = client.inetAddress
            // RFC 1928: the client MAY announce the port it will send from.
            val clientPeer = AtomicReference<InetSocketAddress?>(
                if (request.port != 0) InetSocketAddress(clientIp, request.port) else null,
            )

            // client -> tunnel
            val up = thread(name = "share-udp-up", isDaemon = true) {
                val buf = ByteArray(MAX_UDP_DATAGRAM)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    while (!cs.isClosed) {
                        packet.setData(buf, 0, buf.size)
                        cs.receive(packet)
                        if (packet.address != clientIp) continue // only the associated client
                        val known = clientPeer.get()
                        if (known != null && known.port != packet.port) continue
                        if (known == null) clientPeer.set(InetSocketAddress(packet.address, packet.port))
                        // r6: a DNS query for aether.check is answered locally, so
                        // TUN/VPN clients can open the check page at all.
                        val local = ShareLeakGuard.checkHostDnsReply(packet.data, packet.offset, packet.length)
                        if (local != null) {
                            val peer = clientPeer.get() ?: InetSocketAddress(packet.address, packet.port)
                            cs.send(DatagramPacket(local, local.size, peer))
                            continue
                        }
                        if (!ShareLeakGuard.udpDatagramAllowed(packet.data, packet.offset, packet.length)) {
                            noteRefusal("a LAN UDP datagram to a non-public or malformed destination")
                            continue
                        }
                        if (!stunNoted && ShareLeakGuard.isStunDatagram(packet.data, packet.offset, packet.length)) {
                            stunNoted = true
                            DiagnosticsLog.i(
                                TAG,
                                "WebRTC (STUN) from a shared device is being carried through the tunnel - " +
                                    "its WebRTC address is the exit, not this line.",
                            )
                        }
                        us.send(DatagramPacket(packet.data, packet.offset, packet.length, upstreamRelay))
                        uploadBytesCounter.addAndGet(packet.length.toLong())
                    }
                } catch (_: Exception) {
                }
            }
            // tunnel -> client
            val down = thread(name = "share-udp-down", isDaemon = true) {
                val buf = ByteArray(MAX_UDP_DATAGRAM)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    while (!us.isClosed) {
                        packet.setData(buf, 0, buf.size)
                        us.receive(packet)
                        if (packet.address != upstreamRelay.address || packet.port != upstreamRelay.port) continue
                        val peer = clientPeer.get() ?: continue
                        cs.send(DatagramPacket(packet.data, packet.offset, packet.length, peer))
                        downloadBytesCounter.addAndGet(packet.length.toLong())
                    }
                } catch (_: Exception) {
                }
            }

            // The association ends when EITHER control connection ends.
            val upstreamWatch = thread(name = "share-udp-ctl", isDaemon = true) {
                runCatching { while (upIn.read() >= 0) { /* drain */ } }
                runCatching { client.shutdownInput() }
            }
            runCatching { while (client.getInputStream().read() >= 0) { /* drain */ } }
            runCatching { cs.close() }
            runCatching { us.close() }
            runCatching { upstream.close() }
            runCatching { up.join(1_000) }
            runCatching { down.join(1_000) }
            runCatching { upstreamWatch.join(1_000) }
        } catch (_: Exception) {
        } finally {
            runCatching { clientSide?.close() }
            runCatching { upstreamSide?.close() }
            runCatching { upstream.close() }
        }
    }

    /** Loopback client: byte-for-byte relay into the upstream SOCKS5 (unchanged). */
    private fun relayToLocalSocks(client: Socket) {
        val upstream = Socket()
        try {
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(TunnelConfig.SOCKS_HOST, upstreamPort), DIAL_TIMEOUT_MS)
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /**
     * Opens a connection to the upstream SOCKS5 and completes its no-auth
     * greeting. The upstream is ALWAYS a loopback address - asserted, because
     * "the bridge never dials the internet itself" is the zero-leak invariant.
     */
    private fun dialUpstreamGreeted(): Socket? {
        val host = TunnelConfig.SOCKS_HOST
        val socket = Socket()
        return try {
            val address = InetAddress.getByName(host)
            check(address.isLoopbackAddress) { "upstream must be loopback" }
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, upstreamPort), DIAL_TIMEOUT_MS)
            socket.soTimeout = DIAL_TIMEOUT_MS
            val out = socket.getOutputStream()
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val reply = socket.getInputStream().readExact(2)
            if (reply == null || reply[0] != 5.toByte() || reply[1] != 0.toByte()) {
                throw IllegalStateException("SOCKS5 greeting failed")
            }
            socket.soTimeout = 0
            socket
        } catch (e: Exception) {
            DiagnosticsLog.e(TAG, "Tunnel upstream not reachable - connection refused (fail closed): $e")
            runCatching { socket.close() }
            null
        }
    }

    private fun socksReply(code: Int): ByteArray =
        byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)

    private fun socksReplyBound(address: InetAddress, port: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x05); out.write(SOCKS_REP_SUCCEEDED); out.write(0x00)
        when (address) {
            is Inet6Address -> { out.write(0x04); out.write(address.address) }
            else -> { out.write(0x01); out.write(address.address) }
        }
        out.write((port shr 8) and 0xFF)
        out.write(port and 0xFF)
        return out.toByteArray()
    }

    // ================================================================= HTTP

    /**
     * HTTP proxy: CONNECT tunnels + absolute-form plain HTTP, both dialled
     * THROUGH the upstream SOCKS5 with the host name passed as a name (so DNS
     * is resolved inside the tunnel).
     *
     * 1.4.0-r5 additions: `/proxy.pac` (and `/wpad.dat`) served to LAN clients
     * for one-step, fail-closed setup; `http://aether.check/` answered locally
     * so a client can confirm it is going through the share; identity headers
     * stripped; non-public destinations refused for LAN clients; IPv6 CONNECT
     * targets (`[2001:db8::1]:443`) parsed correctly.
     */
    private fun serveHttpClient(client: Socket) {
        val remote = !LanGuard.isLoopback(client.inetAddress)
        if (remote) client.soTimeout = AUTH_TIMEOUT_MS
        val input = client.getInputStream()
        val header = readHeaderBlock(input) ?: return
        val lines = header.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.firstOrNull().orEmpty().split(" ")
        if (parts.size < 3) return
        val method = parts[0]
        val target = parts[1]
        val out = client.getOutputStream()

        // Origin-form request straight to the proxy port: the setup endpoints.
        if (target.startsWith("/")) {
            serveLocalPage(out, target.substringBefore('?'), client.localAddress, remote)
            return
        }

        if (remote && _authRequired.value && !httpAuthorized(lines)) {
            noteRefusal("an unauthenticated HTTP proxy request")
            out.writeAscii(
                "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                    "Proxy-Authenticate: Basic realm=\"Aether\", charset=\"UTF-8\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n",
            )
            return
        }
        if (remote) client.soTimeout = 0

        if (method.equals("CONNECT", ignoreCase = true)) {
            val (host, port) = splitHostPort(target, 443) ?: run {
                out.writeAscii("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                return
            }
            // r6: CONNECT aether.check (HTTPS-first browsers, apps that tunnel
            // everything) is answered here instead of dying upstream.
            if (ShareLeakGuard.isCheckTarget(host)) {
                if (port == 443) {
                    // No certificate can be valid for this name: refuse at once so
                    // the browser falls back to http:// without waiting on a timeout.
                    out.writeAscii("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    return
                }
                out.writeAscii("HTTP/1.1 200 Connection Established\r\n\r\n")
                serveCheckOverStream(client, port, socks = false)
                return
            }
            if (remote && !ShareLeakGuard.destinationAllowed(host)) {
                noteRefusal("a LAN request for a non-public destination")
                out.writeAscii("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                return
            }
            val upstream = socksOpen(host, port) ?: run {
                out.writeAscii("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
                return
            }
            try {
                out.writeAscii("HTTP/1.1 200 Connection Established\r\n\r\n")
                relay(client, upstream)
            } finally {
                runCatching { upstream.close() }
            }
            return
        }

        // Plain HTTP with an absolute URI, e.g. "GET http://example.com/x HTTP/1.1".
        val url = target.removePrefix("http://").removePrefix("HTTP://")
        if (url == target) { // https:// or malformed - TLS must use CONNECT
            out.writeAscii("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            return
        }
        val hostPort = url.substringBefore('/')
        val path = "/" + url.substringAfter('/', "")
        val (host, port) = splitHostPort(hostPort, 80) ?: run {
            out.writeAscii("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            return
        }
        if (ShareLeakGuard.isCheckTarget(host)) {
            serveCheckPath(out, path.substringBefore('?'), remote, via = "HTTP proxy")
            return
        }
        if (remote && !ShareLeakGuard.destinationAllowed(host)) {
            noteRefusal("a LAN request for a non-public destination")
            out.writeAscii("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            return
        }
        val upstream = socksOpen(host, port) ?: run {
            out.writeAscii("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n")
            return
        }
        try {
            val rebuilt = buildString {
                append("$method $path ${parts[2]}\r\n")
                lines.drop(1).forEach { line ->
                    if (line.isEmpty()) return@forEach
                    val lower = line.lowercase()
                    if (lower.startsWith("proxy-connection:") ||
                        lower.startsWith("proxy-authorization:") ||
                        lower.startsWith("connection:") ||
                        lower.startsWith("keep-alive:") ||
                        ShareLeakGuard.isIdentityHeader(line)
                    ) {
                        return@forEach
                    }
                    append(line).append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            upstream.getOutputStream().writeAscii(rebuilt)
            uploadBytesCounter.addAndGet(rebuilt.length.toLong())
            relay(client, upstream)
        } finally {
            runCatching { upstream.close() }
        }
    }

    /** `host:port`, `[v6]:port` or a bare host -> (host, port); null if malformed. */
    private fun splitHostPort(value: String, defaultPort: Int): Pair<String, Int>? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (v.startsWith("[")) {
            val end = v.indexOf(']')
            if (end < 0) return null
            val host = v.substring(1, end)
            val rest = v.substring(end + 1)
            val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else defaultPort
            return if (port == null || port !in 1..65535 || host.isEmpty()) null else host to port
        }
        val colons = v.count { it == ':' }
        if (colons > 1) return null // bare IPv6 without brackets is ambiguous
        val host = v.substringBefore(':')
        val port = if (colons == 1) v.substringAfter(':').toIntOrNull() else defaultPort
        return if (port == null || port !in 1..65535 || host.isEmpty()) null else host to port
    }

    /**
     * Origin-form requests straight to the share port: `/proxy.pac`,
     * `/wpad.dat`, the setup page at `/`, and (r6) `/check` plus the WebRTC
     * lock downloads under `/webrtc/`.
     */
    private fun serveLocalPage(out: OutputStream, path: String, localAddress: InetAddress, remote: Boolean) {
        val host = (localAddress.hostAddress ?: LOOPBACK).substringBefore('%')
        val shownHost = if (localAddress is Inet6Address) "[$host]" else host
        val download = SharePages.download(path)
        when {
            download != null -> writePage(out, download)
            path.equals("/proxy.pac", true) || path.equals("/wpad.dat", true) -> writeResponse(
                out,
                "200 OK",
                "application/x-ns-proxy-autoconfig",
                ShareLeakGuard.pacScript(shownHost, HTTP_SHARE_PORT),
            )
            path == "/" || path.equals("/index.html", true) -> writeResponse(
                out,
                "200 OK",
                "text/html; charset=utf-8",
                SharePages.setupPage(
                    shownHost,
                    HTTP_SHARE_PORT,
                    SOCKS_SHARE_PORT,
                    mode = _mode.value, // r10: open the guide for the current mode
                    authRequired = _authRequired.value, // r10: says whether a login is needed, never the password
                ),
            )
            path.equals("/check", true) -> writeResponse(
                out,
                "200 OK",
                "text/html; charset=utf-8",
                SharePages.checkPage(via = null, exitIp = currentExitIp(), onDevice = !remote, mode = _mode.value),
            )
            else -> writeResponse(out, "404 Not Found", "text/plain; charset=utf-8", "Not found\n")
        }
    }

    /**
     * r6: the check host reached through a CONNECT tunnel (HTTP or SOCKS5).
     * Reads the one HTTP request the browser sends inside it and answers it.
     * Port 443 is refused immediately (no certificate can be valid for the
     * name), which makes HTTPS-first browsers fall back to http:// at once.
     */
    private fun serveCheckOverStream(client: Socket, port: Int, socks: Boolean) {
        val out = client.getOutputStream()
        if (socks) {
            if (port == 443) {
                out.write(socksReply(SOCKS_REP_CONN_REFUSED))
                out.flush()
                return
            }
            out.write(socksReply(SOCKS_REP_SUCCEEDED))
            out.flush()
        }
        client.soTimeout = AUTH_TIMEOUT_MS
        val header = readHeaderBlock(client.getInputStream()) ?: return
        val requestLine = header.toString(Charsets.ISO_8859_1.name()).substringBefore("\r\n")
        val rawPath = requestLine.split(" ").getOrNull(1).orEmpty()
        val path = if (rawPath.startsWith("/")) rawPath else "/" + rawPath.substringAfter("://", "").substringAfter('/', "")
        serveCheckPath(
            out,
            path.substringBefore('?'),
            remote = !LanGuard.isLoopback(client.inetAddress),
            via = if (socks) "SOCKS5" else "HTTP CONNECT",
        )
    }

    /** Answer for `http://aether.check/<path>` - only reachable THROUGH the share. */
    private fun serveCheckPath(out: OutputStream, path: String, remote: Boolean, via: String) {
        val download = SharePages.download(path)
        if (download != null) {
            writePage(out, download)
            return
        }
        writeResponse(
            out,
            "200 OK",
            "text/html; charset=utf-8",
            SharePages.checkPage(via = via, exitIp = currentExitIp(), onDevice = !remote, mode = _mode.value),
        )
    }

    /** The tunnel's verified exit address, for the WebRTC test (null if unknown). */
    private fun currentExitIp(): String? =
        runCatching { AetherController.ipInfo.value?.takeIf { it.viaTunnel }?.ip }.getOrNull()

    private fun writePage(out: OutputStream, page: SharePages.Page) {
        val bytes = page.body.toByteArray(Charsets.UTF_8)
        val disposition = page.filename?.let { "Content-Disposition: attachment; filename=\"$it\"\r\n" }.orEmpty()
        out.writeAscii(
            "HTTP/1.1 ${page.status}\r\nContent-Type: ${page.contentType}\r\nContent-Length: ${bytes.size}\r\n" +
                disposition + "Cache-Control: no-store\r\nConnection: close\r\n\r\n",
        )
        out.write(bytes)
        out.flush()
    }

    private fun writeResponse(out: OutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.writeAscii(
            "HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n",
        )
        out.write(bytes)
        out.flush()
    }

    /** True when the request carries the configured `Proxy-Authorization: Basic`. */
    private fun httpAuthorized(headerLines: List<String>): Boolean {
        val password = _proxyPassword.value
        if (password.isBlank()) return false
        val header = headerLines.firstOrNull {
            it.length > PROXY_AUTH_HEADER.length &&
                it.regionMatches(0, PROXY_AUTH_HEADER, 0, PROXY_AUTH_HEADER.length, ignoreCase = true)
        } ?: return false
        val offered = LanGuard.parseBasicCredential(header.substring(PROXY_AUTH_HEADER.length)) ?: return false
        return LanGuard.secretEquals(_proxyUser.value, offered.first) &&
            LanGuard.secretEquals(password, offered.second)
    }

    /** Opens a TCP stream to host:port THROUGH the upstream SOCKS5 (name -> tunnel DNS). */
    private fun socksOpen(host: String, port: Int): Socket? {
        val socket = dialUpstreamGreeted() ?: return null
        return try {
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            val literal = ShareLeakGuard.parseIpLiteral(host)
            val request = ByteArrayOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))
                when (literal) {
                    is Inet4Address -> { write(0x01); write(literal.address) }
                    is Inet6Address -> { write(0x04); write(literal.address) }
                    else -> {
                        val hostBytes = host.toByteArray(Charsets.ISO_8859_1)
                        require(hostBytes.size in 1..255) { "bad host length" }
                        write(0x03); write(hostBytes.size); write(hostBytes)
                    }
                }
                write((port shr 8) and 0xFF)
                write(port and 0xFF)
            }
            out.write(request.toByteArray())
            out.flush()

            val reply = inp.readExact(4) ?: throw IllegalStateException("SOCKS5 reply truncated")
            if (reply[1] != 0.toByte()) throw IllegalStateException("SOCKS5 connect refused (${reply[1]})")
            val remaining = when (reply[3].toInt()) {
                0x01 -> 4 + 2
                0x03 -> (inp.readExact(1)?.get(0)?.toInt()?.and(0xFF)
                    ?: throw IllegalStateException("SOCKS5 reply truncated")) + 2
                0x04 -> 16 + 2
                else -> throw IllegalStateException("Bad SOCKS5 address type")
            }
            inp.readExact(remaining) ?: throw IllegalStateException("SOCKS5 reply truncated")
            socket.soTimeout = 0
            socket
        } catch (e: Exception) {
            // SECURITY (audit 1.2.7-r2): the destination is NOT logged.
            DiagnosticsLog.e(TAG, "Upstream dial failed (dest port $port) - $e")
            runCatching { socket.close() }
            null
        }
    }

    // ============================================================== plumbing

    private fun readHeaderBlock(input: InputStream): ByteArrayOutputStream? {
        val buf = ByteArrayOutputStream()
        var run = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            run = when {
                b == '\r'.code && (run == 0 || run == 2) -> run + 1
                b == '\n'.code && (run == 1 || run == 3) -> run + 1
                else -> 0
            }
            if (run == 4) return buf
        }
        return null
    }

    /** Full-duplex pipe; every byte feeds the session traffic counters. */
    private fun relay(client: Socket, upstream: Socket) {
        val reverse = thread(isDaemon = true) { pipe(upstream, client, downloadBytesCounter) }
        pipe(client, upstream, uploadBytesCounter)
        runCatching { reverse.join(1_000) }
    }

    private fun pipe(from: Socket, to: Socket, counter: AtomicLong) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                output.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            runCatching { to.shutdownOutput() }
            runCatching { from.shutdownInput() }
        }
    }

    private fun InputStream.readExact(n: Int): ByteArray? {
        val out = ByteArray(n)
        var done = 0
        while (done < n) {
            val r = read(out, done, n - done)
            if (r < 0) return null
            done += r
        }
        return out
    }

    private fun OutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.ISO_8859_1))
        flush()
    }

    private const val PROXY_AUTH_HEADER = "proxy-authorization:"

    private const val SOCKS_CMD_CONNECT = 0x01
    private const val SOCKS_CMD_UDP_ASSOCIATE = 0x03
    private const val SOCKS_REP_SUCCEEDED = 0x00
    private const val SOCKS_REP_GENERAL_FAILURE = 0x01
    private const val SOCKS_REP_NOT_ALLOWED = 0x02
    private const val SOCKS_REP_CONN_REFUSED = 0x05
    private const val SOCKS_REP_CMD_NOT_SUPPORTED = 0x07

    /** Default username; the real one comes from [setCredentials]. */
    const val DEFAULT_USER = "aether"
}
