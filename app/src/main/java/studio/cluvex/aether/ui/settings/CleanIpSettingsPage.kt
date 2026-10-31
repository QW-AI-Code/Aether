package studio.cluvex.aether.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.ui.ai.AiTopicIcon
import studio.cluvex.aether.R
import studio.cluvex.aether.scanner.CleanIpScanManager
import studio.cluvex.aether.scanner.CustomIpRanges
import studio.cluvex.aether.scanner.ScanProfile
import studio.cluvex.aether.ui.components.LtrOutlinedTextField

/** Ordinary settings only; opening this page never initiates a network probe. */
@Composable
internal fun CleanIpSettingsPage(enabled: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var options by remember { mutableStateOf(CleanIpScanManager.options(context)) }
    var limit by rememberSaveable { mutableStateOf(options.ceiling.toString()) }
    var rangesText by rememberSaveable { mutableStateOf(options.customRanges) }
    val scan by CleanIpScanManager.state.collectAsState()
    val unlocked = enabled && !scan.running
    fun update(next: CleanIpScanManager.Options) {
        options = next; CleanIpScanManager.saveOptions(context, next)
    }
    SettingsScaffold(stringResource(R.string.cip_settings_title), onBack, modifier) {
        settingsSection {
            GroupCaption(stringResource(R.string.cip_profile), aiTopic = AiTopic.CLEAN_IP_PROFILE)
            SettingsGroup {
                Text(stringResource(R.string.cip_profiles_desc), Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall)
                ScanProfile.entries.forEach { profile ->
                    Row(Modifier.fillMaxWidth().testTag("scan-profile:${profile.name}")
                        .selectable(selected = profile == options.profile, enabled = unlocked,
                            role = Role.RadioButton, onClick = { update(options.copy(profile = profile)) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(when (profile) {
                                ScanProfile.FAST -> R.string.cip_fast
                                ScanProfile.BALANCED -> R.string.cip_balanced
                                ScanProfile.DEEP -> R.string.cip_deep
                            }), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(when (profile) {
                                ScanProfile.FAST -> R.string.cip_fast_desc
                                ScanProfile.BALANCED -> R.string.cip_balanced_desc
                                ScanProfile.DEEP -> R.string.cip_deep_desc
                            }), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.cip_profile_detail, profile.candidateBudget,
                                profile.timeoutMs, profile.probesPerIp, profile.parallelism),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AiTopicIcon(when (profile) {
                            ScanProfile.FAST -> AiTopic.CLEAN_IP_FAST
                            ScanProfile.BALANCED -> AiTopic.CLEAN_IP_BALANCED
                            ScanProfile.DEEP -> AiTopic.CLEAN_IP_DEEP
                        })
                        Spacer(Modifier.width(8.dp))
                        RadioButton(selected = profile == options.profile, onClick = null, enabled = unlocked)
                    }
                }
            }
        }
        settingsSection {
            GroupCaption(stringResource(R.string.cip_title), aiTopic = AiTopic.CLEAN_IP_SCANNER)
            SettingsGroup {
                SettingsSwitchRow(title = stringResource(R.string.cip_manual_ceiling),
                    summary = stringResource(R.string.cip_manual_desc), checked = options.manual,
                    enabled = unlocked, aiTopic = AiTopic.CLEAN_IP_LIMIT,
                    onCheckedChange = { update(options.copy(manual = it)) })
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val valid = limit.toIntOrNull()?.let { it in 50..5000 } == true
                    LtrOutlinedTextField(value = limit, onValueChange = { text ->
                        limit = text.filter(Char::isDigit).take(5)
                        limit.toIntOrNull()?.takeIf { it in 50..5000 }?.let { update(options.copy(ceiling = it)) }
                    }, enabled = unlocked && options.manual, keyboardType = KeyboardType.Number,
                        modifier = Modifier.fillMaxWidth().testTag("scan-ping-limit"),
                        label = { Text(stringResource(R.string.cip_ceiling)) },
                        isError = options.manual && !valid,
                        supportingText = { Text(stringResource(if (options.manual && !valid)
                            R.string.cip_limit_error else R.string.cip_limit_policy)) })
                    Text(stringResource(R.string.cip_notice), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        settingsSection {
            GroupCaption(stringResource(R.string.cip_custom_title), aiTopic = AiTopic.CLEAN_IP_SCANNER)
            SettingsGroup {
                SettingsSwitchRow(title = stringResource(R.string.cip_custom_enable),
                    summary = stringResource(R.string.cip_custom_desc), checked = options.customEnabled,
                    enabled = unlocked, aiTopic = AiTopic.CLEAN_IP_SCANNER,
                    onCheckedChange = { update(options.copy(customEnabled = it)) })
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val parsed = remember(rangesText) { CustomIpRanges.parse(rangesText) }
                    val blank = rangesText.isBlank()
                    val hasError = parsed.invalid.isNotEmpty()
                    LtrOutlinedTextField(value = rangesText, onValueChange = { text ->
                        rangesText = text.take(CleanIpScanManager.MAX_RANGES_TEXT)
                        update(options.copy(customRanges = rangesText))
                    }, enabled = unlocked && options.customEnabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).testTag("scan-custom-ranges"),
                        singleLine = false,
                        label = { Text(stringResource(R.string.cip_custom_label)) },
                        isError = options.customEnabled && (hasError || blank),
                        supportingText = { Text(when {
                            hasError -> stringResource(R.string.cip_custom_invalid, parsed.invalid.first())
                            blank -> stringResource(R.string.cip_custom_hint)
                            else -> stringResource(R.string.cip_custom_count, parsed.ranges.size,
                                CustomIpRanges.total(parsed.ranges).toString())
                        }) })
                }
            }
        }
    }
}
