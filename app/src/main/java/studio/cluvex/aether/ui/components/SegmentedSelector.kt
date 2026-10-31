package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.theme.Navy700
import studio.cluvex.aether.ui.theme.Navy800
import studio.cluvex.aether.ui.theme.OnDark

/**
 * A single-choice control whose options are SEPARATE cards (1.4.0-r4).
 *
 * ## What changed, and why
 *
 * Up to r3 this was one pill-shaped track with the options as bare labels on it
 * and only the selected one filled. With five protocols it wrapped into 3 + 2,
 * and the unselected labels floated on the track with nothing around them - the
 * control read as a block of words rather than as five things you can tap
 * (reported on Connection → Protocol, and true of every place it is used).
 *
 * Every option is now its own card, drawn exactly like the option cards in the
 * app's choice sheets ([studio.cluvex.aether.ui.settings.SettingsChoiceRow]):
 * the same surface, the same hairline border, and the same selected treatment -
 * tinted fill, stronger outline, bold primary label and a check mark - with an
 * 8dp gutter between cards, so the options are visibly separate and the current
 * one is obvious from the shape of the grid, not from one colour.
 *
 * ## Columns are chosen by measurement, not by count
 *
 * The number of columns is the LARGEST that lets every label fit on one line in
 * its card (measured with the real font, check mark included), capped at three.
 * "V4 / V6 / Both" stays one row of three; the five protocols become a 2-column
 * grid; "All except selected" style labels drop to one card per row instead of
 * being ellipsised. A short last row keeps the cell width of the rows above it,
 * so the grid stays uniform; a single leftover card spans the whole last row.
 */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (options.isEmpty()) return
    val labels = options.map { label(it) }
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val gutter = 8.dp
        val maxColumns = minOf(MAX_PER_ROW, options.size)
        val longest = remember(labels, labelStyle) {
            labels.maxOf { measurer.measure(it, labelStyle, maxLines = 1).size.width }
        }
        val columns = remember(longest, maxWidth, maxColumns) {
            with(density) {
                // Card chrome: horizontal padding on both sides + check mark + gap.
                val chrome = (CELL_H_PADDING * 2 + CHECK_SIZE + CHECK_GAP).roundToPx()
                var c = maxColumns
                while (c > 1) {
                    val cell = ((maxWidth - gutter * (c - 1)) / c).roundToPx()
                    if (longest + chrome <= cell) break
                    c--
                }
                // Four options in three columns would be 3 + 1; 2 + 2 reads better
                // and every label that fits a third of the width fits a half.
                if (c == 3 && options.size == 4) 2 else c
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(gutter)) {
            options.indices.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    row.forEach { index ->
                        OptionCard(
                            text = labels[index],
                            isSelected = options[index] == selected,
                            enabled = enabled,
                            onClick = { onSelect(options[index]) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val missing = columns - row.size
                    // A short last row keeps the cell width of the rows above it
                    // (3 + 2), except a single leftover card, which takes the whole
                    // row (2 + 2 + 1): a lone half-width card next to a hole reads
                    // as a layout bug, a full-width one reads as intentional.
                    if (missing > 0 && row.size > 1) {
                        Spacer(Modifier.weight(missing.toFloat()))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionCard(
    text: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(CELL_RADIUS)
    val fill by animateColorAsState(
        targetValue = if (isSelected) primary.copy(alpha = if (enabled) 0.14f else 0.07f) else Navy800,
        animationSpec = tween(160),
        label = "optfill",
    )
    val outline by animateColorAsState(
        targetValue = if (isSelected) primary.copy(alpha = if (enabled) 0.6f else 0.3f) else Navy700,
        animationSpec = tween(160),
        label = "optline",
    )
    val fg = when {
        isSelected -> primary.copy(alpha = if (enabled) 1f else 0.5f)
        else -> OnDark.copy(alpha = if (enabled) 0.92f else 0.45f)
    }
    Row(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(width = if (isSelected) 1.dp else 0.5.dp, color = outline, shape = shape)
            .selectable(
                selected = isSelected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = CELL_H_PADDING, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(CHECK_SIZE),
            )
            Spacer(Modifier.width(CHECK_GAP))
        }
        Text(
            text = text,
            color = fg,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** Most cards the control will put in one row. */
private const val MAX_PER_ROW = 3

private val CELL_RADIUS = 14.dp
private val CELL_H_PADDING = 10.dp
private val CHECK_SIZE = 16.dp
private val CHECK_GAP = 6.dp
