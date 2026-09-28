package studio.cluvex.aether

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.ai.AiSession
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PortLease
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.data.OnboardingStore
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.HomeScreen
import studio.cluvex.aether.ui.LoginCodeDialog
import studio.cluvex.aether.ui.OnboardingScreen
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File

class MainActivity : ComponentActivity() {

    /**
     * Applies the in-app language before a single resource is read.
     *
     * This is why the choice lives in SharedPreferences rather than the DataStore
     * every other setting uses: `attachBaseContext` runs before `onCreate` and
     * cannot suspend, so an async read would paint the first frame in the old
     * language and then re-layout. See [LanguagePrefs].
     */
    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(base?.let { LanguagePrefs.wrap(it) } ?: base)
    }

    /**
     * Re-asserts the layout direction the language choice implies.
     *
     * `attachBaseContext` cannot do this on its own: the framework rewrites
     * `Locale.getDefault()` from the activity's own locale list after it runs, and
     * the decor view resolves its direction from exactly that. On an English phone
     * with the app set to Persian, that is what left the whole UI unmirrored. See
     * [LanguagePrefs] for the full chain.
     */
    private fun pinLayoutDirection() = LanguagePrefs.applyLayoutDirection(this)

    /**
     * `configChanges` keeps this activity alive across rotation, density and
     * uiMode changes, so nothing re-runs `onCreate` to re-apply the direction -
     * the framework's own re-application of the configuration, however, does run.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        pinLayoutDirection()
    }

    private lateinit var profileStore: ProfileStore

    /** Feature merge: first-run onboarding gate. */
    private lateinit var onboardingStore: OnboardingStore

    // Holds the profile to connect with once VPN consent is granted.
    private var pendingProfile: ConnectionProfile? = null

    // ------------------------------------------------------------------
    // SCRAMBLED-INPUT FIX (root cause): the UI used to render the settings —
    // including the ip:port / CIDR text fields — straight from the DataStore
    // flow while every keystroke was saved asynchronously. Fast typing raced
    // that disk round-trip: a keystroke was applied on top of a STALE value
    // that echoed back a moment later, so digits were dropped/reordered
    // ("127.0.0.1" -> "27.0.0.11") in EVERY locale, English and Persian alike.
    //
    // Fix: the UI owns a synchronous in-memory profile state updated
    // immediately on every change. DataStore is demoted to plain background
    // persistence: a single collector writes the LATEST snapshot (conflated),
    // so saves can never interleave or feed stale values back into the UI.
    // ------------------------------------------------------------------
    private val uiProfile = MutableStateFlow<ConnectionProfile?>(null)
    private val profileSaves = MutableSharedFlow<ConnectionProfile>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingProfile?.let { AetherController.connect(this, it) }
            }
            pendingProfile = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first frame: the window has to agree with the language, or
        // Compose inherits an LTR decor view and lays Persian out backwards.
        pinLayoutDirection()
        enableEdgeToEdge()
        profileStore = ProfileStore(applicationContext)
        onboardingStore = OnboardingStore(applicationContext)

        // Load the persisted profile ONCE as the initial UI state; from then
        // on the in-memory state is the single source of truth for the UI.
        // compareAndSet: if the user already changed something before the
        // initial load finished, never overwrite their edit.
        lifecycleScope.launch {
            uiProfile.compareAndSet(null, profileStore.profile.first())
        }
        // Single background writer persisting the latest profile snapshot.
        //
        // UI-SPEED: `conflate()` alone still writes as fast as the collector can
        // drain, so holding a key down in a text field produced a DataStore
        // commit per keystroke - each one a serialise plus an fsync, on a device
        // that is often already busy bringing a tunnel up. A short debounce
        // collapses a burst of typing into ONE write of the final value, and
        // conflate keeps only the newest snapshot in the meantime, so no
        // intermediate value is ever written and nothing can be lost: the UI
        // already owns the authoritative state in memory (see uiProfile).
        lifecycleScope.launch {
            profileSaves
                .conflate()
                .debounce(PROFILE_SAVE_DEBOUNCE_MS)
                .collect { snapshot -> profileStore.save(snapshot) }
        }

        maybeRequestNotificationPermission()

        // Feature merge: a previous run died with an uncaught JVM
        // exception — open the saved crash report once so the user can see and
        // copy it. The report file deletes itself on dismiss.
        if (savedInstanceState == null && File(filesDir, AetherApp.CRASH_FILE).exists()) {
            startActivity(Intent(this, CrashReportActivity::class.java))
        }

        // Launched from the Quick Settings tile while VPN consent was still
        // missing: run the normal connect flow, which shows the system's VPN
        // consent dialog and then connects.
        if (intent?.getBooleanExtra(EXTRA_CONNECT_ON_LAUNCH, false) == true) {
            intent.removeExtra(EXTRA_CONNECT_ON_LAUNCH)
            lifecycleScope.launch {
                val current = AetherController.state.value
                if (!current.isConnected && !current.isBusy) {
                    toggleConnection(current)
                }
            }
        }

        setContent {
            AetherTheme {
                // Feature merge: first-run onboarding gate. initial =
                // true so upgrading users never see a flash of the pager; a
                // fresh install flips to the pager as soon as the (fast)
                // DataStore read lands.
                val onboardingDone by onboardingStore.completed.collectAsState(initial = true)
                val state by AetherController.state.collectAsState()
                // Synchronous UI profile state (see uiProfile above); null
                // only until the one-time initial load completes.
                val profile by uiProfile.collectAsState()
                val connectedSince by AetherController.connectedSince.collectAsState()
                val ipInfo by AetherController.ipInfo.collectAsState()
                val ipLoading by AetherController.ipLoading.collectAsState()

                // Refresh the shown IP whenever the connection phase flips:
                //  - connected  -> exit server IP (through the SOCKS proxy) + flag
                //  - idle       -> the user's real operator IP (direct)
                // NOTE: any resting, non-busy state (Idle OR Error/failed
                // connect) must show the real IP — previously Error fell into
                // "busy" and the operator IP was never fetched after a failure.
                val phase = when {
                    state.isConnected -> "connected"
                    state.isBusy -> "busy"
                    else -> "idle"
                }
                LaunchedEffect(phase) {
                    when (phase) {
                        "connected" -> {
                            // FLAG-FLICKER FIX: the automatic self-test
                            // (Diagnostics) is the single owner of the exit-IP
                            // lookup. This block used to fire its OWN parallel
                            // lookup; whichever finished last overwrote the
                            // badge, and because geo providers can disagree
                            // about the exit country, the flag flickered or
                            // suddenly changed. Now we only WAIT for the
                            // self-test's result and fetch ourselves purely as
                            // a last-resort fallback (guarded by
                            // offerTunnelIpInfo, so it can never overwrite).
                            AetherController.setIpLoading(true)
                            // 1.2.2 CPU FIX: this used to busy-poll a StateFlow
                            // every 250 ms for up to 100 s — as many as 400
                            // pointless wake-ups on the UI dispatcher right
                            // after connecting, exactly when the device is
                            // already busy. StateFlow is observable, so we now
                            // SUSPEND until the value we are waiting for
                            // actually arrives (zero wake-ups in between) and
                            // simply bound that wait with a timeout.
                            withTimeoutOrNull(100_000L) {
                                AetherController.ipInfo.first { it?.viaTunnel == true }
                            }
                            if (AetherController.ipInfo.value?.viaTunnel != true) {
                                val info = withContext(Dispatchers.IO) {
                                    NetProbe.fetchIpInfoViaSocksWithRetry(
                                        TunnelConfig.SOCKS_HOST,
                                        PortLease.socks,
                                    )
                                }
                                if (info != null) {
                                    AetherController.offerTunnelIpInfo(
                                        IpEndpoint(info.ip, info.countryCode, true),
                                    )
                                }
                            }
                            AetherController.setIpLoading(false)

                            // 1.2.9 AI: analyse THIS session's log and propose
                            // tuning, if the user asked for that.
                            //
                            // Here, at the end of the connected branch, and
                            // deliberately not inside the VpnService: by this point
                            // the tunnel is verified, the self-test has written its
                            // results into the log, and the exit IP is known - so the
                            // log the model reads is the complete story of the
                            // connect rather than its first two seconds. AiSession
                            // does the rest of the gating itself (feature off, no
                            // key, wrong mode, too soon after the last run).
                            uiProfile.value?.let { current ->
                                AiSession.analyze(
                                    profile = current,
                                    state = AetherController.state.value,
                                    persian = LanguagePrefs.isPersian(this@MainActivity),
                                    auto = true,
                                    onApply = { patched ->
                                        // The UI owns the profile (see uiProfile), so
                                        // an AI-applied change goes through exactly
                                        // the same path a tapped switch does.
                                        uiProfile.value = patched
                                        profileSaves.tryEmit(patched)
                                    },
                                )
                            }
                        }
                        "idle" -> {
                            AetherController.setIpInfo(null)
                            AetherController.setIpLoading(true)
                            val info = withContext(Dispatchers.IO) { NetProbe.fetchIpInfoDirectWithRetry() }
                            AetherController.setIpInfo(info?.let { IpEndpoint(it.ip, it.countryCode, false) })
                            AetherController.setIpLoading(false)
                        }
                        else -> {
                            AetherController.setIpInfo(null)
                            AetherController.setIpLoading(false)
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    // ZERO TRUST (1.3.1, issue #12): the engine may stop mid-connect
                    // and wait on stdin for the one-time code the organization just
                    // e-mailed. Placed here rather than inside a screen because the
                    // request can arrive while the user is anywhere in the app, and
                    // a prompt that only exists on one screen is one they miss.
                    // Renders nothing unless a request is outstanding.
                    LoginCodeDialog()
                    if (!onboardingDone) {
                        OnboardingScreen(
                            onFinished = {
                                lifecycleScope.launch { onboardingStore.markCompleted() }
                            },
                        )
                    } else {
                        HomeScreen(
                            state = state,
                            profile = profile ?: ConnectionProfile(),
                            connectedSince = connectedSince,
                            ipInfo = ipInfo,
                            ipLoading = ipLoading,
                            onProfileChange = { updated ->
                                // Update the UI synchronously — keystrokes must
                                // never wait for disk I/O — then persist in the
                                // background.
                                uiProfile.value = updated
                                profileSaves.tryEmit(updated)
                            },
                            onToggleConnection = { toggleConnection(state) },
                        )
                    }
                }
            }
        }
    }

    private fun toggleConnection(state: ConnectionState) {
        if (state.isConnected || state.isBusy) {
            AetherController.disconnect(this)
            return
        }
        lifecycleScope.launch {
            val profile = uiProfile.value ?: profileStore.profile.first()
            val consent = AetherController.prepare(this@MainActivity)
            if (consent != null) {
                pendingProfile = profile
                vpnPermissionLauncher.launch(consent)
            } else {
                AetherController.connect(this@MainActivity, profile)
            }
        }
    }

    /**
     * Flushes the pending settings snapshot when the activity leaves the
     * foreground.
     *
     * The debounce above is a batching window, not a place to lose data: if the
     * user changes a setting and immediately leaves, the coroutine that would
     * have written it 300ms later is cancelled with the lifecycle scope. Saving
     * here closes that window. `lifecycleScope` is already cancelled by the time
     * `onDestroy` runs, so this uses the store's own scope via a plain launch on
     * the process scope instead.
     */
    override fun onStop() {
        super.onStop()
        val pending = uiProfile.value ?: return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { profileStore.save(pending) }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** Set by the Quick Settings tile when it needs the consent dialog. */
        const val EXTRA_CONNECT_ON_LAUNCH = "studio.cluvex.aether.CONNECT_ON_LAUNCH"

        /**
         * How long a settings change waits before it is written to disk.
         *
         * Long enough to swallow a burst of typing, short enough that the value
         * is durable well before the user can leave the screen - and irrelevant
         * to correctness either way, because a connect reads the in-memory
         * profile, not the stored one.
         */
        private const val PROFILE_SAVE_DEBOUNCE_MS = 300L
    }
}
