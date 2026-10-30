package studio.cluvex.aether.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import studio.cluvex.aether.core.*
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.scanner.CleanIpScanManager
import studio.cluvex.aether.ui.AboutPanel
import studio.cluvex.aether.ui.components.ConnectionCard
import studio.cluvex.aether.ui.theme.AetherTheme

/** Actual production Compose components + native Skia; fixture data, not a live VPN. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], application = android.app.Application::class,
    qualifiers = "fa-rIR-ldrtl-w400dp-h900dp-mdpi")
class UiRepairVisualTest {
    @get:Rule(order = 0) val host = ComposeHostRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private var view: View? = null
    @Composable private fun Capture() { val v = LocalView.current; SideEffect { view = v } }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            System.getenv("AETHER_REPAIR_ARTIFACTS")?.let { output ->
                val v = requireNotNull(view).rootView
                assertTrue(v.width > 0 && v.height > 0)
                val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bitmap)); File(output).mkdirs()
                File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
    private fun fixture(id: String, endpoint: String) = CustomConfig(id, "ad https://stale.workers.dev", ConfigProtocol.VLESS,
        ConfigFormat.XRAY_JSON, ConfigSource.FILE,
        """{"protocol":"vless","settings":{"vnext":[{"address":"$endpoint","port":2096}]}}""",
        server = "fxmrrich.haven-relock-myth.workers.dev:2096", groupId = "g")
    @Test fun cleanTabsAndServerRowsUseProductionComponents() {
        val configs = listOf(fixture("s12","www.speedtest.net"),fixture("s13","172.67.187.13"),fixture("worker","only.workers.dev"))
        val st = CustomConfigStore.State(configs = configs, groups = listOf(CustomConfigStore.Group("g", "تست")), activeTab = "user:g")
        compose.setContent { AetherTheme { Surface(Modifier.width(400.dp).height(900.dp)) { Capture(); Column {
            ConfigTabStrip(ConfigCatalog.tabs(st,"همه"),st.activeTab,true,true,{}, {})
            ConfigDataTable(ConfigCatalog.rows(st,st.activeTab),null,emptyMap(),true,false,{}, {}, {},
                serverNumbers = mapOf("s12" to 12L,"s13" to 13L,"worker" to 3L))
        } } } }
        compose.onNodeWithText("VLESS S12 • www.speedtest.net:2096").assertIsDisplayed()
        compose.onNodeWithText("VLESS S13 • 172.67.187.13:2096").assertIsDisplayed()
        compose.onNodeWithText("VLESS S3").assertIsDisplayed()
        compose.onAllNodesWithText("workers.dev", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.cc_delete_tab_title)).assertCountEquals(0)
        capture("01-tabs-clean-server-rows-fa")
    }
    @Test fun scanNoticeDisappearsAfterItsRealManagerTimeout() {
        CleanIpScanManager.clearMessage()
        compose.setContent { AetherTheme { Surface(Modifier.width(400.dp).height(900.dp)) { Capture(); Column {
            val scan by CleanIpScanManager.state.collectAsState()
            CleanIpScanNotice(scan.message, 1) // Fixture result count; no scan/network is started.
            ConfigTabStrip(listOf(ConfigCatalog.Tab("all", "همه", 0)), "all", true, true, {}, {})
            ConfigDataTable(emptyList(),null,emptyMap(),true,false,{}, {}, {})
        } } } }
        compose.runOnIdle { CleanIpScanManager.postMessage("finished") }
        compose.onNodeWithTag("clean-ip-notice").assertIsDisplayed()
        capture("02-scanner-notice-visible-fa")
        compose.waitUntil(6000) { CleanIpScanManager.state.value.message.isEmpty() }
        compose.onNodeWithTag("clean-ip-notice").assertDoesNotExist()
        capture("03-scanner-notice-expired-fa")
    }
    @Test fun sameIpCountryUpdateReplacesGlobeInProductionConnectionCard() {
        AetherController.setIpInfo(IpEndpoint("192.0.2.1", null, true))
        compose.setContent { AetherTheme { Surface(Modifier.width(400.dp).height(900.dp)) { Capture()
            val ip by AetherController.ipInfo.collectAsState()
            ConnectionCard(true,"متصل","برای قطع لمس کنید",null,ip,false,false,TransportBackend.entries.first())
        } } }
        val flag = NetProbe.flagEmojiOrNull("DE")!!
        compose.onNodeWithText(flag).assertDoesNotExist()
        capture("04-country-pending-globe-fa")
        compose.runOnIdle { AetherController.offerTunnelIpInfo(IpEndpoint("192.0.2.1", "DE", true)) }
        compose.onNodeWithText(flag).assertIsDisplayed()
        compose.onNodeWithText("192.0.2.1").assertIsDisplayed()
        capture("05-country-enriched-flag-fa")
        compose.runOnIdle { AetherController.setIpInfo(null) }
    }
    @Test fun engineCreditPrecedesUiCreditInActualAboutPanel() {
        compose.setContent { AetherTheme { Surface(Modifier.width(400.dp).height(900.dp)) { Capture()
            Column(Modifier.verticalScroll(rememberScrollState())) { AboutPanel(startExpanded = true) }
        } } }
        val ctx = RuntimeEnvironment.getApplication()
        val core = compose.onNodeWithText(ctx.getString(R.string.about_original_title))
        core.performScrollTo().assertIsDisplayed()
        capture("06-about-core-first-fa")
        val ui = compose.onNodeWithText(ctx.getString(R.string.about_port_title))
        ui.performScrollTo().assertIsDisplayed()
        capture("07-about-ui-credit-fa")
        val coreY = core.fetchSemanticsNode().positionInRoot.y
        val uiY = ui.fetchSemanticsNode().positionInRoot.y
        assertTrue(coreY < uiY)
    }
}
