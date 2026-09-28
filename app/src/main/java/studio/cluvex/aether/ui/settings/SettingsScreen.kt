package studio.cluvex.aether.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiSession
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.core.AccessToken
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.SmartPlusPlan
import studio.cluvex.aether.data.AppLanguage
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.ExternalKind
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TorBridges
import studio.cluvex.aether.model.TorMode
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.transport.ExitRegions
import studio.cluvex.aether.transport.TorCountries
import studio.cluvex.aether.ui.AboutPanel
import studio.cluvex.aether.ui.SharePanel
import studio.cluvex.aether.ui.ai.AiAdvisorPage
import studio.cluvex.aether.ui.ai.AiChatScreen
import studio.cluvex.aether.ui.ai.AiHostContext
import studio.cluvex.aether.ui.ai.AiSettingsPage
import studio.cluvex.aether.ui.ai.LocalAiHost
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.DiagnosticsPanel
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.SegmentedSelector
import studio.cluvex.aether.ui.components.SecureSurface

/**
 * Every screen the settings area can show.
 *
 * A flat enum plus an explicit stack, instead of a navigation library: the graph
 * is one level deep, so a dependency and the recomposition its host graph brings
 * would buy nothing. The stack lives in [SettingsHost] and the system back
 * gesture pops it.
 */
enum class SettingsRoute {
    HOME,

    /**
     * The shortcut page behind the tune icon on the home screen (1.2.9).
     *
     * ROOT CAUSE it fixes: that icon and the drawer's "Settings" row both opened
     * [HOME], so the same full settings tree - This device, App, everything -
     * appeared in a place that is meant to be a shortcut to the tunnel itself.
     * This route shows the Tunnel group and the reset action ONLY; everything
     * else stays where it belongs, in the drawer's Settings.
     */
    QUICK,
    CONNECTION,
    TRANSPORT,
    DNS_ROUTING,
    UPSTREAM,
    ZERO_TRUST,
    APPS,
    SECURITY,
    ENGINE,
    APPEARANCE,

    /**
     * 1.2.9 AI: the assistant's own settings (key, model, behaviour), the chat, and
     * the log/DPI advisor.
     *
     * Three routes rather than one screen with tabs, for the reason the whole
     * settings area is built this way: only the open page is composed, so the chat
     * - which owns a text field, an animated indicator and an auto-scrolling list -
     * costs nothing at all while the user is reading about their API key.
     */
    AI,
    AI_CHAT,
    AI_ADVISOR,
    DIAGNOSTICS,
    SHARE,
    ABOUT,
}

/**
 * The settings area.
 *
 * Only ONE page is composed at a time, and a page is a list of independently lazy
 * sections, so opening settings costs the rows on screen rather than the fifty
 * controls the old single-card panel built in one frame. See SettingsUi.kt.
 */
@Composable
fun SettingsHost(
    start: SettingsRoute,
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // QUICK is a ROOT, not a page under HOME: back from the shortcut page has to
    // leave settings, not descend into the full tree the shortcut exists to avoid.
    //
    // 1.2.9: AI_CHAT is a root for exactly the same reason. It is opened straight
    // from the home screen's AI button, and backing out of a chat into the settings
    // list would be the same bug in a new place.
    val stack = remember {
        val root = when (start) {
            SettingsRoute.QUICK -> SettingsRoute.QUICK
            SettingsRoute.AI_CHAT -> SettingsRoute.AI_CHAT
            else -> SettingsRoute.HOME
        }
        mutableStateListOf(root).also {
            if (start != root && start != SettingsRoute.HOME) it.add(start)
        }
    }
    val route = stack.last()

    val open: (SettingsRoute) -> Unit = { next -> stack.add(next) }
    val back: () -> Unit = {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        } else {
            onClose()
        }
    }

    BackHandler(enabled = true) { back() }

    // Engine settings can only change while the tunnel is down: they are
    // command-line flags handed to a process that is already running.
    val editable = state is ConnectionState.Idle || state is ConnectionState.Error

    // 1.2.9 AI: ONE provider for the whole settings area.
    //
    // Every AI icon in every page reads its context from here (see
    // [studio.cluvex.aether.ui.ai.AiHostContext]), which is what keeps "an AI icon
    // next to every option" from meaning "two extra parameters on every row, page
    // and call site in the settings tree". It also means the icons vanish
    // everywhere at once when the user turns the hints off.
    val aiSettings by AiSession.settings.collectAsState()
    CompositionLocalProvider(
        LocalAiHost provides AiHostContext(
            profile = profile,
            state = state,
            enabled = aiSettings.showHints,
            openAiSettings = { open(SettingsRoute.AI) },
            openConnection = { open(SettingsRoute.CONNECTION) },
            // Lets any explanation sheet, anywhere in the settings tree, hand its
            // topic to the assistant and land the user in the chat. Wired here
            // because this is the only place in the app that owns the stack.
            openChat = { open(SettingsRoute.AI_CHAT) },
        ),
    ) {
    when (route) {
        SettingsRoute.HOME -> SettingsHomePage(profile, editable, open, onProfileChange, onClose, modifier)
        SettingsRoute.QUICK ->
            SettingsHomePage(profile, editable, open, onProfileChange, onClose, modifier, quick = true)
        SettingsRoute.CONNECTION -> ConnectionPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.TRANSPORT -> TransportPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.DNS_ROUTING -> DnsRoutingPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.UPSTREAM -> UpstreamPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.ZERO_TRUST -> ZeroTrustPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.APPS -> AppsPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.SECURITY -> SecurityPage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.ENGINE -> EnginePage(profile, editable, onProfileChange, back, modifier)
        SettingsRoute.APPEARANCE -> AppearancePage(back, modifier)
        SettingsRoute.DIAGNOSTICS -> PanelPage(stringResource(R.string.diag_title), back, modifier) {
            DiagnosticsPanel(startExpanded = true)
        }
        SettingsRoute.SHARE -> PanelPage(stringResource(R.string.share_title), back, modifier) {
            SharePanel(
                state = state,
                profile = profile,
                onProfileChange = onProfileChange,
                startExpanded = true,
            )
        }
        SettingsRoute.ABOUT -> PanelPage(stringResource(R.string.about_title), back, modifier) {
            AboutPanel(startExpanded = true)
        }
        SettingsRoute.AI -> AiSettingsPage(
            profile = profile,
            state = state,
            onBack = back,
            onOpenChat = { open(SettingsRoute.AI_CHAT) },
            onOpenAdvisor = { open(SettingsRoute.AI_ADVISOR) },
            modifier = modifier,
        )
        SettingsRoute.AI_CHAT -> AiChatScreen(
            state = state,
            profile = profile,
            onProfileChange = onProfileChange,
            onBack = back,
            modifier = modifier,
        )
        SettingsRoute.AI_ADVISOR -> AiAdvisorPage(
            profile = profile,
            state = state,
            onProfileChange = onProfileChange,
            onBack = back,
            modifier = modifier,
        )
    }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun SettingsHomePage(
    profile: ConnectionProfile,
    editable: Boolean,
    onOpen: (SettingsRoute) -> Unit,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 1.2.9: the shortcut page ([SettingsRoute.QUICK]) is this same page with the
     * Tunnel group and the reset action only. One page, one set of metrics, no
     * second copy of four navigation rows to drift out of step with these.
     */
    quick: Boolean = false,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val title =
        if (quick) stringResource(R.string.quick_settings_title)
        else stringResource(R.string.settings_title)

    SettingsScaffold(title, onBack, modifier) {
        if (!editable) {
            settingsSection {
                SettingsGroup {
                    SettingsNoticeRow(
                        text = stringResource(R.string.settings_locked_hint),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
        }

        // The decisions that change what the tunnel actually IS come first, each
        // with its current value on the row, so the whole configuration can be
        // read without opening anything.
        settingsSection {
            GroupCaption(stringResource(R.string.cat_group_tunnel))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.cat_connection),
                    summary = stringResource(R.string.cat_connection_desc),
                    icon = Icons.Rounded.Layers,
                    value = backendShortLabel(profile.backend),
                    onClick = { onOpen(SettingsRoute.CONNECTION) },
                    aiTopic = AiTopic.BACKEND,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_transport),
                    summary = stringResource(R.string.cat_transport_desc),
                    icon = Icons.Rounded.Bolt,
                    value = noizeLabel(profile.noize),
                    onClick = { onOpen(SettingsRoute.TRANSPORT) },
                    aiTopic = AiTopic.NOIZE,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.cat_dns_routing),
                    summary = stringResource(R.string.cat_dns_routing_desc),
                    icon = Icons.Rounded.Dns,
                    onClick = { onOpen(SettingsRoute.DNS_ROUTING) },
                    aiTopic = AiTopic.DNS,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_upstream),
                    summary = stringResource(R.string.cat_upstream_desc),
                    icon = Icons.Rounded.Link,
                    onClick = { onOpen(SettingsRoute.UPSTREAM) },
                    aiTopic = AiTopic.UPSTREAM,
                )
            }
        }

        if (!quick) settingsSection {
            GroupCaption(stringResource(R.string.cat_group_device))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.cat_apps),
                    summary = stringResource(R.string.cat_apps_desc),
                    icon = Icons.Rounded.Apps,
                    onClick = { onOpen(SettingsRoute.APPS) },
                    aiTopic = AiTopic.SPLIT_MODE,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_security),
                    summary = stringResource(R.string.cat_security_desc),
                    icon = Icons.Rounded.Lock,
                    onClick = { onOpen(SettingsRoute.SECURITY) },
                    aiTopic = AiTopic.KILL_SWITCH,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.share_title),
                    summary = stringResource(R.string.share_subtitle),
                    icon = Icons.Rounded.Wifi,
                    onClick = { onOpen(SettingsRoute.SHARE) },
                    aiTopic = AiTopic.SHARE,
                )
            }
        }

        // 1.2.9 AI: first row of the App group, because it is the entry point to
        // three screens rather than one setting - and because a user who has not
        // set a key yet needs to find it without reading the whole tree.
        if (!quick) settingsSection {
            GroupCaption(stringResource(R.string.cat_ai))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ai_title),
                    summary = stringResource(R.string.cat_ai_desc),
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = { onOpen(SettingsRoute.AI) },
                    aiTopic = AiTopic.AI_CHAT,
                )
            }
        }

        if (!quick) settingsSection {
            GroupCaption(stringResource(R.string.cat_group_app))
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.language_title),
                    summary = stringResource(R.string.cat_appearance_desc),
                    icon = Icons.Rounded.Language,
                    value = languageLabel(LanguagePrefs.read(context)),
                    onClick = { onOpen(SettingsRoute.APPEARANCE) },
                    aiTopic = AiTopic.LANGUAGE,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.diag_title),
                    summary = stringResource(R.string.diag_subtitle),
                    icon = Icons.Rounded.BugReport,
                    onClick = { onOpen(SettingsRoute.DIAGNOSTICS) },
                    aiTopic = AiTopic.DIAGNOSTICS,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_engine_tuning),
                    summary = stringResource(R.string.cat_engine_desc),
                    icon = Icons.Rounded.Tune,
                    onClick = { onOpen(SettingsRoute.ENGINE) },
                    aiTopic = AiTopic.TLS_GROUPS,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.section_zerotrust),
                    summary = stringResource(R.string.cat_zerotrust_desc),
                    icon = Icons.Rounded.VpnKey,
                    onClick = { onOpen(SettingsRoute.ZERO_TRUST) },
                    aiTopic = AiTopic.ZERO_TRUST,
                )
                // ISSUE #5 (1.3.1): "when I turn the screen off and on again the
                // connection drops - the VPN still says connected but nothing
                // loads." On most vendor Android builds that is Doze / app standby
                // suspending the process the tunnel lives in, and the only cure is
                // an exemption the USER has to grant; an app cannot grant it to
                // itself.
                //
                // The row only appears while the exemption is missing, so it
                // disappears once it is dealt with rather than becoming permanent
                // furniture. It opens the system's own battery-optimisation list:
                // ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS needs NO permission,
                // unlike ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which needs
                // REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest. Keeping the
                // permission count at five was worth one extra tap.
                val power = context.getSystemService(PowerManager::class.java)
                fun readExempt(): Boolean =
                    runCatching { power?.isIgnoringBatteryOptimizations(context.packageName) }
                        .getOrNull() ?: true
                var exempt by remember(power) { mutableStateOf(readExempt()) }
                // r8: read once and remembered, the row outlived the grant: the
                // user taps it, allows Aether in the system list, comes back - and
                // this page, which never left the composition, still shows it.
                // Re-read on every resume, which is exactly the moment the user
                // returns from that system screen.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, power) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) exempt = readExempt()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                if (!exempt) {
                    RowDivider()
                    SettingsNavRow(
                        title = stringResource(R.string.battery_title),
                        summary = stringResource(R.string.battery_desc),
                        icon = Icons.Rounded.BatteryAlert,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                )
                            }
                        },
                    )
                }
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.about_title),
                    summary = stringResource(R.string.about_subtitle),
                    icon = Icons.Rounded.Info,
                    onClick = { onOpen(SettingsRoute.ABOUT) },
                )
            }
        }

        settingsSection {
            SettingsGroup {
                SettingsActionRow(
                    title = stringResource(R.string.reset_settings),
                    summary = stringResource(R.string.reset_confirm_body),
                    icon = Icons.Rounded.RestartAlt,
                    enabled = editable,
                    destructive = true,
                    onClick = { confirmReset = true },
                    aiTopic = AiTopic.RESET,
                )
            }
        }
    }

    if (confirmReset) {
        // A confirmation, which the old inline button did not have: one tap wiped
        // every setting, including hand-typed endpoint ranges and enrolment
        // details, with no way back.
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onProfileChange(ConnectionProfile())
                    Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.reset_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- connection

@Composable
private fun ConnectionPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.cat_connection), onBack, modifier) {
    settingsSection {
        GroupCaption(stringResource(R.string.backend_label))
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.backend_label),
                icon = Icons.Rounded.Layers,
                options = TransportBackend.entries,
                selected = profile.backend,
                label = { backendShortLabel(it) },
                onSelect = { onProfileChange(profile.copy(backend = it)) },
                enabled = editable,
                aiTopic = AiTopic.BACKEND,
            )
            RowDivider()
            SettingsChoiceRow(
                title = stringResource(R.string.exit_country_label),
                icon = Icons.Rounded.Public,
                options = ExitRegions.values,
                selected = profile.exitRegion.takeIf { it in ExitRegions.values } ?: "",
                label = { ExitRegions.label(it) },
                onSelect = { onProfileChange(profile.copy(exitRegion = it)) },
                enabled = editable && profile.backend.usesExternal,
                summary = when {
                    // Tor picks its own exit country, per circuit, and there is no
                    // setting anywhere that changes that. Saying so is better than
                    // a greyed-out row with a sentence about Aether next to it.
                    profile.backend.needsTorFront -> stringResource(R.string.exit_help_tor)
                    profile.backend.externalKind == ExternalKind.PSIPHON ->
                        stringResource(R.string.exit_help_psiphon)
                    else -> stringResource(R.string.exit_help_aether)
                },
                aiTopic = AiTopic.EXIT_REGION,
            )
        }
        GroupFooter(backendHelp(profile.backend))
    }

    // ---- Tor (engine core 2.0.0) -------------------------------------------
    //
    // Shown only when the chosen mode actually contains Tor. A settings group that
    // does nothing is worse than a missing one: it invites the user to change
    // something and then silently ignores it.
    if (profile.backend.usesTor) {
        settingsSection {
            GroupCaption(stringResource(R.string.section_tor))
            SettingsGroup {
                SettingsChoiceRow(
                    title = stringResource(R.string.tor_bridges_title),
                    icon = Icons.Rounded.Dns,
                    options = TorBridges.entries,
                    selected = profile.torBridges,
                    label = { torBridgesLabel(it) },
                    onSelect = { onProfileChange(profile.copy(torBridges = it)) },
                    // In `Aether -> Tor` the guards are dialled through the tunnel,
                    // so the local network never sees Tor and a bridge has nothing
                    // to hide from. The row is disabled rather than hidden, because
                    // the reason is worth reading.
                    enabled = editable && profile.backend.torMode != TorMode.CHAIN,
                    summary = if (profile.backend.torMode != TorMode.CHAIN) {
                        stringResource(R.string.tor_bridges_desc)
                    } else {
                        stringResource(R.string.tor_bridges_not_needed)
                    },
                    aiTopic = AiTopic.TOR_BRIDGES,
                )
                if (profile.backend.torMode != TorMode.CHAIN) {
                    RowDivider(inset = false)
                    SettingsChoiceRow(
                        title = stringResource(R.string.tor_country_title),
                        icon = Icons.Rounded.Public,
                        // Blank means "let the engine detect it"; the rest are the
                        // countries where a bridge is most often the only way in.
                        options = TorCountries.values,
                        selected = profile.torCountry.trim().lowercase()
                            .takeIf { TorCountries.isOffered(it) } ?: "",
                        label = { torCountryLabel(it) },
                        onSelect = { onProfileChange(profile.copy(torCountry = it)) },
                        summary = stringResource(R.string.tor_country_desc),
                        enabled = editable,
                        aiTopic = AiTopic.TOR_COUNTRY,
                    )
                    RowDivider(inset = false)
                    SettingsChoiceRow(
                        title = stringResource(R.string.tor_direct_secs_title),
                        icon = Icons.Rounded.Timer,
                        // Presets, not a free number: the useful range is narrow and a
                        // typed value here is a way to make Tor look broken.
                        options = TOR_DIRECT_PRESETS,
                        selected = profile.torDirectSecs
                            .takeIf { it in TOR_DIRECT_PRESETS } ?: 0,
                        label = { torDirectLabel(it) },
                        onSelect = { onProfileChange(profile.copy(torDirectSecs = it)) },
                        summary = stringResource(R.string.tor_direct_secs_desc),
                        enabled = editable,
                        aiTopic = AiTopic.TOR_DIRECT_SECS,
                    )
                    RowDivider(inset = false)
                    // A text BLOCK, not a row: bridge lines are long, LTR and
                    // multi-line, which is exactly what SettingsBlock hosts (the
                    // same shape the routing rules use).
                    SettingsBlock(
                        helper = stringResource(R.string.tor_bridge_lines_desc),
                        aiTopic = AiTopic.TOR_BRIDGE_LINES,
                    ) {
                        LtrOutlinedTextField(
                            value = profile.torBridgeLines,
                            onValueChange = { onProfileChange(profile.copy(torBridgeLines = it)) },
                            enabled = editable,
                            singleLine = false,
                            label = { Text(stringResource(R.string.tor_bridge_lines_title)) },
                            placeholder = { Text(stringResource(R.string.tor_bridge_lines_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                RowDivider(inset = false)
                // Applies in every Tor mode, chained included: the proof runs after
                // the bootstrap, and the default target can be blocked either way.
                SettingsBlock(
                    helper = stringResource(R.string.tor_check_desc),
                    aiTopic = AiTopic.TOR_CHECK,
                ) {
                    LtrOutlinedTextField(
                        value = profile.torCheck,
                        onValueChange = { onProfileChange(profile.copy(torCheck = it)) },
                        enabled = editable,
                        singleLine = true,
                        isError = profile.torCheck.isNotBlank() &&
                            profile.sanitizedTorCheck() == null,
                        label = { Text(stringResource(R.string.tor_check_title)) },
                        placeholder = { Text(stringResource(R.string.tor_check_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            GroupFooter(stringResource(R.string.tor_group_footer))
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.section_core))
        SettingsGroup {
            SettingsBlock(title = stringResource(R.string.protocol), aiTopic = AiTopic.PROTOCOL) {
                SegmentedSelector(
                    options = Protocol.entries,
                    selected = profile.protocol,
                    onSelect = { onProfileChange(profile.copy(protocol = it)) },
                    label = { protocolLabel(it) },
                    // The reverse chain runs MASQUE/HTTP-2 or nothing: the engine
                    // refuses --wg and --gool through Tor. Disabled with the reason
                    // written out, rather than accepting a choice that gets silently
                    // overridden in Profile.effectiveProtocol.
                    enabled = editable && profile.backend.usesAetherEngine &&
                        profile.backend.torMode != TorMode.REVERSE,
                )
                if (profile.backend.torMode == TorMode.REVERSE) {
                    SettingsNoticeRow(
                        text = stringResource(R.string.protocol_forced_reverse),
                        icon = Icons.Rounded.Info,
                    )
                }
                // Smart says which of its two implementations this profile gets:
                // the race needs the plain Aether backend and automatic endpoint
                // selection, and a user who picked Smart on a chained backend should
                // not have to read the diagnostics log to learn that.
                if (profile.protocol == Protocol.AUTO) {
                    SettingsNoticeRow(
                        text = stringResource(
                            if (SmartPlusPlan.eligible(profile)) {
                                R.string.protocol_auto_race
                            } else {
                                R.string.protocol_auto_ladder
                            },
                        ),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
            RowDivider(inset = false)
            SettingsChoiceRow(
                title = stringResource(R.string.scan_mode),
                icon = Icons.Rounded.Speed,
                options = ScanMode.entries,
                selected = profile.scanMode,
                label = { scanLabel(it) },
                onSelect = { onProfileChange(profile.copy(scanMode = it)) },
                enabled = editable && profile.backend.usesAetherEngine,
                aiTopic = AiTopic.SCAN_MODE,
            )
            RowDivider(inset = false)
            SettingsBlock(title = stringResource(R.string.ip_version), aiTopic = AiTopic.IP_VERSION) {
                SegmentedSelector(
                    options = IpVersion.entries,
                    selected = profile.ipVersion,
                    onSelect = { onProfileChange(profile.copy(ipVersion = it)) },
                    label = { ipLabel(it) },
                    enabled = editable && profile.backend.usesAetherEngine,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- transport

@Composable
private fun TransportPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_transport), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.noize_title),
                icon = Icons.Rounded.Bolt,
                options = Noize.entries,
                selected = profile.noize,
                label = { noizeLabel(it) },
                onSelect = { onProfileChange(profile.copy(noize = it)) },
                enabled = editable && profile.backend.usesAetherEngine,
                aiTopic = AiTopic.NOIZE,
            )
        }
        GroupFooter(stringResource(R.string.noize_desc))
    }

    settingsSection {
        GroupCaption(stringResource(R.string.endpoint_mode), aiTopic = AiTopic.ENDPOINT_MODE)
        SettingsGroup {
            SettingsBlock {
                SegmentedSelector(
                    options = EndpointMode.entries,
                    selected = profile.endpointMode,
                    onSelect = { onProfileChange(profile.copy(endpointMode = it)) },
                    label = { endpointLabel(it) },
                    enabled = editable && profile.backend.usesAetherEngine,
                )
            }
            if (profile.endpointMode == EndpointMode.MANUAL_PEER) {
                RowDivider(inset = false)
                SettingsBlock(aiTopic = AiTopic.MANUAL_PEER) {
                    LtrOutlinedTextField(
                        value = profile.manualPeer,
                        onValueChange = { onProfileChange(profile.copy(manualPeer = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.manual_peer_label)) },
                        placeholder = { Text(stringResource(R.string.manual_peer_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (profile.endpointMode == EndpointMode.MANUAL_RANGE) {
                RowDivider(inset = false)
                SettingsBlock(aiTopic = AiTopic.MANUAL_RANGE) {
                    LtrOutlinedTextField(
                        value = profile.manualRange,
                        onValueChange = { onProfileChange(profile.copy(manualRange = it)) },
                        enabled = editable,
                        singleLine = false,
                        label = { Text(stringResource(R.string.manual_range_label)) },
                        placeholder = { Text(stringResource(R.string.manual_range_hint)) },
                        supportingText = { Text(stringResource(R.string.manual_range_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_tuning))
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.keepalive_label),
                options = ConnectionProfile.KEEPALIVE_PRESETS,
                selected = profile.keepalive,
                label = { if (it == 0) stringResource(R.string.keepalive_default) else "$it" },
                onSelect = { onProfileChange(profile.copy(keepalive = it)) },
                enabled = editable,
                aiTopic = AiTopic.KEEPALIVE,
            )
            RowDivider(inset = false)
            SettingsChoiceRow(
                title = stringResource(R.string.mtu_label),
                options = ConnectionProfile.MTU_PRESETS,
                selected = profile.mtu.takeIf { it in ConnectionProfile.MTU_PRESETS } ?: 0,
                label = { "$it" },
                onSelect = { onProfileChange(profile.copy(mtu = it)) },
                enabled = editable,
                aiTopic = AiTopic.MTU,
            )
            RowDivider(inset = false)
            // ISSUE #33 (1.3.1): "let the MTU be chosen freely, not by clicking
            // between options." The presets stay - they are the four or five values
            // that actually matter and most users should not be typing here - but
            // path MTU is a property of a network, and a user hunting for the one
            // value that stops Telegram stalling needs to be able to enter it.
            //
            // The field owns its own text rather than reading profile.mtu back,
            // because a partially typed number ("12" on the way to "1280") is not a
            // valid MTU and must not be pushed into the profile: the SCRAMBLED-INPUT
            // fix in MainActivity solved echo, not semantics. Only a value inside
            // the range the TUN can actually carry is committed.
            var mtuText by remember(profile.mtu) { mutableStateOf(profile.mtu.toString()) }
            val mtuTyped = mtuText.toIntOrNull()
            val mtuBad = mtuText.isNotEmpty() &&
                (mtuTyped == null || mtuTyped !in ConnectionProfile.MTU_MIN..ConnectionProfile.MTU_MAX)
            LtrOutlinedTextField(
                value = mtuText,
                onValueChange = { typed ->
                    mtuText = typed.filter { it.isDigit() }.take(5)
                    mtuText.toIntOrNull()
                        ?.takeIf { it in ConnectionProfile.MTU_MIN..ConnectionProfile.MTU_MAX }
                        ?.let { onProfileChange(profile.copy(mtu = it)) }
                },
                enabled = editable,
                singleLine = true,
                isError = mtuBad,
                label = { Text(stringResource(R.string.mtu_custom_label)) },
                supportingText = {
                    Text(
                        if (mtuBad) {
                            stringResource(
                                R.string.mtu_custom_range,
                                ConnectionProfile.MTU_MIN,
                                ConnectionProfile.MTU_MAX,
                            )
                        } else {
                            stringResource(R.string.mtu_custom_help)
                        },
                    )
                },
                keyboardType = KeyboardType.Number,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        GroupFooter(stringResource(R.string.mtu_desc))
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_antidpi))
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.fragment_title),
                summary = stringResource(R.string.fragment_desc),
                checked = profile.fragment,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(fragment = it)) },
                aiTopic = AiTopic.FRAGMENT,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.ech_title),
                summary = stringResource(R.string.ech_desc),
                checked = profile.ech,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(ech = it)) },
                aiTopic = AiTopic.ECH,
            )
            // ISSUE #44 (1.3.1): only meaningful while ECH is on, so it is not
            // shown otherwise - an always-visible field for a switched-off feature
            // is a setting people fill in and then wonder about.
            if (profile.ech) {
                LtrOutlinedTextField(
                    value = profile.echConfig,
                    onValueChange = { onProfileChange(profile.copy(echConfig = it.trim())) },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.ech_config_label)) },
                    supportingText = { Text(stringResource(R.string.ech_config_help)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.masque_http2),
                summary = stringResource(R.string.masque_http2_desc),
                checked = profile.masqueHttp2,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(masqueHttp2 = it)) },
                aiTopic = AiTopic.MASQUE_HTTP2,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.quick_reconnect),
                summary = stringResource(R.string.quick_reconnect_desc),
                checked = profile.quickReconnect,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(quickReconnect = it)) },
                aiTopic = AiTopic.QUICK_RECONNECT,
            )
            if (profile.quickReconnect) {
                RowDivider(inset = false)
                SettingsSwitchRow(
                    title = stringResource(R.string.fast_endpoint_title),
                    summary = stringResource(R.string.fast_endpoint_desc),
                    checked = profile.fastEndpointOnly,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(fastEndpointOnly = it)) },
                    aiTopic = AiTopic.FAST_ENDPOINT,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- dns + routing

@Composable
private fun DnsRoutingPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.cat_dns_routing), onBack, modifier) {
    settingsSection {
        GroupCaption(stringResource(R.string.dns_label))
        SettingsGroup {
            SettingsBlock(helper = stringResource(R.string.dns_help), aiTopic = AiTopic.DNS) {
                LtrOutlinedTextField(
                    value = profile.dnsServers,
                    onValueChange = { onProfileChange(profile.copy(dnsServers = it)) },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.dns_label)) },
                    placeholder = { Text(stringResource(R.string.dns_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.section_routes), aiTopic = AiTopic.ROUTE_BLOCK)
        SettingsGroup {
            // 1.4.0 smart routing (core/SmartLists.kt)
            SettingsSwitchRow(
                title = stringResource(R.string.bypass_iran_title),
                summary = stringResource(R.string.bypass_iran_desc),
                checked = profile.bypassIran,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(bypassIran = it)) },
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.block_ads_title),
                summary = stringResource(R.string.block_ads_desc),
                checked = profile.blockAds,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(blockAds = it)) },
            )
            RowDivider(inset = false)
            SettingsBlock(aiTopic = AiTopic.ROUTE_DIRECT) {
                LtrOutlinedTextField(
                    value = profile.routeBlock,
                    onValueChange = { onProfileChange(profile.copy(routeBlock = it)) },
                    enabled = editable,
                    singleLine = false,
                    label = { Text(stringResource(R.string.route_block_label)) },
                    placeholder = { Text(stringResource(R.string.route_block_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = profile.routeDirect,
                    onValueChange = { onProfileChange(profile.copy(routeDirect = it)) },
                    enabled = editable,
                    singleLine = false,
                    label = { Text(stringResource(R.string.route_direct_label)) },
                    placeholder = { Text(stringResource(R.string.route_direct_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.route_sniff_title),
                summary = stringResource(R.string.route_sniff_desc),
                checked = profile.routeSniff,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(routeSniff = it)) },
                aiTopic = AiTopic.ROUTE_SNIFF,
            )
            if (profile.routeSniff) {
                RowDivider(inset = false)
                SettingsBlock(aiTopic = AiTopic.ROUTE_SNIFF_MS) {
                    LtrOutlinedTextField(
                        value = if (profile.routeSniffMs == 0) "" else profile.routeSniffMs.toString(),
                        onValueChange = {
                            onProfileChange(
                                profile.copy(
                                    routeSniffMs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0,
                                ),
                            )
                        },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.route_sniff_ms_label)) },
                        placeholder = { Text(stringResource(R.string.route_sniff_ms_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        GroupFooter(stringResource(R.string.routes_help))
    }
}

// ---------------------------------------------------------------- upstream

@Composable
private fun UpstreamPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_upstream), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsBlock(aiTopic = AiTopic.UPSTREAM) {
                LtrOutlinedTextField(
                    value = profile.upstreamProxy,
                    onValueChange = { onProfileChange(profile.copy(upstreamProxy = it)) },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.upstream_label)) },
                    placeholder = { Text(stringResource(R.string.upstream_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        GroupFooter(stringResource(R.string.upstream_help))
    }
}

// ---------------------------------------------------------------- zero trust

@Composable
private fun ZeroTrustPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_zerotrust), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsChoiceRow(
                title = stringResource(R.string.team_auth_label),
                icon = Icons.Rounded.VpnKey,
                options = TeamAuth.entries,
                selected = profile.teamAuth,
                label = { teamAuthLabel(it) },
                onSelect = { onProfileChange(profile.copy(teamAuth = it)) },
                enabled = editable,
                aiTopic = AiTopic.ZERO_TRUST,
            )
        }
        GroupFooter(stringResource(R.string.team_auth_desc))
    }

    if (profile.teamAuth != TeamAuth.OFF) {
        settingsSection {
            // AUDIT F-3: the Access service token / client secret are typed and
            // stored here. Screenshot-, screen-record- and recents-blind while
            // this section is composed.
            SecureSurface()
            SettingsGroup {
                SettingsBlock {
                    LtrOutlinedTextField(
                        value = profile.team,
                        onValueChange = { onProfileChange(profile.copy(team = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.team_label)) },
                        placeholder = { Text(stringResource(R.string.team_hint)) },
                        supportingText = { Text(stringResource(R.string.team_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (profile.teamAuth) {
                        TeamAuth.SERVICE_TOKEN -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessClientId,
                                onValueChange = { onProfileChange(profile.copy(accessClientId = it)) },
                                enabled = editable,
                                singleLine = true,
                                label = { Text(stringResource(R.string.access_id_label)) },
                                // 1.3.1: which of the three methods needs the user
                                // present, and which does not, was nowhere on this
                                // screen. This one does not.
                                supportingText = { Text(stringResource(R.string.team_auth_service_note)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessClientSecret,
                                onValueChange = { onProfileChange(profile.copy(accessClientSecret = it)) },
                                enabled = editable,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text(stringResource(R.string.access_secret_label)) },
                                supportingText = { Text(stringResource(R.string.access_secret_help)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TeamAuth.EMAIL -> {
                            Spacer(Modifier.height(12.dp))
                            LtrOutlinedTextField(
                                value = profile.accessEmail,
                                onValueChange = { onProfileChange(profile.copy(accessEmail = it)) },
                                enabled = editable,
                                singleLine = true,
                                label = { Text(stringResource(R.string.access_email_label)) },
                                // 1.3.1, issue #12: say what actually happens. Since
                                // 1.4.0-r9 the user can sign in beforehand in the
                                // group below (ZeroTrustMembershipGroup); without
                                // that, the code is asked for DURING the connect, in
                                // a dialog (see LoginCodeDialog), and the engine
                                // waits five minutes for it.
                                supportingText = { Text(stringResource(R.string.team_auth_email_note)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TeamAuth.TOKEN -> {
                            Spacer(Modifier.height(12.dp))
                            // 1.3.1: tell the user what the verdict on their paste
                            // is, next to the field, instead of letting a bad or
                            // expired token become a failed connect whose reason is
                            // one line in the diagnostics log. Same two checks the
                            // engine makes; see [AccessToken] for why this is not
                            // and cannot be a verification.
                            val verdict = AccessToken.inspect(profile.accessToken)
                            LtrOutlinedTextField(
                                value = profile.accessToken,
                                onValueChange = { onProfileChange(profile.copy(accessToken = it)) },
                                enabled = editable,
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                label = { Text(stringResource(R.string.access_token_label)) },
                                isError = verdict is AccessToken.Verdict.Malformed ||
                                    verdict is AccessToken.Verdict.Expired,
                                supportingText = {
                                    Text(
                                        when (verdict) {
                                            AccessToken.Verdict.Empty ->
                                                stringResource(R.string.access_token_how)
                                            AccessToken.Verdict.Malformed ->
                                                stringResource(R.string.access_token_bad)
                                            AccessToken.Verdict.Expired ->
                                                stringResource(R.string.access_token_expired)
                                            is AccessToken.Verdict.Usable -> when (val d = verdict.daysLeft) {
                                                null -> stringResource(R.string.access_token_nodate)
                                                0L -> stringResource(R.string.access_token_ok_soon)
                                                // See the note in LoginCodeDialog:
                                                // a count in front of a noun is a
                                                // <plurals>, not a format string.
                                                else -> pluralStringResource(
                                                    R.plurals.access_token_ok,
                                                    d.toInt(),
                                                    d,
                                                )
                                            }
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        TeamAuth.OFF -> Unit
                    }
                }
                RowDivider(inset = false)
                SettingsSwitchRow(
                    title = stringResource(R.string.gateway_title),
                    summary = stringResource(R.string.gateway_desc),
                    checked = profile.gateway,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(gateway = it)) },
                    aiTopic = AiTopic.GATEWAY,
                )
            }
        }
        // 1.4.0-r9 (issue #12): membership and "sign in first, then connect".
        // Its own group, below the fields it reads, so the status always sits
        // under the team and address it is about. See ZeroTrustMembership.kt.
        settingsSection {
            ZeroTrustMembershipGroup(profile, editable)
        }
    }
}

// ---------------------------------------------------------------- apps

@Composable
private fun AppsPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSplitPicker by remember { mutableStateOf(false) }
    var showBlockedPicker by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copied = stringResource(R.string.share_copied)
    // The ports the bridge is ACTUALLY listening on, falling back to the standard
    // ones while it is not running (1.3.1).
    //
    // These two rows used to print the constants unconditionally, while the Share
    // card on the home screen showed the live values from the same object - so the
    // two screens could disagree, and this one is the one with a copy button. The
    // bridge binds a fixed port and retries it rather than moving, so today the
    // live value is either the constant or null; reading it anyway means this row
    // cannot start lying if that ever changes.
    val liveSocksPort by ShareBridge.socksPort.collectAsState()
    val liveHttpPort by ShareBridge.httpPort.collectAsState()
    val socks = "127.0.0.1:${liveSocksPort ?: ShareBridge.SOCKS_SHARE_PORT}"
    val http = "127.0.0.1:${liveHttpPort ?: ShareBridge.HTTP_SHARE_PORT}"

    SettingsScaffold(stringResource(R.string.cat_apps), onBack, modifier) {
        settingsSection {
            GroupCaption(stringResource(R.string.section_routing))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.proxy_mode_title),
                    summary = stringResource(R.string.proxy_mode_desc),
                    icon = Icons.Rounded.Link,
                    checked = profile.proxyMode,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(proxyMode = it)) },
                    aiTopic = AiTopic.PROXY_MODE,
                )
                if (profile.proxyMode) {
                    RowDivider(inset = false)
                    // Fixed, standard ports: the same value every session, so
                    // what the user copies into another app keeps working.
                    SettingsValueRow(
                        title = stringResource(R.string.proxy_socks_label),
                        value = socks,
                    ) {
                        CopyButton {
                            clipboard.setText(AnnotatedString(socks))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                    RowDivider(inset = false)
                    SettingsValueRow(
                        title = stringResource(R.string.proxy_http_label),
                        value = http,
                    ) {
                        CopyButton {
                            clipboard.setText(AnnotatedString(http))
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        settingsSection {
            GroupCaption(stringResource(R.string.split_mode), aiTopic = AiTopic.SPLIT_MODE)
            SettingsGroup {
                SettingsBlock {
                    SegmentedSelector(
                        options = SplitMode.entries,
                        selected = profile.splitMode,
                        onSelect = { onProfileChange(profile.copy(splitMode = it)) },
                        label = { splitLabel(it) },
                        enabled = editable,
                    )
                }
                if (profile.splitMode != SplitMode.OFF) {
                    RowDivider(inset = false)
                    SettingsActionRow(
                        title = stringResource(R.string.split_select_apps, profile.splitApps.size),
                        icon = Icons.Rounded.Apps,
                        enabled = editable,
                        onClick = { showSplitPicker = true },
                        aiTopic = AiTopic.BLOCKED_APPS,
                    )
                }
            }
        }

        settingsSection {
            SettingsGroup {
                SettingsActionRow(
                    title = stringResource(R.string.blocked_select_apps, profile.blockedApps.size),
                    icon = Icons.Rounded.Block,
                    enabled = editable,
                    onClick = { showBlockedPicker = true },
                    aiTopic = AiTopic.BLOCKED_APPS,
                )
            }
            GroupFooter(stringResource(R.string.blocked_apps_desc))
        }
    }

    if (showSplitPicker) {
        AppPickerDialog(
            selected = profile.splitApps,
            onDismiss = { showSplitPicker = false },
            onConfirm = {
                onProfileChange(profile.copy(splitApps = it))
                showSplitPicker = false
            },
        )
    }
    if (showBlockedPicker) {
        AppPickerDialog(
            selected = profile.blockedApps,
            onDismiss = { showBlockedPicker = false },
            onConfirm = {
                onProfileChange(profile.copy(blockedApps = it))
                showBlockedPicker = false
            },
        )
    }
}

@Composable
private fun CopyButton(onCopy: () -> Unit) {
    IconButton(onClick = onCopy) {
        Icon(
            imageVector = Icons.Rounded.ContentCopy,
            contentDescription = stringResource(R.string.share_copy),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------------------------------------------------------------- security

@Composable
private fun SecurityPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_security), onBack, modifier) {
    settingsSection {
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.kill_switch_title),
                summary = stringResource(R.string.kill_switch_desc),
                icon = Icons.Rounded.Lock,
                checked = profile.killSwitch,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(killSwitch = it)) },
                aiTopic = AiTopic.KILL_SWITCH,
            )
            if (profile.killSwitch) {
                RowDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.strict_kill_switch_title),
                    summary = stringResource(R.string.strict_kill_switch_desc),
                    checked = profile.strictKillSwitch,
                    enabled = editable,
                    onCheckedChange = { onProfileChange(profile.copy(strictKillSwitch = it)) },
                    aiTopic = AiTopic.STRICT_KILL,
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.ipv6_leak_title),
                summary = stringResource(R.string.ipv6_leak_desc),
                checked = profile.ipv6LeakProtection,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(ipv6LeakProtection = it)) },
                aiTopic = AiTopic.IPV6_LEAK,
            )
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_recovery))
        SettingsGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.reprovision_title),
                summary = stringResource(R.string.reprovision_desc),
                checked = profile.autoReprovision,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(autoReprovision = it)) },
                aiTopic = AiTopic.REPROVISION,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.smart_reconnect_title),
                summary = stringResource(R.string.smart_reconnect_desc),
                checked = profile.smartReconnect,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(smartReconnect = it)) },
                aiTopic = AiTopic.SMART_RECONNECT,
            )
            if (profile.smartReconnect) {
                RowDivider(inset = false)
                SettingsChoiceRow(
                    title = stringResource(R.string.reconnect_limit_label),
                    options = listOf(3, 5, 10, 15, 20),
                    selected = profile.reconnectRetryLimit,
                    label = { "$it" },
                    onSelect = { onProfileChange(profile.copy(reconnectRetryLimit = it)) },
                    enabled = editable,
                    aiTopic = AiTopic.RECONNECT_LIMIT,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- engine

@Composable
private fun EnginePage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) = SettingsScaffold(stringResource(R.string.section_engine_tuning), onBack, modifier) {
    if (profile.fragment) {
        settingsSection {
            GroupCaption(stringResource(R.string.fragment_title), aiTopic = AiTopic.FRAGMENT_SIZE)
            SettingsGroup {
                SettingsBlock(aiTopic = AiTopic.FRAGMENT_DELAY) {
                    LtrOutlinedTextField(
                        value = profile.fragmentSize,
                        onValueChange = { onProfileChange(profile.copy(fragmentSize = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.fragment_size_label)) },
                        placeholder = { Text(stringResource(R.string.fragment_size_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = profile.fragmentDelay,
                        onValueChange = { onProfileChange(profile.copy(fragmentDelay = it)) },
                        enabled = editable,
                        singleLine = true,
                        label = { Text(stringResource(R.string.fragment_delay_label)) },
                        placeholder = { Text(stringResource(R.string.fragment_delay_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    settingsSection {
        GroupCaption(stringResource(R.string.cat_group_tuning))
        SettingsGroup {
            // AI-ICON COVERAGE: these were three unrelated fields inside ONE
            // block, so a single icon labelled "TLS groups" was the only
            // explanation available for the validation and reconnect timeouts as
            // well - and AiTopic.RECONNECT_SECS existed with nothing pointing at
            // it. One block per field, one icon per field.
            SettingsBlock(aiTopic = AiTopic.TLS_GROUPS) {
                LtrOutlinedTextField(
                    value = profile.tlsGroups,
                    onValueChange = { onProfileChange(profile.copy(tlsGroups = it)) },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.tls_groups_label)) },
                    placeholder = { Text(stringResource(R.string.tls_groups_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            SettingsBlock(aiTopic = AiTopic.VALIDATE_SECS) {
                LtrOutlinedTextField(
                    value = if (profile.validateSecs == 0) "" else profile.validateSecs.toString(),
                    onValueChange = {
                        onProfileChange(
                            profile.copy(validateSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0),
                        )
                    },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.validate_secs_label)) },
                    placeholder = { Text(stringResource(R.string.secs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            SettingsBlock(aiTopic = AiTopic.RECONNECT_SECS) {
                LtrOutlinedTextField(
                    value = if (profile.reconnectSecs == 0) "" else profile.reconnectSecs.toString(),
                    onValueChange = {
                        onProfileChange(
                            profile.copy(reconnectSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0),
                        )
                    },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.reconnect_secs_label)) },
                    placeholder = { Text(stringResource(R.string.secs_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_data_check_title),
                summary = stringResource(R.string.no_data_check_desc),
                checked = profile.noDataCheck,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(noDataCheck = it)) },
                aiTopic = AiTopic.NO_DATA_CHECK,
            )
            RowDivider(inset = false)
            SettingsSwitchRow(
                title = stringResource(R.string.no_profile_retry_title),
                summary = stringResource(R.string.no_profile_retry_desc),
                checked = profile.noProfileRetry,
                enabled = editable,
                onCheckedChange = { onProfileChange(profile.copy(noProfileRetry = it)) },
                aiTopic = AiTopic.NO_PROFILE_RETRY,
            )
            RowDivider(inset = false)
            SettingsChoiceRow(
                title = stringResource(R.string.core_log_level_label),
                options = CoreLogLevel.entries,
                selected = profile.coreLogLevel,
                label = { it.name },
                onSelect = { onProfileChange(profile.copy(coreLogLevel = it)) },
                enabled = editable,
                aiTopic = AiTopic.CORE_LOG,
            )
        }
    }
}

// ---------------------------------------------------------------- language

@Composable
private fun AppearancePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val current = LanguagePrefs.read(context)

    SettingsScaffold(stringResource(R.string.language_title), onBack, modifier) {
        settingsSection {
            SettingsGroup {
                AppLanguage.entries.forEachIndexed { index, language ->
                    if (index > 0) RowDivider(inset = false)
                    SettingsRadioRow(
                        // Radio rows rather than a picker: the choice has to be
                        // readable in BOTH languages at the same time, so a user
                        // who cannot read the language the app is currently in
                        // can still find the row that says "English" or "فارسی".
                        title = languageLabel(language),
                        selected = language == current,
                        onSelect = {
                            if (language != current) {
                                LanguagePrefs.write(context, language)
                                // The locale lives in the Activity's base
                                // context, so it can only change by rebuilding
                                // the Activity. This is the same recreate() the
                                // system runs for a configuration change, so
                                // nothing is lost that a rotation would not also
                                // lose.
                                //
                                // findActivity, not `as? Activity`: LocalContext
                                // is not guaranteed to BE the activity, and this
                                // app deliberately wraps its contexts, so the
                                // plain cast could silently return null and turn
                                // the whole language switch into a no-op.
                                LanguagePrefs.findActivity(context)?.recreate()
                            }
                        },
                        icon = if (language == AppLanguage.SYSTEM) Icons.Rounded.Language else null,
                        aiTopic = if (language == AppLanguage.SYSTEM) AiTopic.LANGUAGE else null,
                    )
                }
            }
            GroupFooter(stringResource(R.string.language_desc))
        }
    }
}

// ---------------------------------------------------------------- panel host

/** Hosts one of the existing self-contained cards on its own settings page. */
@Composable
private fun PanelPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = SettingsScaffold(title, onBack, modifier) {
    item {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

// ---------------------------------------------------------------- labels

@Composable
private fun backendShortLabel(backend: TransportBackend): String = when (backend) {
    TransportBackend.AETHER -> "Aether"
    TransportBackend.AETHER_PSIPHON -> "Aether \u2192 Psiphon"
    TransportBackend.TOR -> "Tor"
    TransportBackend.AETHER_TOR -> "Aether \u2192 Tor"
    TransportBackend.TOR_PSIPHON -> "Tor \u2192 Psiphon"
    TransportBackend.TOR_AETHER -> "Tor \u2192 Aether"
}

@Composable
private fun backendHelp(backend: TransportBackend): String = when (backend) {
    // Localised now. Both lines were hard-coded English in the old panel, so the
    // Persian UI explained its single most important setting in English.
    TransportBackend.AETHER -> stringResource(R.string.backend_help_aether)
    TransportBackend.AETHER_PSIPHON -> stringResource(R.string.backend_help_chained)
    TransportBackend.TOR -> stringResource(R.string.backend_help_tor)
    TransportBackend.AETHER_TOR -> stringResource(R.string.backend_help_aether_tor)
    TransportBackend.TOR_PSIPHON -> stringResource(R.string.backend_help_tor_psiphon)
    TransportBackend.TOR_AETHER -> stringResource(R.string.backend_help_tor_aether)
}

/** Bootstrap patience presets in seconds; 0 keeps the engine's own 75. */
private val TOR_DIRECT_PRESETS = listOf(0, 20, 45, 120, 240)

@Composable
private fun torCountryLabel(code: String): String =
    TorCountries.label(code, stringResource(R.string.tor_country_auto))

@Composable
private fun torDirectLabel(secs: Int): String = when (secs) {
    0 -> stringResource(R.string.tor_direct_auto)
    else -> stringResource(R.string.tor_direct_secs_value, secs)
}

@Composable
private fun torBridgesLabel(bridges: TorBridges): String = when (bridges) {
    TorBridges.AUTO -> stringResource(R.string.tor_bridges_auto)
    TorBridges.ALWAYS -> stringResource(R.string.tor_bridges_always)
    TorBridges.OFF -> stringResource(R.string.tor_bridges_off)
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
    AppLanguage.PERSIAN -> stringResource(R.string.language_persian)
}

@Composable
private fun protocolLabel(protocol: Protocol): String = when (protocol) {
    Protocol.AUTO -> stringResource(R.string.protocol_auto)
    Protocol.MASQUE -> stringResource(R.string.protocol_masque)
    Protocol.WIREGUARD -> stringResource(R.string.protocol_wireguard)
    Protocol.GOOL -> stringResource(R.string.protocol_gool)
    Protocol.MIM -> stringResource(R.string.protocol_mim)
}

@Composable
private fun scanLabel(mode: ScanMode): String = when (mode) {
    ScanMode.TURBO -> stringResource(R.string.scan_turbo)
    ScanMode.BALANCED -> stringResource(R.string.scan_balanced)
    ScanMode.THOROUGH -> stringResource(R.string.scan_thorough)
    ScanMode.STEALTH -> stringResource(R.string.scan_stealth)
    ScanMode.IRONCLAD -> stringResource(R.string.scan_ironclad)
}

@Composable
private fun ipLabel(ip: IpVersion): String = when (ip) {
    IpVersion.V4 -> stringResource(R.string.ip_v4)
    IpVersion.V6 -> stringResource(R.string.ip_v6)
    IpVersion.BOTH -> stringResource(R.string.ip_both)
}

@Composable
private fun noizeLabel(n: Noize): String = when (n) {
    Noize.OFF -> stringResource(R.string.noize_off)
    Noize.LIGHT -> stringResource(R.string.noize_light)
    Noize.FIREWALL -> stringResource(R.string.noize_firewall)
    Noize.BALANCED -> stringResource(R.string.noize_balanced)
    Noize.GFW -> stringResource(R.string.noize_gfw)
    Noize.AGGRESSIVE -> stringResource(R.string.noize_aggressive)
}

@Composable
private fun endpointLabel(m: EndpointMode): String = when (m) {
    EndpointMode.AUTO -> stringResource(R.string.endpoint_auto)
    EndpointMode.MANUAL_PEER -> stringResource(R.string.endpoint_peer)
    EndpointMode.MANUAL_RANGE -> stringResource(R.string.endpoint_range)
}

@Composable
private fun teamAuthLabel(a: TeamAuth): String = when (a) {
    TeamAuth.OFF -> stringResource(R.string.team_auth_off)
    TeamAuth.SERVICE_TOKEN -> stringResource(R.string.team_auth_service)
    TeamAuth.EMAIL -> stringResource(R.string.team_auth_email)
    TeamAuth.TOKEN -> stringResource(R.string.team_auth_token)
}

@Composable
private fun splitLabel(m: SplitMode): String = when (m) {
    SplitMode.OFF -> stringResource(R.string.split_off)
    SplitMode.INCLUDE -> stringResource(R.string.split_include)
    SplitMode.EXCLUDE -> stringResource(R.string.split_exclude)
}
