package studio.cluvex.aether.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.model.*
import studio.cluvex.aether.ui.HomeScreen
import studio.cluvex.aether.ui.components.ConnectionCard
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File
import javax.crypto.KeyGenerator

/** Genuine production Compose rendering; not a device/emulator screenshot. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[28], qualifiers="w360dp-h800dp-mdpi")
class ImplementationVisualTest {
    @get:Rule(order=0) val host=ComposeHostRule()
    @get:Rule(order=1) val compose=createComposeRule()
    private var view:View?=null
    @Composable private fun Capture() { val v=LocalView.current; SideEffect {view=v} }
    private fun capture(filename:String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val v=requireNotNull(view).rootView
            assertTrue(v.width>0 && v.height>0)
            val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            System.getenv("AETHER_UI_ARTIFACTS")?.let { path ->
                val dir=File(path).apply{mkdirs()}
                File(dir,"$filename.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            bitmap.recycle()
        }
    }
    private fun home(filename:String) {
        val presets=listOf(ConnectionPreset(ConnectionPreset.SYSTEM_DEFAULT_ID,"Default",ProfileCodec.encode(ConnectionProfile())),
            ConnectionPreset("game","بازی",ProfileCodec.encode(ConnectionProfile(gameMode=true))))
        var selected by mutableStateOf(ConnectionPreset.SYSTEM_DEFAULT_ID)
        var toggles=0
        compose.setContent { AetherTheme { Capture(); HomeScreen(ConnectionState.Idle,ConnectionProfile(),null,null,false,
            {}, {toggles++},presets=presets,activePresetId=selected,onSelectPreset={selected=it}) } }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.preset_default_name)).performClick()
        compose.onNodeWithText("بازی").performClick()
        compose.runOnIdle { assertEquals("game",selected) }
        compose.onNodeWithText("بازی").assertIsDisplayed()
        val label=RuntimeEnvironment.getApplication().getString(R.string.a11y_connect)
        compose.onNodeWithContentDescription(label).performClick()
        compose.runOnIdle { assertEquals(1,toggles) }
        capture(filename)
    }
    @Test fun homeEnglishPresetSwitch()=home("home-en-preset")
    @Test @Config(qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi")
    fun homePersianPresetSwitch()=home("home-fa-preset")

    @Test @Config(qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi")
    fun persianConnectionCardIsFullWidth() {
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)) {
            Capture(); ConnectionCard(false,"آماده برای اتصال","برای آغاز، دکمه اتصال را لمس کنید",null,null,false,false,
                TransportBackend.entries.first(),Modifier.testTag("card"))
        } } }
        compose.onNodeWithTag("card").assertWidthIsEqualTo(360.dp)
        capture("connection-card-fa")
    }

    @Test @Config(qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi")
    fun actualGameModeSettings() {
        val context=RuntimeEnvironment.getApplication()
        var p by mutableStateOf(ConnectionProfile(gameMode=true,gameDnsServers="1.1.1.1:53,8.8.8.8:53"))
        compose.setContent { AetherTheme { Capture(); SettingsHost(SettingsRoute.DNS_ROUTING,ConnectionState.Idle,p,{p=it},{}) } }
        compose.onNodeWithText(context.getString(R.string.game_mode_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.game_dns_label)).assertIsDisplayed()
        capture("game-dns-fa")
    }

    @Test @Config(qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi")
    fun productionTableDescendingFixture() {
        val configs=listOf("fast","slow","none").map { CustomConfig(it,it,ConfigProtocol.VLESS,ConfigFormat.URI,ConfigSource.FILE,"{}") }
        val pings=mapOf("fast" to ConfigPing.Report(42,ConfigPing.PingStatus.OK),"slow" to ConfigPing.Report(320,ConfigPing.PingStatus.OK))
        val state=CustomConfigStore.State(configs=configs,activeId="fast")
        val rows=ConfigCatalog.rows(state,ConfigCatalog.ALL,pings=pings,pingSort=ConfigCatalog.PingSort.DESC)
        assertEquals(listOf("slow","fast","none"),rows.map{it.config!!.id})
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)) { Column(Modifier.padding(12.dp)) {
            Capture(); Text("نمونهٔ آزمایشی — نه پینگ زنده")
            SettingsSwitchRow("مرتب‌سازی نزولی پینگ",true,{})
            ConfigDataTable(rows,"fast",pings,true,false,{}, {}, {})
        } } } }
        capture("ping-desc-fixture-fa")
    }

    @Test fun originalFileImporterAndEncryptedStoreIntegration()=runBlocking {
        val path=System.getenv("AETHER_PRIVATE_NPVO_FILE")
        assumeTrue("Private original sample opt-in",path!=null)
        val context=RuntimeEnvironment.getApplication()
        val keyField=studio.cluvex.aether.core.KeyVault::class.java.getDeclaredField("cached").apply{isAccessible=true}
        val old=keyField.get(null)
        // Robolectric has no hardware keystore. Only the test supplies a JCE key;
        // production encryption/decryption code and storage remain unchanged.
        keyField.set(null,KeyGenerator.getInstance("AES").apply{init(256)}.generateKey())
        try {
            val configs=ConfigImporter.fromFile(context,Uri.fromFile(File(path!!)))
            assertEquals(2,configs.size)
            CustomConfigStore.load(context); CustomConfigStore.deleteAll(context)
            val state=CustomConfigStore.add(context,configs)
            assertEquals(2,state.configs.size)
            val disk=context.getSharedPreferences("aether_custom_configs",0).getString("state",null)!!
            assertFalse(disk.contains("NPVO1"))
            val safe=configs.mapIndexed{i,c->c.copy(name="NPVO1 — ${i+1}")}
            compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)) { Column(Modifier.padding(12.dp)) {
                Capture(); Text("config.npvs — 2 imported / names redacted")
                ConfigDataTable(safe.map{ConfigCatalog.Row(it.id,config=it)},null,emptyMap(),true,false,{}, {}, {})
            } } } }
            capture("npvo-original-import-table")
            println("ORIGINAL_FILE_IMPORT fromFile=PASS store_records=2 encrypted_store=PASS display_names=REDACTED")
        } finally {
            CustomConfigStore.deleteAll(context)
            keyField.set(null,old)
        }
    }

    @Test fun actualCustomPageSortSwitchReordersWithoutSelecting() {
        val context=RuntimeEnvironment.getApplication()
        val key=studio.cluvex.aether.core.KeyVault::class.java.getDeclaredField("cached").apply{isAccessible=true}
        val previous=key.get(null)
        key.set(null,KeyGenerator.getInstance("AES").apply{init(256)}.generateKey())
        try {
            val configs=listOf("fast-fixture","slow-fixture").map {
                CustomConfig(it,it,ConfigProtocol.VLESS,ConfigFormat.URI,ConfigSource.FILE,"{}") }
            CustomConfigStore.load(context); CustomConfigStore.deleteAll(context)
            CustomConfigStore.add(context,configs)
            val results=ConfigPingStore::class.java.getDeclaredField("_results").apply{isAccessible=true}
            @Suppress("UNCHECKED_CAST")
            val flow=results.get(null) as kotlinx.coroutines.flow.MutableStateFlow<Map<String,ConfigPing.Report>>
            flow.value=mapOf("fast-fixture" to ConfigPing.Report(42,ConfigPing.PingStatus.OK),
                "slow-fixture" to ConfigPing.Report(320,ConfigPing.PingStatus.OK))
            compose.setContent { AetherTheme { Capture(); CustomConfigsPage(true,{}) } }
            val title=context.getString(R.string.cc_sort_ping_desc)
            // The settings page is lazy: bring the off-screen section into composition first.
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(title))
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithText(title).assertIsOn()
            capture("custom-page-sort-switch-on-fixture")
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasTestTag("config-capsules"))
            compose.onNodeWithTag("config-capsules").performScrollToNode(hasTestTag("capsule:config:slow-fixture"))
            val slow=compose.onNodeWithTag("capsule:config:slow-fixture", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            val fast=compose.onNodeWithTag("capsule:config:fast-fixture", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            assertTrue("DESC must put larger latency first",slow<fast)
            assertEquals("fast-fixture",CustomConfigStore.state.value.activeId)
            capture("custom-page-desc-fixture")
        } finally {
            ConfigPingStore.clear(); CustomConfigStore.deleteAll(context); key.set(null,previous)
        }
    }
}
