package studio.cluvex.aether.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp

/**
 * ONE line of text whose SLOT HEIGHT never changes and whose glyphs are never
 * clipped (1.4.0-r3).
 *
 * ## Why r2 cut the bottom of "ل" / "ر"
 *
 * r2 forced the row to an arbitrary `lineHeight` (31 sp for a 27 sp title) and
 * pinned the Text to exactly that height. Vazirmatn's real line box is
 * ascent 2100 + descent 1100 units / 2048 upm = 1.5625 em, i.e. 42 sp at 27 sp,
 * and final-form lam / reh / meem hang up to ~0.34 em below the baseline.
 * Compressing 42 sp into 31 sp left ~9 sp under the baseline for a ~10 sp tail.
 * Compose then reports the glyph run as vertical overflow, and because the Text
 * used `TextOverflow.Ellipsis` it clips the draw to its own bounds - which is
 * exactly the half-missing tail in the screenshots. Any Persian word ending in a
 * descender would have done it; it was a design error, not an edge case.
 *
 * ## How r3 does it (the standard way)
 *
 * 1. The slot height comes from the FONT, not from a hand-picked number: it is
 *    the natural single-line height of the resolved style at its base size,
 *    measured once with [rememberTextMeasurer] on a probe that holds the deepest
 *    Persian descenders and the tallest Latin ascenders. That is the same metric
 *    Android's own StaticLayout uses (hhea ascent/descent + fallback line
 *    spacing), so it follows font scale, the bundled face and any system
 *    fallback font. [minLineHeight] is only a floor that keeps the old rhythm
 *    where the font's own box is smaller.
 * 2. The text is laid out with its NATURAL line height (no `lineHeight`
 *    compression, no LineHeightStyle trimming) and is allowed its unbounded
 *    intrinsic height, centred in the slot. The Text can therefore never report
 *    vertical overflow, so nothing ever clips it, whatever the string is.
 * 3. Width is solved the same way Compose Foundation 1.8's `TextAutoSize` does
 *    (measure, then step down), before first draw; the slot height is taken from
 *    the base size, so shrinking the font never moves the layout.
 *    Ellipsis is only reached below [minFontSize], and with `softWrap = false`
 *    Compose ellipsizes in layout (width-bounded), so that path does not clip
 *    either.
 */
@Composable
fun FitLineText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    minLineHeight: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    minFontSize: TextUnit = 9.sp,
    contentAlignment: Alignment = Alignment.Center,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
        .merge(style)
        // Natural font line box. Any lineHeight inherited from the theme would
        // re-introduce the compression that clipped the descenders.
        .copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)
        .let { if (color != Color.Unspecified) it.copy(color = color) else it }

    // Constant for a given style + density + font scale; independent of [text],
    // so the row height is identical in every connection state.
    val slot: Dp = remember(base, density, minLineHeight) {
        val natural = measurer.measure(
            text = HEIGHT_PROBE,
            style = base,
            maxLines = 1,
            softWrap = false,
            constraints = Constraints(),
            density = density,
        ).size.height
        val naturalDp = with(density) { natural.toDp() }
        if (minLineHeight.isSpecified) max(naturalDp, with(density) { minLineHeight.toDp() }) else naturalDp
    }

    BoxWithConstraints(
        modifier = modifier.height(slot),
        contentAlignment = contentAlignment,
    ) {
        val maxWidth = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val fitted = remember(text, base, maxWidth, bounded, density) {
            val start = base.fontSize
            if (!bounded || !start.isSp || text.isEmpty()) return@remember start
            fun widthAt(sizeSp: Float): Int = measurer.measure(
                text = text,
                style = base.copy(fontSize = sizeSp.sp),
                maxLines = 1,
                softWrap = false,
                constraints = Constraints(),
                density = density,
            ).size.width

            val w0 = widthAt(start.value)
            if (w0 <= maxWidth) return@remember start
            var s = (start.value * maxWidth / w0.toFloat() * 0.98f)
                .coerceIn(minFontSize.value, start.value)
            var guard = 0
            while (s > minFontSize.value && widthAt(s) > maxWidth && guard < 24) {
                s = (s - 0.5f).coerceAtLeast(minFontSize.value)
                guard++
            }
            s.sp
        }
        Text(
            text = text,
            style = base.copy(fontSize = fitted),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            // Unbounded: the Text takes its own intrinsic height and is centred
            // in the slot, so it can never be height-constrained into a clip.
            modifier = Modifier.wrapContentHeight(align = Alignment.CenterVertically, unbounded = true),
        )
    }
}

/**
 * Deepest descenders / tallest ascenders of both scripts the UI renders:
 * final lam, reh, jeem, meem, keheh-gaf, jeh; Latin cap + g/y/j.
 */
private const val HEIGHT_PROBE = "\u0644\u0631\u062C\u0645\u06AF\u0698 \u0645\u062A\u0635\u0644 Agyj|"
