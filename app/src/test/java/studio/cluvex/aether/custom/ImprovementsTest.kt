package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.custom.ChainBuilderPolicy.Core
import studio.cluvex.aether.model.TransportBackend

/** 1.7.0 (same version): fast first selection, speed sort and the chain builder. */
class ImprovementsTest {
    private fun config(id: String) = CustomConfig(id, id, ConfigProtocol.VLESS, ConfigFormat.URI,
        ConfigSource.LINK, "{}")

    @Test fun `fastest so far only counts answers of the current run`() {
        val configs = listOf(config("a"), config("b"), config("c"))
        val since = 10_000L
        val reports = mapOf(
            "a" to ConfigPing.Report(300, ConfigPing.PingStatus.OK, at = since + 5),
            "b" to ConfigPing.Report(40, ConfigPing.PingStatus.OK, at = since - 1),
            "c" to ConfigPing.Report(ConfigPing.FAILED_MS, ConfigPing.PingStatus.NO_REPLY, at = since + 9),
        )
        assertEquals("a", AutoSelectPolicy.fastestSoFar(configs, reports, since, since + 10)?.id)
        assertNull(AutoSelectPolicy.fastestSoFar(configs, emptyMap(), since, since + 10))
    }

    @Test fun `eager threshold switches on gains the steady one ignores`() {
        val now = 100_000L
        fun report(ms: Long) = ConfigPing.Report(ms, ConfigPing.PingStatus.OK, at = now)
        val configs = listOf(config("a"), config("b"))
        val reports = mapOf("a" to report(150), "b" to report(115))
        assertEquals("a", AutoSelectPolicy.choose(configs, reports, "a", now)?.id)
        assertEquals("b", AutoSelectPolicy.choose(configs, reports, "a", now,
            AutoSelectPolicy.EAGER_GAIN_MS, AutoSelectPolicy.EAGER_GAIN_DIVISOR)?.id)
    }

    @Test fun `speed sort puts the fastest measured server first`() {
        val state = CustomConfigStore.State(configs = listOf(config("a"), config("b"), config("c")))
        val speeds = mapOf("a" to ConfigSpeedTest.Report(1_000, 9_000), "b" to ConfigSpeedTest.Report(5_000, 100))
        val down = ConfigCatalog.rows(state, ConfigCatalog.ALL, speeds = speeds, sortBy = ConfigCatalog.SortKey.DOWNLOAD)
        assertEquals(listOf("b", "a", "c"), down.mapNotNull { it.config?.id })
        val up = ConfigCatalog.rows(state, ConfigCatalog.ALL, speeds = speeds, sortBy = ConfigCatalog.SortKey.UPLOAD)
        assertEquals(listOf("a", "b", "c"), up.mapNotNull { it.config?.id })
    }

    @Test fun `every builder chain maps to exactly one runnable mode`() {
        assertEquals(TransportBackend.entries.size + ChainMode.entries.size, ChainBuilderPolicy.all.size)
        for (chain in ChainBuilderPolicy.all) {
            val target = requireNotNull(ChainBuilderPolicy.target(chain)) { "$chain" }
            if (Core.CONFIG in chain) {
                assertTrue(target.customEnabled)
                assertEquals(chain, ChainBuilderPolicy.coresOf(target.mode!!))
            } else {
                assertFalse(target.customEnabled)
                assertEquals(chain, ChainBuilderPolicy.coresOf(target.backend!!))
            }
        }
        assertNull(ChainBuilderPolicy.target(listOf(Core.PSIPHON)))
        assertNull(ChainBuilderPolicy.target(listOf(Core.AETHER, Core.CONFIG, Core.TOR)))
    }

    @Test fun `node options only offer chains the app can run`() {
        assertEquals(setOf(Core.AETHER, Core.TOR, Core.CONFIG), ChainBuilderPolicy.options(emptyList(), 1, 0).toSet())
        val second = ChainBuilderPolicy.options(listOf(Core.CONFIG), 2, 1).toSet()
        assertEquals(setOf(Core.AETHER, Core.PSIPHON, Core.TOR), second)
        val grown = ChainBuilderPolicy.complete(listOf(Core.TOR), 3, listOf(Core.TOR, Core.AETHER))
        assertEquals(listOf(Core.TOR, Core.AETHER, Core.CONFIG), grown)
    }

    @Test fun `growing a chain never adds a config node by itself`() {
        assertEquals(listOf(Core.AETHER, Core.PSIPHON), ChainBuilderPolicy.complete(listOf(Core.AETHER), 2, listOf(Core.AETHER)))
        assertEquals(listOf(Core.TOR, Core.AETHER), ChainBuilderPolicy.complete(listOf(Core.TOR), 2, listOf(Core.TOR)))
        assertEquals(listOf(Core.CONFIG, Core.AETHER), ChainBuilderPolicy.complete(listOf(Core.CONFIG), 2, listOf(Core.CONFIG)))
        assertEquals(listOf(Core.AETHER), ChainBuilderPolicy.complete(listOf(Core.AETHER), 1, listOf(Core.AETHER, Core.TOR)))
        assertTrue(ChainBuilderPolicy.lengthNeedsConfig(3))
        assertFalse(ChainBuilderPolicy.lengthNeedsConfig(2))
    }
}
