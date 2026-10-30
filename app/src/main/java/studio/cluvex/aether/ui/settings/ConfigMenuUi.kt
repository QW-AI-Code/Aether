package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.ui.ai.AiTopicIcon

/**
 * Same outlined navy card language as the shared settings choice sheet.
 *
 * 1.6.0 spacing contract: this composable is the ONLY source of an action's
 * vertical rhythm - [MenuActionGap] outside the card, a [MenuActionMinHeight]
 * row (>= 48 dp touch target at every font scale) with [MenuActionPadding]
 * inside. Callers pass bare content; stacking another `padding` on top of the
 * row or the text produced the uneven, over-tall menus of 1.5.x. Typography is
 * the theme's `bodyLarge` and follows the system font scale untouched.
 */
@Composable
internal fun SettingsMenuAction(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Surface(onClick = onClick, enabled = enabled,
        modifier = modifier.fillMaxWidth().padding(vertical = MenuActionGap),
        shape = RoundedCornerShape(20.dp),
        color = studio.cluvex.aether.ui.theme.Navy850,
        contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
            Row(Modifier.fillMaxWidth().heightIn(min = MenuActionMinHeight)
                .padding(horizontal = MenuActionPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

/** Gap above and below one action card (6 dp between neighbours). */
internal val MenuActionGap = 3.dp

/** Minimum row height; keeps the 48 dp accessibility target with margin. */
internal val MenuActionMinHeight = 56.dp

/** Horizontal inset of the action content. */
internal val MenuActionPadding = 16.dp

/** Bounded content prevents tall action menus from losing their last controls. */
@Composable
internal fun ConfigDialog(onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null, title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    topic: AiTopic = AiTopic.CUSTOM_CONFIGS) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .62f).dp
    AlertDialog(onDismissRequest = onDismissRequest, modifier = modifier,
        shape = RoundedCornerShape(28.dp), containerColor = containerColor,
        title = { Row { Column(Modifier.weight(1f)) { title?.invoke() }; AiTopicIcon(topic) } },
        text = text?.let { body -> { Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())) { body() } } },
        confirmButton = confirmButton, dismissButton = dismissButton)
}
