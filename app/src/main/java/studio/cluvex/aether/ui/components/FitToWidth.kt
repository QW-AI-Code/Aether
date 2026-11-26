package studio.cluvex.aether.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.roundToInt

/**
 * 1.7.0 (same version): keeps a row of content on ONE line and scales it down
 * uniformly when it is wider than the space it gets (the horizontal sibling of
 * [FitToHeight]). Nothing wraps, nothing is cut off; content that fits keeps its
 * natural size and is centred.
 */
@Composable
fun FitToWidth(
    modifier: Modifier = Modifier,
    minFactor: Float = 0.45f,
    content: @Composable () -> Unit,
) {
    require(minFactor > 0f && minFactor <= 1f)
    Layout(modifier = modifier, content = { Box { content() } }) { measurables, constraints ->
        val child = measurables.single().measure(
            constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else child.width
        val factor = if (width > 0 && child.width > width)
            (width.toFloat() / child.width).coerceIn(minFactor, 1f) else 1f
        val height = constraints.constrainHeight((child.height * factor).roundToInt())
        val x = ((width - child.width * factor) / 2f).toInt().coerceAtLeast(0)
        layout(width, height) {
            child.placeWithLayer(x, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = factor
                scaleY = factor
            }
        }
    }
}
