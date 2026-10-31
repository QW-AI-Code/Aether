package studio.cluvex.aether.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import studio.cluvex.aether.data.LanguagePrefs

/**
 * The one and only Aether colour scheme: deep navy, always dark.
 *
 * ## Why Material You / dynamic colour is gone
 *
 * Every build up to 1.2.7 called `dynamicDarkColorScheme(context)` on Android
 * 12+, so from Android 12 on the entire app was repainted from the user's
 * WALLPAPER. That is a lovely default for a launcher and the wrong one for this
 * app, and it is the direct cause of the look the settings screens had: the home
 * screen was navy (its connection card pins brand colours precisely to escape
 * this), while every settings surface, switch, segmented control and dropdown
 * came out in whatever hue the wallpaper happened to imply - lilac, olive,
 * terracotta. Half the app on brand, half of it not, differently on every phone,
 * and impossible to design against.
 *
 * The scheme is now fixed and hand-built from the [Navy950]..[Navy600] ramp, so
 * the app looks identical on every device and every Android version, exactly like
 * the big vendors' own system apps do. The surface roles are mapped to the
 * elevation ramp rather than to alpha, which is what stops a card inside a sheet
 * from double-darkening.
 */
private val AetherDarkColorScheme = darkColorScheme(
    primary = AetherBlue,
    onPrimary = Color(0xFF00122E),
    primaryContainer = AetherBlueDim,
    onPrimaryContainer = AetherBlue,

    secondary = AetherMint,
    onSecondary = Color(0xFF00251D),
    secondaryContainer = AetherMintDim,
    onSecondaryContainer = AetherMint,

    tertiary = AetherGlowCyan,
    onTertiary = Color(0xFF00232B),

    background = Navy950,
    onBackground = OnDark,

    // Elevation 0/1: the page and the sheets drawn on it.
    surface = Navy900,
    onSurface = OnDark,
    surfaceVariant = Navy850,
    onSurfaceVariant = OnDarkMuted,

    // Material's own elevation tints, pinned to the ramp so a "tonal elevation"
    // never invents a colour of its own.
    surfaceContainerLowest = Navy950,
    surfaceContainerLow = Navy900,
    surfaceContainer = Navy850,
    surfaceContainerHigh = Navy800,
    surfaceContainerHighest = Navy750,
    surfaceBright = Navy750,
    surfaceDim = Navy950,
    inverseSurface = OnDark,
    inverseOnSurface = Navy950,

    outline = Navy700,
    outlineVariant = Navy700,

    error = AetherError,
    onError = Color.White,
    errorContainer = AetherErrorDim,
    onErrorContainer = AetherError,

    scrim = Color(0xCC03060C),
)

/**
 * Wraps the app in the Aether theme.
 *
 * ## The layout direction is FORCED here, and that is the fix
 *
 * It used to be left to "the configuration of the context", which reads like the
 * right answer and is not one. Compose does not lay out from the configuration:
 * `AndroidComposeView` overrides `onRtlPropertiesChanged`, so the direction it
 * uses is whatever the VIEW hierarchy resolved - and the decor view at the top of
 * that hierarchy resolves it against `Locale.getDefault()`, which the framework
 * rewrites from the *activity's* locale list on bind, on resume and on every
 * configuration change, long after `attachBaseContext` set ours.
 *
 * With a Persian phone the value the framework wrote back was Persian anyway, so
 * the mirroring looked correct and hid the bug. With an ENGLISH phone and the app
 * set to Persian it wrote `en` back over our `fa`: the decor view resolved LTR,
 * handed that to Compose, and the entire UI laid out left-to-right while reading
 * in Persian. That is the reported bug, and it is why it appeared in that one
 * combination only.
 *
 * `LocalLayoutDirection` is therefore pinned from the stored language choice
 * ([LanguagePrefs.isRtl]) and nothing else. One provider at the root covers the
 * whole app - including `Dialog`, `Popup`, `DropdownMenu` and `ModalBottomSheet`,
 * which read `LocalLayoutDirection.current` and apply it to their own windows -
 * so every screen, row, icon, slider and sheet mirrors together. Individual
 * technical fields still opt back out to LTR on purpose (see `LtrOutlinedTextField`).
 *
 * The typeface follows the same single source of truth: Persian gets the bundled
 * Vazirmatn face across the whole type scale, English keeps the stock one.
 */
@Composable
fun AetherTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    // Keyed on the configuration so a language change (which recreates the
    // activity and therefore emits a new configuration) is picked up, without
    // re-reading SharedPreferences on every recomposition.
    val configuration = LocalConfiguration.current
    val rtl = remember(configuration) { LanguagePrefs.isRtl(context) }
    val persian = remember(configuration) { LanguagePrefs.isPersian(context) }

    if (!view.isInEditMode) {
        DisposableEffect(view, rtl) {
            val activity = LanguagePrefs.findActivity(view.context)
            if (activity != null) {
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            // The scheme is permanently dark, so the system bars always need
            // light icons. Set both, not only the status bar: on a device with a
            // gesture nav pill the navigation bar was reading the wallpaper.
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            // Keep the view world in step with the composition. Without this the
            // two disagree, and anything that is NOT a Compose node - dialog
            // windows, popup anchoring, selection handles - mirrors the wrong way.
            val direction = if (rtl) android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
            window.decorView.layoutDirection = direction
            view.layoutDirection = direction
            }
            onDispose { }
        }
    }

    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(
            colorScheme = AetherDarkColorScheme,
            typography = aetherTypography(persian),
            content = content,
        )
    }
}
