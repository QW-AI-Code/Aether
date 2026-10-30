package studio.cluvex.aether.model

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
// ⚠ VERIFY: copy the exact ProfileCodec import line used in core/AetherController.kt
import studio.cluvex.aether.core.ProfileCodec

class PersianLocaleSafetyTest {
    private val nonAsciiDigit = Regex("[\u0660-\u0669\u06F0-\u06F9]")
    private lateinit var saved: Locale

    @Before fun setUp() {
        saved = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("fa-IR"))
    }

    @After fun tearDown() = Locale.setDefault(saved)

    @Test fun `profile codec emits only ascii digits under fa-IR`() {
        val encoded = ProfileCodec.encode(ConnectionProfile())
        assertFalse("Persian/Arabic digits leaked into ProfileCodec: $encoded", nonAsciiDigit.containsMatchIn(encoded))
    }

    @Test fun `engine args emit only ascii digits under fa-IR`() {
        // ⚠ VERIFY: if toArgs() takes parameters, pass the same values MainActivity/service uses.
        val args = ConnectionProfile().toArgs()
        val bad = args.filter { nonAsciiDigit.containsMatchIn(it) }
        assertFalse("Persian/Arabic digits leaked into engine args: $bad", bad.isNotEmpty())
    }
}
