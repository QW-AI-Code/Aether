package studio.cluvex.aether.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiTopic
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.ui.ai.*
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
class ConfigMenuVisualTest {
    @get:Rule(order = 0) val host = ComposeHostRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private var renderedView: View? = null
    @Composable private fun CaptureView() {
        val view = LocalView.current
        SideEffect { renderedView = view }
    }
    private fun capture(tag: String, filename: String) {
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertExists()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = renderedView!!.rootView
            bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        Assert.assertTrue(bitmap.width > 0 && bitmap.height > 0)
        System.getenv("AETHER_UI_ARTIFACTS")?.let { path ->
            val dir=File(path).apply { mkdirs() }
            File(dir,"$filename.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }
    @Test fun importCardsEnglish() {
        compose.setContent { AetherTheme {
            CompositionLocalProvider(LocalAiHost provides AiHostContext(enabled=true)) {
                ConfigDialog(onDismissRequest={}, modifier=Modifier.testTag("menu"),
                    title={CaptureView(); Text(stringResource(R.string.cc_add_config))},
                    text={Column {
                        listOf(R.string.cc_import_paste,R.string.cc_import_file,R.string.cc_import_camera,R.string.cc_import_image).forEach {
                            SettingsMenuAction(onClick={}) { Text(stringResource(it)) }
                        }
                    }},confirmButton={TextButton(onClick={}) {Text(stringResource(R.string.action_cancel))}})
            }
        } }
        capture("menu","import-en")
    }
    @Test @Config(qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi") fun serverActionsPersian() {
        compose.setContent { AetherTheme {
            CompositionLocalProvider(LocalAiHost provides AiHostContext(enabled=true)) {
                ConfigDialog(onDismissRequest={},modifier=Modifier.testTag("menu"),title={CaptureView(); Text("VLESS — سرور نمونه")},
                    text={Column {
                        listOf(R.string.cc_action_use,R.string.cc_ping_one,R.string.cc_move_group,
                            R.string.cc_action_edit,R.string.cc_action_rename,R.string.cc_action_copy,R.string.cc_action_delete).forEach {
                            SettingsMenuAction(onClick={}) {Text(stringResource(it))}
                        }
                    }}, confirmButton={TextButton(onClick={}) {Text(stringResource(R.string.action_cancel))}})
            }
        } }
        capture("menu","actions-fa")
    }
    @Test @Config(qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi") fun tableUsesSemanticPingPalette() {
        val configs=(0..6).map { CustomConfig("$it","سرور آزمایشی $it",ConfigProtocol.VLESS,ConfigFormat.URI,ConfigSource.FILE,"{}") }
        val pings=configs.mapIndexed { i,c -> c.id to ConfigPing.Report(listOf(80L,149L,150L,300L,301L,1200L,-1L)[i],
            if(i==6) ConfigPing.PingStatus.NO_REPLY else ConfigPing.PingStatus.OK) }.toMap()
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp).testTag("table")) {
            Column(Modifier.padding(12.dp)) {
                CaptureView()
                ConfigTabStrip(listOf(ConfigCatalog.Tab("all","همه",7),ConfigCatalog.Tab("npvt","Npvt",0)),"all",true,true,{}, {})
                ConfigDataTable(configs.map {ConfigCatalog.Row(it.id,config=it)},"0",pings,true,false,{}, {}, {})
            }
        } } }
        capture("table","table-fa")
    }
}
