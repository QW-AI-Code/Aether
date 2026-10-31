package studio.cluvex.aether.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

// Latin ascender/descender + Persian tall/descending glyphs + kasra, so the floor covers both scripts.
private const val FLOOR_SAMPLE = "\u00C5gj \u06AF\u062C\u06CC\u0650"

/**
 * Natural single-line height of [style] at its base size. Used as a min height so a
 * FitLineText that shrinks its font for a long (e.g. Persian) string never changes the
 * row height, which keeps FitToHeight's natural height stable across connection states.
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberLineFloor(style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val resolved = LocalTextStyle.current.merge(style).copy(lineHeight = TextUnit.Unspecified, lineHeightStyle = null)
    return remember(resolved, density, measurer) {
        val height = measurer.measure(
            text = AnnotatedString(FLOOR_SAMPLE),
            style = resolved,
            maxLines = 1,
        ).size.height
        with(density) { height.toDp() }
    }
}
