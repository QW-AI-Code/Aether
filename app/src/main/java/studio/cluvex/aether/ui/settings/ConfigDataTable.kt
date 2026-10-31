package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
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
                        if (c != null && onScanIp != null && studio.cluvex.aether.scanner.CleanIpScanManager.supported(c)) {
                            IconButton(enabled = editable && !testingAll && !scanning,
                                onClick = { onScanIp(c) }, modifier = Modifier.size(36.dp).testTag("scan:${c.id}")) {
                                Icon(Icons.Rounded.Search, stringResource(R.string.cip_server_shortcut))
                            }
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
    Surface(modifier = Modifier.widthIn(max = 140.dp),
        shape = RoundedCornerShape(50), color = color.copy(alpha = .12f),
        border = BorderStroke(1.dp, color.copy(alpha = .3f))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = color,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
