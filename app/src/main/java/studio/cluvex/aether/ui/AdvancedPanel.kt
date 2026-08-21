package studio.cluvex.aether.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import androidx.compose.ui.text.input.PasswordVisualTransformation
import studio.cluvex.aether.model.SplitMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.ui.components.AppPickerDialog
import studio.cluvex.aether.ui.components.DropdownSelector
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.SegmentedSelector

/**
 * Collapsible "Advanced" card exposing the full engine v1.3.0 feature set:
 * protocol, scan mode, IP version, Amnezia-style obfuscation, endpoint
 * selection (auto / manual IP / custom range), keepalive, MTU, TLS
 * fragmentation, ECH, MASQUE-over-HTTP/2, quick reconnect, proxy mode and
 * per-app split tunneling.
 */
@Composable
fun AdvancedPanel(
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    // True when hosted in the home-screen bottom sheet, where the card should
    // open already expanded instead of requiring an extra tap.
    startExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(startExpanded) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showBlockedPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val arrowRotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300), label = "arrow")

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "  " + stringResource(R.string.advanced),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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
                    Spacer(Modifier.height(16.dp))

                    // ---------- Core ----------
                    SettingLabel(stringResource(R.string.protocol))
                    SegmentedSelector(
                        options = Protocol.entries,
                        selected = profile.protocol,
                        onSelect = { onProfileChange(profile.copy(protocol = it)) },
                        label = { protocolLabel(it) },
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(stringResource(R.string.scan_mode))
                    DropdownSelector(
                        options = ScanMode.entries,
                        selected = profile.scanMode,
                        onSelect = { onProfileChange(profile.copy(scanMode = it)) },
                        label = { scanLabel(it) },
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(stringResource(R.string.ip_version))
                    SegmentedSelector(
                        options = IpVersion.entries,
                        selected = profile.ipVersion,
                        onSelect = { onProfileChange(profile.copy(ipVersion = it)) },
                        label = { ipLabel(it) },
                        enabled = enabled,
                    )

                    // ---------- Transport & anti-DPI ----------
                    SectionHeader(stringResource(R.string.section_transport))

                    SettingLabel(stringResource(R.string.noize_title))
                    DropdownSelector(
                        options = Noize.entries,
                        selected = profile.noize,
                        onSelect = { onProfileChange(profile.copy(noize = it)) },
                        label = { noizeLabel(it) },
                        enabled = enabled,
                    )
                    HelperText(stringResource(R.string.noize_desc))
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(stringResource(R.string.endpoint_mode))
                    SegmentedSelector(
                        options = EndpointMode.entries,
                        selected = profile.endpointMode,
                        onSelect = { onProfileChange(profile.copy(endpointMode = it)) },
                        label = { endpointLabel(it) },
                        enabled = enabled,
                    )
                    if (profile.endpointMode == EndpointMode.MANUAL_PEER) {
                        Spacer(Modifier.height(12.dp))
                        // BiDi fix: ip:port is LTR technical text — a plain
                        // OutlinedTextField scrambles typed digits in the RTL
                        // (Persian) locale. LtrOutlinedTextField pins LTR.
                        LtrOutlinedTextField(
                            value = profile.manualPeer,
                            onValueChange = { onProfileChange(profile.copy(manualPeer = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.manual_peer_label)) },
                            placeholder = { Text(stringResource(R.string.manual_peer_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (profile.endpointMode == EndpointMode.MANUAL_RANGE) {
                        Spacer(Modifier.height(12.dp))
                        // BiDi fix: CIDR ranges are LTR technical text — this is
                        // the exact field where typed digits appeared shuffled.
                        LtrOutlinedTextField(
                            value = profile.manualRange,
                            onValueChange = { onProfileChange(profile.copy(manualRange = it)) },
                            enabled = enabled,
                            singleLine = false,
                            label = { Text(stringResource(R.string.manual_range_label)) },
                            placeholder = { Text(stringResource(R.string.manual_range_hint)) },
                            supportingText = { Text(stringResource(R.string.manual_range_help)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(stringResource(R.string.keepalive_label))
                    DropdownSelector(
                        options = ConnectionProfile.KEEPALIVE_PRESETS,
                        selected = profile.keepalive,
                        onSelect = { onProfileChange(profile.copy(keepalive = it)) },
                        label = { if (it == 0) stringResource(R.string.keepalive_default) else "$it" },
                        enabled = enabled,
                    )
                    Spacer(Modifier.height(16.dp))

                    SettingLabel(stringResource(R.string.mtu_label))
                    DropdownSelector(
                        options = ConnectionProfile.MTU_PRESETS,
                        selected = profile.mtu,
                        onSelect = { onProfileChange(profile.copy(mtu = it)) },
                        label = { "$it" },
                        enabled = enabled,
                    )
                    HelperText(stringResource(R.string.mtu_desc))
                    Spacer(Modifier.height(8.dp))

                    Divider()

                    ToggleRow(
                        title = stringResource(R.string.fragment_title),
                        description = stringResource(R.string.fragment_desc),
                        checked = profile.fragment,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(fragment = it)) },
                    )
                    ToggleRow(
                        title = stringResource(R.string.ech_title),
                        description = stringResource(R.string.ech_desc),
                        checked = profile.ech,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(ech = it)) },
                    )
                    ToggleRow(
                        title = stringResource(R.string.masque_http2),
                        description = stringResource(R.string.masque_http2_desc),
                        checked = profile.masqueHttp2,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(masqueHttp2 = it)) },
                    )
                    ToggleRow(
                        title = stringResource(R.string.quick_reconnect),
                        description = stringResource(R.string.quick_reconnect_desc),
                        checked = profile.quickReconnect,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(quickReconnect = it)) },
                    )

                    // ---------- DNS inside the tunnel (engine v1.5.0) ----------
                    SettingLabel(stringResource(R.string.dns_label))
                    // BiDi: resolver addresses are LTR technical text.
                    LtrOutlinedTextField(
                        value = profile.dnsServers,
                        onValueChange = { onProfileChange(profile.copy(dnsServers = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.dns_label)) },
                        placeholder = { Text(stringResource(R.string.dns_hint)) },
                        supportingText = { Text(stringResource(R.string.dns_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))

                    // ---------- Routing rules (engine v1.5.0) ----------
                    SectionHeader(stringResource(R.string.section_routes))

                    LtrOutlinedTextField(
                        value = profile.routeBlock,
                        onValueChange = { onProfileChange(profile.copy(routeBlock = it)) },
                        enabled = enabled,
                        singleLine = false,
                        label = { Text(stringResource(R.string.route_block_label)) },
                        placeholder = { Text(stringResource(R.string.route_block_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = profile.routeDirect,
                        onValueChange = { onProfileChange(profile.copy(routeDirect = it)) },
                        enabled = enabled,
                        singleLine = false,
                        label = { Text(stringResource(R.string.route_direct_label)) },
                        placeholder = { Text(stringResource(R.string.route_direct_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HelperText(stringResource(R.string.routes_help))
                    Spacer(Modifier.height(8.dp))

                    // Core 1.7.0: match the domain rules above on the name read
                    // from the first bytes. Android is always a tun front end,
                    // so without this the engine only ever sees an address and
                    // every domain rule above would quietly do nothing.
                    ToggleRow(
                        title = stringResource(R.string.route_sniff_title),
                        description = stringResource(R.string.route_sniff_desc),
                        checked = profile.routeSniff,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(routeSniff = it)) },
                    )
                    if (profile.routeSniff) {
                        LtrOutlinedTextField(
                            value = if (profile.routeSniffMs == 0) "" else profile.routeSniffMs.toString(),
                            onValueChange = {
                                onProfileChange(
                                    profile.copy(
                                        routeSniffMs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0,
                                    ),
                                )
                            },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.route_sniff_ms_label)) },
                            placeholder = { Text(stringResource(R.string.route_sniff_ms_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // ---------- Upstream proxy / chaining (engine v1.7.0) ----------
                    SectionHeader(stringResource(R.string.section_upstream))

                    LtrOutlinedTextField(
                        value = profile.upstreamProxy,
                        onValueChange = { onProfileChange(profile.copy(upstreamProxy = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.upstream_label)) },
                        placeholder = { Text(stringResource(R.string.upstream_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HelperText(stringResource(R.string.upstream_help))
                    Spacer(Modifier.height(8.dp))

                    // ---------- Zero Trust / organization (engine v1.5.0) ----------
                    SectionHeader(stringResource(R.string.section_zerotrust))

                    SettingLabel(stringResource(R.string.team_auth_label))
                    DropdownSelector(
                        options = TeamAuth.entries,
                        selected = profile.teamAuth,
                        onSelect = { onProfileChange(profile.copy(teamAuth = it)) },
                        label = { teamAuthLabel(it) },
                        enabled = enabled,
                    )
                    HelperText(stringResource(R.string.team_auth_desc))

                    if (profile.teamAuth != TeamAuth.OFF) {
                        Spacer(Modifier.height(12.dp))
                        LtrOutlinedTextField(
                            value = profile.team,
                            onValueChange = { onProfileChange(profile.copy(team = it)) },
                            enabled = enabled,
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
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessClientId = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.access_id_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(12.dp))
                                // Masked: a service-token secret is an
                                // organization credential, so it must not be
                                // readable over someone's shoulder or land in a
                                // screenshot.
                                LtrOutlinedTextField(
                                    value = profile.accessClientSecret,
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessClientSecret = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text(stringResource(R.string.access_secret_label)) },
                                    supportingText = {
                                        Text(stringResource(R.string.access_secret_help))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            TeamAuth.EMAIL -> {
                                Spacer(Modifier.height(12.dp))
                                LtrOutlinedTextField(
                                    value = profile.accessEmail,
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessEmail = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    label = { Text(stringResource(R.string.access_email_label)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            TeamAuth.TOKEN -> {
                                Spacer(Modifier.height(12.dp))
                                LtrOutlinedTextField(
                                    value = profile.accessToken,
                                    onValueChange = {
                                        onProfileChange(profile.copy(accessToken = it))
                                    },
                                    enabled = enabled,
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text(stringResource(R.string.access_token_label)) },
                                    supportingText = {
                                        Text(stringResource(R.string.access_secret_help))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            TeamAuth.OFF -> Unit
                        }

                        Spacer(Modifier.height(4.dp))
                        ToggleRow(
                            title = stringResource(R.string.gateway_title),
                            description = stringResource(R.string.gateway_desc),
                            checked = profile.gateway,
                            enabled = enabled,
                            onChange = { onProfileChange(profile.copy(gateway = it)) },
                        )
                    }

                    // ---------- Routing ----------
                    SectionHeader(stringResource(R.string.section_routing))

                    ToggleRow(
                        title = stringResource(R.string.proxy_mode_title),
                        description = stringResource(R.string.proxy_mode_desc),
                        checked = profile.proxyMode,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(proxyMode = it)) },
                    )
                    // Fixed local proxy endpoints (v2rayNG-style standard ports,
                    // they never change) shown right under the toggle with a
                    // one-tap copy button, so nobody has to dig through logs.
                    AnimatedVisibility(visible = profile.proxyMode) {
                        Column {
                            Spacer(Modifier.height(4.dp))
                            HelperText(stringResource(R.string.proxy_endpoints_hint))
                            ProxyEndpointRow(
                                label = stringResource(R.string.proxy_socks_label),
                                value = "127.0.0.1:${ShareBridge.SOCKS_SHARE_PORT}",
                            )
                            ProxyEndpointRow(
                                label = stringResource(R.string.proxy_http_label),
                                value = "127.0.0.1:${ShareBridge.HTTP_SHARE_PORT}",
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    SettingLabel(stringResource(R.string.split_mode))
                    SegmentedSelector(
                        options = SplitMode.entries,
                        selected = profile.splitMode,
                        onSelect = { onProfileChange(profile.copy(splitMode = it)) },
                        label = { splitLabel(it) },
                        enabled = enabled,
                    )
                    if (profile.splitMode != SplitMode.OFF) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { showAppPicker = true },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Apps, contentDescription = null)
                            Text(
                                text = "  " + stringResource(
                                    R.string.split_select_apps,
                                    profile.splitApps.size,
                                ),
                            )
                        }
                    }

                    // ---------- Per-app internet blocking (1.2.4) ----------
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showBlockedPicker = true },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Apps, contentDescription = null)
                        Text(
                            text = "  " + stringResource(
                                R.string.blocked_select_apps,
                                profile.blockedApps.size,
                            ),
                        )
                    }
                    HelperText(stringResource(R.string.blocked_apps_desc))

                    // ---------- Security & stability (1.2.4) ----------
                    SectionHeader(stringResource(R.string.section_security))

                    ToggleRow(
                        title = stringResource(R.string.kill_switch_title),
                        description = stringResource(R.string.kill_switch_desc),
                        checked = profile.killSwitch,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(killSwitch = it)) },
                    )
                    if (profile.killSwitch) {
                        ToggleRow(
                            title = stringResource(R.string.strict_kill_switch_title),
                            description = stringResource(R.string.strict_kill_switch_desc),
                            checked = profile.strictKillSwitch,
                            enabled = enabled,
                            onChange = { onProfileChange(profile.copy(strictKillSwitch = it)) },
                        )
                    }
                    ToggleRow(
                        title = stringResource(R.string.ipv6_leak_title),
                        description = stringResource(R.string.ipv6_leak_desc),
                        checked = profile.ipv6LeakProtection,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(ipv6LeakProtection = it)) },
                    )
                    ToggleRow(
                        title = stringResource(R.string.reprovision_title),
                        description = stringResource(R.string.reprovision_desc),
                        checked = profile.autoReprovision,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(autoReprovision = it)) },
                    )
                    ToggleRow(
                        title = stringResource(R.string.smart_reconnect_title),
                        description = stringResource(R.string.smart_reconnect_desc),
                        checked = profile.smartReconnect,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(smartReconnect = it)) },
                    )
                    if (profile.smartReconnect) {
                        SettingLabel(stringResource(R.string.reconnect_limit_label))
                        DropdownSelector(
                            options = listOf(3, 5, 10, 15, 20),
                            selected = profile.reconnectRetryLimit,
                            onSelect = { onProfileChange(profile.copy(reconnectRetryLimit = it)) },
                            label = { "$it" },
                            enabled = enabled,
                        )
                    }

                    // ---------- Engine tuning (1.2.4) ----------
                    SectionHeader(stringResource(R.string.section_engine_tuning))

                    if (profile.fragment) {
                        LtrOutlinedTextField(
                            value = profile.fragmentSize,
                            onValueChange = { onProfileChange(profile.copy(fragmentSize = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.fragment_size_label)) },
                            placeholder = { Text(stringResource(R.string.fragment_size_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        LtrOutlinedTextField(
                            value = profile.fragmentDelay,
                            onValueChange = { onProfileChange(profile.copy(fragmentDelay = it)) },
                            enabled = enabled,
                            singleLine = true,
                            label = { Text(stringResource(R.string.fragment_delay_label)) },
                            placeholder = { Text(stringResource(R.string.fragment_delay_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    ToggleRow(
                        title = stringResource(R.string.no_data_check_title),
                        description = stringResource(R.string.no_data_check_desc),
                        checked = profile.noDataCheck,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(noDataCheck = it)) },
                    )
                    LtrOutlinedTextField(
                        value = profile.tlsGroups,
                        onValueChange = { onProfileChange(profile.copy(tlsGroups = it)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.tls_groups_label)) },
                        placeholder = { Text(stringResource(R.string.tls_groups_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = if (profile.validateSecs == 0) "" else profile.validateSecs.toString(),
                        onValueChange = { onProfileChange(profile.copy(validateSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.validate_secs_label)) },
                        placeholder = { Text(stringResource(R.string.secs_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    LtrOutlinedTextField(
                        value = if (profile.reconnectSecs == 0) "" else profile.reconnectSecs.toString(),
                        onValueChange = { onProfileChange(profile.copy(reconnectSecs = it.filter(Char::isDigit).take(4).toIntOrNull() ?: 0)) },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text(stringResource(R.string.reconnect_secs_label)) },
                        placeholder = { Text(stringResource(R.string.secs_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        title = stringResource(R.string.no_profile_retry_title),
                        description = stringResource(R.string.no_profile_retry_desc),
                        checked = profile.noProfileRetry,
                        enabled = enabled,
                        onChange = { onProfileChange(profile.copy(noProfileRetry = it)) },
                    )
                    SettingLabel(stringResource(R.string.core_log_level_label))
                    DropdownSelector(
                        options = CoreLogLevel.entries,
                        selected = profile.coreLogLevel,
                        onSelect = { onProfileChange(profile.copy(coreLogLevel = it)) },
                        label = { it.name },
                        enabled = enabled,
                    )

                    // ---------- Reset ----------
                    SectionHeader(stringResource(R.string.section_reset))
                    OutlinedButton(
                        onClick = {
                            // Restore every setting to factory defaults. Persisted
                            // immediately through the normal onProfileChange path
                            // (DataStore), exactly like any other settings change.
                            onProfileChange(ConnectionProfile())
                            Toast.makeText(context, R.string.reset_done, Toast.LENGTH_SHORT).show()
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                        Text(text = "  " + stringResource(R.string.reset_settings))
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            selected = profile.splitApps,
            onDismiss = { showAppPicker = false },
            onConfirm = {
                onProfileChange(profile.copy(splitApps = it))
                showAppPicker = false
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
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(8.dp))
    Divider()
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
    )
}

@Composable
private fun Divider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
    )
}

@Composable
private fun SettingLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun HelperText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun protocolLabel(protocol: Protocol): String = when (protocol) {
    Protocol.AUTO -> stringResource(R.string.protocol_auto)
    Protocol.MASQUE -> stringResource(R.string.protocol_masque)
    Protocol.WIREGUARD -> stringResource(R.string.protocol_wireguard)
    Protocol.GOOL -> stringResource(R.string.protocol_gool)
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

/**
 * One fixed proxy endpoint (e.g. "127.0.0.1:10808") with a copy button.
 * The value is a compile-time constant address: it is the SAME every session,
 * so what the user copies into Psiphon/Telegram/etc. keeps working forever.
 */
@Composable
private fun ProxyEndpointRow(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                // BiDi fix: ip:port must always render LTR, even in RTL locale.
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(value))
            Toast.makeText(context, R.string.share_copied, Toast.LENGTH_SHORT).show()
        }) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.share_copy),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
