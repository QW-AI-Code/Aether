package studio.cluvex.aether

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.custom.ChainMode
import studio.cluvex.aether.data.PresetProfileCodec
import studio.cluvex.aether.data.PresetStore
import studio.cluvex.aether.data.PresetTransferCodec
import studio.cluvex.aether.model.ConnectionPreset
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.content.ActivityNotFoundException
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import studio.cluvex.aether.core.DiagnosticsLog
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.ai.AiSession
import studio.cluvex.aether.auth.AuthGate
import studio.cluvex.aether.auth.AuthRepository
import studio.cluvex.aether.auth.DemoAuthRepository
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.core.PortLease
import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.HomeScreen
import studio.cluvex.aether.ui.LoginCodeDialog
import studio.cluvex.aether.ui.announcements.WhatsNewGate
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File

@OptIn(kotlinx.coroutines.FlowPreview::class)
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
    private lateinit var presetStore: PresetStore
    private val presetMutex = Mutex()
    private val presetBusy = MutableStateFlow(false)
    private var importPreview by mutableStateOf<List<ConnectionPreset>?>(null)
    private val presetImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readPresetImport(uri)
    }
    private val presetExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportPresets(uri)
    }


    /** Sign-in gate. Swap DemoAuthRepository for a real backend implementation. */
    private lateinit var authRepo: AuthRepository

    // Holds the profile to connect with once VPN consent is granted.
    private var pendingProfile: ConnectionProfile? = null
    private val toggleInFlight = AtomicBoolean(false)
    private var lastToggleAt = -400L

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
            val profile = pendingProfile
            pendingProfile = null
            try {
                if (result.resultCode == RESULT_OK && profile != null) AetherController.connect(this, profile)
            } catch (e: Exception) {
                DiagnosticsLog.e(UI_TAG, "VPN consent callback failed: ${e.javaClass.simpleName}")
                AetherController.setState(ConnectionState.Error(getString(R.string.state_error)))
            } finally { toggleInFlight.set(false) }
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
        presetStore = PresetStore(applicationContext)

        // Load the persisted profile ONCE as the initial UI state; from then
        // on the in-memory state is the single source of truth for the UI.
        // compareAndSet: if the user already changed something before the
        // initial load finished, never overwrite their edit.
        lifecycleScope.launch {
            uiProfile.compareAndSet(null, profileStore.profile.first())
            try {
                withContext(Dispatchers.IO) { CustomConfigStore.load(applicationContext) }
                presetMutex.withLock {
                    presetStore.ensureMigrated(uiProfile.value ?: profileStore.profile.first(),
                        CustomConfigStore.state.value, getString(R.string.preset_default_name),
                        preservedName = getString(R.string.preset_preserved_name))
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { presetFailure(e) }
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
                .collect { snapshot ->
                    try { presetMutex.withLock {
                        if (uiProfile.value != snapshot) return@withLock
                        profileStore.save(snapshot)
                        presetStore.syncActive(snapshot, CustomConfigStore.state.value)
                    } } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { presetFailure(e) }
                }
        }

        lifecycleScope.launch {
            CustomConfigStore.state.map { Triple(it.activeId, it.enabled, it.mode) }
                .distinctUntilChanged().drop(1).debounce(PROFILE_SAVE_DEBOUNCE_MS).collect {
                    try { presetMutex.withLock {
                        uiProfile.value?.let { presetStore.syncActive(it, CustomConfigStore.state.value) }
                    } } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { presetFailure(e) }
                }
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
                    toggleConnection()
                }
            }
        }

        setContent {
            AetherTheme {
                val state by AetherController.state.collectAsState()
                val presetsFlow = androidx.compose.runtime.remember(presetStore) {
                    presetStore.state.catch { e ->
                        DiagnosticsLog.w(UI_TAG, "Preset read failed: ${e.javaClass.simpleName}")
                        emit(PresetStore.State())
                    }
                }
                val presets by presetsFlow.collectAsState(initial = PresetStore.State())
                val presetsBusy by presetBusy.collectAsState()
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
                            // The service owns exit-IP lookup for the actual Connected.socksAddr.
                            // No 100-second UI wait or fallback to the unrelated engine port.
                            // Optional AI analysis belongs to the established session.
                            // Exit-IP metadata is now independently fetched by the service;
                            // it is not a prerequisite for UI state or analysis of startup logs.
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
                            onToggleConnection = { toggleConnection() },
                            presets = presets.presets, activePresetId = presets.activeId,
                            onSelectPreset = ::applyPreset, onSavePresetAs = ::savePresetAs,
                            onDeletePreset = ::deletePreset, onRenamePreset = ::renamePreset,
                            onImportPresets = { presetImportLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                            onExportPresets = { presetExportLauncher.launch("Aether-1.6.0-presets.json") },
                            presetBusy = presetsBusy || toggleInFlight.get(),
                        )
                        // Current-version notice; no introductory pager gate.
                        WhatsNewGate()
                        importPreview?.let { preview ->
                            AlertDialog(onDismissRequest = { importPreview = null },
                                title = { Text(getString(R.string.preset_import)) },
                                text = { Text(getString(R.string.preset_import_preview, preview.size)) },
                                confirmButton = { TextButton(onClick = {
                                    importPreview = null
                                    presetAction {
                                        // Save pending edits of the current profile first, then
                                        // import, select and APPLY the imported profile.
                                        presetStore.syncActive(uiProfile.value ?: profileStore.profile.first(), CustomConfigStore.state.value)
                                        presetStore.importPresets(preview, activateFirst = true, custom = CustomConfigStore.state.value)
                                        applyActivePresetLocked()
                                    }
                                }) { Text(getString(R.string.preset_import)) } },
                                dismissButton = { TextButton(onClick = { importPreview = null }) {
                                    Text(getString(R.string.preset_cancel))
                                } })
                        }
                }
            }
        }
    }

    private fun toggleConnection() {
        if (presetBusy.value) return
        // Guard 1: ignore double-taps (no guard existed in 1.6.0).
        val now = SystemClock.elapsedRealtime()
        if (now - lastToggleAt < TOGGLE_DEBOUNCE_MS) return
        lastToggleAt = now

        val liveState = AetherController.state.value
        if (liveState.isConnected || liveState.isBusy) {
            try {
                AetherController.disconnect(this)
            } catch (e: Exception) {
                DiagnosticsLog.e(UI_TAG, "disconnect failed: ${e.javaClass.simpleName}")
            }
            return
        }

        // Guard 2: only one connect coroutine at a time.
        if (!toggleInFlight.compareAndSet(false, true)) return
        lifecycleScope.launch {
            try {
                val profile = uiProfile.value ?: profileStore.profile.first()
                val consent = AetherController.prepare(this@MainActivity)
                if (consent != null) {
                    pendingProfile = profile
                    try {
                        vpnPermissionLauncher.launch(consent)
                    } catch (e: ActivityNotFoundException) {
                        pendingProfile = null
                        DiagnosticsLog.e(UI_TAG, "VPN consent activity missing")
                        AetherController.setState(
                            ConnectionState.Error(getString(R.string.err_vpn_permission_unavailable)),
                        )
                    }
                } else {
                    AetherController.connect(this@MainActivity, profile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingProfile = null
                // Guard 3: never let the click path force-close the app; surface it as Error state.
                DiagnosticsLog.e(UI_TAG, "toggleConnection failed: ${e.javaClass.simpleName}")
                AetherController.setState(
                    ConnectionState.Error(getString(R.string.state_error)),
                )
            } finally {
                if (pendingProfile == null) toggleInFlight.set(false)
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
            runCatching { presetMutex.withLock {
                if (pending != uiProfile.value) return@withLock
                profileStore.save(pending)
                presetStore.syncActive(pending, CustomConfigStore.state.value)
            } }.onFailure { DiagnosticsLog.w(UI_TAG, "Profile flush failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun connectionLocked(): Boolean {
        val state = AetherController.state.value
        return state.isConnected || state.isBusy || toggleInFlight.get() || presetBusy.value
    }

    private fun presetFailure(e: Exception) {
        DiagnosticsLog.w(UI_TAG, "Preset operation failed: ${e.javaClass.simpleName}")
        Toast.makeText(this, getString(R.string.preset_operation_failed), Toast.LENGTH_LONG).show()
    }

    private fun presetAction(action: suspend () -> Unit) {
        if (connectionLocked()) return
        presetBusy.value = true
        lifecycleScope.launch {
            try { presetMutex.withLock {
                val state = AetherController.state.value
                if (state.isConnected || state.isBusy || toggleInFlight.get()) return@withLock
                action()
            } } catch (e: CancellationException) { throw e
            } catch (e: Exception) { presetFailure(e)
            } finally { presetBusy.value = false }
        }
    }

    private fun applyPreset(id: String) = presetAction {
        presetStore.syncActive(uiProfile.value ?: profileStore.profile.first(), CustomConfigStore.state.value)
        if (presetStore.setActive(id) != null) applyActivePresetLocked()
    }

    private fun savePresetAs(name: String) = presetAction {
        val current = uiProfile.value ?: profileStore.profile.first()
        presetStore.syncActive(current, CustomConfigStore.state.value)
        check(presetStore.saveAsNew(name, current, CustomConfigStore.state.value) != null)
    }

    private fun renamePreset(id: String, name: String) = presetAction {
        presetStore.rename(id, name)
    }

    private fun deletePreset(id: String) = presetAction {
        val result = presetStore.delete(id) ?: return@presetAction
        if (result.deletedWasActive) applyActivePresetLocked()
    }

    private suspend fun applyActivePresetLocked() {
        val preset = presetStore.snapshot().active ?: return
        val current = uiProfile.value ?: profileStore.profile.first()
        val merged = PresetProfileCodec.decode(preset.profile).copy(
            accessClientSecret = current.accessClientSecret, accessToken = current.accessToken,
            accessSignInToken = current.accessSignInToken,
            chainedStage = false, bindOverride = 0, enginePsiphonOnly = false)
        uiProfile.value = merged
        profileStore.save(merged)
        applyCustomSelection(preset)
        profileSaves.tryEmit(merged)
    }

    private fun applyCustomSelection(preset: ConnectionPreset) {
        val st = CustomConfigStore.state.value
        val target = preset.customConfigId?.takeIf { id -> st.configs.any { it.id == id } }
        if (target != null && target != st.activeId) CustomConfigStore.select(this, target)
        CustomConfigStore.setMode(this, ChainMode.fromStoredName(preset.chainMode))
        CustomConfigStore.setEnabled(this, preset.customEnabled && target != null)
        if (preset.customConfigId != null && target == null)
            DiagnosticsLog.w(UI_TAG, "Preset custom config missing; custom routing disabled")
    }

    private fun readPresetImport(uri: Uri) {
        if (connectionLocked()) return
        lifecycleScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= PresetTransferCodec.MAX_BYTES) { "Import is too large" }
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    } ?: error("Cannot open settings")
                    PresetTransferCodec.decode(Charsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes)).toString())
                }
                if (!connectionLocked()) importPreview = preview
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { presetFailure(e) }
        }
    }

    private fun exportPresets(uri: Uri) = presetAction {
        presetStore.syncActive(uiProfile.value ?: profileStore.profile.first(), CustomConfigStore.state.value)
        // Export the selected named preset, not an implicit backup of every preset.
        // Import remains compatible with older multi-preset files.
        val selected = checkNotNull(presetStore.captureActiveForExport(
            uiProfile.value ?: profileStore.profile.first(), CustomConfigStore.state.value))
        val text = PresetTransferCodec.encodeSelected(listOf(selected), selected.id)
        withContext(Dispatchers.IO) {
            val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot write settings")
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        Toast.makeText(this, getString(R.string.preset_export_done), Toast.LENGTH_LONG).show()
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

private const val TOGGLE_DEBOUNCE_MS = 400L
private const val UI_TAG = "MainActivity"
