package studio.cluvex.aether.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests that the model allow-list holds on every path into the picker.
 *
 * The requirement is "only these five, even when the user refreshes the list",
 * and the way that requirement breaks is not by the filter being wrong - it is by
 * one of the four entry points not calling it. So these tests are written against
 * the shapes the data arrives in, `models/`-prefixed and unprefixed, fresh and
 * cached.
 */
class AiModelPolicyTest {

    private fun model(id: String, chat: Boolean = true) =
        GeminiModel(id, id, "", 0, 0, chatCapable = chat)

    @Test
    fun `only the five supported models survive`() {
        val discovered = listOf(
            model("models/gemini-3.1-flash-lite"),
            model("models/imagen-4.0-generate"),
            model("models/gemini-3.8-flash"),
            model("models/veo-3.1-generate"),
            model("models/gemini-2.5-pro"),
            model("models/text-embedding-004"),
            model("models/gemini-flash-lite-latest"),
        )
        assertEquals(
            listOf("gemini-3.8-flash", "gemini-3.1-flash-lite", "gemini-flash-lite-latest"),
            AiModelPolicy.filter(discovered).map { it.id },
        )
    }

    @Test
    fun `ordering is newest first and not lexical`() {
        // The whole reason the order is a rank lookup: sorted as text, "3.1" comes
        // before "3.5" and "flash-lite-latest" comes after both.
        val shuffled = AiModelPolicy.ALLOWED.reversed().map { model(it) }
        assertEquals(AiModelPolicy.ALLOWED, AiModelPolicy.filter(shuffled).map { it.id })
    }

    @Test
    fun `the cached id path is filtered too`() {
        // A cache written by 1.2.8 holds everything the key could see.
        val cached = listOf("gemini-2.5-pro", "imagen-4.0-generate", "gemini-3.5-flash-lite")
        assertEquals(listOf("gemini-3.5-flash-lite"), AiModelPolicy.filterIds(cached))
    }

    @Test
    fun `duplicates from paginated discovery collapse`() {
        val paged = listOf(model("gemini-3.8-flash"), model("models/gemini-3.8-flash"))
        assertEquals(1, AiModelPolicy.filter(paged).size)
    }

    @Test
    fun `display numbers are fixed positions not list indices`() {
        // Model 1 is unavailable to this key; the others must still read the number
        // of their POSITION in ALLOWED, not of their place in what this key offers.
        //
        // 1.3.1: gemini-3.7-flash was inserted at rank 2 (it shipped 2026-08-13 and
        // belongs between 3.8-flash and 3.5-flash-lite), so everything after it
        // moved down one. That renumbering is deliberate and harmless: the number is
        // a label in the model picker, while the SELECTION is stored by id - see
        // AiSession.effectiveModel - so nobody's saved choice moves with it. Rank
        // order is what this file is for, and rank order has to follow the models.
        assertEquals(1, AiModelPolicy.displayNumber("models/gemini-3.8-flash"))
        assertEquals(2, AiModelPolicy.displayNumber("gemini-3.7-flash"))
        assertEquals(3, AiModelPolicy.displayNumber("gemini-3.5-flash-lite"))
        assertEquals(0, AiModelPolicy.displayNumber("gemini-2.5-pro"))
    }

    @Test
    fun `default pick is 3_1 flash-lite when the key can see it`() {
        // 1.4.0-r5: the default is the model with the highest free daily limit,
        // not the newest one.
        val all = AiModelPolicy.ALLOWED.map { model("models/$it") }
        assertEquals("gemini-3.1-flash-lite", AiModelPolicy.pickDefault(all))
        assertEquals("gemini-3.1-flash-lite", AiModelPolicy.pickDefaultId(AiModelPolicy.ALLOWED))
    }

    @Test
    fun `default pick falls back through the flash-lite family, then display order`() {
        val noStable = listOf(model("gemini-3.8-flash"), model("gemini-3.1-flash-lite-preview"))
        assertEquals("gemini-3.1-flash-lite-preview", AiModelPolicy.pickDefault(noStable))
        val available = listOf(model("gemini-flash-lite-latest"), model("gemini-3.5-flash-lite"))
        assertEquals("gemini-flash-lite-latest", AiModelPolicy.pickDefault(available))
        val fastOnly = listOf(model("gemini-3.7-flash"), model("gemini-3.8-flash"))
        assertEquals("gemini-3.8-flash", AiModelPolicy.pickDefault(fastOnly))
        assertEquals(null, AiModelPolicy.pickDefault(listOf(model("gemini-2.5-pro"))))
    }

    @Test
    fun `every allowed model has a picker description tier`() {
        AiModelPolicy.ALLOWED.forEach { assertTrue(it, AiModelPolicy.tier(it) != null) }
        assertEquals(AiModelPolicy.Tier.RECOMMENDED_DEFAULT, AiModelPolicy.tier("models/gemini-3.1-flash-lite"))
        assertEquals(AiModelPolicy.Tier.FAST_LOW_QUOTA, AiModelPolicy.tier("gemini-3.8-flash"))
        assertEquals(AiModelPolicy.Tier.FAST_LOW_QUOTA, AiModelPolicy.tier("gemini-3.7-flash"))
        assertEquals(AiModelPolicy.Tier.FAST_LOW_QUOTA, AiModelPolicy.tier("gemini-3.5-flash-lite"))
    }

    @Test
    fun `unsupported ids are rejected wherever they come from`() {
        assertFalse(AiModelPolicy.isAllowed("gemini-3-flash-preview"))
        assertTrue(AiModelPolicy.isAllowed("  models/gemini-3.1-flash-lite-preview  "))
    }
}
