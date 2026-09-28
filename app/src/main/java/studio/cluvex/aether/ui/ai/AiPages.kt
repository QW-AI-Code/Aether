package studio.cluvex.aether.ui.ai

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiAdviceState
import studio.cluvex.aether.ai.AiModelPolicy
import studio.cluvex.aether.ai.AiPatch
import studio.cluvex.aether.ai.AiProbe
import studio.cluvex.aether.ai.AiSession
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.components.SecureSurface
import studio.cluvex.aether.ui.settings.GroupCaption
import studio.cluvex.aether.ui.settings.GroupFooter
import studio.cluvex.aether.ui.settings.RowDivider
import studio.cluvex.aether.ui.settings.SettingsActionRow
import studio.cluvex.aether.ui.settings.SettingsBlock
import studio.cluvex.aether.ui.settings.SettingsChoiceRow
import studio.cluvex.aether.ui.settings.SettingsGroup
import studio.cluvex.aether.ui.settings.SettingsNavRow
import studio.cluvex.aether.ui.settings.SettingsScaffold
import studio.cluvex.aether.ui.settings.SettingsSwitchRow
import studio.cluvex.aether.ui.settings.settingsSection
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.Navy800
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkDim
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * The AI settings page: key, models, behaviour, and the two AI screens.
 *
 * Built out of the SAME row primitives as every other settings page, on purpose.
 * A feature this size invites its own bespoke screen, and a bespoke screen is how
 * an app ends up with two settings design languages - which is precisely the state
 * 1.2.7 spent a release getting out of.
 */
@Composable
fun AiSettingsPage(
    profile: ConnectionProfile,
    state: ConnectionState,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenAdvisor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings by AiSession.settings.collectAsState()
    val models by AiSession.models.collectAsState()
    val discovering by AiSession.discovering.collectAsState()
    val probe by AiSession.probe.collectAsState()
    val gate = AiSession.gate(state, profile)

    // Local draft wins once the user has typed, so a debounced store write can
    // never echo a stale value back into the field mid-key. Same defence as
    // LtrOutlinedTextField's own focus rule, one level up.
    var draft by remember { mutableStateOf<String?>(null) }
    var reveal by remember { mutableStateOf(false) }
    val keyText = draft ?: settings.apiKey

    val forgotten = stringResource(R.string.ai_key_forgotten)

    // AUDIT F-3: the Gemini API key lives on this page, and the reveal toggle
    // below puts it on screen in the clear. Blocks screenshots, screen recording
    // and the recents thumbnail for as long as the page is composed.
    SecureSurface()

    SettingsScaffold(stringResource(R.string.ai_title), onBack, modifier) {
        if (!gate.ready) {
            settingsSection {
                Spacer(Modifier.height(6.dp))
                AiGateNotice(gate)
            }
        }

        // ---- key ---------------------------------------------------------
        settingsSection {
            GroupCaption(stringResource(R.string.ai_section_key))
            SettingsGroup {
                SettingsBlock {
                    LtrOutlinedTextField(
                        value = keyText,
                        onValueChange = {
                            draft = it
                            AiSession.setKey(it)
                        },
                        singleLine = true,
                        // Masked by default: an API key is a credential, and this
                        // screen gets opened in front of other people.
                        visualTransformation = if (reveal) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        label = { Text(stringResource(R.string.ai_key_label)) },
                        placeholder = { Text(stringResource(R.string.ai_key_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (reveal) R.string.ai_key_hide else R.string.ai_key_show,
                                ),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }
                                    ?.let {
                                        draft = it
                                        AiSession.setKey(it)
                                    }
                            },
                        ) { Text(stringResource(R.string.ai_key_paste)) }
                    }
                }
                RowDivider(inset = false)
                SettingsActionRow(
                    title = stringResource(R.string.ai_test),
                    summary = probeSummary(probe, discovering),
                    icon = Icons.Rounded.Science,
                    enabled = settings.hasKey && !discovering,
                    onClick = { AiSession.testConnection(profile, state) },
                    aiTopic = AiTopic.AI_KEY,
                )
                if (settings.hasKey) {
                    RowDivider(inset = false)
                    SettingsActionRow(
                        title = stringResource(R.string.ai_key_forget),
                        icon = Icons.Rounded.Delete,
                        destructive = true,
                        onClick = {
                            draft = ""
                            reveal = false
                            AiSession.forget()
                            Toast.makeText(context, forgotten, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            GroupFooter(stringResource(R.string.ai_key_help))
        }

        // ---- models ------------------------------------------------------
        settingsSection {
            GroupCaption(stringResource(R.string.ai_section_models))
            SettingsGroup {
                if (models.isEmpty()) {
                    // Two genuinely different situations, and telling them apart is
                    // the whole value of the message. Nothing discovered yet is a
                    // "press the button" state. Discovery having RUN and returned
                    // none of the five supported models means the key is real but
                    // cannot see them - a region or tier problem no amount of
                    // pressing refresh will fix, and the user has to be told that
                    // rather than left refreshing an empty list.
                    val ranOut = probe is AiProbe.Ok
                    GroupFooter(
                        if (ranOut) {
                            stringResource(
                                R.string.ai_models_none_allowed,
                                AiModelPolicy.ALLOWED.joinToString("\n") { "• $it" },
                            )
                        } else {
                            stringResource(R.string.ai_models_empty)
                        },
                    )
                } else {
                    SettingsChoiceRow(
                        title = stringResource(R.string.ai_model_label),
                        icon = Icons.Rounded.AutoAwesome,
                        options = models.map { it.id },
                        selected = settings.effectiveModel,
                        // Numbered, from the allow-list's own ordering, so the row
                        // reads "1. gemini-3.8-flash" both in the picker sheet and
                        // in the collapsed row. The number is the model's fixed
                        // position in AiModelPolicy.ALLOWED, not its index in this
                        // list - see AiModelPolicy.displayNumber for why that
                        // distinction matters.
                        label = { modelRowLabel(it) },
                        // 1.4.0-r5: each model says what it trades - the default
                        // (3.1 Flash-Lite) has the highest free daily limit; the
                        // newer Flash models are faster but run out sooner.
                        optionDescription = { modelDescription(it) },
                        onSelect = { AiSession.setModel(it) },
                        summary = stringResource(R.string.ai_models_count, models.size),
                        aiTopic = AiTopic.AI_MODEL,
                    )
                    RowDivider(inset = false)
                }
                SettingsActionRow(
                    title = stringResource(R.string.ai_models_refresh),
                    summary = stringResource(R.string.ai_models_filtered),
                    icon = Icons.Rounded.Refresh,
                    enabled = settings.hasKey && !discovering,
                    onClick = { AiSession.discoverModels(profile, state) },
                )
            }
            GroupFooter(stringResource(R.string.ai_models_help))
        }

        // ---- behaviour ---------------------------------------------------
        settingsSection {
            GroupCaption(stringResource(R.string.ai_section_behaviour))
            SettingsGroup {
                SettingsSwitchRow(
                    title = stringResource(R.string.ai_auto_optimize),
                    summary = stringResource(R.string.ai_auto_optimize_desc),
                    icon = Icons.Rounded.Insights,
                    checked = settings.autoOptimize,
                    onCheckedChange = { AiSession.setAutoOptimize(it) },
                    aiTopic = AiTopic.AI_AUTO_OPTIMIZE,
                )
                if (settings.autoOptimize) {
                    RowDivider(inset = false)
                    SettingsSwitchRow(
                        title = stringResource(R.string.ai_auto_apply),
                        summary = stringResource(R.string.ai_auto_apply_desc),
                        checked = settings.autoApply,
                        onCheckedChange = { AiSession.setAutoApply(it) },
                        aiTopic = AiTopic.AI_AUTO_APPLY,
                    )
                }
                RowDivider(inset = false)
                SettingsSwitchRow(
                    title = stringResource(R.string.ai_hints),
                    summary = stringResource(R.string.ai_hints_desc),
                    icon = Icons.Rounded.AutoAwesome,
                    checked = settings.showHints,
                    onCheckedChange = { AiSession.setShowHints(it) },
                    aiTopic = AiTopic.AI_HINTS,
                )
            }
        }

        // ---- the two AI screens -----------------------------------------
        settingsSection {
            SettingsGroup {
                SettingsNavRow(
                    title = stringResource(R.string.ai_chat_open),
                    summary = stringResource(R.string.ai_chat_subtitle),
                    icon = Icons.Rounded.Chat,
                    onClick = onOpenChat,
                    aiTopic = AiTopic.AI_CHAT,
                )
                RowDivider()
                SettingsNavRow(
                    title = stringResource(R.string.ai_advisor_title),
                    summary = stringResource(R.string.ai_advisor_subtitle),
                    icon = Icons.Rounded.Insights,
                    onClick = onOpenAdvisor,
                )
            }
        }

        // ---- the honest small print --------------------------------------
        settingsSection {
            GroupCaption(stringResource(R.string.ai_privacy_title))
            SettingsGroup {
                InfoBlock(
                    icon = Icons.Rounded.Lock,
                    title = stringResource(R.string.ai_privacy_title),
                    body = stringResource(R.string.ai_privacy_body),
                )
                RowDivider(inset = false)
                InfoBlock(
                    icon = Icons.Rounded.Key,
                    title = stringResource(R.string.ai_scope_title),
                    body = stringResource(R.string.ai_scope_body),
                )
            }
        }
    }
}

/**
 * The DPI / log analysis page.
 *
 * One button, one verdict, one list of proposals. It does NOT auto-run on open:
 * opening a page must not spend somebody's API quota, and the automatic path
 * already exists behind an explicit switch.
 */
@Composable
fun AiAdvisorPage(
    profile: ConnectionProfile,
    state: ConnectionState,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val persian = remember { LanguagePrefs.isPersian(context) }
    val advice by AiSession.advice.collectAsState()
    val gate = AiSession.gate(state, profile)

    SettingsScaffold(stringResource(R.string.ai_advisor_title), onBack, modifier) {
        if (!gate.ready) {
            settingsSection {
                Spacer(Modifier.height(6.dp))
                AiGateNotice(gate)
            }
        }

        settingsSection {
            SettingsGroup {
                SettingsActionRow(
                    title = stringResource(R.string.ai_advisor_run),
                    summary = stringResource(R.string.ai_advisor_subtitle),
                    icon = Icons.Rounded.Insights,
                    enabled = gate.ready && advice !is AiAdviceState.Running,
                    onClick = {
                        AiSession.analyze(
                            profile = profile,
                            state = state,
                            persian = persian,
                            auto = false,
                        )
                    },
                )
            }
            GroupFooter(stringResource(R.string.ai_advisor_idle))
        }

        settingsSection {
            when (val current = advice) {
                is AiAdviceState.Idle -> Unit

                is AiAdviceState.Running -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Navy800)
                        .padding(16.dp),
                ) {
                    AiInlineProgress(stringResource(R.string.ai_advisor_running))
                }

                is AiAdviceState.Failed -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Navy800)
                        .padding(16.dp),
                ) {
                    // "تحلیل ممکن نشد: unreadable answer" was the old output of this
                    // line: a translated prefix followed by an English internal
                    // token. The cause now travels on the state and the wording is
                    // chosen here. See aiFailureText.
                    Text(
                        text = stringResource(
                            R.string.ai_advisor_failed,
                            aiFailureText(
                                gate = current.gate,
                                kind = current.kind,
                                problem = current.reason,
                                detail = current.message,
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                is AiAdviceState.Ready -> Column(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Navy800)
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = AetherViolet,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.ai_advisor_dpi),
                                style = MaterialTheme.typography.labelMedium,
                                color = AetherViolet,
                            )
                            Spacer(Modifier.width(8.dp))
                            confidenceLabel(current.advice.confidence)?.let { label ->
                                Text(
                                    text = stringResource(R.string.ai_advisor_confidence, label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnDarkDim,
                                )
                            }
                        }
                        if (current.advice.dpi.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            AiRichText(
                                text = current.advice.dpi,
                                style = MaterialTheme.typography.bodyMedium,
                                color = OnDark,
                            )
                        }
                        if (current.advice.summary.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            AiRichText(
                                text = current.advice.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnDarkMuted,
                            )
                        }
                        if (current.advice.changes.isEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(R.string.ai_advisor_no_changes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = { AiSession.dismissAdvice() }) {
                                Text(stringResource(R.string.ai_dismiss))
                            }
                        }
                    }
                    if (current.advice.changes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        AiChangesCard(
                            changes = current.advice.changes,
                            applied = current.applied,
                            onApply = {
                                onProfileChange(
                                    AiPatch.apply(profile, current.advice.changes).profile,
                                )
                                AiSession.markAdviceApplied()
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

/** A titled paragraph inside a settings group, for the two small-print blocks. */
@Composable
private fun InfoBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Row(modifier = Modifier.padding(14.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AetherViolet,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = OnDark,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkMuted,
            )
        }
    }
}

/** Summary line under "Test API connection". */
@Composable
private fun probeSummary(probe: AiProbe, discovering: Boolean): String = when {
    discovering -> stringResource(R.string.ai_test_running)
    probe is AiProbe.Ok -> stringResource(R.string.ai_test_ok, probe.modelCount, probe.via)
    probe is AiProbe.Failed -> stringResource(
        R.string.ai_test_failed,
        // Was aiErrorText(kind, message), which printed the gate's enum name as
        // the "detail" for a failure that never left the device.
        aiFailureText(gate = probe.gate, kind = probe.kind, detail = probe.message),
    )
    else -> stringResource(R.string.ai_test_hint)
}

/**
 * "1. gemini-3.8-flash" - the numbered label used in the picker.
 *
 * Not a composable and not localised: a model id is a Latin-script identifier and
 * the number in front of it is a position, so both are the same in every language.
 * Wrapping the digits in a localised format string would give a Persian user
 * Eastern Arabic numerals in front of a Latin id, which is worse to read than
 * either alone.
 */
private fun modelRowLabel(id: String): String {
    val number = AiModelPolicy.displayNumber(id)
    return if (number > 0) "$number. $id" else id
}

/** 1.4.0-r5: the one-line guidance shown under each model in the picker. */
@Composable
private fun modelDescription(id: String): String? = when (AiModelPolicy.tier(id)) {
    AiModelPolicy.Tier.RECOMMENDED_DEFAULT -> stringResource(R.string.ai_model_desc_default)
    AiModelPolicy.Tier.FAST_LOW_QUOTA -> stringResource(R.string.ai_model_desc_fast)
    AiModelPolicy.Tier.HIGH_QUOTA_ALT -> stringResource(R.string.ai_model_desc_alt)
    null -> null
}

/** Maps the model's confidence word onto a localised label, or null if absent. */
@Composable
private fun confidenceLabel(raw: String): String? = when (raw) {
    "high" -> stringResource(R.string.ai_confidence_high)
    "medium" -> stringResource(R.string.ai_confidence_medium)
    "low" -> stringResource(R.string.ai_confidence_low)
    else -> null
}
