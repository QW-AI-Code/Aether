package studio.cluvex.aether.data

import org.junit.Assert.assertEquals
import org.junit.Test
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.model.ConnectionPreset
import studio.cluvex.aether.model.ConnectionProfile

/** Export -> import keeps the exact name and every non-private setting, including multi-line values. */
class PresetImportRoundTripTest {
    @Test fun `exported profile imports with same name and settings`() {
        val p = ConnectionProfile(
            routeBlock = "domain:a.com\ndomain:b.com", routeDirect = "geosite:ir\r\nfull:x.org",
            dnsServers = "1.1.1.1,8.8.8.8", killSwitch = true, mtu = 1280,
        )
        val src = ConnectionPreset("id-1", "Test", PresetProfileCodec.encode(p))
        val back = PresetTransferCodec.decode(PresetTransferCodec.encodeSelected(listOf(src), src.id)).single()
        assertEquals("Test", back.name)
        val q = ProfileCodec.decode(back.profile)
        assertEquals(p.routeBlock, q.routeBlock)
        assertEquals(p.routeDirect, q.routeDirect)
        assertEquals(p.dnsServers, q.dnsServers)
        assertEquals(true, q.killSwitch)
        assertEquals(1280, q.mtu)
    }
}
