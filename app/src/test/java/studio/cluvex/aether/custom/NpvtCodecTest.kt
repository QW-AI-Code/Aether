package studio.cluvex.aether.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The NapsternetV decoder against a known answer: `synthetic.npvt` was produced
 * by the reference (Python) white-box implementation, so these tests pin the
 * Kotlin tables and cipher to it byte for byte.
 */
class NpvtCodecTest {

    private val tables = CustomConfigTestSupport.tables

    @Test
    fun `known answer file decrypts to its three parts`() {
        val text = CustomConfigTestSupport.resource("npvt/synthetic.npvt")
        assertTrue(NpvtCodec.looksLike(text))
        val decoded = NpvtCodec.decode(text, tables)
        assertEquals(1, decoded.version)
        assertTrue(decoded.servers.contains("\"name\": \"Synthetic test\""))
        assertTrue(decoded.servers.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(decoded.lock.contains("\"message\": \"hello\""))
    }

    @Test
    fun `encrypting with the same tables round trips`() {
        val text = CustomConfigTestSupport.npvt("[]", "{\"isLocked\":false}")
        val decoded = NpvtCodec.decode(text, tables)
        assertEquals("[]", decoded.servers)
        assertEquals("{\"isLocked\":false}", decoded.lock)
    }

    @Test
    fun `garbage is rejected with a reason`() {
        try {
            NpvtCodec.decode("NPVT1\nnot-base64!!,x", tables)
            fail("expected a failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}