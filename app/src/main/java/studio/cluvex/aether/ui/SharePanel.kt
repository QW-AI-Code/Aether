package studio.cluvex.aether.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.ShareLeakGuard
import studio.cluvex.aether.data.ShareCredentials
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isConnected
import studio.cluvex.aether.ui.components.SecureSurface
import studio.cluvex.aether.ui.components.copySensitive
import studio.cluvex.aether.ui.theme.AetherAmber
import studio.cluvex.aether.ui.theme.AetherMint

/**
 * "Share VPN" - rewritten in 1.4.0-r5 around two dedicated modes.
 *
 *  - **Home Wi-Fi**: the phone and the other devices are on the same router.
 *  - **Mobile hotspot**: the phone's own hotspot (or USB / Bluetooth tethering)
 *    on mobile data.
 *
 * Each mode detects its own network, tells the user exactly what to do when it
 * is not up yet (with a shortcut to the hotspot settings), and - once ready -
 * shows every value the other device needs with its own copy button, a
 * "copy all" block and a PAC URL for one-step setup. Values are also selectable
 * text. The zero-leak guard is shown as a status, and the optional
 * username/password lives in its own section. See [ShareBridge] and
 * [ShareLeakGuard] for the mechanics.
 */
@Composable
fun SharePanel(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "shareArrow")

    val bridgeActive by ShareBridge.active.collectAsState()
    val lanStatus by ShareBridge.lanStatus.collectAsState()
    val endpoint by ShareBridge.lanEndpoint.collectAsState()
    val allEndpoints by ShareBridge.lanEndpoints.collectAsState()
    val mode by ShareBridge.mode.collectAsState()
    val authRequired by ShareBridge.authRequired.collectAsState()
    val proxyUser by ShareBridge.proxyUser.collectAsState()
    val proxyPassword by ShareBridge.proxyPassword.collectAsState()
    val lanClients by ShareBridge.lanClients.collectAsState()

    // Load mode / auth / credential before anything is shown. Keystore work.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ShareCredentials.ensure(context) }
    }

    // Which modes have a live network right now - refreshed while the card is
    // open so "turn on your hotspot" turns into the addresses by itself.
    val available by produceState(initialValue = emptySet<ShareLeakGuard.Mode>(), expanded) {
        while (expanded) {
            value = withContext(Dispatchers.IO) { ShareBridge.availableModes() }
            delay(3_000)
        }
    }

    // Self-healing: connected + switch on + LAN side not requested yet.
    LaunchedEffect(state.isConnected, profile.lanShare, bridgeActive, lanStatus) {
        if (state.isConnected && profile.lanShare && lanStatus == ShareBridge.LanStatus.OFF) {
            withContext(Dispatchers.IO) { ShareBridge.enableLan() }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ---- header -------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (state.isConnected && profile.lanShare && lanStatus == ShareBridge.LanStatus.READY) {
                            stringResource(R.string.share_status_live, lanClients)
                        } else {
                            stringResource(R.string.share_subtitle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lanStatus == ShareBridge.LanStatus.READY && profile.lanShare) {
                            AetherMint
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrowRotation),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(4.dp))

                    // ---- master switch ------------------------------------
                    SwitchLine(
                        title = stringResource(R.string.share_toggle),
                        summary = stringResource(R.string.share_toggle_desc),
                        checked = profile.lanShare,
                        onChange = { on ->
                            onProfileChange(profile.copy(lanShare = on))
                            if (state.isConnected) {
                                if (on) {
                                    ShareBridge.enableLan()
                                } else if (profile.proxyMode) {
                                    // Proxy mode needs the loopback listeners.
                                    ShareBridge.disableLan()
                                } else {
                                    ShareBridge.stop()
                                }
                            }
                        },
                    )

                    // ---- mode picker --------------------------------------
                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.share_mode_title))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Home,
                            title = stringResource(R.string.share_mode_home),
                            body = stringResource(R.string.share_mode_home_desc),
                            selected = mode == ShareLeakGuard.Mode.HOME_WIFI,
                            detected = ShareLeakGuard.Mode.HOME_WIFI in available,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ShareCredentials.setMode(context, ShareLeakGuard.Mode.HOME_WIFI)
                                }
                            },
                        )
                        ModeCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.SignalCellularAlt,
                            title = stringResource(R.string.share_mode_mobile),
                            body = stringResource(R.string.share_mode_mobile_desc),
                            selected = mode == ShareLeakGuard.Mode.MOBILE_HOTSPOT,
                            detected = ShareLeakGuard.Mode.MOBILE_HOTSPOT in available,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ShareCredentials.setMode(context, ShareLeakGuard.Mode.MOBILE_HOTSPOT)
                                }
                            },
                        )
                    }

                    // ---- status + addresses -------------------------------
                    Spacer(Modifier.height(12.dp))
                    val ep = endpoint
                    when {
                        !state.isConnected -> Notice(Icons.Rounded.Info, stringResource(R.string.share_need_connect))
                        !profile.lanShare -> Notice(Icons.Rounded.Info, stringResource(R.string.share_off_hint))
                        lanStatus == ShareBridge.LanStatus.NEEDS_RECONNECT ->
                            Notice(Icons.Rounded.WarningAmber, stringResource(R.string.share_needs_reconnect), AetherAmber)
                        lanStatus == ShareBridge.LanStatus.PORT_BUSY ->
                            Notice(Icons.Rounded.WarningAmber, stringResource(R.string.share_port_busy), MaterialTheme.colorScheme.error)
                        lanStatus == ShareBridge.LanStatus.WAITING_FOR_NETWORK || (lanStatus == ShareBridge.LanStatus.READY && ep == null) -> {
                            WaitingForNetwork(
                                mode = mode,
                                otherModeAvailable = available.any { it != mode },
                                onSwitchMode = {
                                    val other = if (mode == ShareLeakGuard.Mode.HOME_WIFI) {
                                        ShareLeakGuard.Mode.MOBILE_HOTSPOT
                                    } else {
                                        ShareLeakGuard.Mode.HOME_WIFI
                                    }
                                    scope.launch(Dispatchers.IO) { ShareCredentials.setMode(context, other) }
                                },
                                onOpenHotspotSettings = {
                                    openHotspotSettings(context)
                                },
                            )
                        }
                        lanStatus == ShareBridge.LanStatus.READY && ep != null -> {
                            // Credentials may be on screen from here on (F-3).
                            if (authRequired) SecureSurface()
                            ReadyBlock(
                                endpoint = ep,
                                others = allEndpoints.filter { it.address != ep.address },
                                mode = mode,
                                authRequired = authRequired,
                                user = proxyUser,
                                password = proxyPassword,
                                suspendedDirect = profile.shareSuspendsDirectRules,
                            )
                        }
                        else -> Notice(Icons.Rounded.Info, stringResource(R.string.share_starting))
                    }

                    // ---- optional authentication --------------------------
                    Spacer(Modifier.height(14.dp))
                    Divider()
                    Spacer(Modifier.height(6.dp))
                    AuthSection(
                        authRequired = authRequired,
                        user = proxyUser,
                        password = proxyPassword,
                        recommend = mode == ShareLeakGuard.Mode.HOME_WIFI,
                        onToggle = { on ->
                            scope.launch(Dispatchers.IO) { ShareCredentials.setAuthRequired(context, on) }
                        },
                        onRotate = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ShareCredentials.rotate(context) }
                                Toast.makeText(context, R.string.share_pass_rotated, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

// ================================================================ sections

@Composable
private fun ReadyBlock(
    endpoint: ShareBridge.LanEndpoint,
    others: List<ShareBridge.LanEndpoint>,
    mode: ShareLeakGuard.Mode,
    authRequired: Boolean,
    user: String,
    password: String,
    suspendedDirect: Boolean,
) {
    val ip = endpoint.address
    val http = "$ip:${ShareBridge.HTTP_SHARE_PORT}"
    val socks = "$ip:${ShareBridge.SOCKS_SHARE_PORT}"
    val pac = "http://$ip:${ShareBridge.HTTP_SHARE_PORT}/proxy.pac"
    val check = "http://${ShareLeakGuard.CHECK_HOST}/"
    val setup = "http://$ip:${ShareBridge.HTTP_SHARE_PORT}/"

    // Zero-leak status pill.
    StatusPill(
        icon = Icons.Rounded.Shield,
        text = stringResource(R.string.share_zero_leak_on),
        color = AetherMint,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.share_zero_leak_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (suspendedDirect) {
        Spacer(Modifier.height(6.dp))
        Notice(Icons.Rounded.Info, stringResource(R.string.share_direct_paused))
    }

    Spacer(Modifier.height(10.dp))
    SectionLabel(
        stringResource(
            if (mode == ShareLeakGuard.Mode.HOME_WIFI) R.string.share_values_home else R.string.share_values_mobile,
        ),
    )
    ValueRow(label = stringResource(R.string.share_ip_label), value = ip)
    ValueRow(label = stringResource(R.string.share_http_label), value = http)
    ValueRow(label = stringResource(R.string.share_socks_label), value = socks)
    ValueRow(label = stringResource(R.string.share_pac_label), value = pac)

    // r6: every other link the share listens on (USB / Bluetooth / second band).
    // A device must use the address of the link it is actually connected by.
    if (others.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        SectionLabel(stringResource(R.string.share_other_addresses))
        others.forEach { other ->
            ValueRow(label = ifaceLabel(other.kind), value = other.address)
        }
    }

    // Copy everything as one block - what the user actually pastes into a chat
    // to their own laptop.
    val allLabel = stringResource(R.string.share_copy_all_label)
    val allText = buildString {
        append("Aether VPN share\n")
        append("IP: ").append(ip).append('\n')
        append("HTTP proxy: ").append(http).append('\n')
        append("SOCKS5 proxy: ").append(socks).append('\n')
        append("PAC URL: ").append(pac).append('\n')
        others.forEach { append("Also: ").append(it.address).append(" (").append(it.kind.name).append(")\n") }
        append("Setup + WebRTC lock: ").append(setup).append('\n')
        if (authRequired) {
            append("Username: ").append(user).append('\n')
            append("Password: ").append(password).append('\n')
        }
        append("Check: ").append(check)
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    OutlinedButton(
        onClick = {
            if (authRequired) copySensitive(context, allText, allLabel) else clipboard.setText(AnnotatedString(allText))
            Toast.makeText(context, R.string.share_copied_all, Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.share_copy_all))
    }

    // r6: WebRTC. Browsers send it OUTSIDE a proxy unless locked; the setup page
    // tests it live against the tunnel exit and serves the one-click locks.
    Spacer(Modifier.height(12.dp))
    SectionLabel(stringResource(R.string.share_webrtc_title))
    Notice(Icons.Rounded.Shield, stringResource(R.string.share_webrtc_desc), AetherAmber)
    ValueRow(label = stringResource(R.string.share_setup_label), value = setup)

    // Step-by-step setup for this mode.
    Spacer(Modifier.height(12.dp))
    SectionLabel(stringResource(R.string.share_steps_title))
    val steps = if (mode == ShareLeakGuard.Mode.HOME_WIFI) {
        listOf(
            stringResource(R.string.share_step_home_1),
            stringResource(R.string.share_step_common_proxy),
            stringResource(R.string.share_step_common_check),
        )
    } else {
        listOf(
            stringResource(R.string.share_step_mobile_1),
            stringResource(R.string.share_step_common_proxy),
            stringResource(R.string.share_step_common_check),
        )
    }
    steps.forEachIndexed { index, step -> StepLine(index + 1, step) }
    ValueRow(label = stringResource(R.string.share_check_label), value = check)

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.share_warning_unconfigured),
        style = MaterialTheme.typography.bodySmall,
        color = AetherAmber.copy(alpha = 0.9f),
    )
}

@Composable
private fun ifaceLabel(kind: ShareLeakGuard.IfaceKind): String = stringResource(
    when (kind) {
        ShareLeakGuard.IfaceKind.HOTSPOT -> R.string.share_iface_hotspot
        ShareLeakGuard.IfaceKind.USB_TETHER -> R.string.share_iface_usb
        ShareLeakGuard.IfaceKind.BT_TETHER -> R.string.share_iface_bt
        ShareLeakGuard.IfaceKind.ETHERNET -> R.string.share_iface_eth
        else -> R.string.share_iface_wifi
    },
)

@Composable
private fun WaitingForNetwork(
    mode: ShareLeakGuard.Mode,
    otherModeAvailable: Boolean,
    onSwitchMode: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
) {
    if (mode == ShareLeakGuard.Mode.HOME_WIFI) {
        Notice(Icons.Rounded.Info, stringResource(R.string.share_wait_home))
        if (otherModeAvailable) {
            TextButton(onClick = onSwitchMode) { Text(stringResource(R.string.share_switch_to_mobile)) }
        }
    } else {
        Notice(Icons.Rounded.Info, stringResource(R.string.share_wait_mobile))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenHotspotSettings) { Text(stringResource(R.string.share_open_hotspot)) }
            if (otherModeAvailable) {
                TextButton(onClick = onSwitchMode) { Text(stringResource(R.string.share_switch_to_home)) }
            }
        }
    }
}

@Composable
private fun AuthSection(
    authRequired: Boolean,
    user: String,
    password: String,
    recommend: Boolean,
    onToggle: (Boolean) -> Unit,
    onRotate: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }

    SwitchLine(
        icon = Icons.Rounded.Lock,
        title = stringResource(R.string.share_auth_toggle),
        summary = stringResource(
            if (recommend) R.string.share_auth_toggle_desc_home else R.string.share_auth_toggle_desc,
        ),
        checked = authRequired,
        onChange = onToggle,
    )
    AnimatedVisibility(visible = authRequired) {
        Column {
            SecureSurface()
            Text(
                text = stringResource(R.string.share_auth_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            ValueRow(label = stringResource(R.string.share_user_label), value = user, sensitive = true)
            ValueRow(label = stringResource(R.string.share_pass_label), value = password, sensitive = true, secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { editing = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share_auth_edit))
                }
                TextButton(onClick = onRotate) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.share_pass_rotate))
                }
            }
        }
    }

    if (editing) {
        var draftUser by remember { mutableStateOf(user) }
        var draftPass by remember { mutableStateOf(password) }
        var reveal by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val badUser = stringResource(R.string.share_auth_bad_user)
        val shortPass = stringResource(R.string.share_auth_short_pass, ShareCredentials.MIN_PASSWORD)
        val saved = stringResource(R.string.share_auth_saved)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.share_auth_edit_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draftUser,
                        onValueChange = { draftUser = it; error = null },
                        label = { Text(stringResource(R.string.share_user_label)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draftPass,
                        onValueChange = { draftPass = it; error = null },
                        label = { Text(stringResource(R.string.share_pass_label)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = stringResource(
                                        if (reveal) R.string.a11y_hide_password else R.string.a11y_show_password,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ShareCredentials.setCustom(context, draftUser, draftPass)
                        }
                        when (result) {
                            ShareCredentials.Validation.OK -> {
                                editing = false
                                Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                            }
                            ShareCredentials.Validation.BAD_USER -> error = badUser
                            ShareCredentials.Validation.SHORT_PASSWORD -> error = shortPass
                        }
                    }
                }) { Text(stringResource(R.string.share_auth_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.share_auth_cancel)) }
            },
        )
    }
}

// ============================================================== components

@Composable
private fun Divider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SwitchLine(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The title and the switch used to be unrelated siblings, so the
            // screen reader said just "switch, on" with no name. toggleable
            // merges the row into ONE node (title + summary + state) and makes the
            // whole row tappable; the Switch below hands its own click over.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    body: String,
    selected: Boolean,
    detected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val accent = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) accent.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = shape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 112.dp)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (detected) R.string.share_mode_detected else R.string.share_mode_not_detected),
            style = MaterialTheme.typography.labelSmall,
            color = if (detected) AetherMint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun StatusPill(icon: ImageVector, text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.08f))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StepLine(number: Int, text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = "$number.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A label + an always-LTR monospace value, selectable (long-press) AND with a
 * one-tap copy button. Sensitive values go to the clipboard flagged as such
 * (audit F-4); [secret] values are masked until revealed.
 */
@Composable
private fun ValueRow(label: String, value: String, sensitive: Boolean = false, secret: Boolean = false) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var reveal by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    text = if (secret && !reveal) "\u2022".repeat(value.length.coerceIn(8, 16)) else value,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (secret) {
            IconButton(onClick = { reveal = !reveal }) {
                Icon(
                    if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(
                        if (reveal) R.string.a11y_hide_value else R.string.a11y_show_value,
                    ),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = {
                if (sensitive) copySensitive(context, value, label) else clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
            },
        ) {
            Icon(
                Icons.Rounded.ContentCopy,
                // Was the fixed "Copy address" for every row, including the
                // username and password rows.
                contentDescription = stringResource(R.string.a11y_copy_value, label),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Opens the system tethering / hotspot screen, falling back to wireless settings. */
private fun openHotspotSettings(context: android.content.Context) {
    val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(tether)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (_: SecurityException) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
