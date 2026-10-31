package studio.cluvex.aether.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * `Config -> Tor`: when Tor dials out through a custom config's local proxy the
 * engine is told to send relay TLS handshakes without SNI (a sniffing V2Ray/Xray
 * server would otherwise replace the relay's address with arti's fake name).
 */
class TorUpstreamEnvTest {

    @Test
    fun `tor behind an upstream proxy drops the relay SNI`() {
        val env = ConnectionProfile(
            backend = TransportBackend.TOR,
            upstreamProxy = "socks5://127.0.0.1:18830",
        ).toEnv()
        assertEquals("off", env[ConnectionProfile.TOR_LINK_SNI_ENV])
    }

    @Test
    fun `tor without an upstream proxy keeps the default handshake`() {
        val env = ConnectionProfile(backend = TransportBackend.TOR).toEnv()
        assertFalse(env.containsKey(ConnectionProfile.TOR_LINK_SNI_ENV))
    }

    @Test
    fun `no tor, no tor switch`() {
        val env = ConnectionProfile(
            backend = TransportBackend.AETHER,
            upstreamProxy = "socks5://127.0.0.1:18830",
        ).toEnv()
        assertFalse(env.containsKey(ConnectionProfile.TOR_LINK_SNI_ENV))
    }
}
