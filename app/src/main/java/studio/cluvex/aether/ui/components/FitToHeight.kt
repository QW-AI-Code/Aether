package studio.cluvex.aether.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth

/**
 * Measure and fit in ONE layout pass, before placement/draw. No snapshot-state
 * writes during measurement, delayed visibility gate, lifecycle reset or density
 * recomposition. The same composition owns all effects (including animations).
 *
 * A layer transform is intentional: scaling the density required multiple frames
 * of recomposition and could flash the natural-size Home after its fail-open
 * timeout. The layer is laid out and transformed together, so first-frame bounds
 * are already final. Text/controls share one uniform fit and never distort.
 * The minimum fit is a readability floor; unusually short windows may overflow.
 */
@Composable
fun FitToHeight(
    modifier: Modifier = Modifier,
    minFactor: Float = 0.55f,
    @Suppress("UNUSED_PARAMETER") epochKey: Any? = null,
    content: @Composable () -> Unit,
) {
    require(minFactor > 0f && minFactor <= 1f)
    Layout(modifier = modifier, content = { Box { content() } }) { measurables, constraints ->
        val child = measurables.single().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        val width = constraints.constrainWidth(child.width)
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else child.height
        val factor = if (height > 0 && child.height > height)
            (height.toFloat() / child.height).coerceIn(minFactor, 1f) else 1f
        val x = ((width - child.width * factor) / 2f).toInt().coerceAtLeast(0)
        val y = ((height - child.height * factor) / 2f).toInt().coerceAtLeast(0)
        layout(width, height) {
            child.placeWithLayer(x, y) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = factor
                scaleY = factor
            }
        }
    }
}
