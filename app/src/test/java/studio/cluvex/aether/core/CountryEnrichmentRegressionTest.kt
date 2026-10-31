package studio.cluvex.aether.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.model.ConnectionState

class CountryEnrichmentRegressionTest {
    @Test fun explicitGeoProviderParsesValidatedIsoCountryWithoutBreakingIpOnlyProvider() {
        assertEquals(IpInfo("192.0.2.1", "DE"), NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n{\"success\":true,\"ip\":\"192.0.2.1\",\"country_code\":\"de\"}"))
        assertEquals(IpInfo("192.0.2.1", null), NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n{\"ip\":\"192.0.2.1\",\"country_code\":\"ZZ\"}"))
        assertNull(NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n{\"success\":false,\"ip\":\"192.0.2.1\",\"country_code\":\"DE\"}"))
    }
    @Test fun countryIsNeverBorrowedFromDifferentIpAndIpv6CanonicalFormsMatch() {
        assertNull(NetProbe.countryForIp("192.0.2.1", IpInfo("192.0.2.2", "DE")))
        assertNull(NetProbe.countryForIp("evil.example", IpInfo("192.0.2.1", "DE")))
        assertEquals("DE", NetProbe.countryForIp("2001:db8::1", IpInfo("2001:db8:0:0:0:0:0:1", "de")))
    }
    @Test fun monitoringRefinesPrepublishedIpWithoutReplacingItOrBlockingConnection() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        var ip = IpEndpoint("192.0.2.1", null, true)
        val got = CompletableDeferred<IpEndpoint>()
        var calls = 0
        val job = launch {
            TunnelExitMonitor.watch(states, { ip }, {}, { ip = it; got.complete(it) }, fetch = { error("Do not replace known IP") }, retryMs = 0,
                refine = { _, known -> if (++calls == 1) null else known.copy(countryCode = "DE") })
        }
        assertEquals("DE", withTimeout(2000) { got.await() }.countryCode)
        assertEquals("192.0.2.1", ip.ip); assertTrue(states.value is ConnectionState.Connected)
        assertEquals(2, calls); job.cancelAndJoin()
    }
}
