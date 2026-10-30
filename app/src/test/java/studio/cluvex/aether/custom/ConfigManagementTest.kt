package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test

class ConfigManagementTest {
    private fun config(id: String) = CustomConfig(id, id, ConfigProtocol.VLESS, ConfigFormat.URI,
        ConfigSource.LINK, "{}")

    @Test fun `v1 data migrates with verification enabled and automatic switching disabled`() {
        val old = """{"v":1,"configs":[],"enabled":false,"mode":"SOLO"}"""
        val state = requireNotNull(CustomConfigStore.decode(old))
        assertTrue(state.verifyConnection)
        assertFalse(state.autoSelect)
        assertEquals(300, state.healthIntervalSeconds)
        assertTrue(state.groups.isEmpty())
        assertTrue(state.subscriptions.isEmpty())
    }

    @Test fun `group subscription membership and preferences round trip`() {
        val state = CustomConfigStore.State(
            configs = listOf(config("a").copy(groupId = "g", subscriptionId = "s")),
            activeId = "a", enabled = true,
            groups = listOf(CustomConfigStore.Group("g", "سرورهای آلمان")),
            subscriptions = listOf(CustomConfigStore.Subscription("s", "test", "https://example.invalid/sub", 12)),
            verifyConnection = false, autoSelect = true, healthIntervalSeconds = 180,
        )
        assertEquals(state, CustomConfigStore.decode(CustomConfigStore.encode(state)))
    }

    @Test fun `invalid active id cannot enable custom connections`() {
        val state = CustomConfigStore.decode("""{"configs":[],"active":"missing","enabled":true}""")!!
        assertNull(state.selection)
        assertFalse(state.enabled)
    }

    @Test fun `interval is bounded on disk`() {
        val state = CustomConfigStore.decode("""{"configs":[],"healthIntervalSeconds":-1}""")!!
        assertEquals(60, state.healthIntervalSeconds)
    }

    @Test fun `best healthy candidate wins and small fluctuations retain current`() {
        val now = 100_000L
        fun report(ms: Long) = ConfigPing.Report(ms, ConfigPing.PingStatus.OK, at = now)
        val configs = listOf(config("a"), config("b"))
        assertEquals("b", AutoSelectPolicy.choose(configs, mapOf("a" to report(300), "b" to report(100)), "a", now)?.id)
        assertEquals("a", AutoSelectPolicy.choose(configs, mapOf("a" to report(110), "b" to report(100)), "a", now)?.id)
        assertEquals("b", AutoSelectPolicy.choose(configs, mapOf("b" to report(100)), "a", now)?.id)
    }

    @Test fun `stale failed expired and negative delays never win`() {
        val now = 1_000_000L
        val good = ConfigPing.Report(5, ConfigPing.PingStatus.OK, at = now)
        val candidates = listOf(config("expired").copy(expiresAt = 500), config("failed"), config("stale"), config("negative"))
        assertNull(AutoSelectPolicy.choose(candidates, mapOf(
            "expired" to good, "failed" to good.copy(status = ConfigPing.PingStatus.INVALID),
            "stale" to good.copy(at = 1), "negative" to good.copy(ms = -1),
        ), null, now))
    }
}
