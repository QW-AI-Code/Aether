package studio.cluvex.aether.custom

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NpvoCodecTest {
    private fun enc(s: String) = "npvs1:" + Base64.getEncoder().encodeToString(s.toByteArray())
    private fun input() = "NPVO1\n" + buildJsonObject { put("configs", JsonArray((1..2).map { n -> buildJsonObject {
        put("name", "سرور $n"); put("address", "display.invalid:1")
        put("v2rayProfile", buildJsonObject {
            put("configType", 5); put("server", enc("server$n.invalid")); put("serverPort", enc("443"))
            put("password", enc("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")); put("method", enc("none"))
            put("network", enc("ws")); put("security", enc("tls")); put("path", enc("/ws?n=$n"))
            put("host", enc("cdn.invalid")); put("sni", enc("cdn.invalid")); put("alpn", enc("http/1.1"))
            put("fingerPrint", enc("chrome")); put("tlsAllowInsecure", false)
        })
    } })) }
    private fun parse(text: String) = ConfigParser.parseBytes(text.toByteArray(), ConfigSource.FILE,
        { error("NPVO must not load legacy crypto tables") }, { error("NPVO must not load binary crypto tables") })

    @Test fun `text byte BOM CRLF and subscription paths share the same converter`() {
        val sample = input()
        for (text in listOf(sample, "\uFEFF" + sample.replace("\n", "\r\n"),
            Base64.getEncoder().encodeToString(sample.toByteArray()))) {
            val result = parse(text)
            assertEquals(2, result.size)
            assertEquals(listOf("server1.invalid:443", "server2.invalid:443"), result.map { it.server })
            assertTrue(result.all { it.format == ConfigFormat.NPVT && it.protocol == ConfigProtocol.VLESS })
            assertTrue(result.none { "npvs1:" in it.raw || "npvs1:" in it.outbound })
        }
    }
    @Test fun `each editable record reparses itself not the first record`() {
        val result = parse(input())
        for (config in result) {
            val again = parse(config.raw).single()
            assertEquals(config.outbound, again.outbound)
            assertEquals(config.server, again.server)
        }
    }
    @Test fun `persian locale produces identical outbounds`() {
        val expected = parse(input()).map { it.outbound }
        val saved = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("fa-IR")); assertEquals(expected, parse(input()).map { it.outbound }) }
        finally { Locale.setDefault(saved) }
    }
    @Test fun `header and malformed input are rejected`() {
        assertFalse(NpvoCodec.looksLike("NPVO10\n{}"))
        val bad = listOf("NPVO1", "NPVO1\n[]", "NPVO1\n{\"configs\":[]}",
            input().replace(enc("443"), "npvs1:%%%"), input().replace(enc("443"), enc("70000")),
            input().replace(enc("443"), "npvs1:/w=="), input().replace(enc("443"), "npvs1:NDQz===="),
            input().replace("\"configs\":", "\"policy\":{},\"configs\":"),
            input().replace("\"configType\":", "\"isLocked\":true,\"configType\":"),
            "NPVO1\n" + "[".repeat(60) + "]".repeat(60), "NPVO1\n" + "x".repeat(2*1024*1024))
        for (text in bad) try { NpvoCodec.decode(text); fail("Malformed input accepted") }
            catch (_: ConfigImportException) { }
    }
    @Test fun `only whole-value profile sentinels are decoded`() {
        val raw = input().replace("سرور 1", "literal npvs1:d3M=")
        assertEquals("literal npvs1:d3M=", parse(raw).first().name)
        assertEquals("ws", NpvoCodec.decodeSentinel("npvs1:d3M="))
        assertNull(NpvoCodec.decodeSentinel("prefix npvs1:d3M="))
    }
    @Test fun `original private config bytes import directly in both locales`() {
        val path = System.getenv("AETHER_PRIVATE_NPVO_FILE")
        assumeTrue("Original sample is private and opt-in", path != null)
        val bytes = File(path!!).readBytes()
        assertEquals(1350, bytes.size)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }
        assertEquals("06e46f2484f61954eb1d48f145119fa6c441a0483a2937710bd7f3e6f26acfb3", hash)
        val saved = Locale.getDefault()
        try { for (locale in listOf("en-US", "fa-IR")) {
            Locale.setDefault(Locale.forLanguageTag(locale))
            val configs = ConfigParser.parseBytes(bytes, ConfigSource.FILE, { error("No legacy tables") }, { error("No binary tables") })
            assertEquals(2, configs.size)
            assertTrue(configs.all { it.protocol == ConfigProtocol.VLESS && !it.locked })
            configs.forEach { c ->
                val o = Json.parseToJsonElement(c.outbound).jsonObject
                assertEquals("ws", o["streamSettings"]!!.jsonObject["network"]!!.jsonPrimitive.content)
                assertEquals("tls", o["streamSettings"]!!.jsonObject["security"]!!.jsonPrimitive.content)
                assertEquals(c.outbound, parse(c.raw).single().outbound)
            }
            println("PRIVATE_NPVO_IMPORT locale=$locale bytes=1350 records=2 protocol=VLESS network=ws security=tls direct_parseBytes=PASS")
        } } finally { Locale.setDefault(saved) }
    }
}
