package studio.cluvex.aether.ui.components

import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.ui.ai.AiTopicIcon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.IconButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import studio.cluvex.aether.ui.settings.SettingsMenuAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionPreset

@Composable
fun PresetSelector(
    presets: List<ConnectionPreset>,
    activeId: String?,
    locked: Boolean,
    onSelect: (String) -> Unit,
    onSaveAsNew: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit = {},
    onExport: () -> Unit = {},
    onRename: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(locked) { if (locked) { menuOpen = false; naming = false; renameId = null; deleteId = null } }
    val active = presets.firstOrNull { it.id == activeId }
    val defaultLabel = stringResource(R.string.preset_default_name)
    val label = active?.displayName(defaultLabel) ?: defaultLabel
    val lockedHint = stringResource(R.string.preset_locked_hint)
    val shape = RoundedCornerShape(50)
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val menuWidth = (configuration.screenWidthDp.dp - 32.dp).coerceIn(1.dp, 340.dp)
    val menuHeight = (configuration.screenHeightDp.dp - 32.dp).coerceAtLeast(1.dp)
    val positionProvider = remember(density) {
        with(density) { CenteredPresetMenuPositionProvider(16.dp.roundToPx(), 8.dp.roundToPx()) }
    }

    Box(modifier) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .widthIn(max = 260.dp)
                .alpha(if (locked) 0.55f else 1f)
                .clip(shape)
                .clickable(enabled = !locked, role = Role.Button) { menuOpen = true }
                .semantics { if (locked) stateDescription = lockedHint },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            ) {
                FitLineText(
                    text = label,
                    minFontSize = 11.sp,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
            }
        }
        if (menuOpen) Popup(popupPositionProvider = positionProvider,
            onDismissRequest = { menuOpen = false },
            properties = PopupProperties(focusable = true)) {
            Surface(
                modifier = Modifier.width(menuWidth).heightIn(max = menuHeight).testTag("preset-menu"),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        AiTopicIcon(AiTopic.PRESETS)
                    }
                    Column(Modifier.heightIn(max = if (presets.size > 2) 168.dp else 200.dp)
                        .verticalScroll(rememberScrollState())) {
                        presets.forEach { p ->
                            SettingsMenuAction(onClick = {
                                menuOpen = false
                                // Re-selecting Default must also restore clean settings
                                // after edits made while Default was already selected.
                                if (p.id != activeId || p.isSystemDefault) onSelect(p.id)
                            }) {
                                Icon(if (p.id == activeId) Icons.Rounded.Check else Icons.Rounded.Tune, null,
                                    tint = MaterialTheme.colorScheme.primary)
                                FitLineText(p.displayName(defaultLabel), MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f).width(112.dp), minFontSize = 11.sp,
                                    contentAlignment = Alignment.CenterStart)
                                if (!p.isSystemDefault) {
                                    IconButton(onClick = { menuOpen = false; renameId = p.id }) {
                                        Icon(Icons.Rounded.Edit, stringResource(R.string.cc_action_rename))
                                    }
                                    IconButton(enabled = presets.size > 1,
                                        onClick = { menuOpen = false; deleteId = p.id }) {
                                        Icon(Icons.Rounded.Delete, stringResource(R.string.cc_action_delete),
                                            tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    SettingsMenuAction(onClick = { menuOpen = false; naming = true }) {
                        Icon(Icons.Rounded.Add, null)
                        Text(stringResource(R.string.preset_save_as_new), Modifier.weight(1f))
                    }
                    SettingsMenuAction(onClick = { menuOpen = false; onImport() }) {
                        Icon(Icons.Rounded.FileDownload, null)
                        Text(stringResource(R.string.preset_import), Modifier.weight(1f))
                    }
                    SettingsMenuAction(onClick = { menuOpen = false; onExport() }) {
                        Icon(Icons.Rounded.FileUpload, null)
                        Text(stringResource(R.string.preset_export), Modifier.weight(1f))
                    }
                }
            }
        }
    }

    renameId?.let { id ->
        presets.firstOrNull { it.id == id && !it.isSystemDefault }?.let { preset ->
            PresetNameDialog(initialName = preset.name, renaming = true,
                onDismiss = { renameId = null },
                onConfirm = { renameId = null; onRename(id, it) })
        }
    }
    deleteId?.takeIf { id -> presets.any { it.id == id && !it.isSystemDefault } }?.let { id ->
        AlertDialog(onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.cc_action_delete)) },
            text = { Text(presets.firstOrNull { it.id == id }?.displayName(defaultLabel).orEmpty()) },
            confirmButton = { TextButton(onClick = { deleteId = null; onDelete(id) }) {
                Text(stringResource(R.string.cc_action_delete), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteId = null }) {
                Text(stringResource(R.string.preset_cancel))
            } })
    }
    if (naming) {
        PresetNameDialog(
            onDismiss = { naming = false },
            onConfirm = { naming = false; onSaveAsNew(it) },
        )
    }
}

/** Horizontal placement is based on the window, not the pill's RTL/LTR anchor edge. */
internal class CenteredPresetMenuPositionProvider(
    private val marginPx: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(0)
        val minY = marginPx.coerceAtMost(maxY)
        val below = anchorBounds.bottom + gapPx
        val y = if (below <= maxY) below.coerceAtLeast(minY)
            else (anchorBounds.top - gapPx - popupContentSize.height).coerceIn(minY, maxY)
        return IntOffset(x, y)
    }
}

@Composable
private fun PresetNameDialog(initialName: String = "", renaming: Boolean = false,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val clean = ConnectionPreset.cleanName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (renaming) R.string.cc_action_rename else R.string.preset_save_as_new), Modifier.weight(1f))
            AiTopicIcon(AiTopic.PRESETS)
        } },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= ConnectionPreset.MAX_NAME_LENGTH) name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.preset_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clean) }, enabled = clean.isNotEmpty()) {
                Text(stringResource(R.string.preset_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.preset_cancel)) }
        },
    )
}
