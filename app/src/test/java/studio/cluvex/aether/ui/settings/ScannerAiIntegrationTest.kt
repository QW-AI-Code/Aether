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
import studio.cluvex.aether.ai.*
import studio.cluvex.aether.scanner.*
import studio.cluvex.aether.ui.ai.*
import studio.cluvex.aether.ui.theme.AetherTheme

/** Real production settings, icon, sheet, follow-up and saved preferences; no Gemini call or VPN. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = android.app.Application::class,
    qualifiers = "fa-rIR-ldrtl-w400dp-h1000dp-mdpi")
class ScannerAiIntegrationTest {
    @get:Rule(order = 0) val host = ComposeHostRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private var view: View? = null
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle { System.getenv("AETHER_REPAIR_ARTIFACTS")?.let { output ->
            var v = requireNotNull(view).rootView
            if (dialog) {
                val cls = Class.forName("android.view.WindowManagerGlobal")
                val global = cls.getMethod("getInstance").invoke(null)
                val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST") val roots = field.get(global) as List<View>
                v = roots.last { it.width > 0 && it.height > 0 }
            }
            val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap)); File(output).mkdirs()
            File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } }
    }
    @Test fun eachScannerCardHasHelpAndHelpDoesNotSelectOrStartScan() {
        val context = RuntimeEnvironment.getApplication()
        val initial = CleanIpScanManager.Options(ScanProfile.BALANCED, false, 2000)
        CleanIpScanManager.saveOptions(context, initial)
        compose.setContent { AetherTheme { CompositionLocalProvider(LocalAiHost provides AiHostContext(enabled = true)) {
            Surface(Modifier.width(400.dp).height(1000.dp)) {
                val v = LocalView.current; SideEffect { view = v }
                CleanIpSettingsPage(true, {})
            }
        } } }
        for (topic in listOf(AiTopic.CLEAN_IP_PROFILE, AiTopic.CLEAN_IP_FAST,
            AiTopic.CLEAN_IP_BALANCED, AiTopic.CLEAN_IP_DEEP)) {
            compose.onNodeWithTag("ai-topic:${topic.id}").performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithTag("scan-profile:FAST").performScrollTo()
        capture("08-scanner-ai-profiles-fa")
        compose.onNodeWithTag("ai-topic:cleanIpDeep").performClick()
        compose.onNodeWithText(AiTopic.CLEAN_IP_DEEP.label).assertIsDisplayed()
        compose.onNodeWithText(AiTopic.CLEAN_IP_DEEP.offlineDetail(true)).assertIsDisplayed()
        assertEquals(initial, CleanIpScanManager.options(context))
        assertFalse(CleanIpScanManager.state.value.running)
        capture("09-scanner-ai-offline-help-fa", dialog = true)
    }
    @Test fun manualLimitHelpCarriesSafeCurrentValueToChat() {
        val context = RuntimeEnvironment.getApplication()
        val initial = CleanIpScanManager.Options(ScanProfile.DEEP, true, 850)
        CleanIpScanManager.saveOptions(context, initial)
        var chatOpened = false
        compose.setContent { AetherTheme { CompositionLocalProvider(LocalAiHost provides AiHostContext(
            enabled = true, openChat = { chatOpened = true })) {
            Surface(Modifier.width(400.dp).height(1000.dp)) {
                val v = LocalView.current; SideEffect { view = v }
                CleanIpSettingsPage(true, {})
            }
        } } }
        compose.onNodeWithTag("scan-ping-limit").performScrollTo()
        compose.onNodeWithTag("ai-topic:cleanIpLimit").assertIsDisplayed()
        capture("10-scanner-ai-manual-ceiling-fa")
        compose.onNodeWithTag("ai-topic:cleanIpLimit").performClick()
        compose.onNodeWithText(AiSettingContext.scanner(initial)).performScrollTo().assertIsDisplayed()
        capture("11-scanner-ai-live-context-fa", dialog = true)
        compose.onNodeWithText(context.getString(R.string.ai_explain_ask_chat)).performScrollTo().performClick()
        assertTrue(chatOpened)
        val draft = AiSession.consumeQueuedQuestion()
        assertNotNull(draft)
        assertTrue(draft!!.contains("savedCeilingMs=850"))
        assertTrue(draft.contains("profile=DEEP"))
        assertEquals(initial, CleanIpScanManager.options(context))
        assertFalse(CleanIpScanManager.state.value.running)
    }
    @Test fun disablingHintsHidesScannerIconsWithoutChangingControls() {
        compose.setContent { AetherTheme { CompositionLocalProvider(LocalAiHost provides AiHostContext(enabled = false)) {
            CleanIpSettingsPage(true, {})
        } } }
        compose.onAllNodesWithTag("ai-topic:cleanIpFast").assertCountEquals(0)
        compose.onNodeWithTag("scan-profile:FAST").assertIsDisplayed()
    }
}
