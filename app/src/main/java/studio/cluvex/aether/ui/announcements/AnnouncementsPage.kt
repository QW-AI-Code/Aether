package studio.cluvex.aether.ui.announcements

import studio.cluvex.aether.ai.AiTopic
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.settings.GroupFooter
import studio.cluvex.aether.ui.settings.RowDivider
import studio.cluvex.aether.ui.settings.SettingsGroup
import studio.cluvex.aether.ui.settings.SettingsNavRow
import studio.cluvex.aether.ui.settings.SettingsScaffold
import studio.cluvex.aether.ui.settings.settingsSection

/**
 * Settings → پیام‌ها و اطلاعیه‌ها: the permanent archive of every announcement.
 * Tapping one re-opens it in the same navy dialog (without the "don't show
 * again" checkbox, which only belongs to the automatic launch-time dialog).
 */
@Composable
fun AnnouncementsPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var supportOpen by rememberSaveable { mutableStateOf(false) }
    var openVersion by rememberSaveable { mutableStateOf<String?>(null) }

    SettingsScaffold(stringResource(R.string.announcements_title), onBack, modifier) {
        settingsSection {
            SettingsGroup {
                Announcements.all.forEachIndexed { index, item ->
                    if (index > 0) RowDivider()
                    SettingsNavRow(
                        aiTopic = AiTopic.ANNOUNCEMENTS,
                        title = item.shortTitle,
                        summary = stringResource(R.string.announcements_version, ltr(item.version)),
                        icon = Icons.Rounded.Campaign,
                        onClick = { openVersion = item.version },
                    )
                }
                RowDivider()
                SettingsNavRow(
                    aiTopic = AiTopic.ANNOUNCEMENTS,
                    title = "حمایت از سازندهٔ هستهٔ اتر",
                    summary = "پیام حمایت از سازندهٔ هسته و کانال تلگرام او",
                    icon = Icons.Rounded.Campaign,
                    onClick = { supportOpen = true },
                )
            }
            GroupFooter(stringResource(R.string.announcements_footer))
        }
    }

    Announcements.all.firstOrNull { it.version == openVersion }?.let { item ->
        WhatsNewDialog(
            announcement = item,
            showDontShowAgain = false,
            onDismiss = { openVersion = null },
        )
    }

    if (supportOpen) {
        CreatorSupportDialog(showDontShowAgain = false, onDismiss = { supportOpen = false })
    }
}
