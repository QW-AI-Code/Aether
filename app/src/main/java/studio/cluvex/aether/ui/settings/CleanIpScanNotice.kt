package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R

/** Lifetime belongs to CleanIpScanManager, not to navigation or recomposition. */
@Composable
internal fun CleanIpScanNotice(message: String, applied: Int) {
    if (message.isBlank()) return
    Text(stringResource(when (message) {
        "locked" -> R.string.cip_locked
        "failed" -> R.string.cip_failed
        "cancelled" -> R.string.cip_cancelled
        "empty" -> R.string.cip_empty
        "ranges" -> R.string.cip_custom_none
        else -> R.string.cip_applied
    }, applied), modifier = Modifier.padding(horizontal = 16.dp).testTag("clean-ip-notice"),
        style = MaterialTheme.typography.bodySmall)
}
