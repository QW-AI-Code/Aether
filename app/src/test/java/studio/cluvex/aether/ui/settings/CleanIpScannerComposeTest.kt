package studio.cluvex.aether.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import studio.cluvex.aether.R
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.scanner.*
import studio.cluvex.aether.ui.theme.AetherTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = android.app.Application::class, qualifiers = "fa-rIR-ldrtl-w360dp-h800dp-mdpi")
class CleanIpScannerComposeTest {
    @get:Rule(order = 0) val host = ComposeHostRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private var view: View? = null
    private fun capture(name: String) {
        compose.runOnIdle { System.getenv("AETHER_UI_ARTIFACTS")?.let { output ->
            val root = requireNotNull(view).rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap)); File(output).mkdirs()
            File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        } }
    }
    @Test fun independentSettingsPersistProfilesAndManualLimitWithoutStartingScan() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("clean-ip-scanner", 0).edit().clear().commit()
        assertTrue(CleanIpScanManager.options(context).manual)
        assertFalse(CleanIpScanManager.state.value.running)
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp).height(800.dp)) {
            val current = LocalView.current; SideEffect { view = current }
            CleanIpSettingsPage(true, {})
        } } }
        compose.onNodeWithTag("scan-profile:BALANCED").assertIsSelected()
        compose.onNodeWithTag("scan-profile:DEEP").performClick()
        assertEquals(ScanProfile.DEEP, CleanIpScanManager.options(context).profile)
        // Fresh installs enable the manual ceiling. Exercise both transitions,
        // rather than retaining the old pre-1.6.0 disabled-default assertion.
        compose.onNodeWithTag("scan-ping-limit").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.cip_manual_ceiling)).performClick()
        compose.onNodeWithTag("scan-ping-limit").assertIsNotEnabled()
        assertFalse(CleanIpScanManager.options(context).manual)
        compose.onNodeWithText(context.getString(R.string.cip_manual_ceiling)).performClick()
        compose.onNodeWithTag("scan-ping-limit").assertIsEnabled().performTextReplacement("2300")
        assertEquals(2300, CleanIpScanManager.options(context).ceiling)
        assertTrue(CleanIpScanManager.options(context).manual)
        assertFalse(CleanIpScanManager.state.value.running)
        compose.onNodeWithTag("scan-profile:FAST").performScrollTo()
        capture("scanner-settings-top-fa-fixture.png")
        compose.onNodeWithTag("scan-ping-limit").performScrollTo()
        capture("scanner-settings-limit-fa-fixture.png")
    }
    @Test fun progressIsConfinedToItsServerAndTechnicalTextStaysLtr() {
        val one = CustomConfig("one", "@advertisement", ConfigProtocol.VLESS, ConfigFormat.URI,
            ConfigSource.LINK, "{}", server = "192.0.2.1:443")
        val two = one.copy(id = "two", server = "192.0.2.2:8443")
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp).height(500.dp)) {
            val current = LocalView.current; SideEffect { view = current }
            ConfigDataTable(listOf(ConfigCatalog.Row("one", config = one), ConfigCatalog.Row("two", config = two)),
                one.id, emptyMap(), true, false, {}, {}, {}, serverNumbers = mapOf("one" to 10L, "two" to 14L),
                scanning = true, scanTargetId = one.id, scanProgress = 99)
        } } }
        capture("scanner-row-progress-diagnostic-fa-fixture.png")
        System.getenv("AETHER_UI_ARTIFACTS")?.let { File(it, "progress-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString()) }
        compose.onNodeWithTag("scan-progress:one", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("scan-progress-bar:one", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("scan-progress:two").assertDoesNotExist()
        compose.onNodeWithText("VLESS S10 • 192.0.2.1:443").assertIsDisplayed()
        compose.onNodeWithText("@advertisement", substring = true).assertDoesNotExist()
        capture("scanner-row-progress-fa-fixture.png")
    }
    @Test fun bottomResultIncludesPreviousAndNewEndpointAndRealPingField() {
        var dismissed = false
        val result = CleanIpScanEngine.Report("one", "VLESS", "192.0.2.1:443", "192.0.2.2:8443", 117,
            CleanIpScanEngine.Outcome.APPLIED)
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp).height(500.dp)) {
            val current = LocalView.current; SideEffect { view = current }
            Box(Modifier.fillMaxSize()) { CleanIpScanResultCard(result, 10, { dismissed = true },
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(12.dp)) }
        } } }
        compose.onNodeWithTag("clean-ip-result").assertIsDisplayed()
        compose.onNodeWithText("VLESS S10 • 192.0.2.1:443 → 192.0.2.2:8443 · 117 ms").assertIsDisplayed()
        capture("scanner-result-fa-fixture.png")
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.cip_result_dismiss)).performClick()
        assertTrue(dismissed)
    }
}
