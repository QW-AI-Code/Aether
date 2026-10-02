package studio.cluvex.aether.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R

enum class ButtonMode { IDLE, BUSY, CONNECTED, ERROR }

/**
 * The centrepiece action: a circular power button with a soft glowing halo, an
 * animated progress ring while busy, and a colour that reflects the current mode.
 *
 * WHAT CHANGED IN THIS REVISION, and why:
 *
 *  - **No travelling ring around the disc.** 1.2.7 had put the connection
 *    card's multi-colour light show around the button as well. Two light shows
 *    on one screen fight each other for attention, the ring's bloom needed a
 *    220 dp box for a 150 dp button (70 dp of pure padding at the top of the
 *    screen - almost exactly the height the content block was missing at the
 *    bottom), and it cost a second set of additive strokes on every frame. The
 *    ring is gone; the travelling light lives on the connection card only.
 *  - **The app's own A, not a tick (1.2.9-r3).** The connected glyph used to be a
 *    large rounded tick. A tick is what a form shows when it accepts an email
 *    address: it says "done", it is the same glyph every other app on the phone
 *    uses for the same thing, and it says nothing about WHICH tunnel is up. The
 *    connected state now draws Aether's own mark - the exact A from the launcher
 *    icon - lit, scanned and drifting through the app's accent ramp. See
 *    [AetherMark]. It is composed ONLY in the connected state, so nothing about
 *    the idle or busy button changed, including its frame cost.
 *  - **Nothing animates unless it must.** The halo pulse is composed only while
 *    connected and the sweep only while busy, so an idle screen subscribes to no
 *    frame callbacks at all. Both are read inside draw/layer lambdas, so a frame
 *    costs a redraw and never a recomposition.
 */
@Composable
fun ConnectButton(
    mode: ButtonMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The human-readable connection state ("Connecting…", "Connected"), read out
     * by screen readers as the button's state. Optional so other callers keep
     * compiling; HomeScreen passes the same title it shows under the button.
     */
    stateText: String? = null,
) {
    val connected = mode == ButtonMode.CONNECTED
    val busy = mode == ButtonMode.BUSY
    val accent = when (mode) {
        ButtonMode.IDLE -> Color(0xFF4C8DFF)
        ButtonMode.BUSY -> Color(0xFF4C8DFF)
        ButtonMode.CONNECTED -> Color(0xFF32E0C4)
        ButtonMode.ERROR -> Color(0xFFFF5C7A)
    }
    val animatedAccent by animateColorAsState(accent, tween(600), label = "accent")

    val haloPulse = if (connected) rememberHaloPulse() else null
    val spin = if (busy) rememberSpin() else null

    val interaction = remember { MutableInteractionSource() }

    // ACCESSIBILITY: this button is a bare disc with a glyph, so without this it
    // reaches TalkBack as an unnamed "button". The label follows what a tap will
    // DO right now, which is why it flips to "Disconnect" the moment the tunnel
    // is up (and to "Cancel connecting" while it is still coming up - a tap in
    // the busy state tears the attempt down, see MainActivity.toggleConnection).
    // The live region makes the screen reader announce that flip by itself
    // instead of waiting for the user to swipe back onto the button.
    val actionLabel = stringResource(
        when (mode) {
            ButtonMode.CONNECTED -> R.string.a11y_disconnect
            ButtonMode.BUSY -> R.string.a11y_cancel_connecting
            ButtonMode.IDLE, ButtonMode.ERROR -> R.string.a11y_connect
        },
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(BUTTON_BOX)) {
        // Soft glowing halo behind the button.
        Canvas(modifier = Modifier.size(BUTTON_BOX)) {
            val radius = size.minDimension / 2f * (haloPulse?.value ?: 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedAccent.copy(alpha = 0.42f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = radius,
                ),
                radius = radius,
            )
        }

        // Inner gradient disc.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(DISC)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            animatedAccent.copy(alpha = 0.28f),
                            Color(0xFF0F1626),
                        ),
                    ),
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = actionLabel
                    role = Role.Button
                    if (stateText != null) stateDescription = stateText
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            // Progress sweep while busy.
            if (spin != null) {
                Canvas(modifier = Modifier.size(SWEEP)) {
                    rotate(degrees = spin.value) {
                        drawArc(
                            color = animatedAccent,
                            startAngle = 0f,
                            sweepAngle = 90f,
                            useCenter = false,
                            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
            }

            // Soft additive core behind the tick, so the glyph glows out of the
            // disc instead of sitting flat on it.
            if (connected) {
                Canvas(modifier = Modifier.size(CORE)) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                animatedAccent.copy(alpha = 0.30f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        ),
                        radius = size.minDimension / 2f,
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            if (connected) {
                // The brand mark, alive. Its own animations live in [AetherMark];
                // this composable exists only in the connected state, so the idle
                // and busy buttons still subscribe to nothing they did not before.
                AetherMark(modifier = Modifier.size(MARK_SIZE))
            } else {
                val icon = when (mode) {
                    ButtonMode.BUSY -> Icons.Rounded.Autorenew
                    else -> Icons.Rounded.PowerSettingsNew
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = animatedAccent,
                    modifier = Modifier
                        .size(ICON_SIZE)
                        .then(
                            if (spin != null) {
                                Modifier.graphicsLayer { rotationZ = spin.value }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

/** The connected halo's slow breathing. Composed only while connected. */
@Composable
private fun rememberHaloPulse(): State<Float> =
    rememberInfiniteTransition(label = "halo").animateFloat(
        initialValue = 0.93f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloPulse",
    )

/** The busy sweep. Composed only while busy. */
@Composable
private fun rememberSpin(): State<Float> =
    rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_200, easing = LinearEasing)),
        label = "spinAngle",
    )

// ---------------------------------------------------------------- geometry
//
// The sizes came down with the ring: with no bloom to leave room for, the box no
// longer needs 70 dp of padding around the disc. Every dp given back here is
// height the content block gets to keep.

private val BUTTON_BOX = 190.dp
private val DISC = 132.dp
private val SWEEP = 116.dp
private val CORE = 112.dp
private val ICON_SIZE = 52.dp

/**
 * The connected mark, sized to fill the disc without touching its rim.
 *
 * 86 dp against the 132 dp disc: the mark's neon edge blooms outward by a couple
 * of dp, and the old 84 dp tick had no bloom to leave room for.
 */
private val MARK_SIZE = 86.dp
