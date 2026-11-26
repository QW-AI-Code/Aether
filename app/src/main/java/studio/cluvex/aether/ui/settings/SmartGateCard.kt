package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.ui.ai.AiTopicIcon
import studio.cluvex.aether.ui.theme.AetherBlue
import studio.cluvex.aether.ui.theme.Navy700
import studio.cluvex.aether.ui.theme.Navy850
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * 1.7.0 (same version): the "Smart connection gate" information card - the same
 * block as Aether Desktop 1.5.0's Connection page (info icon, title, one paragraph).
 *
 * It replaces the old "Verify connection" switch, both in Settings → Connection
 * and on the custom-configs page, so a user who looks for that switch learns that
 * it was replaced, and why. It is information only: there is nothing to toggle,
 * because the smart gate ([studio.cluvex.aether.core.LiveDataProof]) is always on
 * for every session.
 */
@Composable
fun SmartGateCard(aiTopic: AiTopic, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("smart-gate-card")
            .clip(shape)
            .background(Navy850)
            .border(1.dp, Navy700, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(AetherBlue.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = null,
                    tint = AetherBlue,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.smart_gate_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = OnDark,
                modifier = Modifier.weight(1f),
            )
            AiTopicIcon(aiTopic)
        }
        Text(
            text = stringResource(R.string.smart_gate_desc),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkMuted,
            lineHeight = 20.sp,
        )
    }
}
