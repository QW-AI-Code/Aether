package studio.cluvex.aether.custom

import org.junit.Test
import org.junit.Assert.assertEquals

class TabServerNumbersTest {
    @Test fun twelveServersAreNumberedOneToTwelve() {
        val ids = (1..12).map { "id$it" }
        assertEquals((1L..12L).toList(), tabServerNumbers(ids).values.toList())
    }
    @Test fun deletingAServerClosesTheGap() {
        val ids = (1..12).map { "id$it" }.filter { it != "id3" }
        val n = tabServerNumbers(ids)
        assertEquals(11L, n.getValue("id12"))
        assertEquals(3L, n.getValue("id4"))
    }
    @Test fun emptyTab() { assertEquals(emptyMap<String, Long>(), tabServerNumbers(emptyList())) }
}
