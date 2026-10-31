package studio.cluvex.aether.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.cluvex.aether.custom.ConfigCatalog

/** Exercises actual recomposition, not just repository/model arithmetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigTabComposeTest {
    @get:Rule(order = 0) val host = ComposeHostRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    @Test fun creatingSelectingRenamingAndDeletingTabsNeverIndexesOldPositions() {
        val base = listOf(ConfigCatalog.Tab("all", "All", 0),
            ConfigCatalog.Tab("format:v2ray", "V2Ray", 0), ConfigCatalog.Tab("format:npvt", "Npvt", 0))
        val tabs = mutableStateOf(base)
        val selected = mutableStateOf("all")
        compose.setContent { MaterialTheme {
            ConfigTabStrip(tabs.value, selected.value, true, true,
                onSelect = { selected.value = it }, onRename = {})
        } }
        repeat(12) { i ->
            val key = "user:$i"
            // Deliberately select before the list update, reproducing async store delivery.
            compose.runOnIdle { selected.value = key }
            compose.waitForIdle()
            compose.runOnIdle { tabs.value = base + ConfigCatalog.Tab(key, "New $i", 0, true) }
            compose.onNodeWithText("New $i").assertExists()
            compose.runOnIdle { tabs.value = base + ConfigCatalog.Tab(key, "Renamed $i", 0, true) }
            compose.onNodeWithText("Renamed $i").assertExists()
            // Deliberately remove selected tab before updating selection.
            compose.runOnIdle { tabs.value = base }
            compose.waitForIdle()
            compose.runOnIdle { selected.value = "all" }
            compose.onNodeWithText("All").assertExists()
        }
    }
}
