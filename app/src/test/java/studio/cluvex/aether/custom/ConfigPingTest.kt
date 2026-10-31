package studio.cluvex.aether.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The rules behind the ping badge and its verdict.
 *
 * These are unit tests on purpose: the difference between "server down" and
 * "filtered" is a judgement the user acts on, and it must not be reachable only
 * from a real censored network.
 */
class ConfigPingTest {

    private fun endpoint(link: String) = ConfigEndpoints.of(
        ConfigParser.parse(link, ConfigSource.LINK, { CustomConfigTestSupport.tables }).single(),
    )

    @Test
    fun `endpoint of a reality config carries its sni and asks for a handshake`() {
        val ep = endpoint(CustomConfigTestSupport.REALITY_VISION)
        assertNotNull(ep)
        requireNotNull(ep)
        assertTrue(ep.port in 1..65535)
        assertTrue(ep.tls)
        assertTrue(ep.sniOrHost.isNotBlank())
    }

    @Test
    fun `a plain socks config needs no handshake`() {
        val ep = endpoint("socks://user:pass@203.0.113.9:1080#s")
        requireNotNull(ep)
        assertEquals("203.0.113.9", ep.host)
        assertEquals(1080, ep.port)
        assertFalse(ep.tls)
        assertTrue(ep.isIp)
    }

    @Test
    fun `an outbound with no server has no endpoint`() {
        assertNull(ConfigEndpoints.ofOutbound("""{"protocol":"freedom","settings":{}}"""))
        assertNull(ConfigEndpoints.ofOutbound("not json"))
    }

    @Test
    fun `a reachable server is OK and everything else reports minus one`() {
        assertEquals(
            ConfigPing.PingStatus.OK,
            ConfigPing.classify(ConfigPing.Outcome.CONNECTED, networkUp = true),
        )
        assertEquals(-1L, ConfigPing.FAILED_MS)
    }

    /** A dead address: nothing the user can do, and chaining it will not help. */
    @Test
    fun `a gone name, a refused port and an unreachable host read as offline`() {
        for (outcome in listOf(
            ConfigPing.Outcome.NAME_GONE,
            ConfigPing.Outcome.REFUSED,
            ConfigPing.Outcome.UNREACHABLE,
        )) {
            assertEquals(
                outcome.name,
                ConfigPing.PingStatus.OFFLINE,
                ConfigPing.classify(outcome, networkUp = true),
            )
        }
    }

    /** A live server this network will not carry: exactly what a chain is for. */
    @Test
    fun `a dropped SYN, a reset handshake and a sinkholed name read as filtered`() {
        for (outcome in listOf(
            ConfigPing.Outcome.TIMED_OUT,
            ConfigPing.Outcome.RESET,
            ConfigPing.Outcome.NAME_SINKHOLED,
        )) {
            assertEquals(
                outcome.name,
                ConfigPing.PingStatus.BLOCKED,
                ConfigPing.classify(outcome, networkUp = true),
            )
        }
    }

    @Test
    fun `with no internet of its own the app blames nothing`() {
        for (outcome in ConfigPing.Outcome.entries.filter { it != ConfigPing.Outcome.CONNECTED }) {
            assertEquals(
                ConfigPing.PingStatus.NO_NETWORK,
                ConfigPing.classify(outcome, networkUp = false),
            )
        }
    }

    @Test
    fun `the censor's block page addresses are not mistaken for a server`() {
        assertTrue(ConfigPing.isSinkhole("10.10.34.36"))
        assertTrue(ConfigPing.isSinkhole("0.0.0.0"))
        assertTrue(ConfigPing.isSinkhole("127.0.0.1"))
        assertFalse(ConfigPing.isSinkhole("31.77.13.19"))
    }

    @Test
    fun `socket errors map onto the outcome they describe`() {
        assertEquals(ConfigPing.Outcome.NAME_GONE, ConfigPing.outcomeOf(UnknownHostException("nope")))
        assertEquals(ConfigPing.Outcome.TIMED_OUT, ConfigPing.outcomeOf(SocketTimeoutException("connect timed out")))
        assertEquals(ConfigPing.Outcome.UNREACHABLE, ConfigPing.outcomeOf(NoRouteToHostException("no route")))
        assertEquals(ConfigPing.Outcome.REFUSED, ConfigPing.outcomeOf(ConnectException("Connection refused")))
        assertEquals(ConfigPing.Outcome.RESET, ConfigPing.outcomeOf(javax.net.ssl.SSLException("Connection reset")))
    }

    /** A port a config cannot have is a dead config, not a measurement. */
    @Test
    fun `an impossible endpoint fails without touching the network`() {
        val report = ConfigPing.measure(ConfigEndpoint("", 0), networkUp = true)
        assertEquals(ConfigPing.FAILED_MS, report.ms)
        assertEquals(ConfigPing.PingStatus.OFFLINE, report.status)
    }

    @Test
    fun `an mtproto proxy is refused with a reason, not a shrug`() {
        for (link in listOf(
            "tg://proxy?server=web.arixo.shop&port=443&secret=dd27e57f496bbf279605f0198ea6d26067",
            "https://t.me/proxy?server=1.2.3.4&port=4455&secret=dd104462821249bd7ac519130220c25d09",
        )) {
            assertTrue(link, ConfigParser.looksLikeMtProto(link))
            val error = runCatching {
                ConfigParser.parse(link, ConfigSource.LINK, { CustomConfigTestSupport.tables })
            }.exceptionOrNull()
            assertNotNull(link, error)
            assertTrue(link, error!!.message!!.contains("Telegram"))
        }
        assertFalse(ConfigParser.looksLikeMtProto(CustomConfigTestSupport.REALITY_VISION))
    }
}
