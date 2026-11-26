package studio.cluvex.aether.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import studio.cluvex.aether.AetherApp
import studio.cluvex.aether.MainActivity
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AetherProcess
import studio.cluvex.aether.core.Diagnostics
import studio.cluvex.aether.core.DpiBrain
import studio.cluvex.aether.core.IdentityVault
import studio.cluvex.aether.core.LiveDataProof
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.EngineMeta
import studio.cluvex.aether.core.EngineReadiness
import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.core.NetworkIdentity
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.PortLease
import studio.cluvex.aether.core.PortProbe
import studio.cluvex.aether.core.TorBootstrap
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.core.HevTunnel
import studio.cluvex.aether.core.RoutingEngine
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SmartAuto
import studio.cluvex.aether.core.SmartPlusMemory
import studio.cluvex.aether.core.SmartPlusPlan
import studio.cluvex.aether.core.SocksTunBridge
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.core.TeamMembership
import studio.cluvex.aether.core.TeamSignInHandoff
import studio.cluvex.aether.data.TeamSignInStore
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.data.SecretStore
import studio.cluvex.aether.data.ShareCredentials
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TorMode
import studio.cluvex.aether.model.TorBridges
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.transport.ExternalTransport
import studio.cluvex.aether.transport.ExternalTransportFactory
import studio.cluvex.aether.transport.PsiphonRegionFallback
import studio.cluvex.aether.core.PsiphonEngine
import studio.cluvex.aether.core.PsiphonBootstrap
import studio.cluvex.aether.transport.TorSocksFront
import studio.cluvex.aether.widget.AetherWidgetProvider
import studio.cluvex.aether.custom.ChainBase
import studio.cluvex.aether.custom.ChainPlacement
import studio.cluvex.aether.custom.CustomConfig
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.custom.CustomSelection
import studio.cluvex.aether.custom.CustomSession
import studio.cluvex.aether.custom.XrayConfigBuilder
import studio.cluvex.aether.custom.XrayProcess
import studio.cluvex.aether.transport.PsiphonSocksFront
import studio.cluvex.aether.transport.PsiphonTransport
import java.io.File

/**
 * The heart of the app. On connect it:
 *   1. launches the bundled `aether` engine (opens SOCKS5 on 127.0.0.1:1819),
 *   2. waits until that port is actually reachable (ground-truth check),
 *   3. builds the VPN TUN interface,
 *   4. starts the embedded hev-socks5-tunnel core (libhev-socks5-tunnel.so) to forward all
 *      traffic through the proxy — replacing the need for v2rayNG entirely,
 *   5. supervises both processes and auto-reconnects on failure.
 */
class AetherVpnService : VpnService() {

    /**
     * The notification this service posts is user-visible text, so it has to obey
     * the in-app language choice exactly like the UI does. Without this the
     * status text would follow the phone's locale while the app showed another
     * language.
     */
    /**
     * 1.6.0: monotonic session counter. Bumped at every connect start and inside
     * every cleanupNativeOnly(reason), so an exit-IP enrichment (country, second
     * address family) that arrives after the session changed can never be
     * attributed to the next session's card.
     */
    private val sessionEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            studio.cluvex.aether.core.TunnelExitMonitor.watch(
                AetherController.state,
                currentIp = { AetherController.ipInfo.value },
                loading = AetherController::setIpLoading,
                offer = AetherController::offerTunnelIpInfo,
                clear = { AetherController.setIpInfo(null) },
                fetch = { endpoint -> fetchTunnelExit(endpoint.host, endpoint.port) },
                refine = { endpoint, known ->
                    val cc = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                        studio.cluvex.aether.core.NetProbe.enrichCountry(endpoint.host, endpoint.port, known.ip)
                    }
                    cc?.let { known.copy(countryCode = it) }
                },
            )
        }
    }

    /**
     * 1.6.0 (validation findings #1/#2). NetProbe answers twice: the raced first
     * answer (IP, maybe country=null, maybe one address family) is returned here and
     * reaches the card at once through the monitor; `onEnriched` fires later, from
     * NetProbe's own pool, with the country (and dual-stack data) for THAT IP. The
     * first answer already carries alternateIp through the four-argument IpEndpoint,
     * so the second family is shown as soon as the race has it.
     *
     * The epoch is captured BEFORE the race starts: a callback that lands after a
     * cleanup / reconnect / new connect is dropped in [applyExitEnrichment].
     */
    private suspend fun fetchTunnelExit(host: String, port: Int): studio.cluvex.aether.core.IpEndpoint? {
        val epoch = sessionEpoch.get()
        val published = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val first = kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            studio.cluvex.aether.core.NetProbe.fetchIpInfoViaSocksRaced(
                host,
                port,
                onEnriched = { enriched -> onExitEnriched(epoch, published.get(), enriched) },
            )
        } ?: return null
        published.set(first.ip)
        return studio.cluvex.aether.core.IpEndpoint(first.ip, first.countryCode, true, alternateIp = first.alternateIp)
    }

    /**
     * Shape-tolerant entry for NetProbe's enrichment callback: a full IpInfo carries
     * its own IP (and is matched against it); a bare country code can only belong
     * to the IP this very race published, so it is matched against that.
     */
    private fun onExitEnriched(epoch: Long, publishedIp: String?, enriched: Any?) {
        when (enriched) {
            is studio.cluvex.aether.core.IpInfo -> {
                if (AetherController.ipInfo.value?.let { it.viaTunnel && it.ip == enriched.ip } == true) {
                    applyExitEnrichment(epoch, enriched.ip, enriched.countryCode, enriched.alternateIp)
                } else scope.launch {
                    // The provider can finish BEFORE the monitor publishes the first
                    // IP. Wait for that publication without blocking the geo pool.
                    val ready = withTimeoutOrNull(10_000L) {
                        AetherController.ipInfo.first { it?.viaTunnel == true && it.ip == enriched.ip }
                    }
                    if (ready != null) applyExitEnrichment(epoch, enriched.ip, enriched.countryCode, enriched.alternateIp)
                }
            }
            is String -> applyExitEnrichment(epoch, publishedIp, enriched, null)
            else -> DiagnosticsLog.d(TAG, "Exit enrichment of an unexpected shape was ignored.")
        }
    }

    /**
     * Applies a late enrichment ONLY IF (a) the session epoch is unchanged - no
     * cleanup, reconnect or new connect happened in between - and (b) the card
     * still shows the very tunnel IP the enrichment belongs to. A country measured
     * for another IP, or a callback from a previous session, is dropped and logged.
     * Goes through setIpInfo because offerTunnelIpInfo does not upgrade the
     * metadata of an IP it already holds.
     */
    private fun applyExitEnrichment(epoch: Long, ip: String?, countryCode: String?, alternateIp: String?) {
        val now = sessionEpoch.get()
        if (epoch != now) {
            DiagnosticsLog.d(TAG, "Exit enrichment for ${ip ?: "the exit"} arrived after the session changed (epoch $epoch != $now) - dropped.")
            return
        }
        val current = AetherController.ipInfo.value
        if (current == null || !current.viaTunnel) {
            DiagnosticsLog.d(TAG, "Exit enrichment for ${ip ?: "the exit"} arrived while no tunnel IP is shown - dropped.")
            return
        }
        if (ip != null && current.ip != ip) {
            DiagnosticsLog.d(TAG, "Exit enrichment for $ip does not match the shown tunnel IP ${current.ip} - dropped.")
            return
        }
        if (ip == null && current.countryCode != null) {
            // A bare country code without a known target IP may only ever fill a blank.
            return
        }
        val merged = studio.cluvex.aether.core.IpEndpoint(
            current.ip,
            studio.cluvex.aether.core.NetProbe.normalizeCountryCode(countryCode) ?: current.countryCode,
            true,
            alternateIp = alternateIp?.takeIf { it.isNotBlank() } ?: current.alternateIp,
        )
        if (merged == current) return
        AetherController.setIpInfo(merged)
        DiagnosticsLog.i(
            TAG,
            "Exit enriched: ${merged.ip} (${merged.countryCode ?: "??"})" +
                (merged.alternateIp?.let { ", second family $it" } ?: ""),
        )
    }

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base?.let { LanguagePrefs.wrap(it) } ?: base)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private var engine: AetherProcess? = null

    /**
     * Engines belonging to a Smart Plus race, for as long as one is alive.
     *
     * Separate from [engine] on purpose: [engine] is the SESSION's engine, the one
     * the supervisor watches and the one teardown stops. A racing engine is a probe
     * that is always killed inside [runRace]; keeping the two apart means a lane
     * can never be mistaken for the session, and that teardown of a session cannot
     * be confused by a probe that no longer exists.
     *
     * Synchronised because lanes add and remove from several coroutines.
     */
    private val raceEngines: MutableList<AetherProcess> =
        java.util.Collections.synchronizedList(mutableListOf())
    private var externalTransport: ExternalTransport? = null
    private var tunnelStarted: Boolean = false
    private var runJob: Job? = null

    /**
     * The teardown coroutine of the PREVIOUS session, if one is still
     * finishing. A new connect waits for it instead of racing it (1.2.2
     * protocol-switch fix).
     */
    private var stopJob: Job? = null

    /** Active userspace filter bridge (only when per-app blocking is on). */
    private var tunBridge: SocksTunBridge? = null

    /** Last profile the service ran with (kill-switch decisions). */
    private var lastProfile: ConnectionProfile? = null

    /** True while the kill-switch blackhole TUN is up. */
    @Volatile
    private var lockdownTunActive = false

    /** Consecutive failed watchdog probes (1.2.4 stability watchdog). */
    private var probeFailures = 0

    /** One-shot guard for [registerSelfProbes]. */
    private var selfProbesRegistered = false

    /** True between [promoteToForeground] and [stopForegroundCompat]. */
    @Volatile
    private var foregroundActive = false

    /** Text of the notification currently posted, for the crash-handler rescue. */
    @Volatile
    private var lastNotifText: String? = null

    /**
     * Data-path byte total as it read at the previous watchdog decision, so a
     * failed probe can be checked against whether anything is actually moving.
     * See [dataPathBytes] and DATA_PATH_ALIVE_BYTES.
     */
    private var lastDataPathBytes = -1L

    // ---- 1.5.0 custom configs ---------------------------------------------
    /** The custom chain this session runs, or null for an ordinary session. */
    @Volatile
    private var customSelection: CustomSelection? = null
    private var sessionExitPort: Int = 0

    /** The running Xray hop of a custom chain, and the watchdog that restarts it. */
    @Volatile
    private var xray: XrayProcess? = null
    private var xrayWatch: Job? = null
    private val xrayLock = Any()
    private var xrayGeneration = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        live = this
        studio.cluvex.aether.core.SmartLists.attach(applicationContext) // 1.4.0 smart routing
        // FIRST STATEMENT, EVERY ACTION, NO EXCEPTIONS (1.3.0-r2 crash fix).
        //
        // ROOT CAUSE of "the app just closes" (ForegroundServiceDidNotStartInTime
        // Exception in AetherVpnService): every caller reaches this service through
        // ContextCompat.startForegroundService() -- AetherController.connect() AND
        // .disconnect(), the notification's Disconnect action, the tile and the
        // widget. Android's contract is per START REQUEST: each one must be
        // answered with startForeground() within 10 seconds or the framework kills
        // the process. The old code answered it only in the connect branch, so the
        // DISCONNECT branch returned without ever promoting the service and the
        // system killed the app about ten seconds later -- with the tunnel possibly
        // still up. Tapping Disconnect (or Cancel while it was verifying) was
        // therefore a guaranteed crash, not a rare race.
        //
        // Promoting first is also correct for the strict kill switch: that path
        // deliberately KEEPS the service alive with a blackhole TUN, so it needs a
        // foreground notification anyway. And it must never itself throw: a failure
        // to post the notification may not take the disconnect down with it.
        promoteToForeground(
            when (intent?.action) {
                ACTION_DISCONNECT -> getString(R.string.state_disconnecting)
                else -> getString(R.string.state_launching)
            },
        )
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                // STRICT KILL SWITCH (1.2.4): a manual disconnect must not
                // open a leak window. With strict mode on, the first
                // disconnect engages lockdown instead; disconnecting FROM
                // lockdown lifts it.
                val last = lastProfile
                when {
                    lockdownTunActive -> stopEverything()
                    last != null && last.strictKillSwitch -> enterLockdown(last)
                    else -> stopEverything()
                }
                return START_NOT_STICKY
            }
            else -> {
                val profile = ProfileCodec.decode(intent?.getStringExtra(EXTRA_PROFILE))
                startTunnel(profile)
            }
        }
        return START_STICKY
    }

    /**
     * Answers the current start request with [startForeground], swallowing any
     * failure.
     *
     * Idempotent by design: calling it when the service is already foreground just
     * refreshes the notification, which is exactly what a repeat start request
     * needs. Also used by [AetherApp]'s crash handler as a last-ditch rescue.
     */
    fun promoteToForeground(text: String) {
        lastNotifText = text
        try {
            startForeground(NOTIF_ID, buildNotification(text))
            foregroundActive = true
        } catch (t: Throwable) {
            // Anything here (a denied notification channel, an OEM restriction, a
            // start-not-allowed on Android 12+) must not turn into a second crash
            // on top of the first problem. The tunnel logic below still runs.
            DiagnosticsLog.w(TAG, "startForeground failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Puts the Zero Trust secrets back on the profile the Intent delivered.
     *
     * They are the two values [ProfileCodec] deliberately does not carry: an
     * organization credential must not travel inside an Intent extra. The
     * service reads them straight from the hardware-backed store instead, which
     * is also what makes the Zero Trust section work at all - before 1.2.7 the
     * enrolment fields never reached the engine in any form.
     */
    private fun hydrateSecrets(profile: ConnectionProfile): ConnectionProfile {
        if (profile.teamAuth == TeamAuth.OFF) return profile
        val secrets = SecretStore(applicationContext)
        return profile.copy(
            accessClientSecret = secrets.read(SecretStore.ACCESS_SECRET),
            accessToken = secrets.read(SecretStore.ACCESS_TOKEN),
            accessSignInToken = signInTokenFor(profile),
        )
    }

    /**
     * 1.4.0-r9 (issue #12): the token from "Sign in" under Settings -> Zero Trust,
     * or "" when this connect must not use one.
     *
     * Read here, by the service, from the sealed store - never from the Intent -
     * for the same reason as the two secrets above. [TeamSignInHandoff] makes the
     * decision (the Settings screen asks it the same question, so what the screen
     * promised is what happens); this only acts on it and says so in the log,
     * without the token.
     *
     * "" is always safe: the e-mail method then does exactly what it did before a
     * sign-in existed, i.e. the engine mails a code during the connect and
     * [studio.cluvex.aether.core.LoginCodePrompt] asks for it.
     */
    private fun signInTokenFor(profile: ConnectionProfile): String {
        if (profile.teamAuth != TeamAuth.EMAIL || !profile.hasTeam) return ""
        val store = TeamSignInStore(applicationContext)
        val decision = TeamSignInHandoff.decide(
            record = store.load(),
            rawTeam = profile.team,
            rawEmail = profile.accessEmail,
            isEnrolled = { team -> TeamMembership.isEnrolled(filesDir, team) },
        )
        return when (decision) {
            is TeamSignInHandoff.Decision.Use -> {
                DiagnosticsLog.i(
                    ZT_TAG,
                    "Using the sign-in made in Settings for team ${decision.record.team} " +
                        "(${decision.record.email}); no code will be asked for during this connect.",
                )
                decision.token
            }
            TeamSignInHandoff.Decision.NoSignIn -> ""
            TeamSignInHandoff.Decision.OtherProfile -> {
                DiagnosticsLog.w(
                    ZT_TAG,
                    "The saved Zero Trust sign-in is for another team or address and is not used; " +
                        "a code will be asked for during the connect.",
                )
                ""
            }
            TeamSignInHandoff.Decision.AlreadyEnrolled -> {
                store.clear()
                DiagnosticsLog.i(
                    ZT_TAG,
                    "This device is already enrolled in this team, so the saved sign-in is no " +
                        "longer needed and was removed.",
                )
                ""
            }
            TeamSignInHandoff.Decision.Unusable -> {
                store.clear()
                DiagnosticsLog.w(
                    ZT_TAG,
                    "The saved Zero Trust sign-in has expired and was removed; a code will be " +
                        "asked for during the connect. Sign in again in Settings to avoid that.",
                )
                ""
            }
        }
    }

    private fun startTunnel(rawProfile: ConnectionProfile) {
        val profile = hydrateSecrets(rawProfile)
        lastProfile = profile
        // 1.2.2 PROTOCOL-SWITCH FIX: this used to bail out silently whenever a
        // previous run coroutine was still winding down ("if active, return"),
        // so a connect tapped right after a disconnect — or right after
        // switching protocol — was simply DROPPED. The user then waited,
        // tapped again, and the app looked like it took forever to start.
        // Now the new session takes ownership: it waits for the old one to
        // finish, tears its natives down, and only then launches the engine.
        val previousRun = runJob
        val previousStop = stopJob
        runJob = scope.launch {
            if (previousRun != null) {
                // Same ordering rule as the disconnect path: cancel, kill the
                // natives (which unblocks the old session immediately), and
                // only then wait for it to finish. Joining first would stall
                // the new connect for as long as the old session's engine wait
                // still had to run.
                previousRun.cancel()
                cleanupNativeOnly("superseded-by-new-connect")
                stopCustom()
                previousRun.join()
            }
            previousStop?.join()
            try {
                connectFlow(profile)
            } catch (e: CancellationException) {
                ChainTrace.end("cancelled")
                throw e
            } catch (e: Exception) {
                // 1.6.0: this used to be the one failure path that wrote NOTHING to
                // the diagnostics log - the engine simply vanished from the timeline.
                DiagnosticsLog.e(
                    TAG,
                    "Connect failed: ${e.javaClass.simpleName}: ${e.message}" +
                        (e.cause?.message?.let { " (cause: $it)" } ?: ""),
                )
                ChainTrace.end("failed", "${e.javaClass.simpleName}: ${e.message}")
                AetherController.setState(
                    ConnectionState.Error(e.message ?: getString(R.string.state_error)),
                )
                updateNotification(getString(R.string.state_error))
                cleanupNativeOnly("connect-failed")
                stopCustom()
            }
        }
    }

    private suspend fun connectFlow(profile: ConnectionProfile) {
        DiagnosticsLog.clear()
        // 1.3.1, issues #27 and #52: decide this session's loopback ports BEFORE
        // anything reads them. The preferred numbers when they are free, which is
        // every normal install; the next free ones when another Aether instance in
        // a different Android profile already holds them. After DiagnosticsLog.clear
        // so the chosen set is recorded in THIS session's log, and before
        // resetChecks below, which asks for the exposed port.
        PortLease.acquire()
        // STALE-CIRCLES ROOT-CAUSE FIX: the self-test circles were only
        // reset inside Diagnostics.run(), which starts AFTER the engine has
        // launched AND finished its endpoint scan — so on a reconnect the
        // previous session's green circles sat on screen for the entire scan
        // and appeared to "reset late". Reset them the INSTANT a new connect
        // starts, so the panel always reflects the current attempt on time.
        // The circles must describe the port the FINISHED pipeline exposes: in a
        // chained session that is the second stage's listener, not the engine's.
        Diagnostics.resetChecks(SOCKS_HOST, effectiveSocksPort(profile))
        EngineMeta.reset()
        DiagnosticsLog.i(
            TAG,
            "Connect requested: backend=${profile.backend.pipelineLabel} " +
                "protocol=${profile.protocol} exit=${profile.exitRegion.ifBlank { "auto" }}",
        )

        // 1.5.0 CUSTOM CONFIGS. Only when the user switched a custom config on in
        // Settings -> Custom configs; otherwise customSelection is null, every
        // hook below is a no-op and the session runs exactly as it always has.
        val selectedTab = CustomConfigStore.load(applicationContext).activeTab
        var custom = CustomConfigStore.selection(applicationContext)
        // Empty/stale scope must never silently run the engine or another tab.
        if (CustomConfigStore.state.value.enabled && custom == null) {
            throw IllegalStateException(getString(R.string.cc_err_active_tab_empty))
        }
        // 1.7.0 (same version): FAST FIRST CONNECT. The old path measured every
        // config and only then connected, so one dead or slow server held the
        // whole connect for its full timeout. Now the measurement runs in the
        // background, the session starts on the fastest server that has ALREADY
        // answered, and the session monitor below switches to the overall
        // fastest one as soon as the full run has finished.
        var pendingRun: studio.cluvex.aether.custom.ConfigPingStore.Run? = null
        if (custom != null && CustomConfigStore.state.value.autoSelect) {
            AetherController.setState(ConnectionState.Selecting)
            updateNotification(getString(R.string.state_selecting))
            val (pick, firstRun) = chooseFirstCustom(custom!!.config)
            pendingRun = firstRun
            pick?.let {
                check(CustomConfigStore.selectInTab(this, it.id, selectedTab)) { getString(R.string.cc_err_active_tab_empty) }
                custom = CustomConfigStore.selection(this)
            }
            AetherController.setState(ConnectionState.Launching)
            updateNotification(getString(R.string.state_launching))
        }
        check(CustomConfigStore.state.value.activeTab == selectedTab) { getString(R.string.cc_err_active_tab_empty) }
        customSelection = custom
        // 1.6.0 CHAIN TRACE: one timeline per connect; every stage, failure and
        // native teardown below is recorded with its offset from this point.
        ChainTrace.begin(
            sessionEpoch.incrementAndGet(),
            profile.backend.pipelineLabel +
                (custom?.let { " + custom ${it.config.protocol.short} [${it.mode.label(it.config.chainLabel)}]" } ?: ""),
        )
        CustomSession.set(null)
        if (custom != null) {
            var selection = custom
            var failedAttempts = 0
            while (currentScopeActive()) {
                var switched = false
                try {
                    kotlinx.coroutines.coroutineScope {
                        val session = launch { connectCustom(profile, selection!!) }
                        val fullRun = pendingRun
                        pendingRun = null
                        val monitor = launch {
                            val decide = kotlinx.coroutines.sync.Mutex()
                            // One decision at a time; the first switch ends the session.
                            suspend fun consider(reason: String, measure: Boolean, eager: Boolean) {
                                decide.withLock {
                                    if (switched || !CustomConfigStore.state.value.autoSelect) return@withLock
                                    val current = selection!!.config
                                    val best = (if (measure) chooseBestCustom(current, eager = eager)
                                        else chooseCachedCustom(current, eager)) ?: return@withLock
                                    if (best.id == current.id) return@withLock
                                    if (!CustomConfigStore.selectInTab(this@AetherVpnService, best.id, selectedTab)) return@withLock
                                    DiagnosticsLog.i(TAG, "Auto-select ($reason): switching to the faster config ${best.name}")
                                    switched = true
                                    session.cancel()
                                }
                            }
                            // 1. Periodic health check (unchanged behaviour, connected only).
                            launch {
                                while (true) {
                                    delay(CustomConfigStore.state.value.healthIntervalSeconds * 1_000L)
                                    if (AetherController.state.value !is ConnectionState.Connected) continue
                                    consider("health check", measure = true, eager = false)
                                }
                            }
                            // 2. The full run behind a fast first connect.
                            if (fullRun != null) launch {
                                runCatching { fullRun.done.await() }
                                consider("all configs measured", measure = false, eager = true)
                            }
                            // 3. The user pressed "Test all": switch to the fastest right away.
                            launch {
                                studio.cluvex.aether.custom.ConfigPingStore.completed.collect { run ->
                                    if (run.origin == studio.cluvex.aether.custom.ConfigPingStore.RunOrigin.MANUAL &&
                                        selection!!.config.id in run.ids) {
                                        consider("manual ping test", measure = false, eager = true)
                                    }
                                }
                            }
                            // 4. Wi-Fi <-> mobile data, another Wi-Fi or another SIM: measure again.
                            launch {
                                studio.cluvex.aether.custom.UnderlyingNetworks.changes(this@AetherVpnService).collect {
                                    DiagnosticsLog.i(TAG, "Network changed - measuring the configs again for auto-select")
                                    consider("network changed", measure = true, eager = false)
                                }
                            }
                        }
                        try { session.join() } finally { monitor.cancel(); monitor.join() }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    if (!CustomConfigStore.state.value.autoSelect || ++failedAttempts > 3) throw e
                    delay(2_000L)
                    val best = chooseBestCustom(selection!!.config, excludeCurrent = true) ?: throw e
                    check(CustomConfigStore.selectInTab(this, best.id, selectedTab)) { getString(R.string.cc_err_active_tab_empty) }
                    switched = true
                }
                if (!switched) return
                AetherController.setState(ConnectionState.Reconnecting(1, 1))
                cleanupNativeOnly("custom-auto-switch")
                stopCustom()
                selection = CustomConfigStore.selection(this) ?: return
                customSelection = selection
            }
            return
        }
        connectEngineFlow(profile)
    }

    /**
     * The engine part of every session: the six [TransportBackend] modes. This is
     * the body [connectFlow] always had; a custom chain runs it unchanged, with
     * the custom config in front of it (the engine's upstream) or behind its exit
     * (see [customExit]).
     */
    private suspend fun connectEngineFlow(profile: ConnectionProfile) {
        if (profile.backend.usesExternal) {
            connectExternal(profile)
            return
        }

        // The two modes whose device traffic leaves through Tor. They cannot take
        // the plain path below for one reason that is a property of the protocol
        // rather than of this app: Tor carries TCP only, and hev sends every UDP
        // flow - so every DNS query - as SOCKS5 UDP ASSOCIATE. See connectTor.
        if (profile.backend.needsTorFront) {
            connectTor(profile)
            return
        }

        // The reverse chain needs no front and no second stage: what it exposes on
        // the usual port is a full WARP tunnel that happens to have been dialled
        // through Tor, so it carries UDP and DNS like any other tunnel. It takes the
        // plain path below - only the connect budget and the transport differ, and
        // both of those are decided in connectAetherStage's plan.
        if (profile.backend.torMode == TorMode.REVERSE) {
            AetherController.setState(ConnectionState.Connecting)
            updateNotification(getString(R.string.state_tor_bootstrap))
        }

        val resolved: ConnectionProfile =
            if (profile.backend.torMode == TorMode.REVERSE) {
                // ------------------------------------------------------------------
                // Tor -> Aether NEVER CONNECTED, in 1.3.0 and 1.3.1. This is the fix.
                //
                // The comment above claims the budget and transport for the reverse
                // chain "are decided in connectAetherStage's plan" - and that plan
                // does contain exactly the right rung. But connectAetherStage is only
                // ever called from connectExternal, i.e. the CHAINED Psiphon path,
                // which this backend is not. With protocol=AUTO the reverse chain
                // therefore fell into connectSmartAuto below and got the ordinary
                // four-rung endpoint-scan ladder. The field log shows what that costs:
                //
                //   Strategy ladder for OPEN (4 steps)
                //   Attempt 1/4 -> WIREGUARD ... ranges[188.114.97.0/24, ...]
                //   [+] tor is ready; the tunnel goes out through 127.0.0.1:1820
                //   [*] scan mode=turbo candidates=896 ports=[443,500,1701,4500,...]
                //       concurrency=20 per_probe=6s budget=45s
                //   [+] dialling out through the socks5 proxy at 127.0.0.1:1820
                //   [-] scan deadline reached with no gateway
                //
                // Tor bootstrapped perfectly every time - 100%, "the way out is
                // open". What cannot work is the SCAN: 896 candidates across seven
                // ports, twenty at a time, in 45 seconds, with every probe a fresh
                // Tor circuit. Tor exit policies do not even permit most of those
                // ports, and the /24 ranges came from a DPI fingerprint measured on
                // the DIRECT path, so they say nothing about what a Tor exit can
                // reach. Three rungs, three bootstraps, no gateway, and the engine's
                // SOCKS5 port never opened.
                //
                // Through Tor there is nothing to scan for: the engine dials one
                // endpoint out through the SOCKS proxy. So this mode gets ONE
                // candidate on the only carrier core 2.0.0 accepts here - MASQUE over
                // HTTP/2, because Tor is TCP-only and WARP's WireGuard endpoints are
                // UDP-only - with a budget sized for a Tor bootstrap rather than for
                // an endpoint scan, and with the scan narrowed to the quickest mode
                // so the engine stops hunting and commits.
                //
                // Same rung connectAetherStage already builds; it just has to be
                // reachable from the unchained path too.
                AetherController.setState(ConnectionState.Connecting)
                updateNotification(getString(R.string.state_tor_bootstrap))
                runLadder(reversePlan(profile), getString(R.string.err_protocol_failed))
            } else if (studio.cluvex.aether.custom.CustomChainPolicy.usesTcpCarrier(profile, customSelection?.mode)) {
                AetherController.setState(ConnectionState.Connecting)
                // 1.6.0 Config -> Aether (field log 2026-10-15): two rungs behind a TCP
                // carrier instead of one pass in the user's scan mode; see
                // [customUpstreamPlan] for why the scan SHAPE is what failed.
                try {
                    runLadder(customUpstreamPlan(profile), getString(R.string.err_protocol_failed))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalStateException) {
                    DiagnosticsLog.e(
                        TAG,
                        "No WARP gateway is reachable THROUGH this custom config. The config itself " +
                            "passed its readiness check; the hop behind it is what fails. A config whose " +
                            "exit is itself behind a CDN (e.g. a Cloudflare-fronted WebSocket/gRPC VLESS) " +
                            "often cannot reach Cloudflare's MASQUE gateways at all - use a config with a " +
                            "plain VPS exit for this chain, or run the config SOLO / as the LAST hop.",
                    )
                    throw e
                }
            } else if (profile.protocol == Protocol.AUTO) {
                // ONE automatic mode, two implementations, chosen by what the
                // backend allows. The race needs two engine processes with two
                // identity files and two ports of its own, which only the plain
                // Aether backend can give it - see SmartPlusPlan.eligible. Every
                // other backend gets the ladder, which is what it always got.
                //
                // 1.3.1-r4: this used to be a second entry in the protocol
                // selector ("Smart Plus"). It was removed because the split was
                // invisible where it mattered - the selector offered a mode that
                // silently behaved like Smart on every chained backend - and
                // because a hand-picked-protocol path was resolving AUTO_PLUS as
                // if it were a concrete protocol, which produced an engine command
                // line with no protocol flag at all and a chained session that
                // could not connect.
                if (SmartPlusPlan.eligible(profile)) {
                    connectSmartPlus(profile)
                } else {
                    connectSmartAuto(profile)
                }
            } else {
                // An explicitly chosen protocol keeps that protocol; the
                // engine still selects its own endpoint (see [directPlan]).
                AetherController.setState(ConnectionState.Launching)
                runLadder(directPlan(profile), getString(R.string.err_protocol_failed))
            }

        // Desktop-parity info row (1.2.4): publish the protocol that actually
        // won (Smart Auto resolves AUTO to a concrete protocol). The endpoint
        // arrives through EngineMeta's engine-log parser; for a pinned peer we
        // already know it here (no selection line is logged).
        EngineMeta.setProtocol(resolved.protocol.name)
        if (resolved.manualPeer.isNotBlank()) EngineMeta.setEndpoint(resolved.manualPeer)

        ChainTrace.end("ok", "exit=$SOCKS_HOST:${sessionExitPort.takeIf { it > 0 } ?: SOCKS_PORT}")
        AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:${sessionExitPort.takeIf { it > 0 } ?: SOCKS_PORT}"))
        updateNotification(getString(R.string.state_connected))
        DiagnosticsLog.i(TAG, "Tunnel is ready; configured initial verification policy applied.")

        superviseEngine(resolved)
    }

    /**
     * Drives a CHAINED session (`Aether -> Psiphon`), then reuses Aether's
     * existing TUN/hev path. The order is the whole point:
     *
     * ```
     *   stage 1  Aether engine     -> SOCKS5 127.0.0.1:1819   (no TUN yet!)
     *   stage 2  Psiphon           -> SOCKS5 127.0.0.1:1827, dialling via 1819
     *   front    PsiphonSocksFront -> SOCKS5 127.0.0.1:1825
     *   then     TUN + tun2socks   -> 1825                    (exit = Psiphon)
     * ```
     *
     * Stage 1 must NOT build the TUN, because stage 2 has to reach the engine
     * over loopback while the engine itself still reaches the internet over the
     * phone's real network. That works because the TUN, once built, excludes this
     * package (see [applyAppFilter]).
     *
     * The FRONT is what tun2socks is handed, never the transport's own listener:
     * the front is the only listener here that answers SOCKS5 `UDP ASSOCIATE`,
     * and without that the device resolves no names at all. The transport reports
     * its front's port from `start()`, so this method just follows it.
     *
     * There is no longer an unchained branch: the single-hop PSIPHON and TOR
     * backends were removed in 1.2.7 because they could not get their own first
     * hop past the networks this app exists for, and Tor was dropped altogether
     * in the same release, so Psiphon is the only external stage left.
     */
    private suspend fun connectExternal(profile: ConnectionProfile) {
        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        cleanupNativeOnly("external-prestart")

        // 1.4.0-r1: the engine's Psiphon needs a CA bundle and its embedded server
        // list on disk BEFORE stage 1 launches the engine that spawns it. See
        // PsiphonBootstrap for the field log this answers.
        withContext(Dispatchers.IO) { PsiphonBootstrap.prepare(this@AetherVpnService) }

        val stageProfile: ConnectionProfile = connectAetherStage(profile)

        val transport = ExternalTransportFactory.create(this, profile) { engine?.isAlive() == true }
        externalTransport = transport

        // 1.4.0 smart routing (#66): in this mode the engine only ever sees
        // Psiphon's own server connections, so the block/direct rules - the
        // user's plus the built-in lists, direct ones dropped while sharing - are
        // applied by the Psiphon front, the one place the destination is visible.
        // Installed before the front starts; the front clears them on stop.
        val frontRoutes = withContext(Dispatchers.IO) {
            studio.cluvex.aether.core.SmartLists.frontRules(profile)
        }
        studio.cluvex.aether.transport.PsiphonSocksFront.setRoutes(frontRoutes)

        // Never let the new listener race a dying one. The single-hop path used
        // to skip this and Psiphon would silently bind a random port because
        // 1819 was still held by the previous session -- which the old
        // `check(port == SOCKS_PORT)` then turned into a hard failure.
        val wantedPort = effectiveSocksPort(profile)
        if (!PortProbe.awaitClosed(SOCKS_HOST, wantedPort, PORT_RELEASE_WAIT_MS)) {
            DiagnosticsLog.w(
                TAG,
                "Local port $wantedPort is still busy after ${PORT_RELEASE_WAIT_MS / 1000}s - starting anyway.",
            )
        }

        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        // The transport reports the port it ACTUALLY bound. Following it instead
        // of demanding one is what makes a chained session possible at all, and
        // it turns a port clash from a failed connection into a logged warning.
        val enginePort = try {
            transport.start()
        } catch (e: PsiphonRegionFallback) {
            // 1.4.0: the engine's Psiphon treats the exit country as a HARD
            // filter. Rebuild the chain once with an automatic exit instead of
            // leaving the user on "Connecting" (same promise as before 1.4.0).
            DiagnosticsLog.w(TAG, e.message ?: "Psiphon could not serve the chosen exit - retrying automatically.")
            runCatching { transport.stop() }
            externalTransport = null
            cleanupNativeOnly("psiphon-region-fallback")
            connectExternal(profile.copy(exitRegion = ""))
            return
        }
        if (enginePort != wantedPort) {
            DiagnosticsLog.w(TAG, "${profile.backend.pipelineLabel} exposed SOCKS5 on $enginePort (expected $wantedPort).")
        }
        // 1.5.0: a `... -> Config` chain puts the custom config behind this exit.
        val port = customExit(enginePort)

        // 1.2.9-r3 (F-5): the LAN listeners authenticate remote clients, so the
        // credential has to be loaded BEFORE they bind. Synchronous on purpose -
        // one keystore-backed decrypt on a background coroutine - because the
        // bridge otherwise falls back to an ephemeral password the user cannot see.
        if (profile.lanShare) runCatching { ShareCredentials.ensure(this) }

        if (profile.proxyMode) {
            check(ShareBridge.startSync(localOnly = !profile.lanShare, upstreamPort = port)) {
                getString(R.string.err_proxy_ports)
            }
        } else {
            establishTun(profile)
            startTun2Socks(profile, port)
            if (profile.lanShare) ShareBridge.start(localOnly = false, upstreamPort = port)
        }

        val diagPort = if (profile.proxyMode) ShareBridge.socksPort.value ?: port else port
        val healthy = probeOrFalse { smartGateFinal(profile, diagPort, Diagnostics.EXTERNAL_GRACE_MS) }
        check(healthy) { getString(R.string.err_selftest) }

        // 1.3.1-r2 LABEL FIX: the ENGINE's transport only, never the pipeline.
        //
        // This used to publish "WIREGUARD \u2192 PSIPHON" - the whole chain - because
        // before 1.3.1 the info row printed this value raw and had no other way to
        // say that a second hop existed. Since 1.3.1 the row asks
        // [TransportBackend.pipelineLabel] for the path and puts THIS value in the
        // brackets, so the old string produced
        // "Aether(WIREGUARD \u2192 PSIPHON) \u2192 Psiphon": the arrow and the exit named
        // twice, once inside the brackets where only the transport belongs.
        EngineMeta.setProtocol(stageProfile.protocol.name)
        // The latency badge must measure the port the WHOLE pipeline exposes. In a
        // chained session that is the front (stage 2's exit), not the engine's own
        // listener - probing 1819 there measured stage 1 alone and reported a
        // number that had nothing to do with what the user was browsing through.
        PingMonitor.setTunnelPort(port)
        // The warm probe session belongs to the OLD pipeline; a retarget or a
        // server rotation invalidates it. Dropping it here costs one dial and
        // stops the badge reporting a dead connection's timeout as latency.
        PingMonitor.reset()
        ChainTrace.end("ok", "exit=$SOCKS_HOST:$port")
        AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:$port"))
        updateNotification(getString(R.string.state_connected))
        DiagnosticsLog.i(
            TAG,
            "${profile.backend.pipelineLabel} tunnel ready, exit=${profile.exitRegion.ifBlank { "automatic" }}",
        )

        // Supervise BOTH hops. A chained session is only as alive as its weakest
        // stage, and a dead stage 1 would leave stage 2 holding a proxy that
        // cannot dial -- "connected" with nothing moving.
        //
        // 1.2.7 STABILITY: the transport is required to read dead on
        // TRANSPORT_DEAD_CONFIRMATIONS CONSECUTIVE checks before the session is
        // torn down. A single false reading used to be enough, and the external
        // stage briefly reports not-alive on purpose while it moves itself onto a
        // different exit server -- so the app tore a perfectly recoverable
        // session down mid-rotation and the user saw a sudden disconnect.
        // PsiphonTransport already masks its own rotation window; this is the
        // second, dumber net underneath it, and it costs one extra second of
        // patience in the case that really is dead.
        // 1.2.8 MEDIA-STALL FIX: liveness is not health. Everything above only
        // ever asked "is the Psiphon library still running?", and it always was
        // -- the stall the user reported (fine for a minute, then a video pins
        // the ping at ~2000 ms and the session carries nothing at all, while
        // both stages and every port stay up) is invisible to that question. So
        // a chained session now ALSO gets the end-to-end probe, aimed at the
        // pipeline's own listener, which is the exact path the user's traffic
        // takes. Two consecutive dead readings rebuild the session instead of
        // leaving the user to toggle the switch by hand.
        var transportDead = 0
        var pipelineDead = 0
        var nextPipelineProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
        while (currentScopeActive() && engine?.isAlive() == true) {
            if (transport.isAlive()) {
                transportDead = 0
            } else if (++transportDead >= TRANSPORT_DEAD_CONFIRMATIONS) {
                break
            } else {
                DiagnosticsLog.i(
                    TAG,
                    "${profile.backend.pipelineLabel} stage 2 is not answering " +
                        "($transportDead/$TRANSPORT_DEAD_CONFIRMATIONS) - giving it a moment " +
                        "before rebuilding the session.",
                )
            }

            if (SystemClock.elapsedRealtime() >= nextPipelineProbe) {
                // Sampled on EVERY probe decision so the delta always covers the
                // interval since the last one, success or failure.
                val dataPathMoving = dataPathIsMoving()
                if (probeTunnelCycle(port)) {
                    if (pipelineDead > 0) {
                        DiagnosticsLog.i(
                            TAG,
                            "${profile.backend.pipelineLabel} is carrying traffic again.",
                        )
                    }
                    pipelineDead = 0
                    nextPipelineProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
                } else if (dataPathMoving) {
                    // 1.2.8-r3: A PROBE IS AN OPINION, THE METER IS A FACT.
                    //
                    // The r2 log shows the old code tearing down a session that
                    // was moving hundreds of kilobytes (Psiphon's own
                    // TotalBytesTransferred: received 703150, sent 385706)
                    // because one probe destination timed out. A path carrying
                    // traffic is not wedged, by definition, so the verdict is
                    // now gated on the tunnel core's byte counters and a probe
                    // failure alone can never rebuild anything.
                    if (pipelineDead > 0) pipelineDead = 0
                    DiagnosticsLog.i(
                        TAG,
                        "${profile.backend.pipelineLabel} probe did not answer but the data " +
                            "path is still carrying traffic - not a wedge, leaving the session " +
                            "alone.",
                    )
                    nextPipelineProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
                } else {
                    pipelineDead++
                    DiagnosticsLog.w(
                        TAG,
                        "${profile.backend.pipelineLabel} is up but carries nothing " +
                            "($pipelineDead/$PIPELINE_DEAD_CONFIRMATIONS).",
                    )
                    if (pipelineDead >= PIPELINE_DEAD_CONFIRMATIONS) {
                        DiagnosticsLog.w(TAG, "Rebuilding the session: the data path is wedged.")
                        break
                    }
                    // Re-check sooner while it looks broken.
                    nextPipelineProbe =
                        SystemClock.elapsedRealtime() + PIPELINE_PROBE_RETRY_MS
                }
            }

            delay(1_000L)
        }
        if (currentScopeActive()) {
            if (engine?.isAlive() != true) {
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            throw IllegalStateException("${profile.backend.pipelineLabel} transport stopped")
        }
    }

    /**
     * Drives a session whose exit is Tor: `Tor` and `Aether -> Tor`.
     *
     * ```
     *   Tor              engine --tor-only  -> SOCKS5 127.0.0.1:1819 IS Tor
     *   Aether -> Tor    engine --tor       -> 1819 stays WARP, Tor on 1820
     *   both             TorSocksFront      -> SOCKS5 127.0.0.1:1821
     *   then             TUN + tun2socks    -> 1821                 (exit = Tor)
     * ```
     *
     * The front is not optional and it is not a nicety. hev-socks5-tunnel carries
     * UDP over SOCKS5 `UDP ASSOCIATE`, a Tor proxy answers `CONNECT` only, and
     * every DNS query on Android is UDP - so handing tun2socks a Tor listener
     * directly produces a session that establishes, verifies, reports a Tor exit
     * and opens nothing at all. That is the failure the 1.2.7 Tor backend shipped,
     * and [TorSocksFront] is where it is answered.
     *
     * `Aether -> Tor` is the mode to use on a network that blocks Tor: the guards
     * are dialled through the tunnel, so the operator never sees Tor traffic and
     * the bootstrap runs at tunnel speed. Plain `Tor` has to reach the network by
     * itself and turns to bridges when it cannot (see [ConnectionProfile.torBridges]).
     */
    private suspend fun connectTor(profile: ConnectionProfile) {
        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        cleanupNativeOnly("tor-prestart")

        val engineProfile = connectAetherStage(profile)

        // Where the engine put Tor. In --tor-only mode this is the engine's only
        // listener and the stage gate above already proved it carries TCP; with
        // Tor inside the tunnel it is a second listener that appears once Tor has
        // bootstrapped THROUGH the tunnel, which is why it is waited for
        // separately and with a budget measured in minutes rather than seconds.
        val torPort = profile.backend.torSocksPort
            ?: error("${profile.backend} has no Tor listener")
        if (torPort != SOCKS_PORT) {
            AetherController.setState(ConnectionState.Connecting)
            updateNotification(getString(R.string.state_tor_bootstrap))
            DiagnosticsLog.i(
                TAG,
                "Waiting for Tor on $SOCKS_HOST:$torPort - the first bootstrap downloads " +
                    "the directory and can take a few minutes.",
            )
            val up = PortProbe.awaitOpen(SOCKS_HOST, torPort, TOR_BOOTSTRAP_TIMEOUT_MS) {
                engine?.isAlive() == true
            }
            if (!up) {
                DiagnosticsLog.e(TAG, "Tor never opened its listener on $torPort.")
                throw IllegalStateException(getString(R.string.err_tor_bootstrap))
            }
            DiagnosticsLog.i(TAG, "Tor is up on $SOCKS_HOST:$torPort.")
        }

        if (!PortProbe.awaitClosed(SOCKS_HOST, TOR_FRONT_PORT, PORT_RELEASE_WAIT_MS)) {
            DiagnosticsLog.w(
                TAG,
                "Local port $TOR_FRONT_PORT is still busy after ${PORT_RELEASE_WAIT_MS / 1000}s - " +
                    "starting anyway.",
            )
        }
        // 1.5.0: a `... -> Config` chain puts the custom config behind this exit.
        val frontPort = customExit(TorSocksFront.start(TOR_FRONT_PORT, SOCKS_HOST, torPort))

        if (profile.lanShare) runCatching { ShareCredentials.ensure(this) }

        if (profile.proxyMode) {
            check(ShareBridge.startSync(localOnly = !profile.lanShare, upstreamPort = frontPort)) {
                getString(R.string.err_proxy_ports)
            }
        } else {
            establishTun(profile)
            startTun2Socks(profile, frontPort)
            if (profile.lanShare) ShareBridge.start(localOnly = false, upstreamPort = frontPort)
        }

        val diagPort = if (profile.proxyMode) ShareBridge.socksPort.value ?: frontPort else frontPort
        // The same grace an external stage gets. A Tor circuit is built lazily per
        // stream, so the first request through a freshly bootstrapped Tor is slow
        // in a way that says nothing about whether the session works.
        val healthy = probeOrFalse { smartGateFinal(profile, diagPort, Diagnostics.EXTERNAL_GRACE_MS) }
        check(healthy) { getString(R.string.err_selftest) }

        // Same fix as in the external-chain path above: the transport alone.
        // `usesWarp` decides whether there is an Aether hop to name at all, and
        // [TransportBackend.pipelineLabel] already consults it - a `--tor-only`
        // mode renders as "Tor" / "Tor \u2192 Psiphon" with nothing in brackets, so
        // the value published here is simply unused there.
        EngineMeta.setProtocol(engineProfile.protocol.name)
        PingMonitor.setTunnelPort(frontPort)
        PingMonitor.reset()
        ChainTrace.end("ok", "exit=$SOCKS_HOST:$frontPort")
        AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:$frontPort"))
        updateNotification(getString(R.string.state_connected))
        DiagnosticsLog.i(TAG, "${profile.backend.pipelineLabel} tunnel ready (exit = a Tor exit node).")
        TorSocksFront.dropSummary()?.let { DiagnosticsLog.i(TAG, it) }

        superviseEngine(engineProfile)
    }

    /**
     * Chained stage 1: run the Aether engine until its local SOCKS5 port is
     * genuinely carrying TCP, and stop there.
     *
     * Reuses the existing Smart Auto / hand-picked ladders unchanged, so a
     * chained session gets exactly the same DPI fingerprinting, protocol
     * hardening and retry behaviour as a plain Aether one. Returns the strategy
     * that won.
     */
    private suspend fun connectAetherStage(profile: ConnectionProfile): ConnectionProfile {
        DiagnosticsLog.i(
            TAG,
            "Stage 1 = engine on $SOCKS_HOST:$SOCKS_PORT" +
                (profile.backend.torMode?.let { " (Tor: $it)" } ?: "") +
                ", stage 2 = ${profile.backend.externalKind?.name ?: "TorSocksFront"} behind its " +
                "UDP-capable front on $SOCKS_HOST:${profile.backend.exposedSocksPort}",
        )
        // Stage 1 owns neither the TUN nor the share bridge: those belong to the
        // finished chain, and letting stage 1 build them would capture the
        // engine's own traffic and deadlock the tunnel inside itself.
        // Which engine mode stage 1 runs in. Overwriting this with plain AETHER -
        // which is what a chained Psiphon session needs - would strip `--tor` or
        // `--tor-only` from the engine's argv and silently produce a session with
        // no Tor in it at all, i.e. the right exit type replaced by the wrong one
        // with nothing in the log to say so.
        val stageBackend = when (profile.backend.torMode) {
            null -> TransportBackend.AETHER
            TorMode.CHAIN -> TransportBackend.AETHER_TOR
            TorMode.ONLY -> TransportBackend.TOR
            TorMode.REVERSE -> TransportBackend.TOR_AETHER
        }
        val stage = profile.copy(
            backend = stageBackend,
            proxyMode = false,
            lanShare = false,
            // 1.4.0-r5 zero-leak sharing: stage 1 is the engine the finished
            // chain's share traffic ultimately rides on, so when the session
            // shares to the LAN it must carry no direct (bypass) rules either.
            // (lanShare = false above would otherwise let toArgs() pass them.)
            //
            // 1.4.0 smart routing (#66 follow-up): in a Psiphon mode stage 1 only
            // ever carries Psiphon's own server connections - the user's rules are
            // applied by PsiphonSocksFront (see connectExternal). Handing them to
            // stage 1 as well could only misfire there: a direct rule covering a
            // Psiphon server address would send Psiphon out WITHOUT Aether, and a
            // block rule could cut Psiphon off. So in that mode stage 1 gets none
            // (the built-in lists are already withheld via enginePsiphon, see
            // SmartLists.engineRoutesFile). Tor modes keep them: there the engine
            // does see the device's destinations.
            routeBlock = if (profile.backend.usesExternal) "" else profile.routeBlock,
            routeDirect = if (profile.lanShare || profile.backend.usesExternal) "" else profile.routeDirect,
            // A chained session pays this hop's latency on every packet and then
            // again inside Psiphon's own hop, so the cached-endpoint budget is
            // tighter here than for a plain Aether session. See
            // ConnectionProfile.chainedStage.
            chainedStage = true,
            // 1.4.0: Psiphon runs inside the engine, so stage 1 is started with it.
            enginePsiphon = profile.backend.usesExternal,
        )
        val plan = when {
            // --tor-only brings up no tunnel, so there is no endpoint to scan, no
            // transport to obfuscate and nothing for a protocol ladder to try:
            // every rung would be the same engine invocation. One attempt, with a
            // budget sized for a Tor bootstrap instead of an endpoint scan.
            !stage.backend.usesWarp -> listOf(
                AutoCandidate(stage, torBudget(stage), "Tor \u00b7 direct or via bridges"),
            )
            // The reverse chain: one attempt, on the only transport it can use, with
            // a Tor-sized budget. A protocol ladder here would be actively harmful -
            // the engine refuses --wg and --gool in this mode, so the rungs that try
            // them would fail instantly and burn the budget Tor needs to bootstrap.
            stage.backend.torMode == TorMode.REVERSE -> listOf(
                AutoCandidate(
                    stage.copy(protocol = Protocol.MASQUE, masqueHttp2 = true),
                    torBudget(stage),
                    "MASQUE \u00b7 h2 \u00b7 through Tor",
                ),
            )
            // 1.6.0: `Config -> Aether -> Psiphon|Tor` - the engine is dialled through
            // a TCP-only carrier, so the UDP rungs of the AUTO ladder can never work.
            studio.cluvex.aether.custom.CustomChainPolicy.usesTcpCarrier(stage, customSelection?.mode) ->
                customUpstreamPlan(stage)
            stage.protocol == Protocol.AUTO -> {
                AetherController.setState(ConnectionState.Launching)
                updateNotification(getString(R.string.state_analyzing))
                SmartAuto.buildPlan(
                    stage,
                    SmartAuto.fingerprint(this),
                    onMobileData = NetworkIdentity.onMobileData(this),
                    memory = SmartPlusMemory.recall(this, NetworkIdentity.key(this)),
                )
            }
            else -> directPlan(stage)
        }
        val resolved = runLadder(plan, getString(R.string.err_protocol_failed), stageOnly = true)
        DiagnosticsLog.i(TAG, "Stage 1 up (${resolved.protocol.name}) - handing the exit to stage 2.")
        return resolved
    }

    /**
     * Time budget for one tor-fronted attempt: long enough for the engine's own
     * bridge fallback when bridges are permitted, the plain bootstrap budget when
     * they are not. Paired with the stall detector in [connectAttempt], which is
     * what keeps the long budget from turning into a long silence.
     */
    private fun torBudget(stage: ConnectionProfile): Long =
        if (stage.torBridges != TorBridges.OFF) TOR_BRIDGE_BUDGET_MS else TOR_BOOTSTRAP_TIMEOUT_MS

    /**
     * The one and only connect attempt for `Tor -> Aether` (`--tor-reverse`).
     *
     * Not a ladder, on purpose. See the long note at the call site in
     * [connectFlow] for the field log this answers; the short version is that the
     * endpoint SCAN is what fails in this mode, not Tor, and no number of rungs
     * fixes a scan that cannot work.
     *
     * Three things are pinned here:
     *
     *  * **MASQUE over HTTP/2.** The only carrier core 2.0.0 accepts in the reverse
     *    chain: Tor is TCP-only and WARP's WireGuard endpoints are UDP-only, so
     *    `--wg` and `--gool` are refused outright. The engine is spawned with
     *    `--masque` regardless of what the user picked; sending their WireGuard
     *    choice anyway is what made the log say `Attempt 1/4 -> WIREGUARD` while the
     *    command line said `--masque`.
     *  * **TURBO scan.** The shortest scan the engine has: 45 s, first answer
     *    wins, Cloudflare's known gateways (MASQUE_SEEDS) probed first, every probe
     *    carried over h2 through Tor. It is still a scan, not a single forced peer
     *    (r8: the old wording here said otherwise); what fixed the field log is the
     *    Tor-sized budget below and the engine waiting for Tor before it starts.
     *  * **No manual ranges.** `endpointMode` and `manualRange` are cleared. Those
     *    ranges come from a DPI fingerprint measured on the DIRECT path; what this
     *    phone can reach has no bearing on what a Tor exit can reach, and feeding
     *    them in is what narrowed the scan onto two /24s that the exit could not
     *    get to.
     *
     * The budget is [torBudget] - minutes, sized for a directory fetch and a circuit
     * build - because the 60 s scan timeout the ordinary ladder uses expires while
     * Tor is still bootstrapping.
     */
    private fun reversePlan(profile: ConnectionProfile): List<AutoCandidate> = listOf(
        AutoCandidate(
            profile.copy(
                protocol = Protocol.MASQUE,
                masqueHttp2 = true,
                scanMode = ScanMode.TURBO,
                endpointMode = EndpointMode.AUTO,
                manualRange = "",
            ),
            torBudget(profile),
            "MASQUE \u00b7 h2 \u00b7 through Tor",
        ),
    )

    /**
     * 1.6.0: the engine BEHIND a custom config (`Config -> Aether` and every chain
     * CustomChainPolicy.usesTcpCarrier says yes to) climbs two rungs instead of one.
     *
     * Field log 2026-10-15: the upstream mapping was right (the engine dialled
     * 127.0.0.1:18830 and registered WARP through it) and the budget was right
     * (180 s service wait > 120 s engine scan). What cannot work is the SCAN SHAPE:
     * BALANCED is sized for a direct UDP path - 2011 candidates, four at a time,
     * 20 s each. Through a TCP carrier every probe is a proxied TCP+TLS+h2
     * CONNECT-IP handshake, so four-wide coverage reaches about 1 % of the space
     * before any budget ends. The reverse chain hit the same wall in 1.3.x and was
     * fixed the same way (see [reversePlan]): the quickest scan the engine has,
     * known gateways first, first verified answer wins, no DPI-derived ranges
     * (those were measured on the DIRECT path and say nothing about what the
     * config's exit can reach).
     *
     * Rung 1 is that quick scan on a capped budget; rung 2 is the user's own scan
     * mode on the full chain budget, so nothing that used to work is lost. A user
     * already on TURBO gets the single full-budget rung as before.
     */
    private fun customUpstreamPlan(profile: ConnectionProfile): List<AutoCandidate> {
        val fullBudget = studio.cluvex.aether.custom.CustomChainPolicy.connectBudgetMs(profile)
        val behindConfig = profile.copy(endpointMode = EndpointMode.AUTO, manualRange = "")
        if (profile.scanMode == ScanMode.TURBO) {
            return listOf(
                AutoCandidate(behindConfig, fullBudget, "MASQUE · h2 · through custom config · turbo scan"),
            )
        }
        return listOf(
            AutoCandidate(
                behindConfig.copy(scanMode = ScanMode.TURBO),
                fullBudget.coerceAtMost(UPSTREAM_QUICK_PASS_MAX_MS),
                "MASQUE · h2 · through custom config · quick scan (known gateways first)",
            ),
            AutoCandidate(
                behindConfig,
                fullBudget,
                "MASQUE · h2 · through custom config · ${profile.scanMode.name.lowercase()} scan",
            ),
        )
    }

    /** upstreamProxy as a trimmed, never-null string, whatever its declared nullability. */
    private fun upstreamOf(profile: ConnectionProfile): String =
        listOfNotNull(profile.upstreamProxy).joinToString("").trim()

    /**
     * SMART MODE (1.7.0 rewrite): test every protocol fairly, measure the ones
     * that work, connect the best.
     *
     * 1. FINGERPRINT the network ([SmartAuto.fingerprint]) - DPI class, UDP and
     *    QUIC reachability - only to ORDER tactics, never to exclude a family.
     * 2. RACE ([runRace]): one lane per identity family (WARP / MASQUE), both
     *    starting at the same instant with the same budgets; each lane goes from
     *    its cleanest tactic to its heaviest. Readiness is judged by the engine
     *    really serving ([EngineReadiness]) and then carrying outbound TCP.
     * 3. COMPARE: after the first proof the race runs on for
     *    [SmartPlusPlan.QUALITY_WINDOW_MS]; every proven route is measured through
     *    itself (latency, download, upload) and ranked with its overhead
     *    ([SmartPlusPlan.rank]).
     * 4. CONFIRM the best route as the session through [connectAttempt] - the same
     *    path a hand-picked protocol takes - and, if that fails, the next proven
     *    route, before anything else.
     * 5. LAST RESORT: only if nothing proved itself, a deep scan of the two
     *    cleanest carriers over the full built-in ranges.
     *
     * A race only SELECTS: each lane engine is a probe on a port of its own and is
     * always killed before the session's engine starts (see [runRace]).
     */
    private suspend fun connectSmartPlus(userProfile: ConnectionProfile): ConnectionProfile {
        if (!SmartPlusPlan.eligible(userProfile)) {
            DiagnosticsLog.i(
                TAG,
                "Smart race is not available for this profile (" +
                    "endpoint=${userProfile.endpointMode}, backend=${userProfile.backend.name}, " +
                    "zero-trust=${userProfile.teamAuth.name}) - using the Smart ladder instead.",
            )
            return connectSmartAuto(userProfile)
        }

        AetherController.setState(ConnectionState.Launching)
        updateNotification(getString(R.string.state_analyzing))
        val fingerprint = SmartAuto.fingerprint(this)
        val networkKey = NetworkIdentity.key(this)
        val onMobileData = NetworkIdentity.onMobileData(this)
        // 1.7.0 (same version) SMART BRAIN: evidence of THIS network first; on a
        // network seen for the first time, what worked on the same operator.
        val operatorKey = DpiBrain.operatorKey(fingerprint.operatorName, fingerprint.iranSim)
        val networkMemory = SmartPlusMemory.recall(this, networkKey)
        val memory = networkMemory?.takeIf { it.isProvenAt(System.currentTimeMillis()) }
            ?: operatorKey?.let { SmartPlusMemory.recall(this, it) }?.takeIf { it.isProvenAt(System.currentTimeMillis()) }
                ?.let { op -> op.copy(failedAtMs = networkMemory?.failedAtMs.orEmpty()) }
            ?: networkMemory
        if (memory != null && memory !== networkMemory) {
            DiagnosticsLog.i(TAG, "Smart brain: new network of a known operator - starting with '${memory.tacticId}', which worked there.")
        }
        val brain = DpiBrain.Board(DpiBrain.initialKnowledge(fingerprint))
        val lanes = SmartPlusPlan.plan(
            dpi = fingerprint.dpiClass,
            onMobileData = onMobileData,
            memory = memory,
            nowMs = System.currentTimeMillis(),
            quicReachable = fingerprint.quicReachable,
            goolClassic = SmartPlusPlan.goolClassic(userProfile),
            preferTcp = fingerprint.iranSim,
        )
        DiagnosticsLog.i(
            TAG,
            "Smart on network $networkKey (${fingerprint.dpiClass}, " +
                "${if (onMobileData) "mobile data" else "local link"}, quic=${fingerprint.quicReachable}, " +
                "iranSim=${fingerprint.iranSim}): " +
                "fair race[" + lanes.joinToString(" | ") { lane ->
                    lane.tactics.joinToString(",") { it.id } + "@+${lane.startAfterMs / 1000}s"
                } + "]",
        )

        val ranked = runRace(userProfile, lanes, networkKey, brain)
        for ((index, tactic) in ranked.withIndex()) {
            currentCoroutineContext().ensureActive()
            DiagnosticsLog.i(
                TAG,
                "Smart: confirming ${tactic.id} as the session (choice ${index + 1}/${ranked.size}).",
            )
            updateNotification(getString(R.string.state_connecting))
            // The user's own scan mode for the session (the supervisor restarts it
            // with this profile); the lane's cached gateway makes the confirming
            // connect take the quick path.
            val profile = tactic.applyTo(userProfile).copy(scanMode = userProfile.scanMode)
            val ok = runCatching {
                connectAttempt(profile, profile.connectTimeoutMs().coerceAtMost(CONFIRM_MAX_MS))
                true
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                DiagnosticsLog.w(
                    TAG,
                    "Smart: ${tactic.id} proved itself in the race but the session did not come up " +
                        "(${e.message}) - trying the next proven route.",
                )
                SmartPlusMemory.noteFailure(this, networkKey, tactic.id)
                cleanupNativeOnly("smart-confirm-failed")
                Diagnostics.resetChecks()
                false
            }
            if (ok) {
                SmartPlusMemory.remember(this, networkKey, tactic.id)
                operatorKey?.let { SmartPlusMemory.remember(this, it, tactic.id) }
                return profile
            }
        }

        DiagnosticsLog.w(
            TAG,
            "Smart: no route proved itself in the race (${brain.now.describe()}) - running the deep last-resort scan.",
        )
        return connectSmartAuto(userProfile, fingerprint, lastResortOnly = true, brain = brain)
    }

    /** A route that carried real traffic in the race, with the lane engine still holding it. */
    private data class ProvenRoute(
        val tactic: SmartPlusPlan.Tactic,
        val port: Int,
        val laneNumber: Int,
        val provenAfterMs: Long,
    )

    /**
     * Runs the lanes side by side and returns every route that carried traffic,
     * best first (empty if none did).
     *
     * ## Fairness
     *
     * Both lanes start at the same instant; every tactic has a fixed budget that
     * depends only on its weight (light / hardened / two-hop), never on its family.
     * The race does not end with the first answer: it runs on for
     * [SmartPlusPlan.QUALITY_WINDOW_MS] so that a slightly slower but better route
     * can still prove itself, and the proven routes are then measured one after
     * another under the same conditions.
     *
     * ## Why no lane is ever leaked
     *
     * A lane owns one [AetherProcess] and one port and kills its engine in
     * `finally` - on failure, on cancellation, and after a win (a proven lane holds
     * its engine only until the measurement is done). `coroutineScope` does not
     * return before every lane's `finally` has run, and [raceEngines] is swept
     * once more on the way out.
     */
    private suspend fun runRace(
        userProfile: ConnectionProfile,
        lanes: List<SmartPlusPlan.Lane>,
        networkKey: String,
        brain: DpiBrain.Board = DpiBrain.Board(),
    ): List<SmartPlusPlan.Tactic> {
        if (lanes.isEmpty()) return emptyList()
        val ports = PortLease.leaseRacePorts(lanes.size)
        if (ports.isEmpty()) {
            DiagnosticsLog.w(TAG, "Smart: no free local port for a lane - not racing.")
            return emptyList()
        }
        val running = lanes.take(ports.size)
        if (running.size < lanes.size) {
            DiagnosticsLog.w(
                TAG,
                "Smart: only ${ports.size} free local port(s), racing ${running.size} of ${lanes.size} lanes.",
            )
        }
        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_smart_plus_racing))

        val proven = java.util.Collections.synchronizedList(mutableListOf<ProvenRoute>())
        val searching = java.util.concurrent.atomic.AtomicInteger(running.size)
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            coroutineScope {
                val laneJobs = running.mapIndexed { index, lane ->
                    launch {
                        if (lane.startAfterMs > 0) delay(lane.startAfterMs)
                        runLane(userProfile, lane, ports[index], index + 1, startedAt, networkKey, searching, brain) {
                            proven += it
                        }
                    }
                }
                val ceiling = startedAt + SmartPlusPlan.RACE_CEILING_MS
                // Phase 1: until a route proves itself, or every lane is out of tactics.
                while (proven.isEmpty() && searching.get() > 0 && SystemClock.elapsedRealtime() < ceiling) {
                    delay(RACE_POLL_MS)
                }
                // Phase 2: the quality window - let the other lane prove a route too.
                if (proven.isNotEmpty() && searching.get() > 0) {
                    val windowEnd = minOf(SystemClock.elapsedRealtime() + SmartPlusPlan.QUALITY_WINDOW_MS, ceiling)
                    while (searching.get() > 0 && SystemClock.elapsedRealtime() < windowEnd) delay(RACE_POLL_MS)
                }
                val routes = synchronized(proven) { proven.toList() }
                // Lanes still searching are stopped NOW: they must not compete for
                // the link while the proven routes are measured.
                laneJobs.forEachIndexed { index, job ->
                    if (routes.none { it.laneNumber == index + 1 }) job.cancel()
                }
                if (routes.isEmpty() && SystemClock.elapsedRealtime() >= ceiling) {
                    DiagnosticsLog.w(TAG, "Smart: race ceiling reached with no route that carries traffic.")
                }
                val ranked = rankRoutes(routes)
                // Ends every lane, including the proven ones holding their engine;
                // coroutineScope then waits for their `finally` blocks.
                coroutineContext.cancelChildren()
                ranked
            }
        } finally {
            raceEngines.forEach { runCatching { it.stop() } }
            raceEngines.clear()
        }
    }

    /** Measures every proven route through its own lane port and ranks them. */
    private suspend fun rankRoutes(routes: List<ProvenRoute>): List<SmartPlusPlan.Tactic> {
        if (routes.size <= 1) {
            routes.firstOrNull()?.let {
                DiagnosticsLog.i(TAG, "Smart: ${it.tactic.id} is the only route that carries traffic here.")
            }
            return routes.map { it.tactic }
        }
        updateNotification(getString(R.string.state_smart_comparing))
        val measured = routes.map { route ->
            val quality = withContext(Dispatchers.IO) {
                runCatching {
                    studio.cluvex.aether.core.NetProbe.measureTunnelQuality(
                        SOCKS_HOST,
                        route.port,
                        SmartPlusPlan.MEASURE_MS,
                    )
                }.getOrNull()
            }
            DiagnosticsLog.i(
                TAG,
                "Smart: ${route.tactic.id} proved after ${route.provenAfterMs / 1000.0}s - " +
                    (quality?.let { q ->
                        "rtt=${q.rttMs}ms down=${q.downKbps}kbps up=${q.upKbps}kbps " +
                            "overhead=${route.tactic.overhead} score=" +
                            String.format(java.util.Locale.US, "%.0f", SmartPlusPlan.score(q, route.tactic.overhead))
                    } ?: "could not be measured"),
            )
            route.tactic to quality
        }
        val ranked = SmartPlusPlan.rank(measured)
        DiagnosticsLog.i(TAG, "Smart: ranking by latency, speed and overhead -> ${ranked.joinToString(" > ") { it.id }}")
        return ranked
    }

    /**
     * One lane: its tactics on one port, until one carries traffic or the lane runs
     * out. A proven tactic keeps its engine alive (for the measurement) until the
     * race cancels the lane.
     *
     * 1.7.0 (same version) SMART BRAIN: the order is no longer fixed. After every
     * failed tactic the engine's own output is diagnosed ([DpiBrain.diagnose]) and
     * what both lanes learned ([brain]) re-plans the rest of this lane
     * ([DpiBrain.adapt]): UDP carriers move behind TCP ones once UDP is known to
     * be dropped, TCP carriers get TLS fragmentation once TLS is interfered with,
     * a hopeless UDP rung is abandoned early, and a damaged identity is healed and
     * the same tactic retried once. A route counts as proven only when real data
     * crossed it ([LiveDataProof]), never on a bare SOCKS5 reply.
     */
    private suspend fun runLane(
        userProfile: ConnectionProfile,
        lane: SmartPlusPlan.Lane,
        port: Int,
        laneNumber: Int,
        startedAt: Long,
        networkKey: String,
        searching: java.util.concurrent.atomic.AtomicInteger,
        brain: DpiBrain.Board,
        onProven: (ProvenRoute) -> Unit,
    ) {
        var provenHere = false
        val queue = ArrayDeque(lane.tactics)
        val tried = mutableSetOf<String>()
        var healed = false
        var healNext = false
        try {
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                if (healNext) {
                    healNext = false
                    IdentityVault.healCorrupt(filesDir)
                }
                // Re-plan what is left with everything learned so far (both lanes).
                val planned = DpiBrain.adapt(queue.toList(), brain.now)
                queue.clear()
                queue.addAll(planned)
                val tactic = queue.removeFirst()
                if (!tried.add(DpiBrain.signature(tactic))) continue
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed + tactic.budgetMs > SmartPlusPlan.RACE_CEILING_MS) {
                    DiagnosticsLog.d(TAG, "Smart lane $laneNumber: no room left for ${tactic.id}.")
                    break
                }
                val profile = tactic.applyTo(userProfile, bindPort = port)
                DiagnosticsLog.i(TAG, "Smart lane $laneNumber on :$port -> ${tactic.label}")
                val engine = AetherProcess(applicationInfo.nativeLibraryDir, filesDir)
                raceEngines.add(engine)
                try {
                    engine.start(profile)
                    val t0 = SystemClock.elapsedRealtime()
                    var abandoned = false
                    val opened = PortProbe.awaitOpen(SOCKS_HOST, port, tactic.budgetMs) { engine.isAlive() }
                    val verdict: String? = if (!opened) {
                        if (engine.isAlive()) "never bound :$port" else "engine exited (${engine.fatalLine ?: "no reason given"})"
                    } else {
                        val left = (tactic.budgetMs - (SystemClock.elapsedRealtime() - t0)).coerceAtLeast(3_000L)
                        val outcome = EngineReadiness.await(
                            engine,
                            SOCKS_HOST,
                            port,
                            left,
                            "lane $laneNumber ${tactic.id}",
                            abort = {
                                DpiBrain.shouldAbandon(
                                    tactic,
                                    brain.now,
                                    SystemClock.elapsedRealtime() - t0,
                                    engine.isServing,
                                ).also { if (it) abandoned = true }
                            },
                        )
                        when (outcome) {
                            EngineReadiness.Outcome.READY -> {
                                // Serving is not carrying: prove that real data crosses it.
                                val proof = LiveDataProof.prove(
                                    SOCKS_HOST,
                                    port,
                                    LANE_TCP_GRACE_MS,
                                    alive = { engine.isAlive() },
                                    label = "lane $laneNumber ${tactic.id}",
                                    updateChecks = false,
                                )
                                if (proof.ok) null else "served SOCKS5 but carried no data"
                            }
                            EngineReadiness.Outcome.DIED -> "engine exited (${engine.fatalLine ?: "no reason given"})"
                            EngineReadiness.Outcome.TIMEOUT ->
                                if (abandoned) "abandoned early - UDP is dropped on this network"
                                else "no working gateway within ${tactic.budgetMs / 1000}s"
                        }
                    }
                    if (verdict != null) {
                        val died = !engine.isAlive()
                        val diagnosis = DpiBrain.diagnose(
                            engine.dpiSignals,
                            died,
                            SystemClock.elapsedRealtime() - t0,
                            DpiBrain.ridesUdp(tactic),
                            engine.fatalLine,
                        )
                        // An attempt the brain abandoned itself is not new evidence: counting
                        // it as another UDP strike would let one verdict reinforce itself.
                        val knowledge = if (abandoned) brain.now else brain.learn(diagnosis)
                        DiagnosticsLog.w(TAG, "Smart lane $laneNumber: ${tactic.id} - $verdict.")
                        DiagnosticsLog.i(TAG, "Smart brain: ${tactic.id} -> $diagnosis | ${knowledge.describe()}")
                        if (diagnosis == DpiBrain.Verdict.IDENTITY_DAMAGED && !healed) {
                            // Not the network's fault: heal the identity (after this
                            // engine is reaped, see `healNext`) and run the SAME tactic again.
                            healed = true
                            healNext = true
                            tried.remove(DpiBrain.signature(tactic))
                            queue.addFirst(tactic)
                            DiagnosticsLog.w(TAG, "Smart brain: the engine's identity is damaged - healing it and retrying ${tactic.id}.")
                        } else {
                            SmartPlusMemory.noteFailure(this, networkKey, tactic.id)
                        }
                        continue
                    }
                    val after = SystemClock.elapsedRealtime() - startedAt
                    DiagnosticsLog.i(TAG, "Smart lane $laneNumber: ${tactic.id} carries traffic after ${after / 1000.0}s.")
                    provenHere = true
                    searching.decrementAndGet()
                    onProven(ProvenRoute(tactic, port, laneNumber, after))
                    // Hold the engine for the measurement; the race cancels us.
                    kotlinx.coroutines.awaitCancellation()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLog.w(TAG, "Smart lane $laneNumber: ${tactic.id} failed (${e.message}).")
                    SmartPlusMemory.noteFailure(this, networkKey, tactic.id)
                } finally {
                    runCatching { engine.stop() }
                    raceEngines.remove(engine)
                }
            }
            if (!provenHere) DiagnosticsLog.d(TAG, "Smart lane $laneNumber: out of tactics.")
        } finally {
            if (!provenHere) searching.decrementAndGet()
        }
    }

    /**
     * SOCKS5 port the finished pipeline exposes for [profile].
     *
     * For an external backend that is always the second stage's FRONT, because
     * every external backend is chained now and the front is the listener that
     * speaks UDP.
     */
    private fun effectiveSocksPort(profile: ConnectionProfile): Int =
        profile.backend.exposedSocksPort

    /**
     * SMART AUTO (root-cause rework of the broken Auto protocol): fingerprint
     * the network's DPI first (see [SmartAuto]), then walk an ordered ladder
     * of concrete strategies — protocol + obfuscation + the IP ranges that
     * actually answered on THIS network — until one passes the full 5-step
     * self-test. Returns the strategy that won so the supervisor restarts the
     * engine with the SAME working configuration.
     */
    private suspend fun connectSmartAuto(
        userProfile: ConnectionProfile,
        knownFingerprint: studio.cluvex.aether.core.NetworkFingerprint? = null,
        /** 1.7.0: after a fair race found nothing, only the deep full-range rungs are left to try. */
        lastResortOnly: Boolean = false,
        /** 1.7.0 (same version): what the Smart brain already learned (the race), or a fresh one. */
        brain: DpiBrain.Board? = null,
    ): ConnectionProfile {
        AetherController.setState(ConnectionState.Launching)
        updateNotification(getString(R.string.state_analyzing))
        val fingerprint = knownFingerprint ?: SmartAuto.fingerprint(this)
        val networkKey = NetworkIdentity.key(this)
        val operatorKey = DpiBrain.operatorKey(fingerprint.operatorName, fingerprint.iranSim)
        val now = System.currentTimeMillis()
        val networkMemory = SmartPlusMemory.recall(this, networkKey)
        val memory = networkMemory?.takeIf { it.isProvenAt(now) }
            ?: operatorKey?.let { SmartPlusMemory.recall(this, it) }?.takeIf { it.isProvenAt(now) }
            ?: networkMemory
        val board = brain ?: DpiBrain.Board(DpiBrain.initialKnowledge(fingerprint))
        val full = SmartAuto.buildPlan(
            userProfile,
            fingerprint,
            onMobileData = NetworkIdentity.onMobileData(this),
            memory = memory,
        )
        val base = if (lastResortOnly) full.filter { it.lastResort }.ifEmpty { full } else full
        // The serial ladder starts already adapted to what the race learned.
        val plan = DpiBrain.adaptLadder(base, board.now)
        val won = runLadder(plan, getString(R.string.err_auto_failed), brain = board)
        val wonId = lastLadderWinner?.tacticId ?: plan.firstOrNull { it.profile == won }?.tacticId
        wonId?.let { id ->
            SmartPlusMemory.remember(this, networkKey, id)
            operatorKey?.let { SmartPlusMemory.remember(this, it, id) }
        }
        return won
    }

    /** 1.7.0 (same version): the rung [runLadder] connected with (it may have been adapted on the way). */
    @Volatile
    private var lastLadderWinner: AutoCandidate? = null

    /**
     * Two-pass plan for a protocol the user picked by hand (MASQUE, WireGuard
     * or Gool).
     *
     * 1.2.2 "MASQUE hangs forever" FIX: a hand-picked protocol used to get ONE
     * attempt with the full scan budget of the selected scan mode — up to 150 s
     * on Balanced and 300 s on Thorough — with no second chance. On a network
     * where QUIC/UDP is throttled that means the user stares at "Connecting"
     * for minutes and then just fails, while Smart mode (which walks a ladder
     * of shorter, hardened attempts) connects in seconds. So the chosen
     * protocol now gets:
     *   1. a first pass exactly as configured, on a capped budget,
     *   2. (MASQUE over HTTP/3 only, 1.7.0) the same protocol over HTTP/2 - the
     *      TCP carrier that passes where QUIC is filtered, and
     *   3. the SAME protocol again with anti-DPI hardening (obfuscation on, plus
     *      HTTP/2 + TLS fragmentation for MASQUE) on the full budget. ECH is never
     *      forced (1.7.0): without a fetchable key it stops the engine at start.
     * The protocol the user chose is never swapped for another one.
     */
    private fun directPlan(profile: ConnectionProfile): List<AutoCandidate> {
        val fullBudget = profile.connectTimeoutMs()
        val hardenedNoize = if (profile.noize == Noize.OFF) Noize.FIREWALL else profile.noize
        val masque = profile.protocol == Protocol.MASQUE
        // 1.7.0 (Tehran field log, manual MASQUE): the anti-DPI pass used to turn ECH
        // on by itself. Where no ECH key can be fetched the engine refuses to start
        // ("ECH is on but there is no ECH key to offer ... stopping rather than send
        // the server name in the clear") and died 0.4 s into its 150 s budget, so
        // the "hardened" pass could never work. ECH stays exactly what the user set.
        val candidates = mutableListOf(
            AutoCandidate(
                profile,
                fullBudget.coerceAtMost(FIRST_PASS_MAX_MS),
                "${profile.protocol.name} · as configured",
            ),
        )
        if (masque && !profile.masqueHttp2) {
            // MASQUE over HTTP/2 (TCP): the carrier that passes where QUIC/UDP is
            // filtered - which is what made MASQUE work in Tehran when it worked.
            candidates += AutoCandidate(
                profile.copy(masqueHttp2 = true),
                fullBudget.coerceAtMost(QUICK_PASS_MAX_MS),
                "MASQUE · h2 (TCP carrier)",
            )
        }
        val hardened = profile.copy(
            noize = hardenedNoize,
            masqueHttp2 = profile.masqueHttp2 || masque,
            fragment = profile.fragment || masque,
        )
        candidates += AutoCandidate(
            hardened,
            fullBudget,
            "${profile.protocol.name} · noize=${hardenedNoize.name.lowercase()}" +
                (if (masque) " · h2 · fragment" else "") + " (anti-DPI pass)",
        )
        return candidates.distinctBy { it.profile }
    }

    /**
     * Walks a ladder of strategies until one comes up and passes the smart data
     * gate. Each failed rung is torn down before the next is tried.
     *
     * 1.7.0 (same version): with a [brain] the ladder is adaptive - every failed
     * rung is diagnosed from the engine's own output and the rungs still to come
     * are re-planned ([DpiBrain.adaptLadder]). Without one (a hand-picked protocol)
     * it still heals a damaged engine identity once and retries the same rung,
     * instead of failing every rung on the same broken file.
     */
    private suspend fun runLadder(
        plan: List<AutoCandidate>,
        failureMessage: String,
        /** Chained stage 1: bring the engine up as a proxy only, no TUN, no exit check. */
        stageOnly: Boolean = false,
        brain: DpiBrain.Board? = null,
    ): ConnectionProfile {
        var lastError: Exception? = null
        lastLadderWinner = null
        val queue = ArrayDeque(plan)
        val total = plan.size
        var index = 0
        var healed = false
        var lastFault: Pair<String, Protocol>? = null

        while (queue.isNotEmpty()) {
            // A cancelled session must not climb to the next rung: starting an
            // engine here is what left an unsupervised libaether.so running after
            // the user pressed Disconnect. See [probeOrFalse].
            currentCoroutineContext().ensureActive()
            val candidate = queue.removeFirst()
            index++
            DiagnosticsLog.i(TAG, "Attempt $index/${maxOf(total, index)} → ${candidate.label}")
            try {
                connectAttempt(candidate.profile, candidate.timeoutMs, stageOnly)
                DiagnosticsLog.i(TAG, "Connected using ${candidate.label}")
                lastLadderWinner = candidate
                return candidate.profile
            } catch (e: CancellationException) {
                // 1.6.0: the one exit that used to leave no trace at all. Field log
                // 2026-10-15 ended exactly like this - engine and Xray closed 75 s
                // in, inside a 180 s wait, with no verdict line.
                ChainTrace.warn(
                    "ladder-cancelled",
                    "candidate=$index/${maxOf(total, index)} label=${candidate.label} " +
                        "(cancelled from outside this attempt - see the cleanup-native reason that follows)",
                )
                throw e
            } catch (e: Exception) {
                lastError = e
                ChainTrace.warn(
                    "ladder-fail",
                    "candidate=$index/${maxOf(total, index)} label=${candidate.label} error=${e.message}",
                )
                // Read the failed engine's own account BEFORE it is cleaned up.
                val failed = engine
                val signals = failed?.dpiSignals ?: 0
                val fatal = failed?.fatalLine
                val died = failed?.isAlive() != true
                val aliveMs = failed?.let { SystemClock.elapsedRealtime() - it.startedAtMs } ?: 0L
                val diagnosis = DpiBrain.diagnose(signals, died, aliveMs, DpiBrain.ridesUdp(candidate.profile), fatal)
                DiagnosticsLog.w(
                    TAG,
                    "${candidate.label} failed (${e.message}) — moving to the next strategy.",
                )
                cleanupNativeOnly("ladder-next-candidate (${candidate.label})")
                Diagnostics.resetChecks()
                if (diagnosis == DpiBrain.Verdict.IDENTITY_DAMAGED && !healed) {
                    healed = true
                    IdentityVault.healCorrupt(filesDir)
                    DiagnosticsLog.w(TAG, "Smart brain: the engine's identity was damaged - healed, retrying the same strategy.")
                    queue.addFirst(candidate)
                    continue
                }
                val previousFault = lastFault
                if (diagnosis == DpiBrain.Verdict.ENGINE_FAULT && fatal != null && healed && previousFault != null &&
                    previousFault.first == fatal && previousFault.second != candidate.profile.protocol
                ) {
                    // The same start-up fault on two different strategies, after a
                    // heal: nothing on the network side can fix it, so stop here
                    // instead of failing every remaining rung the same way.
                    DiagnosticsLog.e(TAG, "Smart brain: the engine fails to start the same way on every strategy ($fatal).")
                    throw IllegalStateException(failureMessage, lastError)
                }
                if (diagnosis == DpiBrain.Verdict.ENGINE_FAULT && !healed) {
                    // A start-up fault can be a damaged identity the engine did not
                    // name; one heal costs nothing when everything is fine.
                    healed = IdentityVault.healCorrupt(filesDir) > 0 || healed
                }
                lastFault = if (diagnosis == DpiBrain.Verdict.ENGINE_FAULT && fatal != null) {
                    fatal to candidate.profile.protocol
                } else {
                    null
                }
                if (brain != null) {
                    val knowledge = brain.learn(diagnosis)
                    DiagnosticsLog.i(TAG, "Smart brain: ${candidate.label} -> $diagnosis | ${knowledge.describe()}")
                    val adapted = DpiBrain.adaptLadder(queue.toList(), knowledge)
                    queue.clear()
                    queue.addAll(adapted)
                }
            }
        }

        throw IllegalStateException(failureMessage, lastError)
    }

    /**
     * One full connect attempt with a CONCRETE protocol: launch engine, wait
     * for SOCKS5, bring up TUN/proxy, and gate on the 5-step self-test.
     * Throws on any failure; the caller decides whether to retry differently.
     */
    private suspend fun connectAttempt(
        profile: ConnectionProfile,
        timeoutMs: Long,
        stageOnly: Boolean = false,
    ) {
        AetherController.setState(ConnectionState.Launching)
        updateNotification(getString(R.string.state_launching))
        // 1.2.2 PROTOCOL-SWITCH FIX: never start an engine on top of a dying
        // one. Tear the previous natives down and wait for the local SOCKS5
        // port to be released first, otherwise the probe below can "see" the
        // old listener and the whole attempt is verified against a socket that
        // is about to disappear.
        // Leaving lockdown (if any): the blackhole TUN is torn down here.
        lockdownTunActive = false
        cleanupNativeOnly("attempt-prestart")
        if (!PortProbe.awaitClosed(SOCKS_HOST, SOCKS_PORT, PORT_RELEASE_WAIT_MS)) {
            DiagnosticsLog.w(
                TAG,
                "Local port $SOCKS_PORT is still busy after ${PORT_RELEASE_WAIT_MS / 1000}s — starting anyway.",
            )
        }
        // 1.6.0 Config -> Aether: when the engine is told to dial through a local
        // carrier, prove that the carrier answers a SOCKS5 greeting NOW and name the
        // mapping in the log. A carrier that died between its readiness check and
        // the engine launch used to burn the whole scan budget silently.
        val upstream = upstreamOf(profile)
        if (upstream.isNotBlank()) {
            val upstreamHost = upstream.substringAfter("://").substringBeforeLast(':')
            val upstreamPort = upstream.substringAfterLast(':').toIntOrNull()
            val greets = upstreamPort != null && withContext(Dispatchers.IO) {
                studio.cluvex.aether.core.NetProbe.checkSocksHandshake(upstreamHost, upstreamPort, 2_000)
            }
            ChainTrace.stage(
                "engine-upstream",
                "engine $SOCKS_HOST:$SOCKS_PORT dials through $upstream socks-greeting=${if (greets) "ok" else "FAILED"}",
            )
            if (!greets) {
                DiagnosticsLog.e(TAG, "The custom config's listener ($upstream) stopped answering SOCKS5 before the engine was launched.")
                throw IllegalStateException(getString(R.string.cc_err_unreachable))
            }
        }
        DiagnosticsLog.i(TAG, "Launching engine (libaether.so)…")
        val launchedAt = SystemClock.elapsedRealtime()
        ChainTrace.stage(
            "engine-start",
            "protocol=${profile.protocol} scan=${profile.scanMode} h2=${profile.masqueHttp2} budget=${timeoutMs}ms" +
                (if (upstream.isNotBlank()) " upstream=$upstream" else ""),
        )
        engine = AetherProcess(applicationInfo.nativeLibraryDir, filesDir).also { it.start(profile, studio.cluvex.aether.custom.CustomChainPolicy.runtimeEnvironment(profile, customSelection?.mode)) }

        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        // Timeout comes from the caller: the profile's scan-mode budget for a
        // direct connect, or the per-candidate budget in the Smart Auto ladder.
        DiagnosticsLog.i(
            TAG,
            "Waiting for SOCKS5 on $SOCKS_HOST:$SOCKS_PORT… (scan=${profile.scanMode}, timeout=${timeoutMs / 1000}s)",
        )
        val opened = PortProbe.awaitOpen(SOCKS_HOST, SOCKS_PORT, timeoutMs) { engine?.isAlive() == true }
        val waited = SystemClock.elapsedRealtime() - launchedAt
        if (!opened) {
            val engineDied = engine?.isAlive() != true
            if (engineDied) {
                ChainTrace.warn("engine-fail", "category=died after=${waited}ms budget=${timeoutMs}ms")
                DiagnosticsLog.e(TAG, "Engine exited before it opened the SOCKS5 port (after ${waited / 1000}s of a ${timeoutMs / 1000}s budget).")
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            ChainTrace.warn("engine-fail", "category=timeout after=${waited}ms budget=${timeoutMs}ms (engine alive; stopped by the caller)")
            DiagnosticsLog.e(TAG, "Engine still scanning after ${timeoutMs / 1000}s — SOCKS5 port never opened.")
            throw IllegalStateException(getString(R.string.err_engine_timeout))
        }
        ChainTrace.stage("engine-socks-open", "after=${waited}ms")
        DiagnosticsLog.i(TAG, "SOCKS5 port is bound; waiting for the engine to start serving it.")

        // 1.7.0 ROOT-CAUSE FIX (Tehran field logs): a bound port is NOT a ready
        // engine. The engine binds at start-up and serves only once its tunnel is
        // validated, so the self-test that used to start here gave every attempt a
        // single 4 s handshake and killed engines that were still verifying their
        // cached gateway or scanning. Wait for the engine to really serve, for the
        // rest of THIS attempt's budget, ending at once if it exits. Tor-fronted
        // stages and engines dialled through a custom upstream keep their own,
        // bootstrap-aware waits below.
        val torOwnsReadiness = profile.backend.torMode == TorMode.ONLY || profile.backend.torMode == TorMode.REVERSE
        if (!torOwnsReadiness && upstream.isBlank()) {
            val session = engine ?: throw IllegalStateException(getString(R.string.err_engine_died))
            val remaining = (timeoutMs - (SystemClock.elapsedRealtime() - launchedAt)).coerceAtLeast(MIN_SERVE_WAIT_MS)
            when (EngineReadiness.await(session, SOCKS_HOST, SOCKS_PORT, remaining, "engine")) {
                EngineReadiness.Outcome.READY -> ChainTrace.stage(
                    "engine-serving",
                    "after=${SystemClock.elapsedRealtime() - launchedAt}ms",
                )
                EngineReadiness.Outcome.DIED -> {
                    val reason = session.fatalLine
                    ChainTrace.warn(
                        "engine-fail",
                        "category=died-before-serving after=${SystemClock.elapsedRealtime() - launchedAt}ms" +
                            (reason?.let { " reason=$it" } ?: ""),
                    )
                    DiagnosticsLog.e(TAG, "Engine exited before it could serve SOCKS5" + (reason?.let { ": $it" } ?: "."))
                    throw IllegalStateException(getString(R.string.err_engine_died))
                }
                EngineReadiness.Outcome.TIMEOUT -> {
                    ChainTrace.warn(
                        "engine-fail",
                        "category=timeout-before-serving after=${SystemClock.elapsedRealtime() - launchedAt}ms budget=${timeoutMs}ms",
                    )
                    DiagnosticsLog.e(TAG, "Engine found no working gateway within ${timeoutMs / 1000}s for this strategy.")
                    throw IllegalStateException(getString(R.string.err_engine_timeout))
                }
            }
        }

        if (stageOnly) {
            // 1.7.0 (same version): no switch skips this any more - a chained stage
            // that cannot open outbound connections is never handed to stage 2.
            // CHAINED STAGE 1. An open port is not a working proxy: the engine's
            // inner tunnel can still be building, and handing a half-ready proxy
            // to Psiphon makes stage 2 fail for a reason that looks like
            // stage 2's fault. Gate on real outbound TCP, then stop -- no TUN,
            // no DNS/geo lookup, because the exit belongs to stage 2.
            AetherController.setState(ConnectionState.Proving)
            updateNotification(getString(R.string.state_smart_gate))
            // 1.3.0 FIELD FIX. When stage 1 IS Tor (`Tor`, `Tor -> Psiphon`, and
            // the reverse chain), the listener exists from the first millisecond
            // but cannot answer until the bootstrap has reached the network. The
            // handshake therefore gets the rest of THIS attempt's budget instead
            // of a single 4-second window -- the budget that was computed for the
            // bootstrap in the first place and that the open port used to skip
            // straight past. Every other stage keeps the old single-shot gate.
            val torFronted = profile.backend.torMode.let {
                it == TorMode.ONLY || it == TorMode.REVERSE
            }
            val handshakeGrace = if (torFronted || upstreamOf(profile).isNotBlank()) {
                (timeoutMs - (SystemClock.elapsedRealtime() - launchedAt)).coerceAtLeast(0L)
            } else {
                0L
            }
            // Waiting minutes is only acceptable while something is happening. A
            // bootstrap that stops moving is what a network filtering Tor looks
            // like, and the user gets told that instead of watching a spinner.
            // With bridges configured the engine has its own, slower fallback to
            // work through, so the app's patience for a silent gap grows too.
            val stallMs = if (profile.torBridges != TorBridges.OFF) {
                TOR_STALL_BRIDGED_MS
            } else {
                TOR_STALL_DIRECT_MS
            }
            val ticker = if (torFronted) {
                launchBootstrapTicker(profile.torBridges != TorBridges.OFF)
            } else {
                null
            }
            val stageOk = try {
                probeOrFalse {
                    Diagnostics.runProxyStage(
                        SOCKS_HOST,
                        SOCKS_PORT,
                        handshakeGraceMs = handshakeGrace,
                        alive = { engine?.isAlive() == true },
                        abort = { torFronted && TorBootstrap.stalled(stallMs) },
                    )
                }
            } finally {
                ticker?.cancel()
            }
            if (!stageOk) {
                DiagnosticsLog.e(TAG, "Stage 1 cannot open outbound connections - trying the next strategy.")
                throw IllegalStateException(stageFailureMessage(torFronted))
            }
            return
        }

        // 1.6.0 FIELD FIX (`Tor -> Aether`, `Tor -> Aether -> Config`, `Config -> ...`):
        // the engine opens its listener at once but only answers SOCKS5 after the
        // tunnel is validated, i.e. after Tor has bootstrapped (reverse chain) or
        // after WARP was reached THROUGH the custom config. The self-test and the
        // config probe used to start immediately and gave up after 4 s.
        val reverseTor = profile.backend.torMode == TorMode.REVERSE
        if (reverseTor || upstreamOf(profile).isNotBlank()) {
            val grace = (timeoutMs - (SystemClock.elapsedRealtime() - launchedAt)).coerceAtLeast(0L)
            val stallMs = if (profile.torBridges != TorBridges.OFF) TOR_STALL_BRIDGED_MS else TOR_STALL_DIRECT_MS
            val ticker = if (reverseTor) launchBootstrapTicker(profile.torBridges != TorBridges.OFF) else null
            val answered = try {
                probeOrFalse {
                    Diagnostics.awaitProxyReady(
                        SOCKS_HOST,
                        SOCKS_PORT,
                        graceMs = grace,
                        alive = { engine?.isAlive() == true },
                        abort = { reverseTor && TorBootstrap.stalled(stallMs) },
                    )
                }
            } finally {
                ticker?.cancel()
            }
            if (!answered) {
                DiagnosticsLog.e(TAG, "The engine's SOCKS5 port never started answering within its budget.")
                throw IllegalStateException(getString(R.string.err_selftest))
            }
        }

        // 1.5.0: a `... -> Config` chain puts the custom config behind the
        // engine's listener; for every other session this is SOCKS_PORT itself.
        val exitPort = customExit(SOCKS_PORT)
        sessionExitPort = exitPort
        PingMonitor.setTunnelPort(exitPort)

        if (profile.proxyMode) {
            // Proxy mode: DON'T capture the whole device through a system TUN.
            // Instead expose the engine's SOCKS5 + an HTTP proxy so individual
            // apps (or the Wi-Fi proxy setting) can opt in. This is ideal when
            // only one app (e.g. Telegram) needs the tunnel. LAN exposure only
            // happens when the user explicitly turned sharing on.
            //
            // startSync is ground truth: in proxy mode these listeners ARE the
            // product, so a bind failure must fail the connection loudly
            // instead of claiming "Local proxy ready" over dead ports (the old
            // fire-and-forget start swallowed EADDRINUSE and still reported
            // 1080/8118 as ready — external apps then couldn't connect).
            if (profile.lanShare) runCatching { ShareCredentials.ensure(this) }
            val shareReady =
                ShareBridge.startSync(localOnly = !profile.lanShare, upstreamPort = exitPort)
            if (!shareReady) {
                DiagnosticsLog.e(TAG, "Proxy mode: the fixed local proxy ports could not be opened (see errors above).")
                throw IllegalStateException(getString(R.string.err_proxy_ports))
            }
            // Ports are FIXED (v2rayNG-style standard) — the same values are
            // shown as copyable rows under the Proxy-mode toggle in the UI.
            DiagnosticsLog.i(
                TAG,
                "Proxy mode: system TUN skipped. Local proxy ready — " +
                    "SOCKS5 127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}, HTTP 127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}",
            )
        } else {
            establishTun(profile)
            startTun2Socks(profile, exitPort)
            // LAN sharing: if the user enabled it, expose the tunnel to other
            // devices on the same Wi-Fi/hotspot (HTTP + SOCKS5 bridge). Remote
            // clients must authenticate (1.2.9-r3, F-5), so load the credential
            // first.
            if (profile.lanShare) {
                runCatching { ShareCredentials.ensure(this) }
                ShareBridge.start(localOnly = false, upstreamPort = exitPort)
            }
        }

        // GATING: Connected is never reported the moment the TUN / proxy is up.
        // The state is held at Proving until the smart gate has seen real data
        // cross the tunnel, so Connected == genuinely ready to browse.
        DiagnosticsLog.i(TAG, "Local tunnel/proxy started; the smart gate proves the data path.")

        // In proxy mode, test THROUGH the shared SOCKS5 listener — the exact
        // endpoint external apps connect to — so a dead bridge can no longer
        // hide behind a passing engine-port (1819) self-test.
        val diagPort =
            if (profile.proxyMode) ShareBridge.socksPort.value ?: exitPort
            else exitPort
        val healthy = probeOrFalse {
            smartGateFinal(profile, diagPort)
        }
        if (!healthy) {
            DiagnosticsLog.e(TAG, "Self-test failed — refusing to report Connected.")
            throw IllegalStateException(getString(R.string.err_selftest))
        }

        // Informational only: report where the tunnel actually came out.
        // WARP edges are anycast, so the exit location is decided by the
        // engine's endpoint selection and the operator's routing, not by the
        // app. Nothing here can reject or override that choice.
        val exit = AetherController.ipInfo.value?.takeIf { it.viaTunnel }
        if (exit != null) {
            DiagnosticsLog.i(
                TAG,
                "Exit verified through the tunnel: ${exit.ip} (${exit.countryCode ?: "??"})",
            )
        }
    }

    /** Keeps the engine alive; retries with backoff if it dies. */
    private suspend fun superviseEngine(profile: ConnectionProfile) {
        var attempt = 0
        while (currentScopeActive()) {
            if (engine?.isAlive() == true) {
                attempt = 0
                // 1.2.2 CPU FIX: the supervisor used to wake up every 2 s for
                // the ENTIRE lifetime of the tunnel just to ask "is the engine
                // still alive?" — 1,800 wake-ups per hour of a healthy,
                // otherwise idle connection, each one preventing the CPU from
                // settling into a deep idle state and quietly draining the
                // battery. Instead we now BLOCK on the process itself: the OS
                // wakes us the instant the engine exits and never before, so a
                // healthy tunnel costs exactly zero polling.
                engine?.awaitExit(WATCHDOG_INTERVAL_MS)
                // STABILITY WATCHDOG (1.2.4, hardened): the engine process can
                // stay alive while its session silently dies -- the classic
                // "connected, but after a minute or two no site opens"
                // symptom. Probe end-to-end THROUGH the local SOCKS5 port and
                // restart the engine only on SUSTAINED failure; see
                // probeTunnelCycle() for why the bar is deliberately high.
                if (engine?.isAlive() == true) {
                    val dataPathMoving = dataPathIsMoving()
                    if (probeTunnelCycle(sessionExitPort.takeIf { it > 0 } ?: SOCKS_PORT)) {
                        probeFailures = 0
                    } else if (dataPathMoving) {
                        // Same rule as the chained watchdog: see the comment
                        // there. A tunnel that is moving bytes is not dead, and
                        // restarting the engine under a live stream is exactly
                        // the "it disconnects and comes back over and over"
                        // symptom this watchdog was blamed for.
                        probeFailures = 0
                        DiagnosticsLog.i(
                            TAG,
                            "Watchdog: probe did not answer but the tunnel is still carrying " +
                                "traffic - leaving the engine alone.",
                        )
                    } else if (++probeFailures >= WATCHDOG_FAIL_CYCLES) {
                        DiagnosticsLog.w(
                            TAG,
                            "Watchdog: tunnel dead across $WATCHDOG_FAIL_CYCLES consecutive checks -- restarting the engine.",
                        )
                        probeFailures = 0
                        engine?.stop()
                    }
                }
                continue
            }

            if (attempt >= maxRetries(profile)) {
                // KILL SWITCH (1.2.4): instead of tearing the VPN down and
                // leaking direct, engage the blackhole lockdown.
                if (profile.killSwitch || profile.strictKillSwitch) {
                    enterLockdown(profile)
                    return
                }
                throw IllegalStateException(getString(R.string.err_engine_died))
            }
            val backoff = BACKOFF[attempt.coerceAtMost(BACKOFF.size - 1)]
            attempt++
            AetherController.setState(ConnectionState.Reconnecting(attempt, maxRetries(profile)))
            updateNotification(getString(R.string.state_reconnecting))
            delay(backoff)

            engine = AetherProcess(applicationInfo.nativeLibraryDir, filesDir).also { it.start(profile, studio.cluvex.aether.custom.CustomChainPolicy.runtimeEnvironment(profile, customSelection?.mode)) }
            // 1.7.0: the same readiness rule as the first connect - a bound port is
            // not a serving engine, so wait for it to serve before the self-test.
            val restarted = engine
            if (restarted != null &&
                PortProbe.awaitOpen(SOCKS_HOST, SOCKS_PORT, profile.connectTimeoutMs()) { restarted.isAlive() } &&
                EngineReadiness.await(
                    restarted,
                    SOCKS_HOST,
                    SOCKS_PORT,
                    profile.connectTimeoutMs(),
                    "restarted engine",
                ) == EngineReadiness.Outcome.READY
            ) {
                // Same gate as the initial connect: never claim Connected after
                // a silent engine restart until traffic really flows again.
                if (probeOrFalse { smartGateFinal(profile, sessionExitPort.takeIf { it > 0 } ?: SOCKS_PORT) }) {
                    attempt = 0
                    ChainTrace.end("ok", "reconnected exit=$SOCKS_HOST:${sessionExitPort.takeIf { it > 0 } ?: SOCKS_PORT}")
                    AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:${sessionExitPort.takeIf { it > 0 } ?: SOCKS_PORT}"))
                    updateNotification(getString(R.string.state_connected))
                } else {
                    DiagnosticsLog.w(TAG, "Self-test failed after engine restart — retrying.")
                    engine?.stop()
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // 1.5.0 CUSTOM CONFIGS
    //
    // A custom config is run by a bundled Xray core (libxray.so) as a local
    // SOCKS5 listener. Three placements:
    //
    //   SOLO   xray (direct)                                  -> TUN
    //   FIRST  xray (direct)  <- engine --upstream xray       -> TUN  (Config -> ...)
    //   LAST   engine pipeline -> xray dialerProxy=engine     -> TUN  (... -> Config)
    //
    // Xray is a child process of this app, so - like the engine - it sits
    // outside the VPN (applyAppFilter excludes this package) and never loops
    // back into its own tunnel.
    // ------------------------------------------------------------------------

    /** The configs automatic selection may pick from: the active tab, usable here. */
    private fun selectionCandidates(snapshot: CustomConfigStore.State, current: CustomConfig,
        excludeCurrent: Boolean): List<CustomConfig> = snapshot.configs.filter {
        studio.cluvex.aether.custom.ConfigCatalog.contains(snapshot.activeTab, it) &&
            !it.isExpired() && (!it.mobileOnly || onMobileData()) &&
            (!excludeCurrent || it.id != current.id)
    }

    /** Direct-outbound ranking; never compares TCP-connect-only fallback values. */
    private suspend fun chooseBestCustom(current: CustomConfig, initial: Boolean = false,
        excludeCurrent: Boolean = false, eager: Boolean = false): CustomConfig? {
        val snapshot = CustomConfigStore.state.value
        val candidates = selectionCandidates(snapshot, current, excludeCurrent)
        val reports = studio.cluvex.aether.custom.ConfigPingStore.measureForSelection(this, candidates)
        if (CustomConfigStore.state.value.activeTab != snapshot.activeTab) return null
        val live = CustomConfigStore.state.value.configs
        return studio.cluvex.aether.custom.AutoSelectPolicy.choose(
            candidates.filter { it in live }, reports,
            if (initial || excludeCurrent) null else current.id,
            minGainMs = if (eager) studio.cluvex.aether.custom.AutoSelectPolicy.EAGER_GAIN_MS
                else studio.cluvex.aether.custom.AutoSelectPolicy.STEADY_GAIN_MS,
            minGainDivisor = if (eager) studio.cluvex.aether.custom.AutoSelectPolicy.EAGER_GAIN_DIVISOR
                else studio.cluvex.aether.custom.AutoSelectPolicy.STEADY_GAIN_DIVISOR,
        )
    }

    /** Same ranking on the results already measured (manual run / finished background run). */
    private fun chooseCachedCustom(current: CustomConfig, eager: Boolean): CustomConfig? {
        val snapshot = CustomConfigStore.state.value
        return studio.cluvex.aether.custom.AutoSelectPolicy.choose(
            selectionCandidates(snapshot, current, excludeCurrent = false),
            studio.cluvex.aether.custom.ConfigPingStore.results.value, current.id,
            minGainMs = if (eager) studio.cluvex.aether.custom.AutoSelectPolicy.EAGER_GAIN_MS
                else studio.cluvex.aether.custom.AutoSelectPolicy.STEADY_GAIN_MS,
            minGainDivisor = if (eager) studio.cluvex.aether.custom.AutoSelectPolicy.EAGER_GAIN_DIVISOR
                else studio.cluvex.aether.custom.AutoSelectPolicy.STEADY_GAIN_DIVISOR,
        )
    }

    /**
     * 1.7.0 fast first connect: starts measuring every candidate in the background
     * and returns as soon as the first ones answered (plus a short grace so near
     * ties are compared), with the still-running measurement. When nobody answers
     * within [FIRST_PICK_TIMEOUT_MS] it falls back to waiting for the full run.
     */
    private suspend fun chooseFirstCustom(current: CustomConfig):
        Pair<CustomConfig?, studio.cluvex.aether.custom.ConfigPingStore.Run?> {
        val snapshot = CustomConfigStore.state.value
        val candidates = selectionCandidates(snapshot, current, excludeCurrent = false)
        if (candidates.isEmpty()) return null to null
        val handle = studio.cluvex.aether.custom.ConfigPingStore.startRun(this, candidates)
        val since = handle.startedAt
        val answered = withTimeoutOrNull(FIRST_PICK_TIMEOUT_MS) {
            studio.cluvex.aether.custom.ConfigPingStore.results.first { map ->
                handle.done.isCompleted || candidates.any { c ->
                    val r = map[c.id]
                    r != null && r.ok && r.ms >= 1 && r.at >= since
                }
            }
        }
        if (answered != null && !handle.done.isCompleted) delay(FIRST_PICK_GRACE_MS)
        if (handle.done.isCompleted || answered == null) {
            val all = runCatching { handle.done.await() }.getOrDefault(emptyMap())
            if (CustomConfigStore.state.value.activeTab != snapshot.activeTab) return null to null
            return studio.cluvex.aether.custom.AutoSelectPolicy.choose(
                candidates.filter { it in CustomConfigStore.state.value.configs }, all, null) to null
        }
        val first = studio.cluvex.aether.custom.AutoSelectPolicy.fastestSoFar(
            candidates, studio.cluvex.aether.custom.ConfigPingStore.results.value, since)
        DiagnosticsLog.i(TAG, "Auto-select: connecting to the first fast config (${first?.name ?: "none"}); " +
            "the full test keeps running and the fastest one is applied when it ends")
        return first to handle
    }

    /**
     * 1.7.0 (same version): the final gate before Connected - the SMART GATE.
     *
     * Every session (Aether, Psiphon, Tor, their chains AND custom configs) passes
     * the smart data gate ([LiveDataProof]); there is no switch any more, anywhere.
     * The old optional verification demanded a geolocation answer AND device DNS
     * over UDP for up to 90 s: it kept MASQUE from ever connecting while it was on,
     * and proved nothing at all while it was off. The smart gate connects the
     * moment real data crosses the tunnel (usually within a second) and never
     * accepts a fake connection: a bare SOCKS5 reply does not count.
     */
    private suspend fun smartGateFinal(profile: ConnectionProfile, port: Int, graceMs: Long? = null,
        alive: () -> Boolean = { xray?.isAlive() == true }): Boolean {
        AetherController.setState(ConnectionState.Proving)
        updateNotification(getString(R.string.state_smart_gate))
        val processAlive: () -> Boolean = if (customSelection != null) alive else {
            { engine?.isAlive() != false }
        }
        val budget = graceMs ?: if (customSelection != null) CUSTOM_GATE_BUDGET_MS else LiveDataProof.DIRECT_BUDGET_MS
        val label = if (customSelection != null) "custom session" else "session"
        DiagnosticsLog.d(TAG, "Smart gate for ${profile.backend.name} on :$port (budget ${budget / 1000}s).")
        return LiveDataProof.prove(SOCKS_HOST, port, budget, alive = processAlive, label = label).ok
    }

    /**
     * The smart gate for a custom config's own SOCKS5 listener before anything is
     * chained to it: real data must cross the config's outbound (no geo-IP, no
     * UDP requirement), bounded by [CUSTOM_GATE_BUDGET_MS] and ended at once when
     * Xray exits.
     */
    private suspend fun customGate(port: Int, alive: () -> Boolean): Boolean =
        LiveDataProof.prove(
            SOCKS_HOST,
            port,
            CUSTOM_GATE_BUDGET_MS,
            alive = alive,
            label = "custom config",
            updateChecks = false,
        ).ok

    private suspend fun connectCustom(profile: ConnectionProfile, selection: CustomSelection) {
        val cfg = selection.config
        val mode = selection.mode
        if (cfg.isExpired()) throw IllegalStateException(getString(R.string.cc_err_expired))
        if (cfg.mobileOnly && !onMobileData()) {
            throw IllegalStateException(getString(R.string.cc_err_mobile_only))
        }
        val runner = XrayProcess(applicationInfo.nativeLibraryDir, filesDir)
        if (!runner.available()) {
            DiagnosticsLog.e(TAG, XrayProcess.MISSING)
            throw IllegalStateException(getString(R.string.cc_err_no_core))
        }
        xray = runner
        CustomSession.set(CustomSession.Info(cfg.chainLabel, mode))
        DiagnosticsLog.i(
            TAG,
            "Custom config: ${cfg.protocol.short} (${cfg.format.name.lowercase()}) - chain " +
                mode.label(cfg.chainLabel),
        )
        val base = mode.base
        when (mode.placement) {
            ChainPlacement.SOLO -> connectCustomSolo(profile, runner, cfg)
            ChainPlacement.FIRST -> {
                AetherController.setState(ConnectionState.Connecting)
                updateNotification(getString(R.string.state_connecting))
                cleanupNativeOnly("custom-first-prestart")
                ChainTrace.stage("xray-launch", "placement=first config=${cfg.protocol.short}")
                val port = startXray(runner, cfg, dialVia = null)
                val reachable = customGate(port) { runner.isAlive() }
                if (!reachable) {
                    ChainTrace.warn("xray-unreachable", "socks5=$SOCKS_HOST:$port")
                    DiagnosticsLog.e(TAG, "The custom config opens no outbound connection.")
                    throw IllegalStateException(getString(R.string.cc_err_unreachable))
                }
                ChainTrace.stage("xray-ready", "socks5=$SOCKS_HOST:$port (loopback only; the engine is its only client)")
                DiagnosticsLog.i(TAG, "Custom config is up on $SOCKS_HOST:$port - the engine dials through it.")
                val chained = studio.cluvex.aether.custom.CustomChainPolicy.engineProfile(
                    profile.copy(upstreamProxy = "socks5://$SOCKS_HOST:$port"), mode)
                if (chained.protocol != profile.protocol || chained.masqueHttp2 != profile.masqueHttp2) {
                    DiagnosticsLog.i(TAG, "Custom upstream: using MASQUE over HTTP/2; direct UDP fingerprint/memory is not applicable.")
                }
                if (base == null || base == ChainBase.PSIPHON) {
                    connectPsiphonOnly(chained)
                } else {
                    connectEngineFlow(chained.copy(backend = base.backend ?: TransportBackend.AETHER))
                }
            }
            ChainPlacement.LAST -> {
                if (base == null || base == ChainBase.PSIPHON) {
                    connectPsiphonOnly(profile)
                } else {
                    connectEngineFlow(profile.copy(backend = base.backend ?: TransportBackend.AETHER))
                }
            }
        }
    }

    /** The custom config alone: no engine at all. */
    private suspend fun connectCustomSolo(
        profile: ConnectionProfile,
        runner: XrayProcess,
        cfg: CustomConfig,
    ) {
        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        cleanupNativeOnly("custom-solo-prestart")
        lockdownTunActive = false
        val port = startXray(runner, cfg, dialVia = null)
        val reachable = customGate(port) { runner.isAlive() }
        if (!reachable) {
            DiagnosticsLog.e(TAG, "The custom config opens no outbound connection.")
            throw IllegalStateException(getString(R.string.cc_err_unreachable))
        }
        finishCustom(profile, port, graceMs = null) { runner.isAlive() }
    }

    /**
     * `Config -> Psiphon` and `Psiphon -> Config`: the engine as plain Psiphon
     * (`--psiphon-only`), behind the usual UDP-capable Psiphon front.
     */
    private suspend fun connectPsiphonOnly(profile: ConnectionProfile) {
        AetherController.setState(ConnectionState.Connecting)
        updateNotification(getString(R.string.state_connecting))
        cleanupNativeOnly("psiphon-only-prestart")
        lockdownTunActive = false
        withContext(Dispatchers.IO) { PsiphonBootstrap.prepare(this@AetherVpnService) }

        val stage = profile.copy(
            backend = TransportBackend.AETHER,
            proxyMode = false,
            lanShare = false,
            routeBlock = "",
            routeDirect = "",
            chainedStage = true,
            enginePsiphon = true,
            enginePsiphonOnly = true,
        )
        if (!PortProbe.awaitClosed(SOCKS_HOST, SOCKS_PORT, PORT_RELEASE_WAIT_MS)) {
            DiagnosticsLog.w(TAG, "Local port $SOCKS_PORT is still busy - starting anyway.")
        }
        DiagnosticsLog.i(TAG, "Launching engine as plain Psiphon on $SOCKS_HOST:$SOCKS_PORT")
        engine = AetherProcess(applicationInfo.nativeLibraryDir, filesDir).also { it.start(stage) }

        val transport = PsiphonTransport(
            service = this,
            region = profile.exitRegion,
            engineAlive = { engine?.isAlive() == true },
            localSocksPort = SOCKS_PORT,
            frontPort = PortLease.chain,
        )
        externalTransport = transport
        val frontRoutes = withContext(Dispatchers.IO) {
            studio.cluvex.aether.core.SmartLists.frontRules(profile)
        }
        PsiphonSocksFront.setRoutes(frontRoutes)

        val front = try {
            transport.start()
        } catch (e: PsiphonRegionFallback) {
            DiagnosticsLog.w(TAG, e.message ?: "Psiphon could not serve the chosen exit - retrying automatically.")
            cleanupNativeOnly("psiphon-only-region-fallback")
            connectPsiphonOnly(profile.copy(exitRegion = ""))
            return
        } catch (e: CancellationException) {
            // CancellationException IS an IllegalStateException. Without this
            // clause a Disconnect pressed while Psiphon was still connecting was
            // caught below, logged as "Psiphon could not reach its network
            // directly: StandaloneCoroutine was cancelled" and re-thrown as an
            // ordinary error, which broke structured cancellation of the session.
            throw e
        } catch (e: IllegalStateException) {
            // `Psiphon -> Config` is the only chain in which Psiphon has to reach
            // its own network with nothing underneath it. When that is what failed,
            // say so and name the chain that does work on this network, instead of
            // reporting the generic "the engine exited" the user cannot act on.
            if (customSelection?.mode?.placement == ChainPlacement.LAST) {
                DiagnosticsLog.e(TAG, "Psiphon could not reach its network directly: ${e.message}")
                throw IllegalStateException(getString(R.string.cc_err_psiphon_direct))
            }
            throw e
        }
        val port = customExit(front)
        val runner = xray
        finishCustom(profile, port, graceMs = Diagnostics.EXTERNAL_GRACE_MS) {
            engine?.isAlive() == true && transport.isAlive() && (runner?.isAlive() ?: true)
        }
    }

    /**
     * For a chain whose LAST hop is the custom config: starts Xray dialling
     * through [enginePort] and returns Xray's port, which is then what tun2socks
     * and the self-test use. Any other session gets [enginePort] back unchanged.
     */
    private suspend fun customExit(enginePort: Int): Int {
        val selection = customSelection ?: return enginePort
        if (selection.mode.placement != ChainPlacement.LAST) return enginePort
        val runner = xray ?: throw IllegalStateException(getString(R.string.cc_err_no_core))
        val port = startXray(runner, selection.config, dialVia = enginePort)
        val reachable = customGate(port) { runner.isAlive() }
        if (!reachable) {
            DiagnosticsLog.e(TAG, "The custom config answers nothing through $SOCKS_HOST:$enginePort.")
            throw IllegalStateException(getString(R.string.cc_err_unreachable))
        }
        DiagnosticsLog.i(TAG, "Custom config is the exit: $SOCKS_HOST:$port -> $SOCKS_HOST:$enginePort")
        PingMonitor.setTunnelPort(port)
        return port
    }

    /**
     * True when the hop the config is dialled through is an onion/obfuscated one
     * (Tor or Psiphon), i.e. a hop where opening a new connection is expensive.
     */
    private fun slowChainBehindEngine(): Boolean {
        val mode = customSelection?.mode ?: return false
        if (mode.placement != ChainPlacement.LAST) return false
        return when (mode.base) {
            null -> false
            ChainBase.AETHER -> false
            else -> true
        }
    }

    private suspend fun startXray(runner: XrayProcess, cfg: CustomConfig, dialVia: Int?): Int {
        stopXrayOnly()
        val avoid = setOf(
            PortLease.socks, PortLease.torSocks, PortLease.torFront, PortLease.chain,
            PortLease.psiphon, ShareBridge.SOCKS_SHARE_PORT, ShareBridge.HTTP_SHARE_PORT,
        ) + listOfNotNull(dialVia)
        // 1.5.0-r2: a config dialled THROUGH Tor or Psiphon reuses one connection
        // for every flow (Mux). Without it each flow pays a fresh Tor stream and a
        // fresh handshake to the server, which is the whole of the `Tor -> Config`
        // slowness. A direct (first-hop or solo) config keeps Mux off: there the
        // per-flow cost is a local dial and Mux would only add head-of-line delay.
        //
        // 1.6.0 FIELD FIX (2026-10-30): Mux is OFF for every chain. Mux.cool is not
        // implemented by most VLESS/WS servers (Cloudflare-Worker panels such as BPB,
        // edgetunnel, many CDN fronts): the server drops the very first muxed stream,
        // so `Tor/Psiphon -> Config`, `Aether -> Tor/Psiphon -> Config` and
        // `Tor -> Aether -> Config` all answered nothing, while `Aether -> Config`
        // (no Mux) worked. Verified against a live Worker: plain VLESS/WS returns 204,
        // the same outbound with `mux.enabled` returns nothing.
        val mux = 0
        val (port, config) = withContext(Dispatchers.IO) {
            val picked = XrayProcess.pickPort(avoid)
            val dns = if (dialVia == null) underlyingDns() else emptyList()
            picked to XrayConfigBuilder.build(
                outbound = cfg.outbound,
                listenPort = picked,
                dialVia = dialVia,
                networkDns = dns,
                muxConcurrency = mux,
            )
        }
        withContext(Dispatchers.IO) { runner.start(config, "session") }
        val open = PortProbe.awaitOpen(SOCKS_HOST, port, XRAY_START_TIMEOUT_MS) { runner.isAlive() }
        if (!open) {
            DiagnosticsLog.e(TAG, "Xray did not open $SOCKS_HOST:$port (see the xray lines above).")
            runner.stop()
            throw IllegalStateException(getString(R.string.cc_err_core_failed))
        }
        val speaksSocks = withContext(Dispatchers.IO) {
            studio.cluvex.aether.core.NetProbe.checkSocksHandshake(SOCKS_HOST, port, 2_000)
        }
        if (!speaksSocks) {
            runner.stop()
            throw IllegalStateException(getString(R.string.cc_err_core_failed))
        }
        DiagnosticsLog.i(
            TAG,
            "Xray core listening on $SOCKS_HOST:$port" + (dialVia?.let { " (dialling via $it)" } ?: ""),
        )
        watchXray(runner, config)
        return port
    }

    /** Restarts a crashed Xray on the same port (its config is unchanged), a few times. */
    private fun watchXray(runner: XrayProcess, config: String) {
        val generation = synchronized(xrayLock) { ++xrayGeneration }
        xrayWatch = scope.launch(Dispatchers.IO) {
            var restarts = 0
            var healthySeconds = 0
            while (true) {
                delay(1_000L)
                synchronized(xrayLock) {
                    if (generation != xrayGeneration) return@launch
                    if (runner.isAlive()) {
                        if (++healthySeconds >= 120) restarts = 0
                    } else if (restarts >= XRAY_MAX_RESTARTS) {
                        DiagnosticsLog.e(TAG, "Xray keeps exiting - giving up on restarts.")
                        return@launch
                    } else {
                        restarts++
                        healthySeconds = 0
                        DiagnosticsLog.w(TAG, "Xray exited - restarting it ($restarts/$XRAY_MAX_RESTARTS).")
                        runCatching { runner.start(config, "session") }
                    }
                }
            }
        }
    }

    /**
     * TUN (or proxy mode), self-test and liveness for a session whose exit was
     * set up by the custom-config code itself (solo and the Psiphon chains).
     */
    private suspend fun finishCustom(
        profile: ConnectionProfile,
        port: Int,
        graceMs: Long?,
        alive: () -> Boolean,
    ) {
        if (profile.lanShare) runCatching { ShareCredentials.ensure(this) }
        if (profile.proxyMode) {
            check(ShareBridge.startSync(localOnly = !profile.lanShare, upstreamPort = port)) {
                getString(R.string.err_proxy_ports)
            }
        } else {
            establishTun(profile)
            startTun2Socks(profile, port)
            if (profile.lanShare) ShareBridge.start(localOnly = false, upstreamPort = port)
        }

        val diagPort = if (profile.proxyMode) ShareBridge.socksPort.value ?: port else port
        val healthy = smartGateFinal(profile, diagPort, graceMs, alive)
        check(healthy) { getString(R.string.err_selftest) }

        PingMonitor.setTunnelPort(port)
        PingMonitor.reset()
        ChainTrace.end("ok", "exit=$SOCKS_HOST:$port")
        AetherController.setState(ConnectionState.Connected("$SOCKS_HOST:$port"))
        updateNotification(getString(R.string.state_connected))
        DiagnosticsLog.i(TAG, "Custom-config tunnel ready on $SOCKS_HOST:$port.")

        var dead = 0
        var pipelineDead = 0
        var nextProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
        while (currentScopeActive()) {
            if (alive()) {
                dead = 0
            } else if (++dead >= CUSTOM_DEAD_CONFIRMATIONS) {
                break
            }
            if (SystemClock.elapsedRealtime() >= nextProbe) {
                val moving = dataPathIsMoving()
                if (probeTunnelCycle(port) || moving) {
                    pipelineDead = 0
                    nextProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_INTERVAL_MS
                } else if (++pipelineDead >= PIPELINE_DEAD_CONFIRMATIONS) {
                    DiagnosticsLog.w(TAG, "Rebuilding the session: the custom-config path carries nothing.")
                    break
                } else {
                    DiagnosticsLog.w(
                        TAG,
                        "Custom-config path carries nothing ($pipelineDead/$PIPELINE_DEAD_CONFIRMATIONS).",
                    )
                    nextProbe = SystemClock.elapsedRealtime() + PIPELINE_PROBE_RETRY_MS
                }
            }
            delay(1_000L)
        }
        if (currentScopeActive()) throw IllegalStateException(getString(R.string.cc_err_core_failed))
    }

    private fun stopXrayOnly() {
        synchronized(xrayLock) {
            xrayGeneration++
            xrayWatch?.cancel()
            xrayWatch = null
            runCatching { xray?.stop() }
        }
    }

    /** Everything the custom-config code started; a no-op for every other session. */
    private fun stopCustom() {
        stopXrayOnly()
        xray = null
        CustomSession.set(null)
    }

    /** The resolvers of the network the phone is really on (outside the VPN). */
    private fun underlyingDns(): List<String> = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
            ?: return@runCatching emptyList<String>()
        val network = cm.activeNetwork ?: return@runCatching emptyList<String>()
        val caps = cm.getNetworkCapabilities(network)
        if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) {
            return@runCatching emptyList<String>()
        }
        cm.getLinkProperties(network)?.dnsServers
            ?.filterIsInstance<java.net.Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            .orEmpty()
    }.getOrDefault(emptyList())

    /** True when the phone's real (non-VPN) internet path is mobile data. */
    @Suppress("DEPRECATION")
    private fun onMobileData(): Boolean = runCatching {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
            ?: return@runCatching true
        val physical = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .filter {
                !it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        val wifiOrWired = physical.any {
            it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        !wifiOrWired && physical.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) }
    }.getOrDefault(true)
    private fun currentScopeActive(): Boolean = runJob?.isActive ?: false

    /**
     * Runs a probe and turns a real failure into `false` -- but lets a
     * CANCELLATION through.
     *
     * ROOT CAUSE of the orphaned engine (1.3.0-r2): the self-test used to be
     * wrapped in `runCatching`, which catches Throwable and therefore also
     * CancellationException. Disconnecting while the session was verifying then
     * looked exactly like a failed self-test: the ladder logged "Self-test failed",
     * treated the cancelled session as a bad strategy, and SPAWNED THE NEXT ENGINE
     * -- which the already-cancelled coroutine could no longer supervise or stop.
     * The log of that session ends mid-attempt with a live libaether.so still
     * bootstrapping Tor and no app code left watching it.
     *
     * A cancellation is not a verdict about the tunnel. It has exactly one correct
     * answer: stop.
     */
    private suspend fun probeOrFalse(block: suspend () -> Boolean): Boolean =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }

    /**
     * Live bootstrap progress in the notification while a tor-fronted stage 1 is
     * coming up.
     *
     * Five minutes of a motionless "Building Tor circuits…" is indistinguishable
     * from a hung app, and that is precisely the window in which a first Tor
     * bootstrap is legitimately slow. The percentage the engine already prints is
     * shown instead; once the bootstrap has visibly stopped moving AND bridges are
     * permitted, the notification says THAT, because the wait is then no longer a
     * slow bootstrap but the engine's bridge fallback.
     */
    private fun launchBootstrapTicker(bridgesAllowed: Boolean): Job = scope.launch {
        var bridgeNoticeLogged = false
        while (true) {
            val snap = TorBootstrap.state.value
            val waitingOnBridges = bridgesAllowed && TorBootstrap.stalled(TOR_STALL_DIRECT_MS)
            val text = when {
                waitingOnBridges -> getString(R.string.state_tor_bridges)
                snap.percent >= 0 -> getString(R.string.state_tor_bootstrap_pct, snap.percent)
                else -> getString(R.string.state_tor_bootstrap)
            }
            updateNotification(text)
            if (waitingOnBridges && !bridgeNoticeLogged) {
                bridgeNoticeLogged = true
                DiagnosticsLog.w(
                    TAG,
                    "Tor has not advanced for ${TOR_STALL_DIRECT_MS / 1000}s " +
                        "(${TorBootstrap.describe()}) - this is what a network filtering Tor " +
                        "looks like. Waiting for the engine's bridge fallback.",
                )
            }
            delay(BOOTSTRAP_TICK_MS)
        }
    }

    /**
     * Why stage 1 failed, in the user's own terms.
     *
     * The generic "tunnel started but the self-test failed" was the single most
     * misleading string in 1.3.0: for a tor-fronted stage it was shown for a Tor
     * that had never even reached the network, which sent the user (and the in-app
     * assistant reading the log) looking for a tunnel problem that did not exist.
     */
    private fun stageFailureMessage(torFronted: Boolean): String {
        if (!torFronted) return getString(R.string.err_selftest)
        val snap = TorBootstrap.state.value
        return when {
            !TorBootstrap.seen -> getString(R.string.err_tor_no_progress)
            !snap.done -> getString(R.string.err_tor_blocked, snap.percent)
            else -> getString(R.string.err_tor_no_stream)
        }
    }

    private fun establishTun(profile: ConnectionProfile) {
        // 1.2.7-r3: a chained session's exit is a Psiphon server, and those are
        // IPv4-only in practice. See [buildTun] for why the TUN then carries no
        // IPv6 ADDRESS while still routing ::/0.
        var withoutIpv6Address = profile.backend.isChained
        var descriptor = runCatching { buildTun(profile, withoutIpv6Address).establish() }
            .getOrNull()
        if (descriptor == null && withoutIpv6Address) {
            // Some ROMs refuse an interface that routes a family it has no
            // address for. Falling back keeps the mode working exactly as it did
            // before; the front's AAAA suppression then carries the fix alone.
            DiagnosticsLog.w(
                TAG,
                "This device would not establish an IPv4-only-addressed TUN; re-establishing " +
                    "with the IPv6 address. AAAA suppression in the Psiphon front still applies.",
            )
            withoutIpv6Address = false
            descriptor = runCatching { buildTun(profile, false).establish() }.getOrNull()
        }
        tun = descriptor ?: throw IllegalStateException("Failed to establish the VPN interface")

        val mtu = profile.mtu.coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX)
        DiagnosticsLog.i(
            TAG,
            "TUN established: ipv4=${TunnelConfig.TUN_IPV4}/${TunnelConfig.TUN_IPV4_PREFIX} " +
                "ipv6=" + (
                if (withoutIpv6Address) {
                    "none (::/0 routed, so nothing leaks and the resolver stops returning AAAA)"
                } else {
                    "${TunnelConfig.TUN_IPV6}/${TunnelConfig.TUN_IPV6_PREFIX}"
                }
                ) + " mtu=$mtu split=${profile.splitMode} apps=${profile.splitApps.size} " +
                "dns=${TunnelConfig.DNS_SERVERS}",
        )
    }

    /**
     * Builds the TUN interface.
     *
     * ## Why [withoutIpv6Address] exists (1.2.7-r3 root-cause fix)
     *
     * The TUN used to carry an IPv6 address AND a `::/0` route unconditionally.
     * The route is right - it is what stops IPv6 from leaking past the tunnel with
     * the phone's real address, which is what produces a sanctions page instead of
     * the exit's answer. The ADDRESS is what causes the damage: it is the single
     * thing that tells Android's resolver "this network has IPv6", and from that
     * moment `getaddrinfo` returns AAAA records to every app on the device and
     * Happy Eyeballs prefers them. A chained session's exit is a Psiphon server,
     * and those cannot dial IPv6, so every one of those preferences is a
     * connection attempt that has to fail before the app tries IPv4 - and the apps
     * in the report (Gemini, ChatGPT, CapCut, TikTok) do not retry, they say "no
     * internet". The field log counted 65 of those refusals in two minutes.
     *
     * Dropping just the address is exactly the right lever, because Android's own
     * resolver already does the work: `netd` decides whether to hand out AAAA by
     * testing whether the network has a usable IPv6 SOURCE address. With the route
     * present but no address, that test fails, AAAA is filtered per-network by the
     * platform, every app settles on IPv4 by itself - and `::/0` still swallows any
     * IPv6 an app produces on its own, so there is still no leak.
     *
     * Plain Aether keeps its IPv6 address: WARP has real, working IPv6 and there is
     * nothing to protect against.
     */
    private fun buildTun(profile: ConnectionProfile, withoutIpv6Address: Boolean): Builder {
        // User-tunable MTU (defaults to 1280 -- safe for Iranian mobile/DPI).
        // Clamped to a sane range so a bad saved value can't break establish().
        val mtu = profile.mtu.coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX)
        val builder = Builder()
            .setSession("Aether")
            .setMtu(mtu)
            // The TUN address MUST match hev's tunnel.ipv4/ipv6 (see writeHevConfig).
            .addAddress(TunnelConfig.TUN_IPV4, TunnelConfig.TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)

        if (!withoutIpv6Address) {
            builder.addAddress(TunnelConfig.TUN_IPV6, TunnelConfig.TUN_IPV6_PREFIX)
        }

        // IPv6 LEAK PROTECTION (1.2.4): on by default -- the v6 default
        // route keeps IPv6 traffic inside the tunnel. Can be disabled for
        // networks where a default v6 route breaks connectivity.
        //
        // 1.2.7-r3 ROOT-CAUSE FIX (the "it shows a sanctions error" report): in a
        // CHAINED session that switch is not honoured. Without a ::/0 route the
        // phone's real IPv6 address is used directly for every AAAA destination,
        // so Gemini, ChatGPT and every sanctioned service sees the user's actual
        // address and answers with a country block - while the app still says
        // Connected, and while the very same session works perfectly from a
        // USB-tethered laptop, which has no IPv6 at all. That is not "a network
        // where v6 breaks connectivity", it is a leak with a user-visible
        // consequence, so here the route is unconditional.
        //
        // Nothing is lost by forcing it: with no IPv6 address on the interface the
        // device does not ask for AAAA in the first place, and the front answers
        // whatever IPv6 an app produces anyway in microseconds.
        val forceIpv6Route = profile.backend.isChained
        if (profile.ipv6LeakProtection || forceIpv6Route) {
            builder.addRoute("::", 0)
            if (forceIpv6Route && !profile.ipv6LeakProtection) {
                DiagnosticsLog.w(
                    TAG,
                    "IPv6 leak protection is off in the profile, but a chained session routes " +
                        "::/0 anyway: an uncaptured IPv6 flow would leave with the phone's real " +
                        "address and be answered with a sanctions block, not by the exit.",
                )
            }
        }

        // KILL SWITCH (1.2.4): a blocking interface never falls back to
        // direct traffic while the tunnel is not forwarding.
        if (profile.killSwitch || profile.strictKillSwitch) {
            builder.setBlocking(true)
        }

        TunnelConfig.DNS_SERVERS.forEach { builder.addDnsServer(it) }

        // Split tunneling + loop prevention (keeps the engine's own traffic off
        // the TUN, equivalent to v2rayNG's in-process protect()).
        applyAppFilter(builder, profile)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        return builder
    }

    /**
     * Applies the split-tunnel policy and always keeps the app's own engine
     * traffic off the TUN (loop prevention).
     *
     * - OFF     : everything routes through the VPN except our own package.
     * - INCLUDE : ONLY the chosen apps route through the VPN. Our own package is
     *             implicitly excluded because it is never added to the allow-list.
     * - EXCLUDE : everything routes through the VPN except the chosen apps + us.
     */
    private fun applyAppFilter(builder: Builder, profile: ConnectionProfile) {
        val apps = profile.splitApps.filter { it.isNotBlank() && it != packageName }
        when (profile.splitMode) {
            SplitMode.INCLUDE -> {
                if (apps.isEmpty()) {
                    // Nothing selected -> fall back to OFF so we don't build a
                    // tunnel that carries no traffic at all.
                    safeDisallow(builder, packageName)
                    return
                }
                apps.forEach { safeAllow(builder, it) }
            }
            SplitMode.EXCLUDE -> {
                safeDisallow(builder, packageName)
                // Blocked apps must stay INSIDE the TUN so the filter bridge
                // can drop their traffic; excluding them would give them
                // direct internet instead of none.
                apps.filter { it !in profile.blockedApps }.forEach { safeDisallow(builder, it) }
            }
            SplitMode.OFF -> safeDisallow(builder, packageName)
        }
    }

    private fun safeAllow(builder: Builder, pkg: String) {
        try {
            builder.addAllowedApplication(pkg)
        } catch (_: Exception) {
            DiagnosticsLog.w(TAG, "addAllowedApplication failed for $pkg (not installed?)")
        }
    }

    private fun safeDisallow(builder: Builder, pkg: String) {
        try {
            builder.addDisallowedApplication(pkg)
        } catch (_: Exception) {
            if (pkg != packageName) DiagnosticsLog.w(TAG, "addDisallowedApplication failed for $pkg")
        }
    }

    /**
     * Points the forwarder at [socksPort].
     *
     * The port is a parameter rather than the [SOCKS_PORT] constant because a
     * chained session's exit lives on [CHAIN_SOCKS_PORT]; hardcoding 1819 here
     * would silently forward the whole device through stage 1 and hand the user
     * an Aether exit IP while the UI promised a Psiphon exit.
     */
    private fun startTun2Socks(profile: ConnectionProfile, socksPort: Int) {
        activeForwardPort = socksPort
        if (profile.blockedApps.isNotEmpty()) {
            // PER-APP BLOCKING (1.2.4): hev-socks5-tunnel cannot filter per
            // UID, so a userspace filter bridge (merged into Aether's
            // SocksTunBridge) reads the TUN itself, resolves each flow's
            // owning app and drops blocked apps' packets. It is activated
            // ONLY when blocking is configured; the battle-tested hev path
            // below stays the default for everyone else.
            val pfd = tun ?: throw IllegalStateException("TUN descriptor is null")
            val bridge = SocksTunBridge(
                vpnService = this,
                tunDescriptor = pfd,
                socksHost = SOCKS_HOST,
                socksPort = socksPort,
                mtu = profile.mtu.coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX),
                blockedPackagesProvider = { profile.blockedApps.toSet() },
                routingEngine = RoutingEngine(emptyList()),
            )
            DiagnosticsLog.i(TAG, "Starting userspace filter bridge (blocked apps=${profile.blockedApps.size})")
            bridge.start()
            tunBridge = bridge
            return
        }
        val config = writeHevConfig(profile.mtu.coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX), socksPort)
        // Use the LIVE fd of the ParcelFileDescriptor (do NOT detach): hev uses it
        // while running and we close the pfd ourselves on teardown. The fd is only
        // valid inside THIS process, which is exactly why hev must run in-process.
        val fd = tun?.fd ?: throw IllegalStateException("TUN descriptor is null")
        DiagnosticsLog.i(TAG, "Starting hev-socks5-tunnel in-process (fd=$fd)")
        HevTunnel.start(config.absolutePath, fd)
        tunnelStarted = true
    }

    /**
     * Writes the hev-socks5-tunnel config in the exact shape v2rayNG uses.
     *
     * The critical difference from the previous (broken) version is the
     * `tunnel.ipv4` / `tunnel.ipv6` fields. hev configures its internal lwIP
     * netif from these; without them packets are pulled off the TUN fd but have
     * nowhere to be routed, so the tunnel "connects" but no site ever loads.
     * These MUST equal the VpnService addAddress values.
     */
    private fun writeHevConfig(mtu: Int, socksPort: Int): File {
        val file = File(filesDir, "hev.yaml")
        val yaml = """
            tunnel:
              mtu: $mtu
              ipv4: ${TunnelConfig.TUN_IPV4}
              ipv6: '${TunnelConfig.TUN_IPV6}'
            socks5:
              address: $SOCKS_HOST
              port: $socksPort
              udp: 'udp'
            misc:
              task-stack-size: 86016
              # 1.2.7-r2 MEDIA FIX: 5 s was a direct-dial budget, and a chained
              # session pays stage 1's latency AND Psiphon's own channel dial on
              # every flow. Under the connection fan-out of a video player those
              # dials routinely need longer, and hev abandoning them mid-load is
              # itself a stall the user reads as "it went slow and stopped".
              connect-timeout: 12000
              # 1.2.4 stability: the old 60s idle timeout killed long-lived
              # sessions ("works 1-2 minutes, then no site opens").
              tcp-read-write-timeout: 300000
              # 1.2.7-r3: back to 120000. r2 lowered this to 60 s because QUIC was
              # not carried, so UDP/443 associations were all dead weight. Now that
              # UDP/443 rides the bulk lane (see PsiphonSocksFront.CARRY_QUIC) a
              # live QUIC connection can legitimately idle: a paused video, a
              # backgrounded app, an idle HTTP/3 pool. Reaping it at 60 s forces a
              # new association with a new source port, which the peer sees as a
              # different client and answers with a fresh handshake - the "effects
              # never load / feed stops mid-scroll" symptom.
              udp-read-write-timeout: 120000
              # 1.2.7-r2: a video player opens flows in bursts of dozens; the
              # default descriptor budget is what runs out first when it does.
              limit-nofile: 65535
              log-level: warn
        """.trimIndent()
        file.writeText(yaml)
        DiagnosticsLog.i(TAG, "hev.yaml written:\n$yaml")
        return file
    }

    private fun stopEverything(reason: String = "user-stop") {
        // 1.6.0: a disconnect used to leave NO line in the diagnostics log; a log
        // that ended with "Engine output stream closed" could not say whether the
        // user, the tile, a revoke or a crash stopped the session.
        DiagnosticsLog.i(TAG, "Disconnect requested ($reason).")
        ChainTrace.stage("stop-requested", "reason=$reason")
        AetherController.setState(ConnectionState.Disconnecting)
        updateNotification(getString(R.string.state_disconnecting))
        val job = runJob
        runJob = null
        // DISCONNECT MUST BE INSTANT. Order matters:
        //   1. cancel the session coroutine (does not wait for it),
        //   2. kill the natives right away — this is what actually makes the
        //      tunnel stop, and it also unblocks any wait the session
        //      coroutine is parked in,
        //   3. flip the UI to Idle and drop the foreground notification,
        //   4. only THEN join the finished coroutine, off the critical path.
        // The previous order (join → cleanup) made the button sit on
        // "Disconnecting…" for as long as the supervisor's engine wait had
        // left to run — up to a full minute.
        job?.cancel()
        stopJob = scope.launch(Dispatchers.IO) {
            cleanupNativeOnly(reason)
            stopCustom()
            // STALE-CIRCLES FIX (part 2): clear the finished session's results
            // right at disconnect, so the panel never carries green circles
            // from a dead session into the next connect.
            Diagnostics.resetChecks()
            EngineMeta.reset()
            PingMonitor.resetTunnelPort()
            PingMonitor.reset()
            // Back to the documented ports for the next session: a relocation is a
            // fact about one session's environment, not a setting.
            PortLease.reset()
            AetherController.setState(ConnectionState.Idle)
            AetherTileService.requestUpdate(this@AetherVpnService)
            // ISSUE #20 / #23 ROOT CAUSE (1.3.1): the widget was repainted ONLY
            // from updateNotification(), and this path does not call it - the
            // notification is about to be dropped, not updated. So the last thing
            // ever written to the widget was the "Disconnecting…" painted at the
            // top of this method, and it stayed there forever: "when disconnect
            // with widget it shows disconnecting .... always". The tile was
            // already refreshed on the line above; the widget was simply missed.
            AetherWidgetProvider.updateAllWidgets(this@AetherVpnService)
            stopForegroundCompat()
            stopSelf()
            job?.join()
        }
    }

    /** Max automatic engine restarts (Smart Reconnect, 1.2.4). */
    private fun maxRetries(profile: ConnectionProfile): Int =
        if (profile.smartReconnect) profile.reconnectRetryLimit.coerceIn(1, 50) else 50

    /**
     * WATCHDOG PROBE, hardened (1.2.4 periodic-outage root-cause fix).
     *
     * The old probe was a single TCP connect to 1.1.1.1:53 with a 5 s
     * timeout. On high-RTT, lossy links (the tunnel's own baseline RTT is
     * 350-550 ms and DPI throttling causes multi-second UDP stalls that heal
     * by themselves) that lone probe fails SPURIOUSLY -- two unlucky probes
     * 30 s apart were enough to kill a perfectly healthy engine and force a
     * full endpoint rescan, which is itself a 30-90 s total outage. The cure
     * had become the disease: the periodic "no site opens, then it works
     * again" the user saw every few minutes was the watchdog restarting a
     * tunnel that was only briefly stalled.
     *
     * A check now only counts as failed when THREE attempts in a row --
     * spread over three different anycast resolvers, 8 s timeout each, 1.5 s
     * apart -- all fail, and the engine is restarted only after THREE
     * consecutive failed checks (90 s+ of continuously proven dead tunnel).
     * Brief self-healing stalls no longer trigger restarts, a genuinely dead
     * session still recovers automatically, and MASQUE's in-engine reconnect
     * loop gets room to finish before the app steps in.
     */
    private suspend fun probeTunnelCycle(port: Int = SOCKS_PORT): Boolean {
        registerSelfProbes()
        repeat(PROBE_ATTEMPTS) { attempt ->
            if (probeTunnelOnce(PROBE_TARGETS[attempt % PROBE_TARGETS.size], port)) return true
            if (attempt < PROBE_ATTEMPTS - 1) delay(PROBE_RETRY_GAP_MS)
        }
        return false
    }

    /**
     * Tells the Psiphon health watchdog (removed in 1.4.0) which destinations belong to the app's own health
     * checks, so a refusal of one can never be read as "this exit filters".
     * See [PROBE_TARGETS].
     */
    private fun registerSelfProbes() {
        if (selfProbesRegistered) return
        selfProbesRegistered = true
        for (target in PROBE_TARGETS) {
            PsiphonEngine.noteSelfProbe(target.first, target.second)
        }
        PingMonitor.probeTargets().forEach { (host, probePort) ->
            PsiphonEngine.noteSelfProbe(host, probePort)
        }
    }

    /**
     * Cumulative bytes the device data path has carried, straight from the
     * tunnel core's own counters, or -1 when they are unavailable.
     *
     * This is the ONLY honest answer to "is the data path moving?", and it is
     * the same number in both a plain and a chained session because hev is
     * always the thing between the TUN and the first SOCKS hop. A probe to one
     * destination is an opinion; this is the meter.
     */
    private fun dataPathBytes(): Long {
        val traffic = HevTunnel.traffic() ?: return -1L
        return traffic.downloadBytes + traffic.uploadBytes
    }

    /**
     * One end-to-end probe of [target] ("host:port") through the local SOCKS5
     * listener on [port]: a real DNS query, and a real answer required back.
     *
     * ROOT CAUSE this fixes (1.2.8) - the whole reason "connected but nothing
     * loads" could last until the user toggled the switch by hand. This probe
     * used to be a bare `connect()`, and a SOCKS5 connect is answered by the
     * engine's ACCEPT path, which is a different code path from the one that
     * carries payload. A tunnel whose data plane was wedged still completed
     * every handshake perfectly, so the watchdog kept marking the session
     * healthy, forever, while not one byte moved. Requiring a full round trip
     * means the watchdog now measures exactly what the user experiences.
     */
    private fun probeTunnelOnce(
        target: Triple<String, Int, String>,
        port: Int = SOCKS_PORT,
    ): Boolean = runCatching {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress(SOCKS_HOST, port),
        )
        java.net.Socket(proxy).use { socket ->
            socket.connect(
                java.net.InetSocketAddress(target.first, target.second),
                PROBE_TIMEOUT_MS,
            )
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.tcpNoDelay = true

            // A real TLS handshake on 443: ClientHello out, ServerHello +
            // certificate + Finished back. Bytes have to make the round trip in
            // both directions through the payload path for this to complete, and
            // 443 is the one port an exit cannot refuse without being useless.
            //
            // NO HOSTNAME VERIFICATION HERE, ON PURPOSE - do not "fix" this.
            // Unlike the geolocation probes (NetProbe.tlsWrap) and the Gemini
            // client (GeminiHttp.tlsWrap), which both verify, this handshake sends
            // no request and trusts nothing that comes back: the only thing read
            // off it is "did bytes round-trip". Verifying the certificate would
            // make a captive portal or an interception proxy indistinguishable from
            // a wedged tunnel, and the watchdog would restart the session in a loop
            // on exactly the networks where it must not.
            val factory = javax.net.ssl.SSLSocketFactory.getDefault()
                as javax.net.ssl.SSLSocketFactory
            val tls = factory.createSocket(socket, target.third, target.second, false)
                as javax.net.ssl.SSLSocket
            tls.soTimeout = PROBE_TIMEOUT_MS
            try {
                tls.startHandshake()
                tls.session.isValid
            } finally {
                runCatching { tls.close() }
            }
        }
    }.getOrDefault(false)

    /**
     * True when the device data path has carried real traffic since the previous
     * call, i.e. when a failed probe cannot possibly mean "wedged".
     *
     * Deliberately stateful and deliberately cheap: it reads the tunnel core's
     * cumulative counters and compares them with the last reading. Unavailable
     * counters return false, so a platform without them behaves exactly as
     * before instead of becoming un-restartable.
     */
    private fun dataPathIsMoving(): Boolean {
        val now = dataPathBytes()
        val previous = lastDataPathBytes
        lastDataPathBytes = now
        if (now < 0 || previous < 0) return false
        return now - previous >= DATA_PATH_ALIVE_BYTES
    }

    /**
     * KILL SWITCH lockdown (1.2.4): stop the engine and the forwarder but
     * KEEP a blocking full-tunnel TUN up, so every packet is blackholed
     * instead of leaking direct. The service stays foreground; connecting
     * again or disconnecting lifts the lockdown.
     */
    private fun enterLockdown(profile: ConnectionProfile) {
        val job = runJob
        runJob = null
        job?.cancel()
        stopJob = scope.launch(Dispatchers.IO) {
            cleanupForwardingOnly()
            stopCustom()
            ensureLockdownTun(profile)
            lockdownTunActive = true
            Diagnostics.resetChecks()
            EngineMeta.reset()
            AetherController.setState(ConnectionState.Error(getString(R.string.state_killswitch)))
            updateNotification(getString(R.string.state_killswitch))
            AetherTileService.requestUpdate(this@AetherVpnService)
            job?.join()
        }
    }

    /** Stops sharing, the forwarder and the engine but deliberately KEEPS [tun]. */
    private fun cleanupForwardingOnly() {
        activeForwardPort = 0
        sessionExitPort = 0
        try {
            ShareBridge.stop()
        } catch (_: Throwable) {
        }
        tunBridge?.let { runCatching { it.stop() } }
        tunBridge = null
        if (tunnelStarted) {
            try {
                HevTunnel.stop()
            } catch (_: Throwable) {
            }
            tunnelStarted = false
        }
        try {
            externalTransport?.stop()
        } catch (_: Throwable) {
        }
        externalTransport = null
        // Before the engine: the front's upstream is the engine's Tor listener, and
        // a front left running against a dead listener would answer tun2socks with
        // failures instead of letting the port close.
        try {
            if (TorSocksFront.isRunning()) {
                TorSocksFront.dropSummary()?.let { DiagnosticsLog.i(TAG, it) }
                TorSocksFront.stop()
            }
        } catch (_: Throwable) {
        }
        try {
            engine?.stop()
        } catch (_: Throwable) {
        }
        engine = null
    }

    /** (Re)builds the TUN as a full-tunnel blackhole: routes everything, reads nothing. */
    private fun ensureLockdownTun(profile: ConnectionProfile) {
        runCatching { tun?.close() }
        tun = null
        val builder = Builder()
            .setSession("Aether KillSwitch")
            .setMtu(profile.mtu.coerceIn(ConnectionProfile.MTU_MIN, ConnectionProfile.MTU_MAX))
            .addAddress(TunnelConfig.TUN_IPV4, TunnelConfig.TUN_IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        // AUDIT F-1: the lockdown TUN is a blackhole - nothing it captures is
        // forwarded anywhere. So v6 is claimed UNCONDITIONALLY here, unlike in
        // the real tunnel builder where profile.ipv6LeakProtection exists to let
        // a user rescue a broken v6 network. Gating the blackhole on that switch
        // left a v6 path in the clear during exactly the window the kill switch
        // is for: the tunnel is down, v4 is held, and v6 is not.
        builder.addAddress(TunnelConfig.TUN_IPV6, TunnelConfig.TUN_IPV6_PREFIX)
        builder.addRoute("::", 0)
        tun = runCatching { builder.establish() }.getOrNull()
    }

    /**
     * 1.6.0: every native teardown names its reason. The body is unchanged
     * ([cleanupNativeOnlyImpl]); this records WHO is tearing down and WHAT is still
     * alive at that moment, and invalidates the session epoch so a late exit-IP
     * enrichment cannot land on the next session. No default reason on purpose: a
     * new call site must say why it kills the natives.
     */
    private fun cleanupNativeOnly(reason: String) {
        val newEpoch = sessionEpoch.incrementAndGet()
        runCatching {
            ChainTrace.stage(
                "cleanup-native",
                "reason=$reason engine=${engine?.isAlive() == true} xray=${xray?.isAlive() == true} " +
                    "tun=${tun != null} transport=${externalTransport != null} epoch=$newEpoch",
            )
        }
        cleanupNativeOnlyImpl()
    }

    private fun cleanupNativeOnlyImpl() {
        activeForwardPort = 0
        sessionExitPort = 0
        // Stop sharing first: without the tunnel the bridge would leak direct.
        try {
            ShareBridge.stop()
        } catch (_: Throwable) {
        }
        tunBridge?.let { runCatching { it.stop() } }
        tunBridge = null
        if (tunnelStarted) {
            try {
                HevTunnel.stop()
            } catch (_: Throwable) {
            }
            tunnelStarted = false
        }
        try {
            externalTransport?.stop()
        } catch (_: Throwable) {
        }
        externalTransport = null
        // Before the engine: the front's upstream is the engine's Tor listener, and
        // a front left running against a dead listener would answer tun2socks with
        // failures instead of letting the port close.
        try {
            if (TorSocksFront.isRunning()) {
                TorSocksFront.dropSummary()?.let { DiagnosticsLog.i(TAG, it) }
                TorSocksFront.stop()
            }
        } catch (_: Throwable) {
        }
        try {
            engine?.stop()
        } catch (_: Throwable) {
        }
        engine = null
        try {
            tun?.close()
        } catch (_: Throwable) {
        }
        tun = null
    }

    private fun stopForegroundCompat() {
        foregroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // ISSUE #20 (1.3.1): "when it connects the notification says
        // disconnecting and stays". STOP_FOREGROUND_REMOVE only removes the
        // notification the service currently holds the foreground WITH. But
        // updateNotification() posts through NotificationManager.notify()
        // directly, so if the service was not foreground at that moment - a
        // disconnect arriving after the foreground was already dropped, or a
        // rescued start - that "Disconnecting…" notification belongs to nobody
        // and outlives the service. Cancelling the id explicitly is idempotent
        // and covers both cases.
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)?.cancel(NOTIF_ID)
        }
        lastNotifText = null
    }

    /**
     * Answers a start request the framework says went unanswered, from
     * [AetherApp]'s crash handler. Returns true when the service is foreground
     * afterwards.
     *
     * This exists as a SAFETY NET, not as the fix: the fix is that
     * [onStartCommand] promotes the service for every action. If a future path
     * ever forgets again, the app recovers with a notification instead of
     * vanishing while the tunnel is up.
     */
    fun rescueForeground(): Boolean {
        promoteToForeground(lastNotifText ?: getString(R.string.state_connecting))
        return foregroundActive
    }

    /**
     * Kills the natives from the crash handler so a dying app cannot leave a
     * running engine behind.
     *
     * The engine is a CHILD PROCESS: it survives its parent, which is exactly what
     * the 1.3.0 field log shows -- libaether.so kept bootstrapping Tor for eleven
     * seconds after the app code had stopped watching it. Bounded work only
     * (AetherProcess.stop gives SIGTERM 250 ms, then SIGKILL), because this runs
     * inside the crash path.
     */
    fun killNativesFromCrashHandler() {
        runCatching { cleanupNativeOnly("crash-handler") }
        runCatching { stopCustom() }
    }

    override fun onRevoke() {
        stopEverything("vpn-revoked")
        super.onRevoke()
    }

    override fun onDestroy() {
        if (live === this) live = null
        runJob?.cancel()
        cleanupNativeOnly("service-destroyed")
        stopCustom()
        // 1.3.1: the service can also die WITHOUT going through stopEverything -
        // task removal, a system kill, the user revoking VPN consent. Every one of
        // those left the widget showing whatever transient text it had last. The
        // tunnel is gone by the time we are here, so record Idle and repaint.
        // (WidgetStateCache would map a transient value to Idle on the next cold
        // read anyway, but a widget on a screen that is already visible does not
        // get a cold read - it gets nothing at all unless we paint it.)
        runCatching {
            AetherController.setState(ConnectionState.Idle)
            AetherTileService.requestUpdate(this)
            AetherWidgetProvider.updateAllWidgets(this)
        }
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnectIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AetherVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AetherApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_disconnect), disconnectIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIF_ID, buildNotification(text))
        // Keep the Quick Settings tile in sync with every state transition.
        AetherTileService.requestUpdate(this)
        // Keep the home-screen widget (feature merge) in sync too.
        // Cheap: returns immediately when no widget is placed.
        AetherWidgetProvider.updateAllWidgets(this)
    }

    companion object {
        /** Longest wait for the first answering config before the full run decides. */
        private const val FIRST_PICK_TIMEOUT_MS = 15_000L
        /** After the first answer: a short window so near-simultaneous answers are compared. */
        private const val FIRST_PICK_GRACE_MS = 700L
        /**
         * The local SOCKS5 port the device's traffic is forwarded to while a
         * session runs, 0 otherwise. The app itself is excluded from its own VPN,
         * so app-side downloads that must not leave on the bare network (the
         * custom-config subscription fetch) dial through this port instead.
         */
        @Volatile
        @JvmStatic
        var activeForwardPort: Int = 0
            internal set

        const val ACTION_CONNECT = "studio.cluvex.aether.CONNECT"
        const val ACTION_DISCONNECT = "studio.cluvex.aether.DISCONNECT"
        const val EXTRA_PROFILE = "profile"

        /**
         * The running service instance, or null when none is alive.
         *
         * Only the crash handler in [AetherApp] uses it, and only to promote the
         * service to the foreground when the framework complains that nobody did.
         * Cleared in [onDestroy], so it never keeps a dead Service reachable.
         */
        @Volatile
        var live: AetherVpnService? = null
            private set

        private const val NOTIF_ID = 0x4145
        private const val TAG = "vpn"
        private const val ZT_TAG = "zerotrust"
        private const val SOCKS_HOST = TunnelConfig.SOCKS_HOST

        // ------------------------------------------------------------------
        // 1.3.1, issues #27 and #52: these three were `const val`, i.e. the
        // number 1819 / 1825 / 1821 inlined at every use site. Android's Private
        // Space runs the app as another user but SHARES the network namespace,
        // so a second instance bound the same addresses and lost
        // ("Address already in use (os error 98)") before it ever tried a tunnel.
        //
        // They are now accessors over [PortLease], which is resolved once per
        // session in onStartCommand: the preferred port when it is free - so a
        // normal install is byte-identical in behaviour - and the next free one
        // when it is not. Everything in this file reads them unqualified and did
        // not have to change.
        // ------------------------------------------------------------------
        private val SOCKS_PORT: Int get() = PortLease.socks

        /** Where a chained session's SECOND stage listens (see connectExternal). */
        private val CHAIN_SOCKS_PORT: Int get() = PortLease.chain

        /** Where the DNS-capable Tor front listens (see connectTor). */
        private val TOR_FRONT_PORT: Int get() = PortLease.torFront

        /**
         * How long Tor may take to become usable.
         *
         * Minutes, not seconds, and deliberately so. A first bootstrap downloads a
         * directory consensus; on a network that blocks Tor the engine additionally
         * tries plainly for a while, asks bridgedb for bridges suited to the country
         * it appears to be in, and then works through them one transport at a time -
         * and a bridge only counts as working once a stream has actually opened
         * through it. Cutting that off early is how the 1.2.7 Tor backend earned its
         * reputation for hanging: it was not hanging, it was being killed halfway
         * through the one slow step Tor has.
         */
        private const val TOR_BOOTSTRAP_TIMEOUT_MS = 300_000L

        /**
         * The same budget, for a Tor that is allowed to fall back to bridges.
         *
         * The engine tries directly for `AETHER_TOR_BRIDGE_SECS` (360 s by
         * default) before it asks for bridges at all, so a 300-second app budget
         * guaranteed that the bridge fallback -- the only automatic escape on a
         * network that filters Tor -- could never once happen. The bridged budget
         * has to outlast that fallback plus a bootstrap through the bridge it
         * finds.
         */
        private const val TOR_BRIDGE_BUDGET_MS = 600_000L

        /**
         * How long the bootstrap percentage may stand still before the attempt is
         * abandoned.
         *
         * With bridges switched OFF a stalled bootstrap has nothing left to try,
         * so waiting out the whole budget only delays a failure the user could
         * already act on. With bridges permitted the stall IS the trigger for the
         * engine's fallback, so the patience has to cover it.
         */
        private const val TOR_STALL_DIRECT_MS = 45_000L
        private const val TOR_STALL_BRIDGED_MS = 420_000L

        /** Notification refresh while a Tor bootstrap is in progress. */
        private const val BOOTSTRAP_TICK_MS = 2_000L
        private const val MTU = TunnelConfig.MTU
        private const val MAX_RETRIES = 3
        private val BACKOFF = longArrayOf(2000L, 5000L, 10000L)

        /**
         * Upper bound for one blocking wait on the engine process (1.2.2).
         * The supervisor no longer polls; it parks on the process itself and
         * only wakes up this often to re-check its own cancellation state.
         */
        private const val SUPERVISOR_WAIT_MS = 60_000L

        /**
         * Consecutive one-second checks the external stage must fail before the
         * chained session is rebuilt (1.2.7). See connectExternal().
         */
        const val TRANSPORT_DEAD_CONFIRMATIONS = 5

        /**
         * Watchdog probe cadence while the tunnel is up.
         *
         * 1.2.8: 30 s -> 15 s. The probe now proves that bytes actually make the
         * round trip (see probeTunnelOnce), so a failure means something, and
         * waiting half a minute between meaningful checks is time the user
         * spends staring at a tunnel that says Connected and does nothing.
         */
        private const val WATCHDOG_INTERVAL_MS = 15_000L

        /**
         * Consecutive failed checks before the engine is restarted. 1.2.8: 3 ->
         * 2, for the same reason. Each check is already three round trips over
         * three different resolvers, so two failed checks is six independent
         * failures - plenty of evidence, and roughly half the dead air.
         */
        private const val WATCHDOG_FAIL_CYCLES = 2

        /**
         * Attempts per watchdog check, rotating over anycast endpoints so one
         * blocked or slow target can never fake a dead tunnel (1.2.4 fix).
         */
        private const val PROBE_ATTEMPTS = 3

        /**
         * Probe destinations as `ip`, `port`, `sni`.
         *
         * ## ROOT CAUSE this fixes (1.2.8-r3) - the self-inflicted reconnect loop
         *
         * These used to be `1.1.1.1:53`, `1.0.0.1:53`, `9.9.9.9:53`: DNS over
         * **TCP port 53**. A large share of Psiphon exits refuse TCP/53 outright,
         * and the app's own front says so in the log:
         *
         * ```text
         * PsiphonSocksFront this server does not allow outbound TCP/53
         * Psiphon: LocalProxyError ... ssh: rejected: administratively prohibited
         * ```
         *
         * So on those exits EVERY watchdog check failed, forever, on a tunnel
         * that was carrying traffic normally. The 1.2.8-r2 field log has both
         * verdicts one second apart, which is the proof:
         *
         * ```text
         * 09:33:36.081 diag: device dns via socks5 udp = true
         * 09:33:36.353 diag: dns+http OK, exit ip=146.59.70.6 cc=PL   <- real paths: fine
         * 09:33:36.288 ssh: rejected: administratively prohibited      <- probe: refused
         * ...
         * 09:34:39.090 ping: Latency probe failed (viaTunnel=true): Connect timed out
         * 09:34:59.391 the Psiphon health watchdog (removed in 1.4.0) ... refused 6 different destinations in 45s
         * 09:34:59.396 PsiphonSocksFront udpgw session closed
         * 09:35:03.298 Rebuilding the session: the data path is wedged
         * ```
         *
         * The probe failed, the failures also convicted the exit as a filtering
         * server, the rotation dropped the udpgw association (killing every
         * UDP/QUIC flow on the device) and then the pipeline watchdog tore the
         * whole session down. On a cadence. Which is precisely what the user
         * reported for *every* app and site: ping spikes, it drops, it comes
         * back, it repeats - and why nothing with a long-lived stream (a live
         * dubbing session, a video) ever survived a minute.
         *
         * Port 443 is the one port every exit must allow, and a TLS handshake to
         * it is a genuine payload round trip through the data plane, which is
         * what the probe was supposed to be measuring in the first place. Each
         * target is also registered with the Psiphon health watchdog (removed in 1.4.0) so it can never count
         * as evidence against a server again, whatever port it lands on.
         */
        private val PROBE_TARGETS = arrayOf(
            Triple("1.1.1.1", 443, "cloudflare-dns.com"),
            Triple("8.8.8.8", 443, "dns.google"),
            Triple("9.9.9.9", 443, "dns.quad9.net"),
        )
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val PROBE_RETRY_GAP_MS = 1_000L

        /**
         * Bytes the device data path must have carried since the previous check
         * for a failed probe to be dismissed as "busy, not broken".
         *
         * A wedged tunnel moves nothing at all; anything above idle keepalive
         * noise means the path works and the probe is the thing that is wrong.
         */
        private const val DATA_PATH_ALIVE_BYTES = 16 * 1024L

        /** How often a CHAINED session's full pipeline is probed end to end (1.2.8). */
        private const val PIPELINE_PROBE_INTERVAL_MS = 15_000L

        /** And how soon it is re-probed once a check has failed. */
        private const val PIPELINE_PROBE_RETRY_MS = 5_000L

        /** Consecutive dead pipeline readings before the session is rebuilt. */
        private const val PIPELINE_DEAD_CONFIRMATIONS = 2

        /**
         * How long to wait for the previous engine to release the local SOCKS5
         * port before starting a new one (1.2.2 protocol-switch fix).
         */
        private const val PORT_RELEASE_WAIT_MS = 3_000L

        /**
         * Cap for the FIRST attempt of a hand-picked protocol, so a throttled
         * network cannot hold the user on "Connecting" for the whole scan
         * budget before the hardened second pass is even tried.
         */
        private const val FIRST_PASS_MAX_MS = 75_000L

        /**
         * 1.7.0: the least time an attempt waits for its engine to SERVE after the
         * port is bound, even when the port itself took most of the budget.
         */
        private const val MIN_SERVE_WAIT_MS = 8_000L

        /** 1.7.0: budget of one fast single-hop pass of a hand-picked protocol. */
        private const val QUICK_PASS_MAX_MS = 45_000L

        /** 1.7.0 Smart: longest the confirming connect of a PROVEN route may take. */
        private const val CONFIRM_MAX_MS = 60_000L

        /** 1.7.0 (same version): smart-gate budget for a custom config (was 3 x 12 s HTTP checks). */
        private const val CUSTOM_GATE_BUDGET_MS = 40_000L

        /** 1.7.0 Smart: how long a lane may take to prove outbound TCP once it serves. */
        private const val LANE_TCP_GRACE_MS = 12_000L

        /** 1.7.0 Smart: poll period of the race supervisor. */
        private const val RACE_POLL_MS = 100L

        /**
         * 1.6.0: rung 1 of [customUpstreamPlan] - TURBO's engine scan plus the
         * identity/registration margin measured in the 2026-10-15 field log (1.6 s).
         */
        private const val UPSTREAM_QUICK_PASS_MAX_MS = 75_000L

        /** 1.5.0: how long Xray may take to open its local SOCKS5 port. */
        private const val XRAY_START_TIMEOUT_MS = 15_000L

        /** 1.5.0: automatic Xray restarts within one session before giving up. */
        private const val XRAY_MAX_RESTARTS = 5

        /** 1.5.0: consecutive dead checks (1 s apart) before a custom session is rebuilt. */
        private const val CUSTOM_DEAD_CONFIRMATIONS = 5
    }
}

/**
 * 1.6.0 CHAIN TRACE: one monotonic timeline per connect, written to the
 * diagnostics log the user exports.
 *
 * The 2026-10-15 field log of `Config -> Aether` ends with "Engine output stream
 * closed" 75.2 s after the spawn, inside a 180 s SOCKS wait and a 120 s engine
 * scan budget, and no line says WHO stopped the engine and Xray within 176 ms of
 * each other. Every stage, failure category and cleanupNativeOnly(reason) now
 * lands here with its offset from the connect start, and the timeline is dumped
 * as one block whenever a chain ends, so a failure reads top to bottom.
 *
 * File-private and lock-protected; safe to call from any thread, including the
 * crash handler.
 */
private object ChainTrace {
    private const val TAG = "chain"
    private const val MAX_EVENTS = 160
    private val lock = Any()
    private var startedAt = 0L
    private var epoch = 0L
    private var label = "-"
    private val events = ArrayDeque<String>()

    fun begin(epoch: Long, label: String) {
        synchronized(lock) {
            this.epoch = epoch
            this.label = label
            startedAt = SystemClock.elapsedRealtime()
            events.clear()
        }
        DiagnosticsLog.i(TAG, "begin epoch=$epoch chain=$label")
    }

    fun stage(name: String, detail: String = "") = record(name, detail, warn = false)

    fun warn(name: String, detail: String = "") = record(name, detail, warn = true)

    /**
     * Closes the current phase and dumps everything recorded since the last dump
     * as one block. Events keep accumulating afterwards (supervisor restarts, the
     * final teardown) and the next end() dumps only those.
     */
    fun end(outcome: String, detail: String = "") {
        record("end", "outcome=$outcome $detail".trim(), warn = outcome != "ok")
        val dump = synchronized(lock) {
            val text = events.joinToString("\n  ")
            events.clear()
            text
        }
        DiagnosticsLog.i(TAG, "timeline epoch=$epoch chain=$label\n  $dump")
    }

    fun elapsedMs(): Long = synchronized(lock) {
        if (startedAt == 0L) 0L else SystemClock.elapsedRealtime() - startedAt
    }

    private fun record(name: String, detail: String, warn: Boolean) {
        val line = "+%6.1fs %s%s".format(
            java.util.Locale.ROOT,
            elapsedMs() / 1000.0,
            name,
            if (detail.isBlank()) "" else " $detail",
        )
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(line)
        }
        if (warn) DiagnosticsLog.w(TAG, line) else DiagnosticsLog.i(TAG, line)
    }
}
