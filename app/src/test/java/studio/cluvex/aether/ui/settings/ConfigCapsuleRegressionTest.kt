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
import studio.cluvex.aether.ui.theme.AetherTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fa-rIR-ldrtl-w360dp-h800dp-mdpi")
class ConfigCapsuleRegressionTest {
    @get:Rule(order = 0) val host = ComposeHostRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private var view: View? = null
    private fun render(filename: String) {
        val config = CustomConfig("fixture", "VLESS S5", ConfigProtocol.VLESS, ConfigFormat.URI,
            ConfigSource.FILE, "{}", server = "188.114.98.84:443")
        val locked = config.copy(id = "private", name = "Private fixture", locked = true, server = "203.0.113.88:8443")
        var opened: String? = null; var created = 0
        val tabs = listOf(ConfigCatalog.Tab("all", "All", 2), ConfigCatalog.Tab("user:fixture", "سرورهای Fixture", 2, true))
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)) {
            val current = LocalView.current; SideEffect { view = current }
            Column {
                ConfigTabStrip(tabs, "user:fixture", true, true, {}, {}, onNew = { created++ })
                ConfigDataTable(listOf(ConfigCatalog.Row("config:fixture", config), ConfigCatalog.Row("config:private", locked)),
                    config.id, mapOf(config.id to ConfigPing.Report(112, ConfigPing.PingStatus.OK)),
                    true, false, {}, { opened = it.id }, {})
            }
        } } }
        compose.onNodeWithText("188.114.98.84:443", substring = true).assertIsDisplayed()
        compose.onNodeWithText("203.0.113.88:8443", substring = true).assertDoesNotExist()
        compose.onAllNodesWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.cc_col_actions))
            .onFirst().performClick()
        compose.runOnIdle { assertEquals(config.id, opened) }
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.cc_group_new)).performClick()
        compose.runOnIdle { assertEquals(1, created) }
        compose.onAllNodesWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.cc_delete_tab_title)).assertCountEquals(0)
        compose.runOnIdle {
            System.getenv("AETHER_UI_ARTIFACTS")?.let { output ->
                val v = requireNotNull(view).rootView
                val bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(bitmap))
                File(output).mkdirs()
                File(output, filename).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }
    @Test fun persianCapsulesKeepTechnicalAddressOrderAndLockedMetadataPrivate() = render("capsules-fa-fixture.png")
    @Test @Config(qualifiers = "en-rUS-ldltr-w360dp-h800dp-mdpi")
    fun englishCapsulesKeepTheSameTechnicalAddressOrder() = render("capsules-en-fixture.png")
}
