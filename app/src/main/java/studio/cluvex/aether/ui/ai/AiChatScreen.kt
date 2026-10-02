package studio.cluvex.aether.ui.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiMarkdown
import studio.cluvex.aether.ai.AiMessage
import studio.cluvex.aether.ai.AiPatch
import studio.cluvex.aether.ai.AiSession
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.Navy700
import studio.cluvex.aether.ui.theme.Navy800
import studio.cluvex.aether.ui.theme.Navy850
import studio.cluvex.aether.ui.theme.Navy950
import studio.cluvex.aether.ui.theme.OnDark
import studio.cluvex.aether.ui.theme.OnDarkDim
import studio.cluvex.aether.ui.theme.OnDarkMuted

/**
 * The in-app chat.
 *
 * ## Why it is laid out the way Gemini's own app lays it out
 *
 * Asymmetric on purpose, and that asymmetry is the single thing that makes a chat
 * UI feel professional rather than homemade: the user's turns are short, so they
 * are narrow rounded chips pushed to the trailing edge; the model's turns are
 * long, so they are full-width text with a small mark above them and NO bubble.
 * Two facing bubbles - the shape almost every hand-rolled chat screen uses - waste
 * a third of the width on a phone and force long technical answers into a column
 * they cannot breathe in.
 *
 * Everything else follows the app's own design system rather than Google's brand:
 * the navy ramp, Vazirmatn in Persian, and the same 16dp page inset as every
 * settings page, so this screen belongs to Aether and not to a web view.
 *
 * The conversation lives in [AiSession], not here: this screen is disposed every
 * time the user goes back, and losing the conversation on a back gesture would be
 * indefensible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(
    state: ConnectionState,
    profile: ConnectionProfile,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val persian = remember { LanguagePrefs.isPersian(context) }
    val messages by AiSession.messages.collectAsState()
    val thinking by AiSession.thinking.collectAsState()
    val settings by AiSession.settings.collectAsState()
    val gate = AiSession.gate(state, profile)

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    /**
     * Ids the user has ticked for a group delete.
     *
     * A SnapshotStateList of ids rather than a flag on [AiMessage]: selection is a
     * property of this SCREEN, not of the conversation, so it must not survive a
     * back gesture and must not be something [AiSession] has to model. Ids rather
     * than indices because the list shifts under it - a reply arriving mid-selection
     * would otherwise silently re-point every tick at the message below it.
     */
    val selected = remember { mutableStateListOf<Long>() }
    val selecting = selected.isNotEmpty()
    // Prune ids that no longer exist, so "delete 3" cannot act on a stale tick
    // after a clear or an edit truncated the tail.
    LaunchedEffect(messages) {
        val live = messages.mapTo(HashSet()) { it.id }
        selected.retainAll { it in live }
    }

    /** The message being edited, if any. Never a model turn. */
    var editing by remember { mutableStateOf<AiMessage?>(null) }
    /** Ids queued for a confirmed delete, empty when no dialog is open. */
    var pendingDelete by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // A question handed over from a settings explanation sheet. Sent as soon as the
    // screen is up rather than dropped into the input box: the user already pressed
    // a button that said "ask the assistant", so making them press send again is a
    // second confirmation of a decision they have made.
    LaunchedEffect(Unit) {
        AiSession.consumeQueuedQuestion()?.let { question ->
            AiSession.send(question, profile, state, persian)
        }
    }

    // Follow the conversation. Keyed on the count AND on the thinking flag so the
    // typing indicator appearing also scrolls into view - otherwise the user sends
    // a message and watches a screen where, as far as they can tell, nothing
    // happened.
    LaunchedEffect(messages.size, thinking) {
        val target = messages.size + if (thinking) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Scaffold(
        modifier = modifier,
        containerColor = Navy950,
        topBar = {
            // One app bar with two modes rather than two bars: swapping the whole
            // TopAppBar out would re-run its enter animation on every selection
            // change, and the title/actions slots are exactly what needs to differ.
            TopAppBar(
                title = {
                    if (selecting) {
                        Text(
                            text = stringResource(R.string.ai_chat_selected, selected.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Column {
                            Text(
                                text = stringResource(R.string.ai_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val model = settings.effectiveModel
                            if (model.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.ai_chat_model, model),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnDarkDim,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = { if (selecting) selected.clear() else onBack() },
                    ) {
                        Icon(
                            imageVector = if (selecting) {
                                Icons.Rounded.Close
                            } else {
                                Icons.AutoMirrored.Rounded.ArrowBack
                            },
                            contentDescription = if (selecting) {
                                stringResource(R.string.ai_chat_selection_cancel)
                            } else {
                                stringResource(R.string.a11y_back)
                            },
                        )
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(
                            onClick = {
                                selected.clear()
                                selected.addAll(messages.map { it.id })
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SelectAll,
                                contentDescription = stringResource(R.string.ai_chat_select_all),
                                tint = OnDarkMuted,
                            )
                        }
                        IconButton(onClick = { pendingDelete = selected.toSet() }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(
                                    R.string.ai_chat_delete_selected,
                                ),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else if (messages.isNotEmpty()) {
                        IconButton(
                            onClick = { pendingDelete = messages.mapTo(HashSet()) { it.id } },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.ai_chat_clear),
                                tint = OnDarkMuted,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Navy950,
                    titleContentColor = OnDark,
                    navigationIconContentColor = OnDark,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                // imePadding, not a scroll hack: the input bar has to sit on the
                // keyboard, and `windowSoftInputMode=adjustResize` in the manifest
                // makes this the whole fix.
                .imePadding(),
        ) {
            if (messages.isEmpty() && !thinking) {
                AiChatEmptyState(
                    modifier = Modifier.weight(1f),
                    onSuggestion = { suggestion ->
                        AiSession.send(suggestion, profile, state, persian)
                    },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        val isSelected = message.id in selected
                        // Long-press starts selection; once selecting, a plain tap
                        // toggles. That is the platform's own gesture contract for a
                        // multi-select list, and it keeps a normal tap on a bubble
                        // doing nothing - which is what a normal tap should do.
                        val toggle = {
                            if (isSelected) {
                                selected.remove(message.id)
                            } else {
                                selected.add(message.id)
                            }
                            Unit
                        }
                        MessageRow(
                            selected = isSelected,
                            selecting = selecting,
                            onToggle = toggle,
                        ) {
                            if (message.fromUser) {
                                UserTurn(
                                    message = message,
                                    actionsEnabled = !selecting && !thinking,
                                    onEdit = { editing = message },
                                    onDelete = { pendingDelete = setOf(message.id) },
                                )
                            } else {
                                ModelTurn(
                                    message = message,
                                    actionsEnabled = !selecting,
                                    onApply = {
                                        val patched = AiPatch.apply(profile, message.changes)
                                        onProfileChange(patched.profile)
                                        AiSession.markChangesApplied(message.id)
                                    },
                                    onRetry = {
                                        AiSession.retry(message.id, profile, state, persian)
                                    },
                                    onDelete = { pendingDelete = setOf(message.id) },
                                    retryEnabled = !thinking,
                                )
                            }
                        }
                    }
                    if (thinking) {
                        item { TypingIndicator() }
                    }
                }
            }

            if (!gate.ready) {
                AiGateNotice(
                    gate = gate,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            ChatInputBar(
                value = draft,
                onValueChange = { draft = it },
                enabled = gate.ready && !selecting,
                thinking = thinking,
                onSend = {
                    val text = draft
                    draft = ""
                    AiSession.send(text, profile, state, persian)
                },
                onStop = { AiSession.stop() },
            )
        }
    }

    // ---- delete confirmation ---------------------------------------------
    //
    // Confirmed because a chat is not recoverable: there is no undo, no trash and
    // no server-side copy, so a mis-tap on the sweep icon in selection mode would
    // destroy a conversation the user cannot get back. The count is in the
    // question, which is the difference between confirming a delete and confirming
    // THIS delete.
    val toDelete = pendingDelete
    if (toDelete.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDelete = emptySet() },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.ai_chat_delete_title, toDelete.size)) },
            text = { Text(stringResource(R.string.ai_chat_delete_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        AiSession.deleteMessages(toDelete)
                        selected.clear()
                        pendingDelete = emptySet()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.ai_chat_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptySet() }) {
                    Text(stringResource(R.string.ai_chat_cancel))
                }
            },
            containerColor = Navy850,
        )
    }

    // ---- edit one of the user's own messages ------------------------------
    val underEdit = editing
    if (underEdit != null) {
        EditMessageDialog(
            initial = underEdit.text,
            onDismiss = { editing = null },
            onConfirm = { newText ->
                editing = null
                AiSession.editMessage(underEdit.id, newText, profile, state, persian)
            },
        )
    }
}

/**
 * Wraps a bubble in the selection affordance.
 *
 * The checkbox is laid out INSIDE the row rather than over the bubble so nothing
 * is ever covered by it, and the row keeps its long-press handler in both modes -
 * long-pressing a second message while selecting is how people extend a
 * selection, and a handler that only exists in one mode breaks that.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    // r4: the selection tint is DRAWN with the rounded shape instead of clipping
    // the row to it. A clip cuts the content too, and the AI mark in the top-start
    // corner of a model turn was losing the part that fell outside the 18dp curve
    // - the "half of the icon is hidden" report. Nothing inside a turn is clipped
    // any more; only the tint is rounded.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) {
                    Modifier.background(AetherViolet.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                // Long-press is the ONLY way into selection mode, and without a
                // label a screen reader user is never told it exists. TalkBack
                // lists it in the actions menu under this name.
                onLongClickLabel = stringResource(R.string.a11y_select_message),
                onLongClick = onToggle,
                // In selection mode a tap toggles; outside it a tap on a bubble does
                // nothing, which is why the click is a no-op rather than absent -
                // an absent onClick would also remove the long-press ripple.
                onClick = { if (selecting) onToggle() },
            )
            // ACCESSIBILITY: in selection mode the whole row IS the checkbox. It
            // reads "<message text>, checkbox, checked/not checked" as one stop,
            // instead of an unnamed checkbox sitting beside the message.
            .then(
                if (selecting) {
                    Modifier.semantics {
                        role = Role.Checkbox
                        toggleableState = ToggleableState(selected)
                    }
                } else {
                    Modifier
                },
            )
            .padding(vertical = if (selecting) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = AetherViolet,
                    uncheckedColor = OnDarkDim,
                    checkmarkColor = Navy950,
                ),
            )
            Spacer(Modifier.width(4.dp))
        }
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * The editor for a sent message.
 *
 * A dialog rather than re-using the composer at the bottom: putting the old text
 * back into the input bar loses whatever the user had already typed there, and
 * gives no visual link between the field and the message being changed.
 */
@Composable
private fun EditMessageDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                tint = AetherViolet,
            )
        },
        title = { Text(stringResource(R.string.ai_chat_edit_title)) },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp, max = 200.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Navy800)
                        .padding(12.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnDark),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(AetherViolet),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    // Said before the fact, not after: everything below the edited
                    // message is discarded, and a user who has scrolled up to fix a
                    // typo three questions back has to know that before they commit.
                    text = stringResource(R.string.ai_chat_edit_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkDim,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && text.trim() != initial.trim(),
                onClick = { onConfirm(text) },
            ) { Text(stringResource(R.string.ai_chat_edit_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_chat_cancel)) }
        },
        containerColor = Navy850,
    )
}

// ---------------------------------------------------------------- turns

/** The user's own turn: a narrow chip on the trailing edge, with edit and delete. */
@Composable
private fun UserTurn(
    message: AiMessage,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = OnDark,
            modifier = Modifier
                // Never the full width: a chip that reaches both edges stops
                // reading as "mine" and starts reading as a system notice.
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 6.dp,
                    ),
                )
                .background(Navy800)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (actionsEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.edited) {
                    Text(
                        text = stringResource(R.string.ai_chat_edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnDarkDim,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = stringResource(R.string.ai_chat_edit),
                        tint = OnDarkDim,
                        modifier = Modifier.size(15.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.ai_chat_delete),
                        tint = OnDarkDim,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }
}

/** The model's turn: full-width text under a small mark, plus its proposals. */
@Composable
private fun ModelTurn(
    message: AiMessage,
    actionsEnabled: Boolean,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    retryEnabled: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // r4: the mark sits at the START of the name, like Gemini's own sparkle -
        // on the RIGHT of "هوش مصنوعی اتر" in Persian, on the left of "Aether AI"
        // in English - by letting the Row follow the layout direction instead of
        // pinning it to LTR as r2 did.
        //
        // r2 pinned it to the left because at the start it was drawn half-hidden.
        // The real cause was not the position: MessageRow CLIPPED every turn to an
        // 18dp rounded rectangle (for the selection tint), and a 22dp mark placed
        // flush in the top-start corner of that rectangle lost the part that fell
        // outside the curve. MessageRow no longer clips its content (see there),
        // and the header is also inset from the corner, so the mark is drawn whole
        // on either side, in either language, selected or not.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AetherViolet.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = AetherViolet,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ai_title),
                style = MaterialTheme.typography.labelMedium,
                color = AetherViolet,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (message.failed) {
            // Translated here, from the CAUSE carried on the bubble, rather than
            // printed as whatever string the failure arrived with. See aiFailureText.
            Text(
                text = aiFailureText(
                    gate = message.gate,
                    kind = message.errorKind,
                    detail = message.text,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (actionsEnabled) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // "Send that message again" - the affordance the failed-send
                    // path never had. It resends the ORIGINAL prompt, which is why
                    // AiMessage carries it: re-typing a question you already asked
                    // in order to find out whether a 500 was transient is not a
                    // recovery path.
                    IconButton(
                        onClick = onRetry,
                        enabled = retryEnabled,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.ai_chat_retry),
                            tint = if (retryEnabled) AetherViolet else OnDarkDim,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.ai_chat_retry),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (retryEnabled) AetherViolet else OnDarkDim,
                        modifier = Modifier.clickable(enabled = retryEnabled, onClick = onRetry),
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.ai_chat_delete),
                            tint = OnDarkDim,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        } else {
            // r4: real Markdown rendering (headings, lists, bold, code, tables)
            // with per-block text direction - no `*` or `#` reaches the screen.
            AiRichText(
                text = message.text,
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = OnDark,
            )
            if (message.changes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                AiChangesCard(
                    changes = message.changes,
                    applied = message.applied,
                    onApply = onApply,
                )
            }
            if (actionsEnabled) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(AiMarkdown.toPlainText(message.text))) }) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.ai_chat_copy),
                            tint = OnDarkDim,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.ai_chat_delete),
                            tint = OnDarkDim,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- empty state

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiChatEmptyState(
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        stringResource(R.string.ai_chat_suggest_1),
        stringResource(R.string.ai_chat_suggest_2),
        stringResource(R.string.ai_chat_suggest_3),
        stringResource(R.string.ai_chat_suggest_4),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AetherViolet.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = AetherViolet,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ai_chat_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = OnDark,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ai_chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = OnDarkMuted,
        )
        Spacer(Modifier.height(20.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { suggestion ->
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = OnDarkMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Navy850)
                        .clickable { onSuggestion(suggestion) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- indicator

/** Three breathing dots: the model is working and the app is not frozen. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "ai-typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ai-typing-phase",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val distance = kotlin.math.abs(phase - index)
            val strength = (1f - distance).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .alpha(0.25f + 0.75f * strength)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AetherViolet),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.ai_chat_thinking),
            style = MaterialTheme.typography.bodySmall,
            color = OnDarkDim,
        )
    }
}

// ---------------------------------------------------------------- input

/**
 * The composer.
 *
 * [BasicTextField] rather than a Material text field: the pill shape, the flat
 * surface and the send button inside the same container are the whole look, and
 * fighting `OutlinedTextField`'s built-in label, indicator line and internal
 * padding to get there produces a control that drifts every time Material updates.
 * A `BasicTextField` in a `decorationBox` is the composable Material itself uses
 * underneath.
 */
@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    thinking: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = enabled && !thinking && value.isNotBlank()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp, max = 148.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Navy850)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnDark),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(AetherViolet),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_chat_input_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnDarkDim,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    when {
                        thinking -> Navy700
                        canSend -> AetherViolet
                        else -> Navy800
                    },
                )
                .clickable(enabled = thinking || canSend) {
                    if (thinking) onStop() else onSend()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (thinking) Icons.Rounded.Stop else Icons.Rounded.Send,
                contentDescription = stringResource(
                    if (thinking) R.string.ai_chat_stop else R.string.ai_chat_send,
                ),
                tint = if (canSend && !thinking) Navy950 else OnDarkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
