package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test

class ActiveTabRegressionTest {
    private fun c(id: String, group: String? = null, format: ConfigFormat = ConfigFormat.URI) =
        CustomConfig(id, id, ConfigProtocol.VLESS, format, ConfigSource.FILE, "{}", groupId = group)
    private val state = CustomConfigStore.State(configs = listOf(c("v1"), c("v2"), c("n", format = ConfigFormat.NPVT), c("manual", "g")),
        activeId = "n", enabled = true, autoSelect = true, groups = listOf(CustomConfigStore.Group("g", "دستی")))

    @Test fun selectedTabSurvivesCodecAndSessionSelectionNeverUsesOtherTabs() {
        for (key in listOf(ConfigCatalog.V2RAY, ConfigCatalog.NPVT, "user:g")) {
            val selected = ConfigCatalog.selectTab(state, key)
            val reloaded = requireNotNull(CustomConfigStore.decode(CustomConfigStore.encode(selected)))
            assertEquals(key, reloaded.activeTab)
            assertTrue(ConfigCatalog.contains(key, reloaded.selection!!.config))
            assertEquals(ConfigCatalog.rows(reloaded, key).mapNotNull { it.config }.toSet(),
                reloaded.configs.filter { ConfigCatalog.contains(reloaded.activeTab, it) }.toSet())
        }
    }
    @Test fun fasterServerInOtherTabCannotWin() {
        val now = 100_000L
        val selected = ConfigCatalog.selectTab(state, "user:g")
        val reports = state.configs.associate { it.id to ConfigPing.Report(if(it.id == "manual") 300 else 1, ConfigPing.PingStatus.OK, at = now) }
        val eligible = selected.configs.filter { ConfigCatalog.contains(selected.activeTab, it) }
        assertEquals("manual", AutoSelectPolicy.choose(eligible, reports, null, now)!!.id)
    }
    @Test fun emptyActiveAutomaticTabRemainsVisibleAndFailsClosed() {
        val empty = ConfigCatalog.selectTab(state, ConfigCatalog.NPVT).copy(configs = state.configs.filter { it.id != "n" })
        assertTrue(ConfigCatalog.tabs(empty, "All").any { it.key == ConfigCatalog.NPVT && it.count == 0 })
        assertNull(empty.selection)
        assertTrue(ConfigCatalog.rows(empty, empty.activeTab).isEmpty())
        assertEquals(ConfigCatalog.NPVT, CustomConfigStore.decode(CustomConfigStore.encode(empty))!!.activeTab)
    }
    @Test fun unrelatedDeletionDoesNotResetSelectionAndAllRequiresExplicitChoice() {
        val selected = ConfigCatalog.selectTab(state, "user:g")
        assertEquals("user:g", ConfigCatalog.deleteTab(selected, ConfigCatalog.NPVT).activeTab)
        assertEquals(ConfigCatalog.ALL, ConfigCatalog.selectTab(selected, ConfigCatalog.ALL).activeTab)
        assertFalse(ConfigCatalog.contains("user:missing", state.configs.first()))
    }
    @Test fun legacyStateDefaultsToAllWithoutBreakingExistingUsers() {
        assertEquals(ConfigCatalog.ALL, CustomConfigStore.decode("""{"v":3,"configs":[]}""")!!.activeTab)
    }
    @Test fun staleActiveIdCannotEscapeTheChosenTabEvenBeforeNormalization() {
        val selected = state.copy(activeTab = "user:g", activeId = "v1")
        assertEquals("manual", selected.selection!!.config.id)
        assertNull(selected.copy(activeTab = "user:deleted").selection)
    }
}
