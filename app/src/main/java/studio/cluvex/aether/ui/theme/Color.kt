package studio.cluvex.aether.ui.theme

import androidx.compose.ui.graphics.Color

// Deep navy ("سورمه‌ای") dark palette used when dynamic color is unavailable.
val Navy900 = Color(0xFF0A0E1A)
val Navy800 = Color(0xFF0F1626)
val Navy700 = Color(0xFF16203A)
val Navy600 = Color(0xFF1E2A4A)

val AetherBlue = Color(0xFF4C8DFF)
val AetherCyan = Color(0xFF32E0C4)
val AetherError = Color(0xFFFF5C7A)

val OnDark = Color(0xFFE6ECF5)
val OnDarkMuted = Color(0xFF9AA7BF)

// ---- Brand tokens for the unified connection card (1.2.6) ----
//
// The card is pinned to these instead of MaterialTheme, because Material You
// repaints every themed surface from the user's wallpaper on Android 12+ and
// that turned the connection card into a colour that was no longer Aether.

/** Mint/teal accent of the connected state. */
val AetherMint = Color(0xFF3EDBB0)
/** Second light of the animated card edge, cooler than the mint. */
val AetherGlowCyan = Color(0xFF35D0E8)

/** Glass card surface: a slate a shade lighter than the navy backdrop. */
val CardSurfaceTop = Color(0xF01B2542)
val CardSurfaceBottom = Color(0xF60C1322)
/** Every sub-container inside the card (IP pill, speed strip, protocol strip). */
val CardSubSurface = Color(0xFF151E33)

val CardTextPrimary = Color(0xFFE8EEF7)
val CardTextMuted = Color(0xFF8B98B0)
val CardTextDim = Color(0xFF5E6B84)
