package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R
import studio.cluvex.aether.custom.*
import java.util.Locale

/** QWVPN-style capsules; projection/filter/move rules still belong to ConfigCatalog. */
@Composable
internal fun ConfigDataTable(
    rows: List<ConfigCatalog.Row>, activeId: String?, pings: Map<String, ConfigPing.Report>,
    editable: Boolean, testingAll: Boolean, onSelect: (CustomConfig) -> Unit,
    onConfigMenu: (CustomConfig) -> Unit,
    onSubscriptionMenu: (CustomConfigStore.Subscription) -> Unit,
    serverNumbers: Map<String, Long> = emptyMap(),
    onScanIp: ((CustomConfig) -> Unit)? = null,
    scanning: Boolean = false,
    scanTargetId: String? = null,
    scanProgress: Int = 0,
    scanValidating: Boolean = false,
    // 1.7.0 (same version): one-tap actions on every capsule, so the common
    // operations no longer need the config's menu. Null hides an action.
    onPing: ((CustomConfig) -> Unit)? = null,
    onSpeed: ((CustomConfig) -> Unit)? = null,
    onDelete: ((CustomConfig) -> Unit)? = null,
) {
    if (rows.isEmpty()) { SettingsNoticeRow(stringResource(R.string.cc_empty), Icons.Rounded.MoreVert); return }
    val listState = rememberLazyListState()
    val speeds by ConfigSpeedTest.results.collectAsState()
    val membership = rows.map { it.key }
    LaunchedEffect(membership) {
        if (listState.firstVisibleItemIndex >= rows.size) listState.scrollToItem(0)
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("config-capsules"),
        state = listState, contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            val c = row.config
            val sub = row.subscription
            val selected = c != null && c.id == activeId
            Surface(shape = RoundedCornerShape(24.dp),
                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .22f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .65f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
                modifier = Modifier.fillMaxWidth().testTag("capsule:${row.key}")) {
                Column(Modifier.clickable(enabled = editable && c != null, onClick = { c?.let(onSelect) })
                    .padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = .18f)) {
                            Box(Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
                                .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", color = androidx.compose.ui.graphics.Color.White,
                                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                            }
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (c != null) PingChip(pings[c.id])
                        }
                        IconButton(enabled = !testingAll, onClick = {
                            if (c != null) onConfigMenu(c) else sub?.let(onSubscriptionMenu)
                        }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.MoreVert, stringResource(R.string.cc_col_actions))
                        }
                    }
                    // Both bidi layout AND text direction are pinned, including Persian locale.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        val technical = c?.let { configTechnicalLabel(it, serverNumbers[it.id]) } ?: sub!!.name
                        Text(technical, Modifier.fillMaxWidth().padding(vertical = 7.dp).testTag("technical:${row.key}"),
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                            textAlign = TextAlign.Left, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        c?.let { config -> speeds[config.id]?.let { result ->
                            Text(when {
                                result.running -> stringResource(R.string.cc_speed_running)
                                result.downloadBps > 0 || result.uploadBps > 0 ->
                                    "↓ ${speedLabel(result.downloadBps)}  ↑ ${speedLabel(result.uploadBps)}"
                                else -> stringResource(R.string.cc_speed_failed)
                            }, color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                        } }
                    }
                    // 1.7.0 (same version): the one-tap actions sit on their own row, so the
                    // ping / status chip above keeps the whole width it needs.
                    if (c != null && (onPing != null || onSpeed != null || onDelete != null || onScanIp != null)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                            if (c != null) {
                                val speedRunning = speeds[c.id]?.running == true
                                if (onPing != null) QuickAction(Icons.Rounded.Refresh, stringResource(R.string.cc_quick_ping),
                                    enabled = !testingAll && pings[c.id]?.status != ConfigPing.PingStatus.TESTING,
                                    tag = "quick-ping:${c.id}") { onPing(c) }
                                if (onSpeed != null) QuickAction(Icons.Rounded.Speed, stringResource(R.string.cc_quick_speed),
                                    enabled = !testingAll && !speedRunning, tag = "quick-speed:${c.id}") { onSpeed(c) }
                                if (onDelete != null) QuickAction(Icons.Rounded.Delete, stringResource(R.string.cc_quick_delete),
                                    enabled = editable && !testingAll, tag = "quick-delete:${c.id}",
                                    tint = MaterialTheme.colorScheme.error) { onDelete(c) }
                            }
                            if (c != null && onScanIp != null && studio.cluvex.aether.scanner.CleanIpScanManager.supported(c)) {
                                IconButton(enabled = editable && !testingAll && !scanning,
                                    onClick = { onScanIp(c) }, modifier = Modifier.size(36.dp).testTag("scan:${c.id}")) {
                                    Icon(Icons.Rounded.Search, stringResource(R.string.cip_server_shortcut))
                                }
                            }
                        }
                    }
                    if (scanning && c != null && c.id == scanTargetId) {
                        val progress = scanProgress.coerceIn(0, 100)
                        val blue = androidx.compose.ui.graphics.Color(0xFF4FC3F7)
                        Text(stringResource(if (scanValidating) R.string.cip_validating else R.string.cip_probing, progress),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("scan-progress:${c.id}"),
                            color = blue, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { progress / 100f }, color = blue,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp)
                                .testTag("scan-progress-bar:${c.id}"))
                    }
                    if (sub != null) Text(stringResource(R.string.cc_link_source),
                        color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                }
            }
        }
    }
}

/** A small (32 dp) icon button for the capsule's one-tap actions. */
@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    tag: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(enabled = enabled, onClick = onClick, modifier = Modifier.size(32.dp).testTag(tag)) {
        Icon(icon, label, Modifier.size(18.dp), tint = if (enabled) tint else tint.copy(alpha = .38f))
    }
}

internal fun configTechnicalLabel(config: CustomConfig, serverNumber: Long? = null): String =
    serverTechnicalLabel(config.protocol.short, configDisplayEndpoint(config), serverNumber, config.locked)

private fun speedLabel(bps: Long): String =
    if (bps <= 0) "—" else String.format(Locale.ROOT, "%.2f MB/s", bps / 1_000_000.0)

@Composable
private fun PingChip(report: ConfigPing.Report?) {
    val label = when (report?.status ?: ConfigPing.PingStatus.UNTESTED) {
        ConfigPing.PingStatus.OK -> "${report!!.ms} ms"
        ConfigPing.PingStatus.TESTING -> "…"
        ConfigPing.PingStatus.OFFLINE -> stringResource(R.string.cc_ping_offline)
        ConfigPing.PingStatus.BLOCKED -> stringResource(R.string.cc_ping_blocked)
        ConfigPing.PingStatus.NO_REPLY -> stringResource(R.string.cc_ping_noreply)
        ConfigPing.PingStatus.INVALID -> stringResource(R.string.cc_ping_invalid)
        ConfigPing.PingStatus.NO_NETWORK -> stringResource(R.string.cc_ping_nonet)
        ConfigPing.PingStatus.UNTESTED -> "—"
    }
    val color = when (PingQuality.of(report)) {
        PingQuality.EXCELLENT -> studio.cluvex.aether.ui.theme.AetherMint
        PingQuality.FAIR -> studio.cluvex.aether.ui.theme.AetherAmber
        PingQuality.POOR -> studio.cluvex.aether.ui.theme.AetherError
        PingQuality.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 1.7.0 (same version): the whole status is always shown - the font shrinks
    // to the space the row leaves (FitLineText) instead of ending in "...".
    // White text on the tinted chip: coloured text on colour is hard to read.
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = .22f),
        border = BorderStroke(1.dp, color.copy(alpha = .55f))) {
        studio.cluvex.aether.ui.components.FitLineText(
            text = label,
            style = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            color = androidx.compose.ui.graphics.Color.White,
            minFontSize = 7.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
