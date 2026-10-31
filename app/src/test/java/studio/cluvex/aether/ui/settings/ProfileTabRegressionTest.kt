package studio.cluvex.aether.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import studio.cluvex.aether.R
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.core.KeyVault
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.model.*
import studio.cluvex.aether.ui.HomeScreen
import studio.cluvex.aether.ui.components.*
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File
import javax.crypto.KeyGenerator

/** Production Compose UI; output is a Robolectric render, not an Android VPN/device test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[28], qualifiers="fa-rIR-ldrtl-w360dp-h800dp-mdpi")
class ProfileTabRegressionTest {
    @get:Rule(order=0) val host = ComposeHostRule()
    @get:Rule(order=1) val compose = createComposeRule()
    private var view: View? = null
    @Composable private fun Capture() { val v=LocalView.current; SideEffect { view=v } }
    private fun capture(name:String, popup:Boolean=false) {
        compose.waitForIdle()
        compose.runOnIdle {
            var v=requireNotNull(view).rootView
            if (popup) {
                val cls=Class.forName("android.view.WindowManagerGlobal")
                val global=cls.getMethod("getInstance").invoke(null)
                val field=cls.getDeclaredField("mViews").apply{isAccessible=true}
                @Suppress("UNCHECKED_CAST") val roots=field.get(global) as List<View>
                val activityRoot=v
                v=requireNotNull(roots.lastOrNull { it.rootView !== activityRoot && it.isShown && it.width>0 && it.height>0 }) {
                    "No visible popup window to capture"
                }
            }
            val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bitmap))
            System.getenv("AETHER_UI_ARTIFACTS")?.let { dir ->
                File(dir).mkdirs(); File(dir,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
            bitmap.recycle()
        }
    }
    // Exercise the production SetText accessibility action without starting an
    // Android IME. Robolectric native graphics does not emulate an IME window.
    private fun replaceText(text: String) {
        compose.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.SetText) { set ->
            assertTrue(set(AnnotatedString(text)))
        }
    }
    private fun presets() = listOf("پیش‌فرض", "پروفایل نام بلند برای بررسی کوچک‌سازی فونت", "بازی", "کار").mapIndexed { i,n ->
        ConnectionPreset(if (i == 0) ConnectionPreset.SYSTEM_DEFAULT_ID else "p$i",
            n,ProfileCodec.encode(ConnectionProfile()))
    }
    @Test fun profileRowsRenameDeleteAndBoundedScrolling() {
        Assume.assumeTrue("Text-dialog measurement does not settle in this Robolectric host; opt in only on a compatible host",
            System.getenv("AETHER_TEST_TEXT_DIALOGS") == "true")
        val ctx=RuntimeEnvironment.getApplication()
        var renamed:Pair<String,String>?=null; var deleted:String?=null
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)) {
            Capture(); PresetSelector(presets(),ConnectionPreset.SYSTEM_DEFAULT_ID,false,{}, {}, {deleted=it},onRename={id,name->renamed=id to name})
        } } }
        compose.onNodeWithText("پیش‌فرض").performClick()
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_rename)).assertCountEquals(3)
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_delete)).assertCountEquals(3)
        compose.onAllNodesWithText(ctx.getString(R.string.preset_delete)).assertCountEquals(0)

        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_rename)).onFirst().performClick()
        compose.mainClock.advanceTimeBy(300)
        replaceText("نام واقعی جدید")
        compose.mainClock.advanceTimeByFrame()
        // Semantic interaction test; native dialog rasterization is not asserted here.
        compose.onNodeWithText(ctx.getString(R.string.preset_save)).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("p1" to "نام واقعی جدید",renamed) }
        compose.onNodeWithText("پیش‌فرض").performClick()
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_delete)).onFirst().performClick()
        compose.onAllNodesWithText(ctx.getString(R.string.cc_action_delete)).onLast().performClick()
        compose.runOnIdle { assertEquals("p1",deleted) }
    }
    @Test fun nativeProfileMenuHasInlineActionsAndBoundedList() {
        val ctx=RuntimeEnvironment.getApplication()
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)) {
            Capture(); PresetSelector(presets(),ConnectionPreset.SYSTEM_DEFAULT_ID,false,{}, {}, {},onRename={_,_->})
        } } }
        compose.onNodeWithText("پیش‌فرض").performClick()
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_rename)).assertCountEquals(3)
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_delete)).assertCountEquals(3)
        compose.onAllNodesWithText(ctx.getString(R.string.preset_delete)).assertCountEquals(0)
        capture("profiles-bounded-scroll-inline-actions-fa",true)
    }
    @Test fun defaultHasNoMutationActionsAndCanBeReselectedToRestoreSettings() {
        val ctx = RuntimeEnvironment.getApplication()
        var selected: String? = null
        val baseline = presets().first()
        compose.setContent { AetherTheme { Surface(Modifier.width(360.dp)) {
            Capture(); PresetSelector(listOf(baseline), baseline.id, false,
                { selected = it }, {}, {})
        } } }
        compose.onNodeWithText("پیش‌فرض").performClick()
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_rename)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_action_delete)).assertCountEquals(0)
        compose.onNodeWithText(ctx.getString(R.string.preset_save_as_new)).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.preset_export)).assertIsDisplayed()
        capture("profiles-protected-default-fa", true)
        compose.onAllNodesWithText("پیش‌فرض").onLast().performClick()
        compose.runOnIdle { assertEquals(baseline.id, selected) }
    }
    @Test fun homeSlotIsStableWhenPresetStoreFinishesLoading() {
        var loaded by mutableStateOf(false)
        val ctx=RuntimeEnvironment.getApplication()
        compose.setContent { AetherTheme { Capture(); HomeScreen(ConnectionState.Idle,ConnectionProfile(),null,null,false,{}, {},
            presets=if(loaded)presets() else emptyList(),activePresetId=if(loaded)ConnectionPreset.SYSTEM_DEFAULT_ID else null) } }
        val button=compose.onNodeWithContentDescription(ctx.getString(R.string.a11y_connect))
        val before=button.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { loaded=true }
        val after=button.fetchSemanticsNode().boundsInRoot
        assertEquals(before,after)
        capture("home-stable-loaded-preset-fa")
    }
    @Test fun fitIsFinalWithoutDelayedDensityRecomposition() {
        compose.setContent { AetherTheme { Box(Modifier.size(200.dp)) {
            Capture(); FitToHeight(Modifier.fillMaxSize()) { Box(Modifier.size(200.dp,400.dp).testTag("fitted")) }
        } } }
        val first=compose.onNodeWithTag("fitted").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(500)
        val later=compose.onNodeWithTag("fitted").fetchSemanticsNode().boundsInRoot
        assertEquals(first,later)
        assertTrue(first.height<=220f) // readability floor: 400 * .55
    }
    @Test fun manualTabEditorImportsIntoItsOwnTabWithoutCreatingAutomaticTab() {
        Assume.assumeTrue("Text-dialog measurement does not settle in this Robolectric host; opt in only on a compatible host",
            System.getenv("AETHER_TEST_TEXT_DIALOGS") == "true")
        val ctx=RuntimeEnvironment.getApplication()
        val key=KeyVault::class.java.getDeclaredField("cached").apply { isAccessible=true }
        val old=key.get(null); key.set(null,KeyGenerator.getInstance("AES").apply{init(256)}.generateKey())
        try {
            CustomConfigStore.load(ctx); CustomConfigStore.deleteAll(ctx)
            val st=CustomConfigStore.putGroup(ctx,null,"تست دستی")
            val id=st.groups.last().id
            compose.setContent { AetherTheme { Capture(); CustomConfigsPage(true,{}) } }
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("تست دستی"))
            val pencil=compose.onAllNodesWithContentDescription(ctx.getString(R.string.cc_group_edit)).onLast()
            pencil.performScrollTo()
                pencil.performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(ctx.getString(R.string.cc_group_edit)).assertIsDisplayed()
            compose.onNodeWithText(ctx.getString(R.string.cc_add_config)).assertIsDisplayed()
            // Full text-dialog integration is opt-in; do not publish an unverified popup bitmap.
            compose.onNodeWithText(ctx.getString(R.string.cc_add_config)).performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(ctx.getString(R.string.cc_import_paste)).assertIsDisplayed()
            compose.onNodeWithText(ctx.getString(R.string.cc_import_paste)).performClick()
            compose.mainClock.advanceTimeBy(300)
            replaceText("vless://00000000-0000-4000-8000-000000000001@example.invalid:443?security=tls&type=ws#manual-fixture")
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(ctx.getString(R.string.cc_import_action)).performClick()
            compose.mainClock.advanceTimeByFrame()
                compose.waitUntil(10_000) { CustomConfigStore.state.value.configs.isNotEmpty() }
            compose.runOnIdle {
                val result=CustomConfigStore.state.value
                assertEquals(id,result.configs.single().groupId)
                assertFalse(ConfigCatalog.tabs(result,"همه").any { it.key==ConfigCatalog.V2RAY })
            }
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("تست دستی"))
            compose.onNodeWithText("تست دستی").performClick()
            compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("manual-fixture", substring = true))
            // Store ownership is asserted above; no legacy bitmap is presented as evidence.
        } finally { CustomConfigStore.deleteAll(ctx); key.set(null,old) }
    }
}
