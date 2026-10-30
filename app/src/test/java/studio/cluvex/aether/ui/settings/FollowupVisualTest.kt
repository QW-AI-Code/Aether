package studio.cluvex.aether.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
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
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[28],qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi")
class FollowupVisualTest {
    @get:Rule(order=0) val host=ComposeHostRule()
    @get:Rule(order=1) val compose=createComposeRule()
    private var view:View?=null
    @Composable private fun Capture(){val v=LocalView.current;SideEffect{view=v}}
    private fun capture(name:String,popup:Boolean=false) {
        compose.waitForIdle()
        compose.runOnIdle {
            var v=requireNotNull(view).rootView
            if(popup) {
                val cls=Class.forName("android.view.WindowManagerGlobal")
                val global=cls.getMethod("getInstance").invoke(null)
                val field=cls.getDeclaredField("mViews").apply{isAccessible=true}
                @Suppress("UNCHECKED_CAST") val roots=field.get(global) as List<View>
                v=roots.last { it.width>0 && it.height>0 }
            }
            val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            System.getenv("AETHER_UI_ARTIFACTS")?.let { d ->
                File(d).mkdirs();File(d,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            };bitmap.recycle()
        }
    }
    @Test fun persistedEnglishDefaultIsLocalizedAndPopupUsesSettingsCards() {
        val p=listOf(ConnectionPreset(ConnectionPreset.SYSTEM_DEFAULT_ID,"Default",ProfileCodec.encode(ConnectionProfile())),ConnectionPreset("g","بازی",ProfileCodec.encode(ConnectionProfile())))
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)){Capture();PresetSelector(p,ConnectionPreset.SYSTEM_DEFAULT_ID,false,{}, {}, {})} } }
        compose.onNodeWithText("پیش‌فرض").assertIsDisplayed().performClick()
        compose.onAllNodesWithText("Default").assertCountEquals(0)
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.preset_import)).assertIsDisplayed()
        capture("preset-menu-fa-cards",true)
    }
    @Test fun homeReturnsFromSettingsWithoutLosingPresetOrFullLayout() {
        val ctx=RuntimeEnvironment.getApplication()
        compose.setContent { AetherTheme { Capture();HomeScreen(ConnectionState.Idle,ConnectionProfile(),null,null,false,{}, {},
            presets=listOf(ConnectionPreset(ConnectionPreset.SYSTEM_DEFAULT_ID,"Default",ProfileCodec.encode(ConnectionProfile()))),activePresetId=ConnectionPreset.SYSTEM_DEFAULT_ID) } }
        repeat(3) {
            compose.onNodeWithContentDescription(ctx.getString(R.string.advanced_open)).performClick()
            compose.onNodeWithContentDescription(ctx.getString(R.string.a11y_back)).performClick()
            compose.onNodeWithText("پیش‌فرض").assertIsDisplayed()
            compose.onNodeWithContentDescription(ctx.getString(R.string.a11y_connect)).assertIsDisplayed()
        }
        capture("home-fa-after-three-back-cycles")
    }
    @Test fun retainedHostKeepsRememberedHomeAndDoesNotDisposeOnBack() {
        var show by mutableStateOf(true);var created=0;var disposed=0
        compose.setContent { AetherTheme { HomeSettingsHost(show,Modifier.fillMaxSize(),home={
            remember{created++};DisposableEffect(Unit){onDispose{disposed++}};Text("HOME")
        },settings={Text("SETTINGS")}) } }
        repeat(10) {
            compose.runOnIdle{show=false};compose.onNodeWithText("SETTINGS").assertIsDisplayed()
            compose.runOnIdle{show=true};compose.onNodeWithText("HOME").assertIsDisplayed()
        }
        compose.runOnIdle{assertEquals(1,created);assertEquals(0,disposed)}
    }
    @Test fun automaticTabHasDeleteConfirmationButAllDoesNot() {
        val ctx=RuntimeEnvironment.getApplication()
        val key=studio.cluvex.aether.core.KeyVault::class.java.getDeclaredField("cached").apply{isAccessible=true}
        val prior=key.get(null);key.set(null,KeyGenerator.getInstance("AES").apply{init(256)}.generateKey())
        try {
            CustomConfigStore.load(ctx);CustomConfigStore.deleteAll(ctx)
            CustomConfigStore.putGroup(ctx,null,"تست")
            CustomConfigStore.add(ctx,listOf(CustomConfig("n","نمونه نپستر",ConfigProtocol.VLESS,ConfigFormat.NPVT,ConfigSource.FILE,"{}")))
            compose.setContent { AetherTheme {Capture();CustomConfigsPage(true,{})} }
            // 1.6.0: the ping-sort switch now lives in the top settings card, so scrolling to
            // it no longer pulls the tab strip into composition. Scroll to the tab strip
            // itself (lazy item) and wait until its edit pencils exist before touching them.
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText(ctx.getString(R.string.cc_ping_all_short)))
            compose.waitUntil(5_000){compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_group_edit)).fetchSemanticsNodes().size>=2}
            compose.onAllNodesWithText(ctx.getString(R.string.cc_group_edit)).assertCountEquals(0)
            capture("custom-tabs-fa-no-extra-edit")
            // First pencil belongs to All; it must not offer Delete.
            compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_group_edit)).onFirst().performScrollTo().performClick()
            compose.onAllNodesWithText(ctx.getString(R.string.cc_action_delete)).assertCountEquals(0)
            compose.onNodeWithText(ctx.getString(R.string.action_cancel)).performClick()
            compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_group_edit)).get(1).performScrollTo().performClick()
            capture("automatic-tab-actions-fa",true)
            compose.onNodeWithText(ctx.getString(R.string.cc_action_delete)).performClick()
            compose.onNodeWithText(ctx.getString(R.string.cc_delete_tab_title)).assertIsDisplayed()
            capture("automatic-tab-cascade-confirm-fa",true)
            compose.onNodeWithText(ctx.getString(R.string.cc_action_delete)).performClick()
            compose.waitUntil(10_000){CustomConfigStore.state.value.configs.isEmpty()}
            compose.runOnIdle { assertNull(CustomConfigStore.state.value.activeId) }
        } finally {CustomConfigStore.deleteAll(ctx);key.set(null,prior)}
    }
}
