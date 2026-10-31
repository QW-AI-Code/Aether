package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which engine log lines the info row's endpoint is allowed to come from.
 *
 * Written for the 1.3.1-r2 report "the endpoint row only shows an ellipsis". The
 * two `selected …` patterns this parser started with are printed by the SCAN and
 * by nothing else, and 1.3.1 stopped discarding a working cached endpoint — so the
 * reuse path became the ordinary one on every reconnect and the row went empty.
 *
 * Every string below is copied from the engine's own `log::info!` calls in
 * `native/aether/aether/src/lib.rs`, so a future engine bump that renames one of
 * them fails here rather than on a phone.
 */
class EngineMetaTest {

    @org.junit.Before
    fun clear() = EngineMeta.reset()

    private fun endpointOf(line: String): String? {
        EngineMeta.reset()
        EngineMeta.ingest(line)
        return EngineMeta.state.value.endpoint
    }

    // ------------------------------------------------------- the scan paths

    @Test
    fun `the scan announcements are read`() {
        assertEquals(
            "162.159.195.96:946",
            endpointOf("[+] selected WireGuard endpoint 162.159.195.96:946 (rtt 412ms)"),
        )
        assertEquals(
            "162.159.195.92:1701",
            endpointOf(
                "[+] selected WireGuard endpoint 162.159.195.92:1701 " +
                    "using aethernoize profile 'firewall'",
            ),
        )
        assertEquals(
            "162.159.198.1:443",
            endpointOf("[+] selected MASQUE gateway 162.159.198.1:443 (rtt 88ms)"),
        )
    }

    // ------------------------------------------- the paths that had no reader

    /**
     * The line every connect prints, on every path, right before the tunnel comes
     * up - `run_masque`, `run_wireguard` and `run_warp_in_warp` all log it. This is
     * what makes the row work after a cached reuse, a hand-pinned peer or a retry
     * of the last known-good gateway, none of which print `selected …`.
     */
    @Test
    fun `the edge line every connect prints is read`() {
        assertEquals(
            "162.159.198.1:443",
            endpointOf("[+] using cloudflare edge 162.159.198.1:443"),
        )
    }

    @Test
    fun `warp in warp reports its outer edge`() {
        assertEquals(
            "162.159.198.1:443",
            endpointOf(
                "[+] using cloudflare edge 162.159.198.1:443 (outer) " +
                    "and 162.159.192.1:443 (inner)",
            ),
        )
    }

    @Test
    fun `masque in masque reports its outer edge`() {
        assertEquals(
            "162.159.198.1:443",
            endpointOf(
                "[+] masque-in-masque ready: 162.159.198.1:443 (outer) " +
                    "and 162.159.192.1:443 (inner)",
            ),
        )
    }

    @Test
    fun `an ipv6 endpoint survives the pattern`() {
        assertEquals(
            "[2606:4700:d0::a29f:c601]:443",
            endpointOf("[+] using cloudflare edge [2606:4700:d0::a29f:c601]:443"),
        )
    }

    // --------------------------------------------------------- the non-cases

    @Test
    fun `ordinary log lines leave the state alone`() {
        assertNull(endpointOf("[+] selected protocol: WireGuard"))
        assertNull(endpointOf("[*] scan mode=turbo ip=ipv4 candidates=896"))
        assertNull(endpointOf("[+] tor reaching the network: 100%: connecting successfully"))
        assertNull(endpointOf(""))
    }

    /** A later line wins: the peer in use is whatever the engine last named. */
    @Test
    fun `the most recent endpoint wins`() {
        EngineMeta.reset()
        EngineMeta.ingest("[+] selected MASQUE gateway 162.159.198.1:443 (rtt 88ms)")
        EngineMeta.ingest("[+] using cloudflare edge 162.159.192.1:443")
        assertEquals("162.159.192.1:443", EngineMeta.state.value.endpoint)
    }
}
