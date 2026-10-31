package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test

class ConfigCatalogTest {
    private fun c(id: String, format: ConfigFormat = ConfigFormat.URI, group: String? = null, sub: String? = null) =
        CustomConfig(id, id, ConfigProtocol.VLESS, format, ConfigSource.LINK, "{\"id\":\"$id\"}",
            groupId = group, subscriptionId = sub, server = "example.invalid:443")
    private val groups = listOf(CustomConfigStore.Group("a", "دانلود"), CustomConfigStore.Group("b", "آلمان"))
    private fun state(vararg configs: CustomConfig) = CustomConfigStore.State(configs = configs.toList(), groups = groups)
    private fun s(group: String? = "a") = CustomConfigStore.Subscription("s", "source", "https://example.invalid/s", groupId = group)

    @Test fun `all counts servers without counting subscription management records`() {
        val st = state(c("1"), c("2", sub = "s")).copy(subscriptions = listOf(s()))
        assertEquals(2, ConfigCatalog.tabs(st, "همه").first().count)
        assertEquals(3, ConfigCatalog.rows(st, ConfigCatalog.ALL).map { it.key }.distinct().size)
    }
    @Test fun `manual import never creates a family tab and family delete preserves manual configs`() {
        val manual = c("manual", ConfigFormat.NPVT, group = "a")
        val st = state(manual)
        assertFalse(ConfigCatalog.tabs(st, "All").any { it.key == ConfigCatalog.NPVT })
        assertEquals(listOf("manual"), ConfigCatalog.rows(st, "user:a").map { it.config!!.id })
        assertEquals(1, ConfigCatalog.rows(st, ConfigCatalog.ALL).size)
        val mixed = st.copy(configs = st.configs + c("auto", ConfigFormat.NPVT))
        assertEquals(listOf("auto"), ConfigCatalog.rows(mixed, ConfigCatalog.NPVT).map { it.config!!.id })
        assertEquals(listOf(manual), ConfigCatalog.deleteTab(mixed, ConfigCatalog.NPVT).configs)
    }
    @Test fun `grouped subscription import and refresh stay exclusively in manual tab`() {
        val imported = ConfigCatalog.mergeSubscription(state(), s(), listOf(c("n", ConfigFormat.NPVT)), 1)
        assertFalse(ConfigCatalog.tabs(imported, "All").any { it.key == ConfigCatalog.NPVT })
        assertEquals(2, ConfigCatalog.rows(imported, "user:a").size)
        assertTrue(ConfigCatalog.rows(imported, ConfigCatalog.NPVT).isEmpty())
        val refreshed = ConfigCatalog.mergeSubscription(imported, s(), listOf(c("next", ConfigFormat.NPVT)), 2)
        assertTrue(ConfigCatalog.rows(refreshed, ConfigCatalog.NPVT).isEmpty())
    }
    @Test fun `npvt wins over underlying vless protocol`() {
        assertEquals(ConfigCatalog.NPVT, ConfigCatalog.family(c("n", ConfigFormat.NPVT)))
        assertEquals(ConfigCatalog.V2RAY, ConfigCatalog.family(c("v", ConfigFormat.XRAY_JSON)))
    }
    @Test fun `automatic tabs appear only for imported families`() {
        assertEquals(listOf("all", "user:a", "user:b"), ConfigCatalog.tabs(state(), "All").map { it.key })
        assertEquals(listOf("all", "format:npvt", "user:a", "user:b"),
            ConfigCatalog.tabs(state(c("n", ConfigFormat.NPVT)), "All").map { it.key })
    }
    @Test fun `mixed subscription appears in both automatic tabs without losing members`() {
        val st = state(c("n", ConfigFormat.NPVT, sub = "s"), c("v", sub = "s")).copy(subscriptions = listOf(s()))
        assertEquals(2, ConfigCatalog.rows(st, ConfigCatalog.NPVT).size)
        assertEquals(2, ConfigCatalog.rows(st, ConfigCatalog.V2RAY).size)
        assertEquals(3, ConfigCatalog.rows(st, ConfigCatalog.ALL).size)
    }
    @Test fun `move updates custom tab and All without automatic duplication`() {
        val st = ConfigCatalog.moveConfig(state(c("1", group = "a")), "1", "b")
        assertTrue(ConfigCatalog.rows(st, "user:a").isEmpty())
        assertEquals("1", ConfigCatalog.rows(st, "user:b").single().config!!.id)
        assertEquals(1, ConfigCatalog.rows(st, ConfigCatalog.ALL).size)
        assertEquals(0, ConfigCatalog.rows(st, ConfigCatalog.V2RAY).size)
    }
    @Test fun `subscription move relocates its source and all members`() {
        val st = state(c("1", group = "a", sub = "s"), c("2", group = "a", sub = "s"), c("manual", group = "a"))
            .copy(subscriptions = listOf(s()))
        val moved = ConfigCatalog.moveSubscription(st, "s", "b")
        assertEquals(3, ConfigCatalog.rows(moved, "user:b").size)
        assertEquals("manual", ConfigCatalog.rows(moved, "user:a").single().config!!.id)
    }
    @Test fun `individual subscription member can move independently`() {
        val st = state(c("1", group = "a", sub = "s")).copy(subscriptions = listOf(s()))
        val moved = ConfigCatalog.moveConfig(st, "1", "b")
        assertEquals("a", moved.subscriptions.single().groupId)
        assertEquals("b", moved.configs.single().groupId)
    }
    @Test fun `delete tab cascades members feed and invalid active selection`() {
        val st = state(c("1", group = "a", sub = "s")).copy(subscriptions = listOf(s()), activeId = "1", enabled = true)
        val deleted = ConfigCatalog.deleteGroup(st, "a")
        assertTrue(deleted.configs.isEmpty())
        assertTrue(deleted.subscriptions.isEmpty())
        assertNull(deleted.activeId)
        assertFalse(deleted.enabled)
        assertNull(deleted.selection)
    }
    @Test fun `automatic family deletion protects All and preserves other family`() {
        val st = state(c("np").copy(format = ConfigFormat.NPVT), c("uri")).copy(activeId = "uri", enabled = true)
        val deleted = ConfigCatalog.deleteTab(st, ConfigCatalog.NPVT)
        assertEquals(listOf("uri"), deleted.configs.map { it.id })
        assertEquals("uri", deleted.activeId)
        assertTrue(deleted.enabled)
        assertFalse(ConfigCatalog.tabs(deleted, "All").any { it.key == ConfigCatalog.NPVT })
        assertThrows(IllegalArgumentException::class.java) { ConfigCatalog.deleteTab(st, ConfigCatalog.ALL) }
    }
    @Test fun `surviving moved member is detached from deleted owned feed not orphaned`() {
        val st = state(c("gone",group="a",sub="s"),c("kept",group="b",sub="s"))
            .copy(subscriptions=listOf(s()))
        val deleted = ConfigCatalog.deleteGroup(st,"a")
        assertEquals(listOf("kept"),deleted.configs.map { it.id })
        assertNull(deleted.configs.single().subscriptionId)
        assertTrue(deleted.subscriptions.isEmpty())
    }
    @Test fun `invalid destination and missing config fail without mutation`() {
        val st = state(c("1"))
        assertThrows(IllegalArgumentException::class.java) { ConfigCatalog.moveConfig(st, "1", "missing") }
        assertThrows(IllegalArgumentException::class.java) { ConfigCatalog.moveConfig(st, "missing", "a") }
        assertNull(st.configs.single().groupId)
    }
    @Test fun `group key namespace cannot collide with automatic ids`() {
        assertEquals("npvt", ConfigCatalog.groupFor("user:npvt"))
        assertNull(ConfigCatalog.groupFor(ConfigCatalog.NPVT))
        assertNull(ConfigCatalog.groupFor(ConfigCatalog.ALL))
    }
    @Test fun `ping orders successes low first and failures last`() {
        val st = state(c("slow"), c("fast"), c("negative"), c("untested"), c("failed"))
        val reports = mapOf("slow" to ConfigPing.Report(300, ConfigPing.PingStatus.OK),
            "fast" to ConfigPing.Report(10, ConfigPing.PingStatus.OK),
            "negative" to ConfigPing.Report(-1, ConfigPing.PingStatus.OK),
            "failed" to ConfigPing.Report(1, ConfigPing.PingStatus.OFFLINE))
        assertEquals(listOf("fast", "slow"), ConfigCatalog.rows(st, "all", pings = reports).take(2).map { it.config!!.id })
    }
    @Test fun `ping update changes ordering immediately and ties are stable`() {
        val st = state(c("b"), c("a"))
        assertEquals("a", ConfigCatalog.rows(st, "all").first().config!!.id)
        assertEquals("b", ConfigCatalog.rows(st, "all", pings = mapOf("b" to ConfigPing.Report(1, ConfigPing.PingStatus.OK))).first().config!!.id)
    }
    @Test fun `source filters do not change the all tab counter`() {
        val st = state(c("manual"), c("member", sub = "s")).copy(subscriptions = listOf(s()))
        assertEquals(1, ConfigCatalog.rows(st, "all", ConfigCatalog.Filter.MANUAL).size)
        assertEquals(2, ConfigCatalog.rows(st, "all", ConfigCatalog.Filter.SUBSCRIPTIONS).size)
        assertEquals(2, ConfigCatalog.tabs(st, "All").first().count)
    }
    @Test fun `refresh preserves moved member identity and adds new members to source tab`() {
        val st = state(c("old", group = "b", sub = "s")).copy(subscriptions = listOf(s()), activeId = "old", enabled = true)
        val incoming = listOf(c("replacement").copy(outbound = st.configs.single().outbound), c("new"))
        val updated = ConfigCatalog.mergeSubscription(st, s(), incoming, 50)
        assertEquals("b", updated.configs.first { it.id == "old" }.groupId)
        assertEquals("a", updated.configs.first { it.id == "new" }.groupId)
        assertEquals("old", updated.activeId)
        assertEquals(50L, updated.subscriptions.single().updatedAt)
    }
    @Test fun `source moved during download controls newly downloaded members`() {
        val st = state(c("1", group = "a", sub = "s")).copy(subscriptions = listOf(s()))
        val moved = ConfigCatalog.moveSubscription(st, "s", "b")
        assertEquals("b", ConfigCatalog.mergeSubscription(moved, s(), listOf(c("new")), 5).configs.single().groupId)
    }
    @Test fun `cleared source group is not resurrected by old refresh request`() {
        val st = state(c("1", group = "a", sub = "s")).copy(subscriptions = listOf(s()))
        val cleared = ConfigCatalog.moveSubscription(st, "s", null)
        assertNull(ConfigCatalog.mergeSubscription(cleared, s(), listOf(c("new")), 5).configs.single().groupId)
    }
    @Test fun `empty refresh leaves input unchanged`() {
        val st = state(c("1", group = "a", sub = "s")).copy(subscriptions = listOf(s()))
        assertThrows(IllegalArgumentException::class.java) { ConfigCatalog.mergeSubscription(st, s(), emptyList(), 5) }
        assertEquals("1", st.configs.single().id)
    }
    @Test fun `duplicate outbound refresh stores one server`() {
        val incoming = c("1")
        val updated = ConfigCatalog.mergeSubscription(state(), s(), listOf(incoming, incoming.copy(id = "2")), 5)
        assertEquals(1, updated.configs.size)
    }
    @Test fun `port is bounded ipv6 aware and never reveals locked metadata`() {
        assertEquals("443", ConfigCatalog.port(c("1")))
        assertEquals("8443", ConfigCatalog.port(c("1").copy(server = "[2001:db8::1]:8443")))
        assertEquals("—", ConfigCatalog.port(c("1").copy(locked = true)))
        assertEquals("—", ConfigCatalog.port(c("1").copy(server = "x:70000")))
    }
    @Test fun `schema v3 roundtrips subscription destination and active server`() {
        val st = state(c("1", group = "a", sub = "s")).copy(subscriptions = listOf(s()), activeId = "1", enabled = true)
        assertEquals(st, CustomConfigStore.decode(CustomConfigStore.encode(st)))
    }
    @Test fun `v2 subscription group migrates from consistent child membership`() {
        val st = state(c("1", group = "a", sub = "s")).copy(subscriptions = listOf(s(null)))
        val old = CustomConfigStore.encode(st).replace("\"v\":4", "\"v\":2")
        assertEquals("a", CustomConfigStore.decode(old)!!.subscriptions.single().groupId)
    }
    @Test fun `v2 mixed group subscription migrates without inventing a destination`() {
        val st = state(c("1", group = "a", sub = "s"), c("2", group = "b", sub = "s")).copy(subscriptions = listOf(s(null)))
        val old = CustomConfigStore.encode(st).replace("\"v\":4", "\"v\":2")
        assertNull(CustomConfigStore.decode(old)!!.subscriptions.single().groupId)
    }
    @Test fun `real private npvt generations classify move and persist without exposing locked ports`() {
        val directory = System.getenv("AETHER_PRIVATE_FIXTURES")
        org.junit.Assume.assumeTrue("Private fixtures are opt-in", directory != null)
        val gen2 = listOf(java.io.File("src/main/assets/npvs/gen2-wb.bin"),
            java.io.File("app/src/main/assets/npvs/gen2-wb.bin")).first { it.exists() }
            .let { NpvtCodec.Tables.parse(it.readBytes()) }
        for (name in listOf("legacy.npvt", "binary.npvt")) {
            val imported = ConfigParser.parseBytes(java.io.File(directory!!, name).readBytes(), ConfigSource.FILE,
                { CustomConfigTestSupport.tables }, { gen2 })
            assertTrue(imported.isNotEmpty())
            val st = CustomConfigStore.State(configs = imported, groups = groups)
            assertEquals(imported.size, ConfigCatalog.rows(st, ConfigCatalog.NPVT).size)
            val moved = ConfigCatalog.moveConfig(st, imported.first().id, "b")
            assertEquals(1, ConfigCatalog.rows(moved, "user:b").size)
            assertEquals(moved, CustomConfigStore.decode(CustomConfigStore.encode(moved)))
            moved.configs.filter { it.locked }.forEach { assertEquals("—", ConfigCatalog.port(it)) }
        }
    }

    @Test fun `descending sort keeps invalid pings last and active choice unchanged`() {
        val st = state(c("a"), c("b"), c("c"), c("d")).copy(activeId = "a")
        val reports = mapOf("a" to ConfigPing.Report(120,ConfigPing.PingStatus.OK),
            "b" to ConfigPing.Report(480,ConfigPing.PingStatus.OK), "c" to ConfigPing.Report(1,ConfigPing.PingStatus.OFFLINE))
        fun ids(sort: ConfigCatalog.PingSort) = ConfigCatalog.rows(st,"all",pings=reports,pingSort=sort).mapNotNull { it.config?.id }
        assertEquals(listOf("a","b","c","d"),ids(ConfigCatalog.PingSort.ASC))
        assertEquals(listOf("b","a","c","d"),ids(ConfigCatalog.PingSort.DESC))
        assertEquals("a",st.activeId)
    }
    @Test fun `equal delay tie breaks remain deterministic in both sort directions`() {
        val st = state(c("z"), c("a"))
        val reports = listOf("z","a").associateWith { ConfigPing.Report(200,ConfigPing.PingStatus.OK) }
        ConfigCatalog.PingSort.entries.forEach { sort ->
            assertEquals(listOf("a","z"),ConfigCatalog.rows(st,"all",pings=reports,pingSort=sort).mapNotNull { it.config?.id })
        }
    }
}
