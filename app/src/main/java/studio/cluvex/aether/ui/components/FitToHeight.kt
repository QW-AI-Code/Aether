package studio.cluvex.aether.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Lays [content] out so that it ALWAYS fits the height it is given, by shrinking
 * the whole subtree's density instead of clipping it or handing the user a
 * scrollbar.
 *
 * ## The problem this exists for
 *
 * The home screen is a fixed composition - title, connect button, connection card
 * - and every phone gives it a different amount of room. Up to 1.2.7 the answer
 * was `verticalScroll`, so on a shorter screen (or with a larger system font, or
 * in Persian where several strings wrap to two lines) the bottom of the
 * connection card sat below the fold: the user had to scroll to see the end of
 * the block, and even then part of it stayed under the navigation bar. Hand-tuned
 * `Spacer` heights only ever move the problem to the next screen size.
 *
 * ## How it is fixed
 *
 * `LocalDensity` is what turns every `dp` and `sp` in a subtree into pixels, so
 * overriding it with `density * factor` scales the ENTIRE subtree - paddings,
 * icon sizes, corner radii, stroke widths and type - by one factor, in one place.
 * Nothing needs a `scale` parameter and nothing can be forgotten.
 *
 * The factor is measured, not guessed:
 *
 *  1. measure the content against an UNBOUNDED height to learn what it naturally
 *     wants;
 *  2. if that is taller than the room available, set
 *     `factor *= available / natural` (with a 0.5% margin for pixel rounding),
 *     which invalidates the subtree and re-runs the pass;
 *  3. repeat at most [MAX_PASSES] times, and never grow - the search starts at
 *     `1f` and only ever shrinks, so it is monotone and cannot oscillate. In
 *     practice it converges in one or two passes, inside the same frame.
 *
 * Because this is a real layout at a real density, text is rasterised at the size
 * it ends up being: a scaled-down composition is exactly as sharp as an unscaled
 * one. That is why the density is overridden rather than the far easier
 * `graphicsLayer { scaleX = factor }`, which would scale rendered pixels and
 * leave the whole screen soft.
 *
 * [minFactor] is the floor: past it the content is left to overflow rather than
 * shrunk into unreadability. At the home screen's proportions the floor is
 * unreachable on any shipping phone.
 *
 * The measured block is placed CENTRED in the height it was given, not at the top.
 * When it had to be shrunk it fills that height anyway and the offset is zero;
 * when it did not - a tall screen, a short language - centring is what keeps the
 * connect button off the top edge instead of leaving all the slack below the
 * connection card. See the note at the `place` call.
 *
 * The content is drawn transparent until the first pass settles, so the initial
 * frame cannot flash at the wrong size.
 *
 * ## 1.4.0-r2: the "home screen shrinks, then grows back minutes later" bug
 *
 * Up to r1 the search could only ever SHRINK, and it was only reset when the
 * viewport height changed. So any transient that made the content taller for a
 * single frame - a status title such as "Verifying connection health..." wrapping
 * to two lines while connecting, an error caption, an AnimatedContent size
 * transition, or the half-applied insets of a window coming back from the
 * background - pushed the factor down, and nothing ever pushed it back up. The
 * screen stayed small until some unrelated inset change happened to reset it,
 * which is the "a few minutes later it is big again" the field report describes.
 *
 * Now the fit is two-way and deterministic: the ideal factor is derived from the
 * measurement every pass (natural / factor = height at factor 1), it shrinks
 * immediately when something does not fit and grows back as soon as the content
 * is short enough again. A 1 % hysteresis, a per-epoch grow budget and an
 * overshoot ceiling make oscillation impossible; degenerate viewports (0 px while
 * hidden) are ignored; returning to the foreground opens a new epoch. Together
 * with the fixed-height rows in ConnectionCard (every state has the same height),
 * the factor on a given screen is one constant.
 */
@Composable
fun FitToHeight(
    modifier: Modifier = Modifier,
    minFactor: Float = MIN_FIT_FACTOR,
    /**
     * 1.4.0-r2: any value whose change should re-open the fit (the connection
     * state class on the home screen). A new epoch never resets the factor - it
     * only re-arms the grow budget, so a factor that was pushed down by a
     * transient can climb back the moment the transient is gone.
     */
    epochKey: Any? = null,
    content: @Composable () -> Unit,
) {
    val outer = LocalDensity.current
    val fit = remember { FitState() }

    // Coming back from the background (minimise -> reopen, screen off -> on) is
    // exactly when the window is re-measured with half-applied insets. Treat it as
    // a new epoch so the next stable measure can restore the full size at once.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) fit.newEpoch()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(epochKey) { fit.newEpoch() }

    // 1.4.0-r6 FAIL-OPEN (black-screen report, Poco X7 Pro class devices): the
    // content is drawn at alpha 0 until the first fit settles. If the window is
    // never measured with a usable height (0 px / unbounded viewports during a
    // vendor window animation, multi-window, a stuck inset pass), "settled" never
    // flips and the user sees only the dark backdrop - a black screen. The fit is
    // a cosmetic optimisation; it must never be able to hide the UI. After a short
    // grace period the content is shown whatever state the search is in.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(FAIL_OPEN_MS)
        if (!fit.settled) {
            fit.settled = true
            studio.cluvex.aether.core.DiagnosticsLog.w(
                "render",
                "Home layout had not settled after $FAIL_OPEN_MS ms - shown anyway (fit factor ${fit.factor}).",
            )
        }
    }

    Layout(
        modifier = modifier,
        content = {
            CompositionLocalProvider(
                LocalDensity provides Density(outer.density * fit.factor, outer.fontScale),
            ) {
                // The alpha is read inside the layer block, so settling costs a
                // redraw and not a recomposition. Only the very FIRST fit is ever
                // hidden; later corrections are invisible-by-size, never by alpha.
                Box(modifier = Modifier.graphicsLayer { alpha = if (fit.settled) 1f else 0f }) {
                    content()
                }
            }
        },
    ) { measurables, constraints ->
        // Reading the epoch here subscribes this measure block to it: a new epoch
        // re-runs the fit even if nothing else in the tree changed.
        if (fit.epoch < 0) error("unreachable")

        val placeable = measurables.first().measure(
            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val natural = placeable.height
        val available = constraints.maxHeight
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width

        // A degenerate viewport (0 px while the window is hidden, unbounded in a
        // preview) tells us nothing about the real screen. Lay out, but do NOT let
        // it move the factor - that is how a minimise used to leave the home screen
        // shrunk for minutes.
        if (!constraints.hasBoundedHeight || available <= 0 || natural <= 0) {
            val h = if (constraints.hasBoundedHeight) available.coerceAtLeast(0) else natural
            return@Layout layout(width, h) { placeable.place(0, 0) }
        }

        // A new viewport (rotation, split screen, insets settling, a change to the
        // system font scale) re-arms the search. The factor is KEPT: it is
        // re-derived below from the measurement, in both directions, so there is
        // no reset-to-1 flash and no shrink-only ratchet.
        if (available != fit.available ||
            outer.density != fit.density ||
            outer.fontScale != fit.fontScale
        ) {
            fit.available = available
            fit.density = outer.density
            fit.fontScale = outer.fontScale
            fit.rearm()
        }

        // Density scales every dp/sp linearly, so the height the content would
        // have at factor 1 is natural / factor. The ideal factor follows directly.
        val unit = natural / fit.factor
        val ideal = (available * FIT_MARGIN / unit).coerceIn(minFactor, 1f)

        var changed = false
        if (natural > available && ideal < fit.factor - FACTOR_EPSILON) {
            // MUST shrink: something does not fit. If the previous pass was a grow,
            // that grow overshot (text re-wrapped at the larger size); remember it
            // as a ceiling so the fit can never oscillate between two sizes.
            if (fit.lastWasGrow) fit.ceiling = fit.factor
            fit.factor = ideal
            fit.lastWasGrow = false
            changed = true
        } else if (ideal > fit.factor * (1f + GROW_HYSTERESIS) &&
            fit.factor < fit.ceiling - FACTOR_EPSILON &&
            fit.grows < MAX_GROWS
        ) {
            // MAY grow: the content got shorter again (a two-line status became one
            // line, a transient banner went away). This is the half the old
            // implementation did not have, and why the screen stayed small.
            fit.factor = minOf(ideal, fit.ceiling - FACTOR_EPSILON)
            fit.grows++
            fit.lastWasGrow = true
            changed = true
        } else {
            fit.lastWasGrow = false
        }
        if (!changed) fit.settled = true

        layout(width, available) {
            // CENTRED, NOT TOP-ALIGNED (1.3.1). When the content fills the height
            // the offset is zero; when it does not, centring keeps the connect
            // button off the top edge. coerceAtLeast(0) anchors an overflowing
            // block (below minFactor) at the top rather than off-screen.
            val y = ((available - placeable.height) / 2).coerceAtLeast(0)
            val x = ((width - placeable.width) / 2).coerceAtLeast(0)
            placeable.place(x, y)
        }
    }
}

/**
 * Search state. Only [factor], [settled] and [epoch] are snapshot state - they are
 * the only fields that should invalidate anything.
 */
private class FitState {
    var factor by mutableFloatStateOf(1f)
    var settled by mutableStateOf(false)
    var epoch by mutableIntStateOf(0)

    var available: Int = Int.MIN_VALUE
    var density: Float = -1f
    var fontScale: Float = -1f

    /** Lowest factor at which a grow was proven to overflow, this epoch. */
    var ceiling: Float = 1f
    var grows: Int = 0
    var lastWasGrow: Boolean = false

    fun rearm() {
        ceiling = 1f
        grows = 0
        lastWasGrow = false
    }

    fun newEpoch() {
        rearm()
        epoch++
    }
}

/** Never shrink the UI below this share of its natural size. */
private const val MIN_FIT_FACTOR = 0.55f

/** Pixel-rounding headroom, so a converged pass is never one pixel over. */
private const val FIT_MARGIN = 0.995f

private const val FACTOR_EPSILON = 0.002f

/** A grow must be worth at least 1 %: below that it is rounding noise. */
private const val GROW_HYSTERESIS = 0.01f

/** r6: the UI is never hidden longer than this, settled or not. */
private const val FAIL_OPEN_MS = 450L

/** Grows allowed per epoch before the fit holds still (shrinks are never capped). */
private const val MAX_GROWS = 3
