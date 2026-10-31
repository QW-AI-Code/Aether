package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NpvtBinaryTest {
    @Test fun `binary marker is detected without lossy text conversion`() {
        val bytes = byteArrayOf(78, 80, 86, 83, 5, 0, 0, 2, 91, -1)
        assertTrue(NpvtCodec.isBinary(bytes))
        try { NpvtCodec.decodeFileText(bytes); fail("Unknown cipher must not become a config") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("NPVS binary version 5")) }
    }
    @Test fun `truncated binary header is rejected`() {
        try { NpvtCodec.decodeFileText("NPVS".toByteArray()); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("truncated")) }
    }
    @Test fun `legacy file bytes remain unchanged`() {
        val text = CustomConfigTestSupport.resource("npvt/synthetic.npvt")
        assertEquals(text, NpvtCodec.decodeFileText(text.toByteArray()))
    }
    // Private server credentials must not be copied into the released source tree.
    @Test fun `supplied legacy fixture decrypts and imports`() {
        val dir = System.getenv("AETHER_PRIVATE_FIXTURES")
        assumeTrue("Private fixtures are opt-in", dir != null)
        val text = NpvtCodec.decodeFileText(File(dir!!, "legacy.npvt").readBytes())
        val configs = ConfigParser.parse(text, ConfigSource.FILE, { CustomConfigTestSupport.tables })
        assertTrue(configs.isNotEmpty())
        assertTrue(configs.all { it.format == ConfigFormat.NPVT && it.outbound.isNotBlank() })
    }
    private fun gen2Tables(): NpvtCodec.Tables {
        val file = listOf(File("src/main/assets/npvs/gen2-wb.bin"), File("app/src/main/assets/npvs/gen2-wb.bin")).first { it.exists() }
        return NpvtCodec.Tables.parse(file.readBytes())
    }
    @Test fun `generation 2 whitebox matches published known answer`() {
        val actual = gen2Tables().encryptBlock(IntArray(16) { it }).map { it.toByte() }.toByteArray()
        assertEquals("f659c73d8c0fa5150e3254dfe0a1106d", actual.joinToString("") { "%02x".format(it) })
    }
    @Test fun `sentinel decoding is UTF8 and URL safe`() {
        assertEquals("ws", NpvsCodec.decodeSentinels("npvs1:d3M="))
        assertEquals("none", NpvsCodec.decodeSentinels("npvs1:bm9uZQ=="))
    }
    @Test fun `supplied NPVS fixture authenticates decrypts and imports`() {
        val dir = System.getenv("AETHER_PRIVATE_FIXTURES")
        assumeTrue("Private fixtures are opt-in", dir != null)
        val bytes = File(dir!!, "binary.npvt").readBytes()
        val tables = gen2Tables()
        val configs = ConfigParser.parseBytes(bytes, ConfigSource.FILE,
            { CustomConfigTestSupport.tables }, { tables })
        assertEquals(1, configs.size)
        val config = configs.single()
        assertEquals(ConfigProtocol.VLESS, config.protocol)
        assertEquals(ConfigFormat.NPVT, config.format)
        assertTrue(config.locked)
        assertEquals("", config.raw)
        assertEquals("", config.server)
        assertTrue(config.outbound.contains("pinnedPeerCertSha256"))
        System.getenv("XRAY_BIN")?.let { executable ->
            val file = File.createTempFile("npvs-core-test", ".json")
            try {
                file.writeText(RealDelay.buildConfig(listOf(RealDelay.Item(config.id, config.outbound) to 26001), emptyList()))
                val process = ProcessBuilder(executable, "run", "-test", "-c", file.path)
                    .redirectErrorStream(true).start()
                process.inputStream.use { it.readBytes() } // never print private config/core diagnostics
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("Converted NPVS must be accepted by the real Xray core", 0, process.exitValue())
            } finally { file.delete() }
        }
        val corrupt = bytes.clone().also { it[it.size - 70] = (it[it.size - 70].toInt() xor 1).toByte() }
        try { NpvsCodec.decode(corrupt, tables); fail("Tampered file accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("signature")) }
        try { NpvsCodec.decode(bytes + byteArrayOf(0), tables); fail("Trailing data accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
