package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.custom.serverTechnicalLabel
import studio.cluvex.aether.scanner.CleanIpScanEngine

/** A bottom result card, not another nested scrollable dialog. */
@Composable
internal fun CleanIpScanResultCard(report: CleanIpScanEngine.Report, number: Long?,
    onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.testTag("clean-ip-result"), shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val before = serverTechnicalLabel(report.protocol, report.previous, number)
                    Text(if (report.next != null && report.pingMs != null)
                        "$before → ${report.next} · ${report.pingMs} ms" else before,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                        textAlign = TextAlign.Left)
                }
                if (report.outcome != CleanIpScanEngine.Outcome.APPLIED) Text(stringResource(when (report.outcome) {
                    CleanIpScanEngine.Outcome.CANCELLED -> R.string.cip_cancelled
                    CleanIpScanEngine.Outcome.FAILED -> R.string.cip_failed
                    else -> R.string.cip_unchanged
                }), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, stringResource(R.string.cip_result_dismiss))
            }
        }
    }
}
