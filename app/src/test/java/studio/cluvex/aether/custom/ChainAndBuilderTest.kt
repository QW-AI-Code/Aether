package studio.cluvex.aether.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ChainAndBuilderTest {

    @Test
    fun `chain labels read in traffic order`() {
        assertEquals("VLESS", ChainMode.SOLO.label("VLESS"))
        assertEquals("VLESS \u2192 Aether", ChainMode.CONFIG_AETHER.label("VLESS"))
        assertEquals("Aether \u2192 VLESS", ChainMode.AETHER_CONFIG.label("VLESS"))
        assertEquals("Npvt \u2192 Psiphon", ChainMode.CONFIG_PSIPHON.label("Npvt"))
        assertEquals("Tor \u2192 Trojan", ChainMode.TOR_CONFIG.label("Trojan"))
        assertEquals("Aether \u2192 Psiphon \u2192 VLESS", ChainMode.AETHER_PSIPHON_CONFIG.label("VLESS"))
        assertEquals("VLESS \u2192 Aether \u2192 Psiphon", ChainMode.CONFIG_AETHER_PSIPHON.label("VLESS"))
        assertEquals("VMess \u2192 Aether(WIREGUARD)", ChainMode.CONFIG_AETHER.label("VMess", "WIREGUARD"))
    }

    @Test
    fun `every mode has the right hop count and placement`() {
        assertEquals(15, ChainMode.entries.size)
        assertEquals(1, ChainMode.entries.count { it.hops == 1 })
        assertEquals(6, ChainMode.entries.count { it.hops == 2 })
        assertEquals(8, ChainMode.entries.count { it.hops == 3 })
        for (m in ChainMode.entries) {
            val arrows = m.label("C").split("\u2192").size
            assertEquals(m.name, m.hops, arrows)
            assertEquals(m, ChainMode.fromStoredName(m.name))
        }
        assertEquals(ChainMode.SOLO, ChainMode.fromStoredName("nonsense"))
    }

    @Test
    fun `compact labels get shorter`() {
        val forms = ChainMode.CONFIG_AETHER_PSIPHON.compactLabels("Shadowsocks", "WIREGUARD")
        assertEquals("Shadowsocks \u2192 Aether(WIREGUARD) \u2192 Psiphon", forms.first())
        assertEquals("Shadowsocks\u2192Aether\u2192Psiphon", forms.last())
        for (i in 1 until forms.size) assertTrue(forms[i].length < forms[i - 1].length)
    }

    private fun built(dialVia: Int?): JsonObject {
        val c = ConfigParser.parse(
            CustomConfigTestSupport.REALITY_VISION,
            ConfigSource.LINK,
            { CustomConfigTestSupport.tables },
        ).single()
        return Json.parseToJsonElement(XrayConfigBuilder.build(c.outbound, 18830, dialVia, listOf("192.0.2.53")))
            .jsonObject
    }

    @Test
    fun `solo config dials directly`() {
        val o = built(null)
        val inbound = o["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("127.0.0.1", inbound["listen"]!!.jsonPrimitive.content)
        assertEquals("18830", inbound["port"]!!.jsonPrimitive.content)
        val outs = o["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals("proxy", outs[0]["tag"]!!.jsonPrimitive.content)
        assertTrue(outs.none { it["tag"]!!.jsonPrimitive.content == XrayConfigBuilder.CHAIN_TAG })
        val sockopt = outs[0]["streamSettings"]!!.jsonObject["sockopt"]?.jsonObject
        assertNull(sockopt?.get("dialerProxy"))
        assertTrue(o["dns"].toString().contains("192.0.2.53"))
    }

    /**
     * Regression for the Config -> Tor stall: sniffing with destOverride swapped
     * a Tor relay's address for the fake SNI in Tor's link handshake.
     */
    @Test
    fun `inbound never rewrites the destination from a sniffed name`() {
        for (via in listOf(null, 1819)) {
            val inbound = built(via)["inbounds"]!!.jsonArray[0].jsonObject
            val sniffing = inbound["sniffing"]?.jsonObject
            assertTrue(sniffing == null || sniffing["enabled"]!!.jsonPrimitive.content == "false")
        }
    }

    /**
     * Regression for the `Npvt -> Tor` stall. The 8 s handshake budget cut Tor's
     * link handshake to a guard mid-TLS, which arti reports as "tls handshake
     * eof"; the 2 s / 5 s half-close budgets tore down silent-but-live channels.
     */
    @Test
    fun `chain timeouts are sized for a chain, not for a direct dial`() {
        for (via in listOf(null, 1819)) {
            val level = Json.parseToJsonElement(
                XrayConfigBuilder.build(outboundOf(), 18830, via),
            ).jsonObject["policy"]!!.jsonObject["levels"]!!.jsonObject["8"]!!.jsonObject
            assertEquals(
                XrayConfigBuilder.HANDSHAKE_SECONDS.toString(),
                level["handshake"]!!.jsonPrimitive.content,
            )
            assertEquals(
                XrayConfigBuilder.CONN_IDLE_SECONDS.toString(),
                level["connIdle"]!!.jsonPrimitive.content,
            )
            assertEquals("10", level["uplinkOnly"]!!.jsonPrimitive.content)
            assertEquals("30", level["downlinkOnly"]!!.jsonPrimitive.content)
            assertTrue(XrayConfigBuilder.HANDSHAKE_SECONDS >= 20)
            assertTrue(XrayConfigBuilder.CONN_IDLE_SECONDS >= 600)
        }
    }

    /**
     * Regression for `Tor -> Npvt` crawling: without Mux every flow paid a fresh
     * Tor stream plus a fresh handshake to the server.
     */
    @Test
    fun `a config behind a slow hop reuses one connection`() {
        val muxed = Json.parseToJsonElement(
            XrayConfigBuilder.build(
                outboundOf(CustomConfigTestSupport.XHTTP_CDN),
                18830,
                1819,
                muxConcurrency = XrayConfigBuilder.SLOW_CHAIN_MUX,
            ),
        ).jsonObject["outbounds"]!!.jsonArray[0].jsonObject["mux"]?.jsonObject
        assertEquals("true", muxed!!["enabled"]!!.jsonPrimitive.content)
        assertEquals(
            XrayConfigBuilder.SLOW_CHAIN_MUX.toString(),
            muxed["concurrency"]!!.jsonPrimitive.content,
        )
    }

    /** A direct config must not be muxed: there is no expensive hop to amortise. */
    @Test
    fun `mux stays off unless it is asked for`() {
        for (via in listOf(null, 1819)) {
            val out = Json.parseToJsonElement(XrayConfigBuilder.build(outboundOf(), 18830, via))
                .jsonObject["outbounds"]!!.jsonArray[0].jsonObject
            assertNull(out["mux"])
        }
    }

    /** Xray refuses Mux on an XTLS flow, so asking for it must be ignored, not obeyed. */
    @Test
    fun `mux is never put on an xtls flow`() {
        val out = Json.parseToJsonElement(
            XrayConfigBuilder.build(outboundOf(), 18830, 1819, muxConcurrency = 8),
        ).jsonObject["outbounds"]!!.jsonArray[0].jsonObject
        assertNull(out["mux"])
    }

    private fun outboundOf(link: String = CustomConfigTestSupport.REALITY_VISION): String =
        ConfigParser.parse(link, ConfigSource.LINK, { CustomConfigTestSupport.tables }).single().outbound

    @Test
    fun `last-hop config dials through the engine`() {
        val o = built(1819)
        val outs = o["outbounds"]!!.jsonArray.map { it.jsonObject }
        val sockopt = outs[0]["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertEquals(XrayConfigBuilder.CHAIN_TAG, sockopt["dialerProxy"]!!.jsonPrimitive.content)
        val chain = outs.single { it["tag"]!!.jsonPrimitive.content == XrayConfigBuilder.CHAIN_TAG }
        assertTrue(chain.toString().contains("1819"))
        assertTrue(o["dns"].toString().contains("tcp://"))
    }

    /**
     * Optional end-to-end check: with `XRAY_BIN` pointing at an Xray binary
     * (`xray run -test`), every generated config must be accepted by the core.
     * Skipped when the variable is not set, so CI does not need the binary.
     * `XRAY_EXTRA_INPUT` may name a local file of links / an npvt file to check too.
     */
    @Test
    fun `generated configs pass xray -test`() {
        val bin = System.getenv("XRAY_BIN")?.let(::File)
        assumeTrue(bin != null && bin.canExecute())
        val links = listOf(
            CustomConfigTestSupport.REALITY_VISION,
            CustomConfigTestSupport.TLS_VISION,
            CustomConfigTestSupport.REALITY_FIREFOX,
            CustomConfigTestSupport.XHTTP_CDN,
            "trojan://secret@t.example.com:443?sni=t.example.com&type=grpc&serviceName=svc#tr",
            "trojan://secret@t.example.com:443?type=ws&path=%2Fw&host=h.example.com#ws",
            "vless://${CustomConfigTestSupport.UUID}@h.example.com:443?type=httpupgrade&path=%2Fu&security=tls#hu",
            "vless://${CustomConfigTestSupport.UUID}@h.example.com:443?type=kcp&headerType=wechat-video#kcp",
            "wireguard://yAnz5TF%2BlXXJte14tji3zlMNq%2Bhd2rYUIgJBgB3fBmk%3D@wg.example.com:51820" +
                "?publickey=xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg%3D&address=10.0.0.2%2F32&mtu=1280#wg",
            "ss://" + java.util.Base64.getEncoder().encodeToString("aes-256-gcm:pw".toByteArray()) +
                "@ss.example.com:8388#ss",
        )
        val extra = System.getenv("XRAY_EXTRA_INPUT")
            ?.split(File.pathSeparator)?.map(::File)?.filter { it.isFile }.orEmpty()
        val tables = { CustomConfigTestSupport.tables }
        val configs = links.flatMap { ConfigParser.parse(it, ConfigSource.LINK, tables) } +
            extra.flatMap { ConfigParser.parse(it.readText(), ConfigSource.FILE, tables) } +
            ConfigParser.parse(CustomConfigTestSupport.resource("npvt/synthetic.npvt"), ConfigSource.FILE, tables)
        val keep = System.getenv("XRAY_DUMP_DIR")?.let(::File)
        val dir = keep?.apply { mkdirs() } ?: kotlin.io.path.createTempDirectory("xraytest").toFile()
        configs.forEachIndexed { i, c ->
            // Direct, chained, and chained WITH Mux - the three shapes a session
            // can actually run, so a knob the core rejects fails here and not on
            // a phone.
            for ((via, mux) in listOf(null to 0, 1819 to 0, 1819 to XrayConfigBuilder.SLOW_CHAIN_MUX)) {
                val f = File(dir, "c$i-${via ?: "direct"}-mux$mux.json")
                f.writeText(
                    XrayConfigBuilder.build(c.outbound, 18830, via, muxConcurrency = mux),
                )
                val p = ProcessBuilder(bin!!.absolutePath, "run", "-test", "-c", f.absolutePath)
                    .redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText()
                assertTrue(p.waitFor(30, TimeUnit.SECONDS))
                assertEquals("${c.name} via=$via mux=$mux:\n$text", 0, p.exitValue())
            }
        }
        println("xray -test accepted ${configs.size * 3} generated configs")
        if (keep == null) dir.deleteRecursively()
    }
}