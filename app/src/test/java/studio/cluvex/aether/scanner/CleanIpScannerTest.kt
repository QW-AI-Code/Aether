package studio.cluvex.aether.scanner
import org.junit.Assert.*
import org.junit.Test

class CleanIpScannerTest {
    @Test fun referenceProfilesKeepTheirBudgets() {
        assertEquals(120, ScanProfile.FAST.candidateBudget)
        assertEquals(300, ScanProfile.BALANCED.candidateBudget)
        assertEquals(600, ScanProfile.DEEP.candidateBudget)
        assertEquals(ScanProfile.BALANCED, ScanProfile.fromName("invalid"))
        assertEquals(ScanProfile.DEEP, ScanProfile.fromName("deep"))
    }
    @Test fun randomCandidatesStayInsideCloudflareRanges() {
        repeat(5000) { assertTrue(CleanIpScanner.isCloudflareIp(CleanIpScanner.randomIp())) }
        listOf("127.0.0.1", "10.0.0.1", "192.168.1.1", "1.1.1.1", "104.16.999.1", "104.16.-1.1", "x", "104.16.1.1/8")
            .forEach { assertFalse(it, CleanIpScanner.isCloudflareIp(it)) }
    }
    @Test fun neighborsAreUniqueBoundedAndPublic() {
        val neighbors = CleanIpScanner.neighborsAround("104.16.0.1", 32, 12)
        assertEquals(12, neighbors.size); assertEquals(12, neighbors.distinct().size)
        assertFalse(neighbors.contains("104.16.0.1"))
        neighbors.forEach { assertTrue(CleanIpScanner.isCloudflareIp(it)) }
        assertTrue(CleanIpScanner.neighborsAround("104.16.0.1", 32, 0).isEmpty())
    }
    @Test fun latencyBucketDominatesThroughputAsInReference() {
        val slow = CleanIpScanner.Result("104.16.0.1", 1100, downloadBps = 99999999)
        val fast = CleanIpScanner.Result("104.16.0.2", 100, downloadBps = 100)
        assertEquals(fast, listOf(slow, fast).sortedWith(CleanIpScanner.rankComparator).first())
    }
    @Test fun parsesColoEvidenceNotBareTlsSuccess() {
        assertEquals("FRA", CleanIpScanner.parseColoRay("abcdef-FRA"))
        assertEquals("", CleanIpScanner.parseColoRay("invalid"))
        assertEquals("AMS", CleanIpScanner.parseColoCdn("ip=1.2.3.4\ncolo=AMS\r\n"))
        assertEquals("", CleanIpScanner.parseColoCdn("HTTP 200"))
    }
}
