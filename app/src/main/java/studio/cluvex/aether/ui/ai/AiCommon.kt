package studio.cluvex.aether.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiAdviceProblem
import studio.cluvex.aether.ai.AiAvailability
import studio.cluvex.aether.ai.AiChange
import studio.cluvex.aether.ai.AiErrorKind
import studio.cluvex.aether.ai.AiGate
import studio.cluvex.aether.ai.AiMarkdown
import studio.cluvex.aether.ai.AiPatch
import studio.cluvex.aether.ai.AiResult
import studio.cluvex.aether.ai.AiSession
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.Navy800
import studio.cluvex.aether.ui.theme.Navy850
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkDim
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * What an AI icon needs to know, without every row having to be handed it.
 *
 * ## Why a composition local rather than parameters
 *
 * "Put an AI icon next to every option" means the icon appears on ~50 rows spread
 * over ten settings pages, and it needs the live connection state and the current
 * profile to decide what it can do and what value it is explaining. Threading two
 * more parameters through every row composable, every page and [
 * studio.cluvex.aether.ui.settings.SettingsHost] would mean touching every call
 * site in the app for something none of those call sites care about - and the row
 * API in `SettingsUi.kt` exists precisely so that rows stay identical to each
 * other.
 *
 * So a row declares only WHICH topic it is about (one nullable enum), and the icon
 * reads the context from here. The default is [enabled] = false, so a row rendered
 * outside a provider simply shows no icon instead of crashing or showing a broken
 * one.
 */
data class AiHostContext(
    val profile: ConnectionProfile = ConnectionProfile(),
    val state: ConnectionState = ConnectionState.Idle,
    /** False when the user turned the hints off, or when no provider is present. */
    val enabled: Boolean = false,
    /** Opens the AI settings page from inside a sheet. Null when not available. */
    val openAiSettings: (() -> Unit)? = null,
    /** Opens the connection page, for the "wrong mode" fix. */
    val openConnection: (() -> Unit)? = null,
    /**
     * Opens the chat screen. Null when the host cannot navigate there.
     *
     * Present so an explanation sheet can hand its topic to the assistant: the
     * sheet gives one fixed ~120-word answer, and a user who did not follow it has
     * nowhere to go from there. With this, "ask about this in the chat" is a button
     * rather than a paragraph telling them to open the chat and retype the
     * question.
     */
    val openChat: (() -> Unit)? = null,
)

val LocalAiHost = compositionLocalOf { AiHostContext() }

/**
 * The little AI mark that sits on a settings row.
 *
 * Renders nothing at all when hints are off, which is what keeps the promise that
 * this feature is additive: with the switch off the settings screens are byte-for-
 * byte the app they were before.
 */
@Composable
fun AiTopicIcon(topic: AiTopic?, modifier: Modifier = Modifier) {
    val host = LocalAiHost.current
    if (topic == null || !host.enabled) return
    var open by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(AetherViolet.copy(alpha = 0.16f))
            .clickable { open = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = stringResource(R.string.ai_explain_title),
            tint = AetherViolet,
            modifier = Modifier.size(16.dp),
        )
    }

    if (open) {
        AiExplainSheet(topic = topic, onDismiss = { open = false })
    }
}

/**
 * The bottom sheet that answers "what is this setting, and how do I use it".
 *
 * The topic's own factual description is shown IMMEDIATELY, from
 * [AiTopic.detail], and Gemini's explanation streams in underneath it. That order
 * is the point: the sheet is useful the instant it opens, it is useful with no key
 * entered, and it is useful while disconnected - which is exactly when a user is
 * most likely to be reading about settings, because that is the only time the
 * tunnel settings can be edited. The AI adds depth and the user's own language on
 * top; it is not the gate to basic help.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiExplainSheet(topic: AiTopic, onDismiss: () -> Unit) {
    val host = LocalAiHost.current
    val context = LocalContext.current
    val persian = remember { LanguagePrefs.isPersian(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var loading by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<Pair<String, AiErrorKind>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    val gate = AiSession.gate(host.state, host.profile)

    LaunchedEffect(topic, attempt, gate) {
        if (!gate.ready) return@LaunchedEffect
        loading = true
        failure = null
        when (val result = AiSession.explain(topic, host.profile, host.state, persian)) {
            is AiResult.Ok -> answer = result.value
            is AiResult.Err -> failure = result.message to result.kind
        }
        loading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Navy850,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 26.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(AetherViolet.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = AetherViolet,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = topic.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = OnDark,
                    )
                    topic.profileKey?.let { key ->
                        Text(
                            text = AiPatch.read(host.profile, key),
                            style = MaterialTheme.typography.bodySmall,
                            color = AetherViolet,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // The offline truth, always present.
            Text(
                text = topic.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = OnDarkMuted,
            )

            Spacer(Modifier.height(18.dp))

            when {
                !gate.ready -> AiGateNotice(gate)
                loading && answer == null -> AiInlineProgress(stringResource(R.string.ai_explain_loading))
                failure != null -> Column {
                    Text(
                        text = stringResource(
                            R.string.ai_explain_failed,
                            aiErrorText(failure!!.second, failure!!.first),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { attempt++ }) {
                        Text(stringResource(R.string.ai_explain_retry))
                    }
                }
                answer != null -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Navy800)
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = AetherViolet,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.ai_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = AetherViolet,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // r4: rendered, not printed. The raw Markdown used to reach the
                    // sheet verbatim ("Split tunneling ###", "**۱. چیست؟**").
                    AiRichText(
                        text = answer!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnDark,
                    )
                }
            }

            // ---- carry this topic into the chat --------------------------
            //
            // Offered whether or not the AI answered, and whether or not the gate
            // is open, because the sheet's own factual description is always
            // there: a user reading topic.detail while disconnected is exactly the
            // person who wants to ask a follow-up, and the chat screen will tell
            // them what it needs to work.
            val openChat = host.openChat
            if (openChat != null) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        // The whole context travels with the question: the option's
                        // name, its current value, the app's own description of it
                        // and the explanation the user just failed to follow. The
                        // chat can then be asked for something SIMPLER rather than
                        // for the same answer again, which is the entire point.
                        AiSession.askInChat(
                            explainFollowUpPrompt(
                                persian = persian,
                                label = topic.label,
                                detail = topic.detail,
                                currentValue = topic.profileKey
                                    ?.let { AiPatch.read(host.profile, it) },
                                previousAnswer = answer,
                            ),
                        )
                        onDismiss()
                        openChat()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Forum,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_explain_ask_chat))
                }
            }
        }
    }
}

/**
 * Builds the question the "ask the assistant" button sends.
 *
 * Written in the user's own language, because it lands in the chat as THEIR turn
 * and a Persian user must not see themselves apparently typing English. The
 * option name, its description and its value stay verbatim: they are the app's
 * technical ground truth and translating them here would have the assistant
 * explaining a slightly different setting - the same rule [AiPrompts] follows.
 */
private fun explainFollowUpPrompt(
    persian: Boolean,
    label: String,
    detail: String,
    currentValue: String?,
    previousAnswer: String?,
): String = buildString {
    if (persian) {
        append("توضیح گزینهٔ «")
        append(label)
        append("» را نفهمیدم. ساده‌تر و با یک مثال عملی توضیح بده که این گزینه چه کاری می‌کند")
        append(" و من باید روی چه مقداری بگذارمش.")
    } else {
        append("I did not understand the option \"")
        append(label)
        append("\". Explain in simpler words, with a practical example, what it does")
        append(" and what I should set it to.")
    }
    append("\n\n")
    append("Option: ").append(label).append('\n')
    append("Description: ").append(detail).append('\n')
    if (currentValue != null) append("My current value: ").append(currentValue).append('\n')
    if (!previousAnswer.isNullOrBlank()) {
        append("\nThe explanation I did not follow:\n").append(previousAnswer)
    }
}

/**
 * Explains why the AI is unavailable, and offers the one action that fixes it.
 *
 * A dead end with no way out is the worst possible version of this card, and the
 * two interesting cases both have an obvious fix that is two taps away in a
 * different screen - so the card carries the navigation instead of describing it.
 */
@Composable
fun AiGateNotice(gate: AiGate, modifier: Modifier = Modifier) {
    if (gate.ready) return
    val host = LocalAiHost.current
    val icon = when (gate) {
        AiGate.NO_KEY -> Icons.Rounded.Key
        AiGate.NO_MODEL -> Icons.Rounded.Insights
        AiGate.WRONG_MODE -> Icons.Rounded.Layers
        else -> Icons.Rounded.CloudOff
    }
    val body = aiGateText(gate)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Navy800)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AetherViolet,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = OnDarkMuted,
            )
        }
        val fix: Pair<String, () -> Unit>? = when (gate) {
            AiGate.NO_KEY, AiGate.NO_MODEL ->
                host.openAiSettings?.let { stringResource(R.string.ai_gate_fix_key) to it }
            AiGate.WRONG_MODE ->
                host.openConnection?.let { stringResource(R.string.ai_gate_fix_mode) to it }
            else -> null
        }
        if (fix != null) {
            TextButton(onClick = fix.second) { Text(fix.first) }
        }
    }
}

/** A spinner with a label, used wherever the AI is working. */
@Composable
fun AiInlineProgress(label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = AetherViolet,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkMuted,
        )
    }
}

/**
 * The card that turns a model's proposal into something a human approves.
 *
 * Every proposed change is shown as `key: old -> new` with the model's own reason,
 * because "Apply 4 changes" is not consent. The note about the next connect is
 * always visible: tunnel settings are handed to the engine at start-up, so an
 * applied change genuinely does nothing until the session is rebuilt, and a user
 * who is not told that will conclude the AI lied to them.
 */
@Composable
fun AiChangesCard(
    changes: List<AiChange>,
    applied: Boolean,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (changes.isEmpty()) return
    val host = LocalAiHost.current

    // ---- the "takes effect on the next connect" notice ----------------------
    //
    // ROOT CAUSE of "the message is given but cannot be seen in the box": the
    // notice was a line of 12sp dimmed body text INSIDE this card, directly above
    // the Apply button. In the chat it sits at the bottom of a scrolling list, so
    // pressing Apply scrolled it out of view; in the advisor page the card is
    // below the fold on a small screen. The single most important sentence in the
    // whole feature - that nothing the user just approved is live yet - was the
    // least visible thing on screen.
    //
    // A dialog cannot be scrolled past and cannot be dismissed by accident, and it
    // fires on the Apply TAP rather than on composition, so it is tied to the
    // action it is explaining. The inline line stays as well: after the dialog is
    // dismissed the card still has to say why it is showing "Applied".
    var showAppliedDialog by rememberSaveable { mutableStateOf(false) }
    if (showAppliedDialog) {
        AlertDialog(
            onDismissRequest = { showAppliedDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    tint = AetherViolet,
                )
            },
            title = { Text(stringResource(R.string.ai_applied_dialog_title)) },
            text = { Text(stringResource(R.string.ai_applied_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showAppliedDialog = false }) {
                    Text(stringResource(R.string.ai_applied_dialog_ok))
                }
            },
            containerColor = Navy850,
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Navy800)
            .padding(14.dp),
    ) {
        Text(
            text = stringResource(R.string.ai_changes_title, changes.size),
            style = MaterialTheme.typography.labelLarge,
            color = AetherViolet,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        changes.forEach { change ->
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    text = "${change.key}: ${AiPatch.read(host.profile, change.key)} → ${change.value}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDark,
                )
                if (change.why.isNotBlank()) {
                    Text(
                        text = AiMarkdown.plainInline(change.why),
                        style = MaterialTheme.typography.bodySmall,
                        color = OnDarkDim,
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.ai_apply_note),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkDim,
        )
        Spacer(Modifier.height(4.dp))
        if (applied) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.ai_applied),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onApply()
                        showAppliedDialog = true
                    },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_apply))
                }
            }
        }
    }
}

/**
 * Turns an [AiErrorKind] into a sentence, keeping Google's own text as detail.
 *
 * The headline is always translated; the raw upstream text is appended in
 * parentheses ONLY when it is worth reading. Before 1.2.9 that detail was
 * appended unconditionally, which is why a Persian user saw a Persian headline
 * followed by "You exceeded your current quota, please check your plan and
 * billing details. For more information on this error, head to:
 * https://ai.google.dev/... Quota exceeded for metric: ..." - four lines of
 * English boilerplate that says nothing the headline has not already said, on a
 * screen whose whole job is to explain the cause in the user's own language.
 *
 * [isBoilerplate] drops exactly those: the messages whose content is fully
 * covered by the translated headline. Anything unexpected still comes through,
 * because an unrecognised upstream message is the thing a bug report needs.
 */
@Composable
fun aiErrorText(kind: AiErrorKind, detail: String): String = when (kind) {
    AiErrorKind.BAD_KEY -> stringResource(R.string.ai_error_bad_key)
    AiErrorKind.RATE_LIMIT -> stringResource(R.string.ai_error_rate_limit)
    AiErrorKind.NO_SUCH_MODEL -> stringResource(R.string.ai_error_no_model)
    AiErrorKind.TRANSPORT -> stringResource(R.string.ai_error_transport)
    AiErrorKind.SERVER_ERROR -> stringResource(R.string.ai_error_server)
    AiErrorKind.PROTOCOL -> stringResource(R.string.ai_error_protocol)
    AiErrorKind.TRUNCATED -> stringResource(R.string.ai_error_truncated)
    AiErrorKind.BLOCKED -> stringResource(R.string.ai_error_blocked)
}.let { headline ->
    if (detail.isBlank() || detail == kind.name || isBoilerplate(kind, detail)) {
        headline
    } else {
        "$headline ($detail)"
    }
}

/**
 * True when Google's own message adds nothing the translated headline has not said.
 *
 * Matched on the stable technical fragments rather than on whole sentences, since
 * the prose around them changes without notice. A quota URL is a quota URL.
 */
private fun isBoilerplate(kind: AiErrorKind, detail: String): Boolean {
    val lower = detail.lowercase()
    return when (kind) {
        AiErrorKind.RATE_LIMIT ->
            lower.contains("exceeded your current quota") ||
                lower.contains("quota exceeded for metric") ||
                lower.contains("rate-limits") ||
                lower.contains("please retry in")
        AiErrorKind.BAD_KEY ->
            lower.contains("api key not valid") || lower.contains("api_key_invalid")
        AiErrorKind.SERVER_ERROR ->
            lower.contains("internal error encountered") ||
                lower.contains("service is currently unavailable") ||
                lower.startsWith("http 5")
        // The partial answer rides on TRUNCATED errors as the message; it is text
        // the model wrote, not a diagnostic, and must never be shown as a cause.
        AiErrorKind.TRUNCATED -> true
        else -> false
    }
}

/**
 * The one function that turns anything the AI layer failed with into a sentence.
 *
 * Every failure surface in the feature - the chat bubble, the advisor card, the
 * explain sheet, the "test API connection" row - used to translate its own
 * subset and fall through to raw text for the rest. That is how `DISCONNECTED`,
 * `unreadable answer` and `empty log` reached the screen untranslated. One
 * function, three inputs, no fall-through to English.
 */
@Composable
fun aiFailureText(
    gate: AiGate? = null,
    kind: AiErrorKind? = null,
    problem: AiAdviceProblem? = null,
    detail: String = "",
): String = when {
    gate != null && !gate.ready -> aiGateText(gate)
    problem != null -> when (problem) {
        AiAdviceProblem.EMPTY_LOG -> stringResource(R.string.ai_advisor_empty_log)
        AiAdviceProblem.UNREADABLE -> stringResource(R.string.ai_advisor_unreadable)
    }
    kind != null -> aiErrorText(kind, detail)
    // Last resort: a raw string from a path that predates this function. Recognised
    // gate names are still mapped, so an old bubble does not print an enum.
    else -> gateByName(detail)?.let { aiGateText(it) }
        ?: detail.ifBlank { stringResource(R.string.ai_error_protocol) }
}

/** The user-facing sentence for a closed gate. */
@Composable
fun aiGateText(gate: AiGate): String = when (gate) {
    AiGate.NO_KEY -> stringResource(R.string.ai_gate_no_key)
    AiGate.NO_MODEL -> stringResource(R.string.ai_gate_no_model)
    AiGate.WRONG_MODE -> stringResource(R.string.ai_gate_wrong_mode)
    AiGate.DISCONNECTED -> stringResource(R.string.ai_gate_disconnected)
    AiGate.READY -> ""
}

private fun gateByName(raw: String): AiGate? =
    AiGate.entries.firstOrNull { it.name == raw.trim() }

/** The SOCKS port AI traffic uses, for the diagnostics row on the AI page. */
fun aiSocksPort(profile: ConnectionProfile): Int = AiAvailability.socksPort(profile.backend)
