package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test

class ServerPresentationRegressionTest {
    private fun config(address: String, server: String = "stale.workers.dev:2096") = CustomConfig("id", "ad @channel", ConfigProtocol.VLESS,
        ConfigFormat.XRAY_JSON, ConfigSource.FILE,
        """{"protocol":"vless","settings":{"vnext":[{"address":"$address","port":2096}]},"streamSettings":{"tlsSettings":{"serverName":"camouflage.example"}}}""", server = server)
    @Test fun actualOutboundWinsOverPollutedDisplayNameAndWorkerMetadata() {
        val c = config("www.speedtest.net")
        assertEquals("www.speedtest.net:2096", configDisplayEndpoint(c))
        assertEquals("VLESS S12 • www.speedtest.net:2096", serverTechnicalLabel(c.protocol.short, configDisplayEndpoint(c),12))
        assertTrue(c.outbound.contains("camouflage.example")) // Presentation never edits the dial target.
    }
    @Test fun workerOnlyAddressIsOmittedNotReplacedByCamouflageOrInventedIp() {
        val c = config("fxmrrich.haven-relock-myth.workers.dev")
        assertEquals("", configDisplayEndpoint(c))
        assertEquals("VLESS S3", serverTechnicalLabel("VLESS", configDisplayEndpoint(c),3))
        assertEquals("", cleanServerEndpoint("A.B.WORKERS.DEV.:443"))
    }
    @Test fun credentialsPathsSchemesAndBidiCannotLeakIntoTechnicalTitle() {
        listOf("https://bad.example:443/path", "id@bad.example:443", "good.example:443/path", "good.example:443?secret=1",
            "bad.example:443#advert", "good.example:0", "999.1.1.1:443", "\u202egood.example:443").forEach {
            assertEquals(it, "", cleanServerEndpoint(it))
        }
    }
    @Test fun ipv6CleanIpAndLockedDataAreHandledWithoutRenumberingOrDisclosure() {
        assertEquals("[2001:db8::1]:443", cleanServerEndpoint("[2001:db8::1]:443"))
        assertEquals("172.67.187.13:2096", configDisplayEndpoint(config("172.67.187.13")))
        assertEquals("", configDisplayEndpoint(config("203.0.113.1").copy(locked = true)))
        assertEquals("VLESS S7", serverTechnicalLabel("VLESS", "203.0.113.1:443",7,locked = true))
    }
}
