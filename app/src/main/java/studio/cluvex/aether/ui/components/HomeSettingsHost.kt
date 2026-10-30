package studio.cluvex.aether.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout

internal val LocalHomeVisible = staticCompositionLocalOf { true }
internal val LocalHomeAnimationsEnabled = staticCompositionLocalOf { true }

/** Unplaced Home keeps remembered layout/text state; no overlay input or draw cost. */
@Composable
internal fun HomeSettingsHost(showHome: Boolean, modifier: Modifier = Modifier,
    home: @Composable () -> Unit, settings: @Composable () -> Unit) {
    Layout(modifier = modifier, content = {
        androidx.compose.foundation.layout.Box { CompositionLocalProvider(LocalHomeVisible provides showHome) { home() } }
        if (!showHome) androidx.compose.foundation.layout.Box { settings() }
    }) { measurables, constraints ->
        val visible = measurables[if (showHome) 0 else 1].measure(constraints)
        layout(visible.width, visible.height) { visible.placeRelative(0, 0) }
    }
}
