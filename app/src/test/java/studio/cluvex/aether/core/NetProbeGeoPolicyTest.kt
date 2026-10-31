package studio.cluvex.aether.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides whether the country-refinement lookup may happen.
 *
 * Written because that lookup was the 1.3.0 leak that F-5 did not close: one
 * plaintext HTTP request to `ip-api.com:80`, on a RAW socket (so
 * `usesCleartextTraffic="false"` never applied to it), whose request line carried
 * the device's real public IP - and it fired on the DIRECT path, on every IP
 * refresh while disconnected. What leaked was not the address, which the operator
 * sees on every packet anyway, but a plaintext, app-shaped "what country is this
 * IP" query that identifies Aether on a network where running Aether is itself
 * the sensitive fact.
 *
 * The fix is a policy, so it is tested as one. Two conditions, both mandatory:
 * tunnelled, and only when there is no country to show at all. A future refactor
 * that reintroduces the direct call, or that revives the cosmetic
 * "harmonise the flag on every probe" behaviour, fails here instead of on a
 * user's phone.
 *
 * Pure JVM: [NetProbe.shouldRefineCountry] opens no socket and touches no Android
 * type.
 */
class NetProbeGeoPolicyTest {

    // ---------------------------------------------------- the direct path

    @Test
    fun `never refines on an untunnelled socket even when the country is unknown`() {
        assertFalse(
            NetProbe.shouldRefineCountry(
                providerHost = "www.cloudflare.com",
                countryCode = null,
                viaTunnel = false,
            ),
        )
    }

    @Test
    fun `no untunnelled refinement for any provider or country value`() {
        for (host in listOf("www.cloudflare.com", "1.1.1.1", "ip-api.com")) {
            for (cc in listOf(null, "", "  ", "DE", "IR")) {
                assertFalse(
                    "host=$host cc=$cc",
                    NetProbe.shouldRefineCountry(host, cc, viaTunnel = false),
                )
            }
        }
    }

    // ------------------------------------------------- inside the tunnel

    @Test
    fun `refines through the tunnel when the country is missing`() {
        assertTrue(NetProbe.shouldRefineCountry("www.cloudflare.com", null, viaTunnel = true))
    }

    @Test
    fun `treats an empty or blank country as missing`() {
        assertTrue(NetProbe.shouldRefineCountry("www.cloudflare.com", "", viaTunnel = true))
        assertTrue(NetProbe.shouldRefineCountry("www.cloudflare.com", "   ", viaTunnel = true))
    }

    @Test
    fun `does not spend a request on refining a country that is already known`() {
        assertFalse(NetProbe.shouldRefineCountry("www.cloudflare.com", "DE", viaTunnel = true))
        assertFalse(NetProbe.shouldRefineCountry("1.1.1.1", "NL", viaTunnel = true))
    }

    @Test
    fun `ip-api never re-asks itself`() {
        assertFalse(NetProbe.shouldRefineCountry("ip-api.com", null, viaTunnel = true))
        assertFalse(NetProbe.shouldRefineCountry("ip-api.com", "DE", viaTunnel = true))
    }

    // --------------------------------------------------------- truth table

    @Test
    fun `full truth table`() {
        data class Case(val host: String, val cc: String?, val tunnel: Boolean, val expect: Boolean)
        val cases = listOf(
            Case("www.cloudflare.com", null, true, true),
            Case("www.cloudflare.com", "DE", true, false),
            Case("www.cloudflare.com", null, false, false),
            Case("www.cloudflare.com", "DE", false, false),
            Case("1.1.1.1", null, true, true),
            Case("1.1.1.1", "", true, true),
            Case("1.1.1.1", null, false, false),
            Case("ip-api.com", null, true, false),
            Case("ip-api.com", null, false, false),
        )
        for (c in cases) {
            org.junit.Assert.assertEquals(
                "host=${c.host} cc=${c.cc} tunnel=${c.tunnel}",
                c.expect,
                NetProbe.shouldRefineCountry(c.host, c.cc, c.tunnel),
            )
        }
    }
}
