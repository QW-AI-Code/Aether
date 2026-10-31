package studio.cluvex.aether.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * The connected mark: Aether's own **A**, alive.
 *
 * ## Why this replaces the tick
 *
 * The tick said "done". It is the glyph a form uses when it accepts an email
 * address, it is the glyph every other app on the phone uses for the same thing,
 * and it says nothing about *which* tunnel is up. The mark that belongs on this
 * button once the tunnel carries traffic is the app's own: the same A that is on
 * the launcher icon, in the notification, and on the release page. Connected now
 * shows you the product, not a checkbox.
 *
 * ## It is the launcher icon, to the coordinate
 *
 * The geometry in [MARK_OUTLINE] and [MARK_COUNTER] is lifted verbatim from
 * `res/drawable/ic_launcher_foreground.xml` - the chevron A with the small
 * triangular counter under its apex - and mapped into whatever box the caller
 * gives it. Nothing here is a new letterform: if the icon is ever redrawn, these
 * two coordinate lists are the only thing that has to follow, and the animation
 * rides on top unchanged.
 *
 * ## What moves, and why it is not decoration
 *
 * Four independent animations, all of them cheap, each doing one job:
 *
 *  * **Colour cycle** - the mark drifts continuously through the app's accent ramp
 *    (mint, cyan, azure, violet, orchid, amber) instead of holding one hue. It is
 *    the same idea as the connection card's travelling edge, so the two read as one
 *    living surface rather than two light shows competing, and a glance at the
 *    button tells you it is live *now* and not a frozen "connected" state from
 *    twenty minutes ago.
 *  * **Light sweep** - a soft diagonal highlight crossing the glyph. This is what
 *    makes the A read as a physical, polished object catching light rather than a
 *    flat fill.
 *  * **Scan bar + hairlines** - a bright bar travelling up the inside of the letter
 *    over a faint static hairline grid: the visual language of something being
 *    *inspected and verified*, which is precisely what the tunnel is doing while
 *    this glyph is on screen.
 *  * **Reveal** - a single bottom-to-top wipe with a bright construction line at
 *    its edge, played once, when the mark appears. Connection is an event and it
 *    deserves one; a mark that faded in like a tooltip would waste it.
 *
 * ## Cost
 *
 * Same discipline as [GlowCycle], for the same reason - this runs on a phone that
 * is also encrypting every packet on the device:
 *
 *  * the whole thing is ONE `Canvas` and five `animateFloat`s;
 *  * every animated value is read INSIDE the draw lambda, so a frame costs a
 *    redraw and never a recomposition;
 *  * the caller composes this only while connected (see [ConnectButton]), so a
 *    disconnected screen subscribes to no frame callbacks at all;
 *  * the two paths are scratch objects that are `reset()` and refilled per frame -
 *    a dozen `lineTo` calls, no allocation - so a size change needs no cache
 *    invalidation logic to get wrong;
 *  * the bloom is additive strokes, not a blur pass and not an extra layer.
 */
@Composable
fun AetherMark(modifier: Modifier = Modifier, animated: Boolean = true) {
    val transition = if (animated) rememberInfiniteTransition(label = "aetherMark") else null

    // 0 -> N linearly, so floor() is the current colour and the fraction is the
    // blend to the next one. Restarting at 0 after the last colour is what makes
    // the ramp endless with no visible seam.
    val hue = transition?.animateFloat(
        initialValue = 0f,
        targetValue = MARK_COLORS.size.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(MARK_COLORS.size * HUE_STEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hue",
    )
    val sweep = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SWEEP_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val scan = transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SCAN_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scan",
    )
    val breath = transition?.animateFloat(
        initialValue = 0.74f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(BREATH_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )

    // The one-shot entrance. Driven by a state that flips after the first
    // composition, so the animation always runs from 0 - which is the whole point:
    // this composable only exists while connected, so its first frame IS the
    // moment the tunnel came up.
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(animated) { arrived = true }
    val reveal = animateFloatAsState(
        targetValue = if (animated) { if (arrived) 1f else 0f } else 1f,
        animationSpec = tween(REVEAL_MS, easing = FastOutSlowInEasing),
        label = "reveal",
    )

    val outline = remember { Path() }
    val counter = remember { Path() }

    Canvas(modifier = modifier) {
        val phase = hue?.value ?: 0.8f
        val colour = cycleColour(phase)
        val ahead = cycleColour(phase + 0.45f)
        val pulse = breath?.value ?: 0.9f
        val entered = reveal.value.coerceIn(0f, 1f)
        if (entered <= 0.001f) return@Canvas

        buildMark(outline, counter)

        // 1. The bloom the glyph sits in, behind everything and outside the clip so
        //    it can spill past the letter's edges.
        drawHalo(colour, pulse * entered)

        // 2. Everything that belongs INSIDE the letter, wiped in from the bottom.
        val wipeTop = size.height * (1f - entered)
        clipRect(left = 0f, top = wipeTop, right = size.width, bottom = size.height) {
            clipPath(outline) {
                drawBody(colour, ahead)
                drawHairlines(scan?.value ?: 0.35f)
                drawSweepLight(sweep?.value ?: 0.42f, pulse)
                drawScanBar(scan?.value ?: 0.35f, colour, pulse)
            }
        }

        // 3. The neon edge, drawn on the wiped region too but never clipped to the
        //    letter, so its bloom reads as light instead of as a border.
        clipRect(left = 0f, top = wipeTop, right = size.width, bottom = size.height) {
            drawEdge(outline, colour, pulse)
            drawCounter(counter, colour, pulse, scan?.value ?: 0.35f)
        }

        // 4. The construction line: only while the wipe is running.
        if (entered < 0.999f) drawBuildLine(wipeTop, colour, entered)
    }
}

// ------------------------------------------------------------------ geometry

/**
 * The A, in the launcher icon's 108x108 viewport.
 *
 * `ic_launcher_foreground.xml`, path 1:
 * `M54,20 L82,86 L66,86 L54,54 L42,86 L26,86 Z`
 */
private val MARK_OUTLINE = floatArrayOf(
    54f, 20f,
    82f, 86f,
    66f, 86f,
    54f, 54f,
    42f, 86f,
    26f, 86f,
)

/** Path 2, the counter under the apex: `M54,44 L64,70 L44,70 Z`. */
private val MARK_COUNTER = floatArrayOf(
    54f, 44f,
    64f, 70f,
    44f, 70f,
)

// The glyph's bounding box inside that viewport. Hard-coded rather than computed
// so the mapping is stable and obvious: x 26..82, y 20..86.
private const val BOX_LEFT = 26f
private const val BOX_TOP = 20f
private const val BOX_WIDTH = 56f
private const val BOX_HEIGHT = 66f

/**
 * Rewrites [outline] and [counter] to fill the current canvas, preserving the
 * icon's aspect ratio and leaving a hair of margin so the neon edge's bloom is not
 * clipped by the layout box.
 */
private fun DrawScope.buildMark(outline: Path, counter: Path) {
    val scale = minOf(size.width / BOX_WIDTH, size.height / BOX_HEIGHT) * 0.94f
    val dx = (size.width - BOX_WIDTH * scale) / 2f
    val dy = (size.height - BOX_HEIGHT * scale) / 2f
    fun mapX(x: Float) = dx + (x - BOX_LEFT) * scale
    fun mapY(y: Float) = dy + (y - BOX_TOP) * scale

    outline.reset()
    var i = 0
    while (i < MARK_OUTLINE.size) {
        val x = mapX(MARK_OUTLINE[i])
        val y = mapY(MARK_OUTLINE[i + 1])
        if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        i += 2
    }
    outline.close()

    counter.reset()
    i = 0
    while (i < MARK_COUNTER.size) {
        val x = mapX(MARK_COUNTER[i])
        val y = mapY(MARK_COUNTER[i + 1])
        if (i == 0) counter.moveTo(x, y) else counter.lineTo(x, y)
        i += 2
    }
    counter.close()
}

// -------------------------------------------------------------------- layers

/** The glow the mark sits in. Additive, so it lifts the disc it is drawn on. */
private fun DrawScope.drawHalo(colour: Color, intensity: Float) {
    val radius = size.minDimension * 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(colour.copy(alpha = 0.34f * intensity), Color.Transparent),
            center = Offset(size.width / 2f, size.height * 0.56f),
            radius = radius,
        ),
        radius = radius,
        center = Offset(size.width / 2f, size.height * 0.56f),
        blendMode = BlendMode.Plus,
    )
}

/**
 * The body fill: this lap's colour at the apex, the NEXT colour at the feet.
 *
 * Two stops of the same ramp rather than colour-to-transparent: a letter that
 * fades out at the bottom loses its footing against the disc, and the two-colour
 * gradient is what makes the cycle visible in the glyph itself instead of only in
 * its glow.
 */
private fun DrawScope.drawBody(colour: Color, ahead: Color) {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                lerp(colour, Color.White, 0.22f).copy(alpha = 0.95f),
                colour.copy(alpha = 0.80f),
                ahead.copy(alpha = 0.62f),
            ),
            startY = 0f,
            endY = size.height,
        ),
    )
}

/**
 * The hairline grid: thin dark scanlines across the fill, drifting slowly.
 *
 * Dark rather than bright, and only 5% opaque: this is the texture that makes the
 * mark look like a display being read out, and the moment it is bright enough to
 * notice on its own it stops being texture and becomes stripes.
 */
private fun DrawScope.drawHairlines(scan: Float) {
    val gap = 5.dp.toPx()
    if (gap <= 0.5f) return
    val drift = (scan * gap)
    val thickness = 1.dp.toPx().coerceAtLeast(1f)
    var y = -gap + drift
    while (y < size.height) {
        drawRect(
            color = Color.Black.copy(alpha = 0.14f),
            topLeft = Offset(0f, y),
            size = Size(size.width, thickness),
        )
        y += gap
    }
}

/**
 * The travelling highlight. Rotated off-axis on purpose: a vertical or horizontal
 * sweep on a symmetrical glyph looks like a rendering artefact, a diagonal one
 * looks like light.
 */
private fun DrawScope.drawSweepLight(phase: Float, pulse: Float) {
    val band = size.width * 0.42f
    val travel = -band + phase * (size.width + 2f * band)
    rotate(degrees = -22f, pivot = Offset(size.width / 2f, size.height / 2f)) {
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.38f * pulse),
                    1f to Color.Transparent,
                ),
                start = Offset(travel - band / 2f, 0f),
                end = Offset(travel + band / 2f, 0f),
            ),
            topLeft = Offset(-size.width / 2f, -size.height / 2f),
            size = Size(size.width * 2f, size.height * 2f),
            blendMode = BlendMode.Plus,
        )
    }
}

/** The scan bar, bottom to top, with a bright core line at its centre. */
private fun DrawScope.drawScanBar(phase: Float, colour: Color, pulse: Float) {
    val height = size.height * 0.16f
    val top = size.height + height - phase * (size.height + 2f * height)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                lerp(colour, Color.White, 0.45f).copy(alpha = 0.50f * pulse),
                Color.Transparent,
            ),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(0f, top),
        size = Size(size.width, height),
        blendMode = BlendMode.Plus,
    )
    val core = 1.5.dp.toPx()
    drawRect(
        color = Color.White.copy(alpha = 0.30f * pulse),
        topLeft = Offset(0f, top + height / 2f - core / 2f),
        size = Size(size.width, core),
        blendMode = BlendMode.Plus,
    )
}

/**
 * The neon edge: three graded additive strokes, widest and faintest first.
 *
 * Round cap AND round join, for the reason [GlowCycle] documents - a mitred join
 * on this letterform spikes at the apex, which at 84 dp is a visible needle.
 */
private fun DrawScope.drawEdge(outline: Path, colour: Color, pulse: Float) {
    val base = 1.6.dp.toPx()
    EDGE_LAYERS.forEach { (widthScale, alphaScale) ->
        drawPath(
            path = outline,
            color = lerp(colour, Color.White, 0.25f)
                .copy(alpha = (alphaScale * pulse).coerceIn(0f, 1f)),
            style = Stroke(
                width = base * widthScale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * The counter triangle: the icon's second path, treated as the mark's core.
 *
 * It gets its own faster pulse (the scan phase at double rate) so the glyph has an
 * inner rhythm as well as an outer one - the difference between "a letter with a
 * light on it" and "a letter with something running inside it".
 */
private fun DrawScope.drawCounter(counter: Path, colour: Color, pulse: Float, scan: Float) {
    val beat = 0.55f + 0.45f * kotlin.math.abs(kotlin.math.sin(scan * TWO_PI))
    drawPath(
        path = counter,
        color = lerp(colour, Color.White, 0.55f).copy(alpha = 0.42f + 0.38f * beat * pulse),
        blendMode = BlendMode.Plus,
    )
    drawPath(
        path = counter,
        color = Color.White.copy(alpha = 0.22f * beat),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus,
    )
}

/** The bright line the reveal wipe builds behind, while it is still running. */
private fun DrawScope.drawBuildLine(y: Float, colour: Color, entered: Float) {
    val fade = (1f - entered).coerceIn(0f, 1f)
    val thickness = 2.dp.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                lerp(colour, Color.White, 0.6f).copy(alpha = 0.85f * fade),
                Color.Transparent,
            ),
        ),
        topLeft = Offset(0f, y - thickness / 2f),
        size = Size(size.width, thickness),
        blendMode = BlendMode.Plus,
    )
}

// --------------------------------------------------------------------- ramp

/**
 * The accent ramp the mark drifts through: the app's own accents, in hue order so
 * consecutive steps are neighbours and no transition passes through mud.
 */
private val MARK_COLORS = listOf(
    Color(0xFF3EDBB0), // brand mint  - the connected accent everywhere else
    Color(0xFF35D0E8), // cyan
    Color(0xFF5B93FF), // azure       - the primary action colour
    Color(0xFF9B8CFF), // violet      - the AI accent
    Color(0xFFFF7AD9), // orchid
    Color(0xFFFFC65C), // amber
)

/** Blends the ramp continuously; [phase] is in laps, so `floor` is the step. */
private fun cycleColour(phase: Float): Color {
    val size = MARK_COLORS.size
    val step = floor(phase).toInt().mod(size)
    val next = (step + 1).mod(size)
    return lerp(MARK_COLORS[step], MARK_COLORS[next], phase - floor(phase))
}

private val EDGE_LAYERS = listOf(
    3.4f to 0.10f,
    2.1f to 0.22f,
    1.0f to 0.88f,
)

/** Milliseconds per colour of the ramp. Slow: this is ambience, not a strobe. */
private const val HUE_STEP_MS = 3_400

private const val SWEEP_MS = 2_600
private const val SCAN_MS = 2_050
private const val BREATH_MS = 1_500
private const val REVEAL_MS = 760
