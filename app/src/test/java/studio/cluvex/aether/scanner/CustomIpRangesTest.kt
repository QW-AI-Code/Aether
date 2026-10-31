package studio.cluvex.aether.scanner

import org.junit.Assert.*
import org.junit.Test

class CustomIpRangesTest {
    @Test fun parsesCidrSingleAndSpan() {
        val p = CustomIpRanges.parse("104.16.0.0/30, 104.17.1.5; 104.18.1.1-104.18.1.80")
        assertTrue(p.invalid.isEmpty())
        assertEquals(3, p.ranges.size)
        assertEquals(2L, p.ranges[0].size)          // /30 minus network+broadcast
        assertEquals(1L, p.ranges[1].size)
        assertEquals(80L, p.ranges[2].size)
    }

    @Test fun rejectsPrivateLoopbackAndMulticast() {
        val p = CustomIpRanges.parse("10.0.0.1 127.0.0.1 192.168.1.0/24 172.16.5.5 169.254.1.1 224.0.0.1 0.0.0.0/8")
        assertTrue(p.ranges.isEmpty())
        assertEquals(7, p.invalid.size)
    }

    @Test fun rejectsMalformedEntries() {
        val p = CustomIpRanges.parse("300.1.1.1 1.2.3 104.16.0.0/7 104.16.0.0/x 104.16.0.9-104.16.0.1 abc")
        assertTrue(p.ranges.isEmpty())
        assertEquals(6, p.invalid.size)
    }

    @Test fun acceptsPersianDigitsAndMergesOverlaps() {
        val p = CustomIpRanges.parse("۱۰۴.۱۶.۱.۱-۱۰۴.۱۶.۱.۱۰\n104.16.1.5-104.16.1.20\n104.16.1.21")
        assertEquals(1, p.ranges.size)
        assertEquals(21L, p.ranges[0].size)
    }

    @Test fun addressAtWalksAcrossRanges() {
        val r = CustomIpRanges.rangesOf("104.16.1.1-104.16.1.2 104.20.0.7")
        assertEquals(3L, CustomIpRanges.total(r))
        assertEquals("104.16.1.2", CustomIpRanges.longToIp(CustomIpRanges.addressAt(r, 1)))
        assertEquals("104.20.0.7", CustomIpRanges.longToIp(CustomIpRanges.addressAt(r, 2)))
    }

    @Test fun capsEntryCount() {
        val text = (1..150).joinToString(" ") { "104.16.${it}.1" }
        val p = CustomIpRanges.parse(text)
        assertEquals(CustomIpRanges.MAX_ENTRIES, p.ranges.size)
        assertEquals(50, p.invalid.size)
    }
}
