package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import studio.cluvex.aether.model.*
import studio.cluvex.aether.ui.announcements.Announcements
import java.io.File

class ChainRepairTest {
    private fun mode(p: ChainPlacement) = ChainMode.entries.first { it.placement == p && it.base == ChainBase.AETHER }
    @Test fun `automatic config first uses tcp carrier without direct udp fingerprint`() {
        val p = ConnectionProfile()
        val result = CustomChainPolicy.engineProfile(p, mode(ChainPlacement.FIRST))
        assertEquals(Protocol.MASQUE, result.protocol); assertTrue(result.masqueHttp2)
        assertEquals("1", result.toEnv()["AETHER_MASQUE_HTTP2"])
    }
    @Test fun `explicit masque first also uses tcp`() {
        assertTrue(CustomChainPolicy.engineProfile(ConnectionProfile(protocol=Protocol.MASQUE), mode(ChainPlacement.FIRST)).masqueHttp2)
    }
    @Test fun `explicit udp is not silently rewritten`() {
        val p=ConnectionProfile(protocol=Protocol.WIREGUARD)
        assertEquals(p, CustomChainPolicy.engineProfile(p, mode(ChainPlacement.FIRST)))
    }
    @Test fun `aether first preserves direct engine strategy`() {
        val p=ConnectionProfile(); assertEquals(p, CustomChainPolicy.engineProfile(p, mode(ChainPlacement.LAST)))
    }
    @Test fun `other chain bases retain previous policy`() {
        val p=ConnectionProfile(); ChainMode.entries.filter { !CustomChainPolicy.isWarpFirstBase(it.base) || it.placement != ChainPlacement.FIRST }.forEach {
            assertEquals(p,CustomChainPolicy.engineProfile(p,it))
        }
    }
    @Test fun `tcp first hop preserves scan choice and gets registration plus scan budget`() {
        val source=ConnectionProfile(scanMode=ScanMode.BALANCED)
        val result=CustomChainPolicy.engineProfile(source,mode(ChainPlacement.FIRST))
        assertEquals(ScanMode.BALANCED,result.scanMode)
        assertTrue(CustomChainPolicy.usesTcpCarrier(result,mode(ChainPlacement.FIRST)))
        assertTrue(CustomChainPolicy.connectBudgetMs(result)>=180_000L)
    }
    @Test fun `tcp policy is not applied to ordinary or reverse sessions`() {
        val p=ConnectionProfile(protocol=Protocol.MASQUE,masqueHttp2=true)
        assertFalse(CustomChainPolicy.usesTcpCarrier(p,null))
        assertFalse(CustomChainPolicy.usesTcpCarrier(p,mode(ChainPlacement.LAST)))
    }
    private fun c(id:String, format:ConfigFormat)=CustomConfig(id,id,ConfigProtocol.VLESS,format,ConfigSource.FILE,"{}")
    @Test fun `npvt then v2ray always has visible npvt member matching counter`() {
        val n=c("n",ConfigFormat.NPVT); val v=c("v",ConfigFormat.URI)
        for (configs in listOf(listOf(n,v),listOf(v,n))) {
            val st=CustomConfigStore.State(configs=configs)
            assertEquals(1,ConfigCatalog.tabs(st,"All").first { it.key==ConfigCatalog.NPVT }.count)
            assertEquals("n",ConfigCatalog.rows(st,ConfigCatalog.NPVT).single().config!!.id)
        }
    }
    @Test fun `automatic rename does not change family key or members`() {
        val st=CustomConfigStore.State(configs=listOf(c("n",ConfigFormat.NPVT)))
        val updated=ConfigCatalog.renameAutomaticTab(st,ConfigCatalog.NPVT,"آلمان")
        assertEquals("آلمان",ConfigCatalog.tabs(updated,"All").first { it.key==ConfigCatalog.NPVT }.name)
        assertEquals(st.configs,ConfigCatalog.rows(updated,ConfigCatalog.NPVT).map { it.config })
    }
    @Test fun `aliases survive codec with version unchanged`() {
        val st=ConfigCatalog.renameAutomaticTab(CustomConfigStore.State(),ConfigCatalog.ALL,"همهٔ من")
        assertEquals(st,CustomConfigStore.decode(CustomConfigStore.encode(st)))
    }
    @Test fun `invalid alias inputs are rejected`() {
        for ((key,name) in listOf("user:a" to "name", ConfigCatalog.NPVT to " ")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigCatalog.renameAutomaticTab(CustomConfigStore.State(),key,name) }
        }
    }
    @Test fun `notice is for actual release and retains troubleshooting`() {
        val news=Announcements.forVersion("1.6.0")
        assertEquals("1.6.0",news.version)
        val text=news.blocks.toString();assertTrue(text.contains("حالت پرواز"));assertTrue(text.contains("Tor"))
        assertTrue(text.contains("۳ تا ۴ دقیقه"));assertTrue(text.contains("1.6.0"))
    }
    @Test fun `private samples export actual session json for isolated chain probes`() {
        val root=System.getenv("AETHER_CHAIN_FIXTURES")?.let(::File)
        assumeTrue("private chain fixtures not configured",root!=null)
        val gen2=listOf(File("src/main/assets/npvs/gen2-wb.bin"),File("app/src/main/assets/npvs/gen2-wb.bin"))
            .first{it.exists()}.let { NpvtCodec.Tables.parse(it.readBytes()) }
        val dir=File(root,"export").apply{mkdirs()};var count=0
        for ((sample,name) in listOf("v2ray" to "v2ray-body.bin","npvt1" to "legacy.npvt","npvs" to "binary.npvt")) {
            val file=File(root,name);if(!file.exists())continue
            val configs=ConfigParser.parseBytes(file.readBytes(),ConfigSource.FILE,{CustomConfigTestSupport.tables},{gen2})
            assertTrue(configs.isNotEmpty())
            configs.forEachIndexed { i,c ->
                File(dir,"$sample-$i-solo.json").writeText(XrayConfigBuilder.build(c.outbound,19080,null))
                File(dir,"$sample-$i-last.json").writeText(XrayConfigBuilder.build(c.outbound,19082,19081))
                count++
            }
        }
        val local = """{"protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":19200,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}"""
        File(dir,"local-first.json").writeText(XrayConfigBuilder.build(local,19202,null))
        File(dir,"local-last.json").writeText(XrayConfigBuilder.build(local,19202,19201))
        assertTrue(count>=2)
    }
}
