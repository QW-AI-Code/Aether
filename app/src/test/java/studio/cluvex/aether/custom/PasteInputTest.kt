package studio.cluvex.aether.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.custom.CustomConfigTestSupport.REALITY_VISION
import studio.cluvex.aether.custom.CustomConfigTestSupport.TLS_VISION

/**
 * Text as it really arrives from the clipboard: wrapped in RTL marks by a
 * messenger, inside a sentence, two links on one line, quoted, with CRLF.
 */
class PasteInputTest {

    private val tables = { CustomConfigTestSupport.tables }

    private fun parse(text: String) = ConfigParser.parse(text, ConfigSource.LINK, tables, null, 1_700_000_000_000L)

    @Test
    fun `invisible marks around a link are removed`() {
        val c = parse("\u200F\u2067$REALITY_VISION\u2069\u200E").single()
        assertEquals("192.0.2.10:443", c.server)
    }

    @Test
    fun `bom and zero-width space before the scheme`() {
        assertEquals(1, parse("\uFEFF\u200B$TLS_VISION").size)
    }

    @Test
    fun `a link inside a sentence`() {
        val c = parse("Config: $REALITY_VISION").single()
        assertEquals("192.0.2.10:443", c.server)
    }

    @Test
    fun `two links on one line and CRLF line endings`() {
        val text = "$REALITY_VISION $TLS_VISION\r\n\r\n$TLS_VISION\r\n"
        assertEquals(3, parse(text).size)
    }

    @Test
    fun `quotes and brackets around a link without a name`() {
        val bare = REALITY_VISION.substringBefore('#')
        val c = parse("\"$bare\",").single()
        assertEquals("192.0.2.10:443", c.server)
        assertEquals(1, parse("($bare)").size)
    }

    @Test
    fun `vless does not also count as an ss link`() {
        assertEquals(listOf(REALITY_VISION), ConfigParser.extractLinks(REALITY_VISION))
    }

    @Test
    fun `cleanInput keeps zwnj in persian names`() {
        val cleaned = ConfigParser.cleanInput("\u200Fسرور\u200Cها\u00A0")
        assertEquals("سرور\u200Cها", cleaned)
        assertTrue(ConfigParser.isShareLink("\u200F$TLS_VISION"))
    }
}
