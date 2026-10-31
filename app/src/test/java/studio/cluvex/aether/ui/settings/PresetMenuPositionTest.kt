package studio.cluvex.aether.ui.settings

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import studio.cluvex.aether.ui.components.CenteredPresetMenuPositionProvider

class PresetMenuPositionTest {
    private val provider = CenteredPresetMenuPositionProvider(marginPx = 16, gapPx = 8)

    @Test fun centeredInWindowRegardlessOfAnchorOrLayoutDirection() {
        for (direction in LayoutDirection.values()) {
            for (anchor in listOf(IntRect(10, 100, 110, 150), IntRect(240, 100, 350, 150))) {
                assertEquals(IntOffset(20, 158),
                    provider.calculatePosition(anchor, IntSize(360, 800), direction, IntSize(320, 400)))
            }
        }
    }

    @Test fun opensAboveAnchorWhenBelowCannotFit() {
        assertEquals(IntOffset(20, 292), provider.calculatePosition(
            IntRect(200, 600, 300, 650), IntSize(360, 800),
            LayoutDirection.Rtl, IntSize(320, 300)))
    }

    @Test fun limitedWindowClampsVerticallyWithoutInvalidRanges() {
        assertEquals(IntOffset(5, 4), provider.calculatePosition(
            IntRect(30, 20, 100, 60), IntSize(180, 120),
            LayoutDirection.Ltr, IntSize(170, 100)))
    }

    @Test fun overTallPopupStillHasANonnegativePosition() {
        assertEquals(IntOffset(20, 0), provider.calculatePosition(
            IntRect(20, 0, 200, 40), IntSize(360, 300),
            LayoutDirection.Rtl, IntSize(320, 400)))
    }
}