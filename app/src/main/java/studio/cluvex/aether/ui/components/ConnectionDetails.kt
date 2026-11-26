package studio.cluvex.aether.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AppTraffic
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.PingMonitor
import studio.cluvex.aether.core.PingStats
import java.util.Locale

/*
 * 1.7.0 (same version): the connection card's rows are interactive.
 *
 *  - Latency row  -> [PingDetailsDialog]: last / min / max / average, jitter,
 *    packet loss and the number of probes, plus a 10-probe burst on demand.
 *  - Pipeline row -> [ChainDetailsDialog]: every node of the chain in the order
 *    traffic leaves the phone, what each node is and which one the local
 *    network (DPI) sees and which one websites see.
 *  - Traffic row  -> [TrafficDetailsDialog]: totals and the apps using the
 *    internet right now (Android "Usage access", granted by the user).
 *
 * Text on coloured labels is white on purpose: coloured text on a tinted chip
 * is hard to read.
 */

private val DetailText = Color(0xFFFFFFFF)
private val DetailMuted = Color(0xFFCBD5E1)
private val DetailDim = Color(0xFF94A3B8)
private val DetailAccent = Color(0xFF32E0C4)
private val DetailChip = Color(0xFF1E2B4A)

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = DetailMuted, fontSize = 13.sp)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(value, color = DetailText, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

private fun msText(ms: Long): String = if (ms < 0L) "\u2014" else "$ms ms"

internal fun detailBytes(v: Long): String {
    val b = v.coerceAtLeast(0L).toDouble()
    return when {
        b >= 1024.0 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1024.0 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
        b >= 1024.0 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> String.format(Locale.US, "%.0f B", b)
    }
}

// ------------------------------------------------------------------ latency

@Composable
internal fun PingDetailsDialog(connected: Boolean, onDismiss: () -> Unit) {
    val history by PingMonitor.history.collectAsState()
    val summary = PingStats.of(history)
    val scope = rememberCoroutineScope()
    var burst by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_ping_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StatRow(stringResource(R.string.cd_ping_last), msText(summary.lastMs))
                StatRow(stringResource(R.string.cd_ping_min), msText(summary.minMs))
                StatRow(stringResource(R.string.cd_ping_max), msText(summary.maxMs))
                StatRow(stringResource(R.string.cd_ping_avg), msText(summary.avgMs))
                StatRow(stringResource(R.string.cd_ping_jitter), msText(summary.jitterMs))
                StatRow(stringResource(R.string.cd_ping_loss), "${summary.lossPercent}%")
                StatRow(stringResource(R.string.cd_ping_samples), "${summary.answered} / ${summary.samples}")
                Spacer(Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.cd_ping_note), color = DetailDim, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = connected && !burst, onClick = {
                burst = true
                scope.launch {
                    try {
                        repeat(BURST_PROBES) {
                            PingMonitor.pingOnce(viaTunnel = true)
                            delay(BURST_GAP_MS)
                        }
                    } finally {
                        burst = false
                    }
                }
            }) { Text(stringResource(if (burst) R.string.cd_ping_measuring else R.string.cd_ping_burst)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_close)) } },
    )
}

private const val BURST_PROBES = 10
private const val BURST_GAP_MS = 300L

// ------------------------------------------------------------------ chain

/** The nodes of a pipeline label such as "VLESS → Aether(MASQUE) → Tor". */
internal fun chainNodes(label: String): List<String> =
    label.split('\u2192').map { it.trim() }.filter { it.isNotEmpty() && it != "\u2014" }

@Composable
private fun nodeKind(name: String): String {
    val n = name.lowercase(Locale.ROOT)
    return stringResource(when {
        n.startsWith("aether") -> R.string.cd_node_aether
        n.startsWith("tor") -> R.string.cd_node_tor
        n.startsWith("psiphon") -> R.string.cd_node_psiphon
        else -> R.string.cd_node_config
    })
}

@Composable
internal fun ChainDetailsDialog(chain: String, ipInfo: IpEndpoint?, connected: Boolean, onDismiss: () -> Unit) {
    val nodes = chainNodes(chain)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_chain_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!connected || nodes.isEmpty()) {
                    Text(stringResource(R.string.cd_chain_offline), color = DetailMuted, fontSize = 13.sp)
                } else {
                EndPoint("\uD83D\uDCF1", stringResource(R.string.cd_chain_device))
                nodes.forEachIndexed { i, node ->
                    Arrow()
                    val role = stringResource(when {
                        nodes.size == 1 -> R.string.cd_role_single
                        i == 0 -> R.string.cd_role_first
                        i == nodes.lastIndex -> R.string.cd_role_exit
                        else -> R.string.cd_role_middle
                    })
                    NodeCard(i + 1, node, nodeKind(node), role)
                }
                Arrow()
                EndPoint("\uD83C\uDF10", stringResource(R.string.cd_chain_internet) +
                    (ipInfo?.ip?.let { "  \u2066$it\u2069" } ?: ""))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_close)) } },
    )
}

@Composable
private fun Arrow() {
    Text("\u2193", Modifier.fillMaxWidth(), color = DetailDim, fontSize = 14.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun EndPoint(icon: String, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = DetailMuted, fontSize = 13.sp)
    }
}

@Composable
private fun NodeCard(index: Int, name: String, kind: String, role: String) {
    Row(
        Modifier.fillMaxWidth()
            .background(DetailChip, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).background(DetailAccent.copy(alpha = .35f), CircleShape),
            contentAlignment = Alignment.Center) {
            Text("$index", color = DetailText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(name, color = DetailText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth())
            }
            Text(kind, color = DetailMuted, fontSize = 12.sp)
            Text(role, color = DetailDim, fontSize = 11.sp)
        }
    }
}

// ------------------------------------------------------------------ traffic

@Composable
internal fun TrafficDetailsDialog(
    connected: Boolean,
    sinceMs: Long?,
    downTotal: Long,
    upTotal: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(AppTraffic.hasAccess(context)) }
    var apps by remember { mutableStateOf<List<AppTraffic.Usage>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(connected, sinceMs) {
        var previous = emptyMap<Int, Long>()
        while (true) {
            access = AppTraffic.hasAccess(context)
            if (access) {
                val since = sinceMs ?: (System.currentTimeMillis() - 60 * 60 * 1000L)
                val now = withContext(Dispatchers.IO) {
                    runCatching { AppTraffic.query(context, since, previous) }.getOrDefault(emptyList())
                }
                apps = now
                previous = now.associate { it.uid to it.total }
                loaded = true
            }
            delay(TRAFFIC_REFRESH_MS)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cd_traffic_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                StatRow(stringResource(R.string.traffic_download), detailBytes(if (connected) downTotal else 0L))
                StatRow(stringResource(R.string.traffic_upload), detailBytes(if (connected) upTotal else 0L))
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = DetailChip)
                Text(stringResource(R.string.cd_traffic_apps), color = DetailText, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.padding(top = 4.dp))
                when {
                    !access -> {
                        Text(stringResource(R.string.cd_traffic_access_needed), color = DetailMuted, fontSize = 12.sp)
                        TextButton(onClick = { AppTraffic.openAccessSettings(context) }) {
                            Text(stringResource(R.string.cd_traffic_grant))
                        }
                    }
                    !loaded -> Text("\u2026", color = DetailMuted)
                    apps.isEmpty() -> Text(stringResource(R.string.cd_traffic_none), color = DetailMuted, fontSize = 12.sp)
                    else -> apps.take(MAX_APPS).forEach { app -> AppRow(app) }
                }
                Spacer(Modifier.padding(top = 6.dp))
                Text(stringResource(R.string.cd_traffic_note), color = DetailDim, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_close)) } },
    )
}

@Composable
private fun AppRow(app: AppTraffic.Usage) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(app.label, color = DetailText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text("\u2193 ${detailBytes(app.rxBytes)}   \u2191 ${detailBytes(app.txBytes)}",
                    color = DetailMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        if (app.activeNow) {
            Box(Modifier.background(DetailAccent.copy(alpha = .35f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(stringResource(R.string.cd_traffic_active), color = DetailText, fontSize = 10.sp)
            }
        }
    }
}

private const val TRAFFIC_REFRESH_MS = 3_000L
private const val MAX_APPS = 25
