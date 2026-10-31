package studio.cluvex.aether.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import studio.cluvex.aether.custom.CustomConfigTestSupport.REALITY_FIREFOX
import studio.cluvex.aether.custom.CustomConfigTestSupport.REALITY_VISION
import studio.cluvex.aether.custom.CustomConfigTestSupport.TLS_VISION
import studio.cluvex.aether.custom.CustomConfigTestSupport.XHTTP_CDN
import java.util.Base64

class ConfigParserTest {

    private val tables = { CustomConfigTestSupport.tables }

    private fun parse(text: String, password: String? = null, now: Long = NOW) =
        ConfigParser.parse(text, ConfigSource.LINK, tables, password, now)

    private fun out(c: CustomConfig): JsonObject = Json.parseToJsonElement(c.outbound).jsonObject
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun `vless reality vision`() {
        val c = parse(REALITY_VISION).single()
        assertEquals(ConfigProtocol.VLESS, c.protocol)
        assertEquals("@reality-one", c.name)
        assertEquals("192.0.2.10:443", c.server)
        val o = out(c)
        val user = o.obj("settings")["vnext"]!!.jsonArray[0].jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("xtls-rprx-vision", user.s("flow"))
        val stream = o.obj("streamSettings")
        assertEquals("reality", stream.s("security"))
        val reality = stream.obj("realitySettings")
        assertEquals("yahoo.com", reality.s("serverName"))
        assertEquals(CustomConfigTestSupport.PBK, reality.s("publicKey"))
        assertEquals("c39cc7310a", reality.s("shortId"))
        assertEquals("chrome", reality.s("fingerprint"))
    }

    @Test
    fun `vless tls vision with alpn`() {
        val c = parse(TLS_VISION).single()
        val stream = out(c).obj("streamSettings")
        assertEquals("tls", stream.s("security"))
        val tls = stream.obj("tlsSettings")
        assertEquals("vision.example.net", tls.s("serverName"))
        assertEquals("h2", tls["alpn"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `vless xhttp behind a cdn`() {
        val c = parse(XHTTP_CDN).single()
        val stream = out(c).obj("streamSettings")
        assertEquals("xhttp", stream.s("network"))
        val x = stream.obj("xhttpSettings")
        assertEquals("/@chan/@chan", x.s("path"))
        assertEquals("front.example.net", x.s("host"))
        assertEquals("auto", x.s("mode"))
        assertEquals("sni.example.com", stream.obj("tlsSettings").s("serverName"))
    }

    @Test
    fun `a multi-line paste becomes one config per link`() {
        val all = parse(listOf(REALITY_VISION, TLS_VISION, "", REALITY_FIREFOX, XHTTP_CDN, "", "").joinToString("\n"))
        assertEquals(4, all.size)
        assertEquals(4, all.map { it.id }.toSet().size)
    }

    @Test
    fun `a base64 subscription is unpacked`() {
        val sub = Base64.getEncoder().encodeToString("$REALITY_VISION\n$XHTTP_CDN".toByteArray())
        assertEquals(2, parse(sub).size)
    }

    @Test
    fun `vmess v2rayN base64 json`() {
        val j = "{\"v\":\"2\",\"ps\":\"vm\",\"add\":\"vm.example.com\",\"port\":\"8443\"," +
            "\"id\":\"${CustomConfigTestSupport.UUID}\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\"," +
            "\"type\":\"none\",\"host\":\"h.example.com\",\"path\":\"/v\",\"tls\":\"tls\",\"sni\":\"h.example.com\"}"
        val c = parse("vmess://" + Base64.getEncoder().encodeToString(j.toByteArray())).single()
        assertEquals(ConfigProtocol.VMESS, c.protocol)
        assertEquals("vm", c.name)
        assertEquals("vm.example.com:8443", c.server)
        val stream = out(c).obj("streamSettings")
        assertEquals("ws", stream.s("network"))
        assertEquals("/v", stream.obj("wsSettings").s("path"))
    }

    @Test
    fun `trojan and shadowsocks`() {
        val t = parse("trojan://secret@t.example.com:443?sni=t.example.com&type=grpc&serviceName=svc#tr").single()
        assertEquals(ConfigProtocol.TROJAN, t.protocol)
        assertEquals("grpc", out(t).obj("streamSettings").s("network"))

        val userInfo = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("chacha20-ietf-poly1305:pass".toByteArray())
        val s = parse("ss://$userInfo@ss.example.com:8388#ss").single()
        assertEquals(ConfigProtocol.SHADOWSOCKS, s.protocol)
        val server = out(s).obj("settings")["servers"]!!.jsonArray[0].jsonObject
        assertEquals("chacha20-ietf-poly1305", server.s("method"))
        assertEquals("pass", server.s("password"))
        assertEquals("SS", s.chainLabel)
    }

    @Test
    fun `a full xray json config yields its proxy outbound`() {
        val json = "{\"outbounds\":[{\"tag\":\"direct\",\"protocol\":\"freedom\"}," +
            "{\"tag\":\"x\",\"protocol\":\"trojan\",\"settings\":{\"servers\":[{\"address\":\"j.example.com\"," +
            "\"port\":443,\"password\":\"p\"}]},\"streamSettings\":{\"network\":\"tcp\",\"security\":\"tls\"}}]}"
        val c = parse(json).single()
        assertEquals(ConfigFormat.XRAY_JSON, c.format)
        assertEquals(ConfigProtocol.TROJAN, c.protocol)
        assertEquals("j.example.com:443", c.server)
    }

    @Test
    fun `unsupported input is refused`() {
        for (bad in listOf("hello world", "vless://", "ss://bm9uZQ@x.example.com:1?plugin=v2ray-plugin")) {
            try {
                parse(bad)
                fail("accepted: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    // ---------------------------------------------------------------- npvt

    @Test
    fun `locked npvt hides its server and keeps the note`() {
        val c = parse(CustomConfigTestSupport.resource("npvt/synthetic.npvt")).single()
        assertEquals(ConfigFormat.NPVT, c.format)
        assertEquals(ConfigProtocol.VLESS, c.protocol)
        assertEquals("Npvt", c.chainLabel)
        assertTrue(c.locked)
        assertEquals("", c.raw)
        assertEquals("", c.server)
        assertEquals("hello", c.note)
        // ...but the outbound the core runs is complete.
        assertTrue(c.outbound.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(c.outbound.contains("\"ws\""))
    }

    @Test
    fun `npvt link form`() {
        val file = CustomConfigTestSupport.resource("npvt/synthetic.npvt").trim()
        val link = "npvt://" + Base64.getEncoder().encodeToString(file.toByteArray())
        assertEquals(1, parse(link).size)
    }

    @Test
    fun `unlocked npvt is editable and keeps insecure`() {
        val text = CustomConfigTestSupport.npvt(
            CustomConfigTestSupport.npvtServer("open", "{\"isLocked\":false,\"password\":\"\",\"expiryDate\":\"\",\"deviceIds\":\"\"}"),
            "{}",
        )
        val c = parse(text).single()
        assertFalse(c.locked)
        assertEquals("example.org:443", c.server)
        assertTrue(c.raw.startsWith("NPVT1"))
        val tls = out(c).obj("streamSettings").obj("tlsSettings")
        assertEquals("true", tls["allowInsecure"]!!.jsonPrimitive.content)
    }

    @Test
    fun `npvt password is enforced`() {
        val text = CustomConfigTestSupport.npvt(
            CustomConfigTestSupport.npvtServer("pw", "{\"isLocked\":true,\"password\":\"s3cret\"}"),
            "{}",
        )
        try {
            parse(text); fail("no password accepted")
        } catch (e: NpvtPasswordException) {
            assertFalse(e.wrong)
        }
        try {
            parse(text, password = "nope"); fail("wrong password accepted")
        } catch (e: NpvtPasswordException) {
            assertTrue(e.wrong)
        }
        assertEquals(1, parse(text, password = "s3cret").size)
    }

    @Test
    fun `npvt expiry, device binding and mobile-only are honoured`() {
        val expired = CustomConfigTestSupport.npvt(
            CustomConfigTestSupport.npvtServer("old", "{\"isLocked\":true,\"expiryDate\":\"2020-01-01\"}"),
            "{}",
        )
        try {
            parse(expired); fail("expired config accepted")
        } catch (_: ConfigImportException) {
        }

        val bound = CustomConfigTestSupport.npvt(
            CustomConfigTestSupport.npvtServer("dev", "{\"isLocked\":true,\"deviceIds\":\"abc\"}"),
            "{}",
        )
        try {
            parse(bound); fail("device-bound config accepted")
        } catch (_: ConfigImportException) {
        }

        val mobile = CustomConfigTestSupport.npvt(
            CustomConfigTestSupport.npvtServer(
                "mob",
                "{\"isLocked\":true,\"onlyMobileNetwork\":true,\"expiryDate\":\"2099-01-01\"}",
            ),
            "{}",
        )
        val c = parse(mobile).single()
        assertTrue(c.mobileOnly)
        assertTrue(c.expiresAt > NOW)
        assertFalse(c.isExpired(NOW))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}