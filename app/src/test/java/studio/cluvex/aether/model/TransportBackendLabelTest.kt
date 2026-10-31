package studio.cluvex.aether.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the home screen's connection row is allowed to say.
 *
 * Written for the 1.3.1-r2 report "inside the brackets there is an arrow and the
 * exit name again": the row showed `Aether(WIREGUARD → PSIPHON) → Psiphon`,
 * naming the second hop twice, because both chained paths in `AetherVpnService`
 * published the whole chain through `EngineMeta.setProtocol` — the shape that made
 * sense before 1.3.1, when this value WAS the row.
 *
 * The callers are fixed to publish the transport alone. These cases pin both
 * halves of the contract: the correct input renders correctly, and the old
 * composite input can no longer produce a doubled label if another caller appears.
 */
class TransportBackendLabelTest {

    // ------------------------------------------------- the transport alone

    @Test
    fun `warp modes name the transport inside the brackets`() {
        assertEquals("Aether(WIREGUARD)", TransportBackend.AETHER.pipelineLabel("WIREGUARD"))
        assertEquals(
            "Aether(WIREGUARD) \u2192 Psiphon",
            TransportBackend.AETHER_PSIPHON.pipelineLabel("WIREGUARD"),
        )
        assertEquals(
            "Aether(WIREGUARD) \u2192 Tor",
            TransportBackend.AETHER_TOR.pipelineLabel("WIREGUARD"),
        )
        assertEquals(
            "Tor \u2192 Aether(MASQUE)",
            TransportBackend.TOR_AETHER.pipelineLabel("MASQUE"),
        )
    }

    /**
     * The two `--tor-only` modes build no WARP tunnel, so there is no Aether hop to
     * name. This is the case that used to read "Auto" — a WARP protocol for a
     * tunnel that mode never creates.
     */
    @Test
    fun `tor only modes add nothing, whatever the engine reported`() {
        assertEquals("Tor", TransportBackend.TOR.pipelineLabel("AUTO"))
        assertEquals("Tor \u2192 Psiphon", TransportBackend.TOR_PSIPHON.pipelineLabel("AUTO"))
        assertEquals("Tor", TransportBackend.TOR.pipelineLabel("MASQUE"))
    }

    // ------------------------------------------------- the reported defect

    @Test
    fun `a composite value is reduced to its first hop`() {
        assertEquals(
            "Aether(WIREGUARD) \u2192 Psiphon",
            TransportBackend.AETHER_PSIPHON.pipelineLabel("WIREGUARD \u2192 PSIPHON"),
        )
        assertEquals(
            "Aether(WIREGUARD) \u2192 Tor",
            TransportBackend.AETHER_TOR.pipelineLabel("WIREGUARD \u2192 TOR"),
        )
        // The ASCII spelling too, because that is how a log line writes it.
        assertEquals(
            "Aether(MASQUE) \u2192 Psiphon",
            TransportBackend.AETHER_PSIPHON.pipelineLabel("MASQUE -> PSIPHON"),
        )
    }

    // ------------------------------------------------- nothing to report yet

    @Test
    fun `a missing transport degrades to the plain pipeline`() {
        assertEquals("Aether \u2192 Tor", TransportBackend.AETHER_TOR.pipelineLabel(null))
        assertEquals("Aether \u2192 Tor", TransportBackend.AETHER_TOR.pipelineLabel(""))
        assertEquals("Aether \u2192 Tor", TransportBackend.AETHER_TOR.pipelineLabel("   "))
        // Never "Aether()": a connect that is still deciding shows the path only.
        assertEquals("Aether", TransportBackend.AETHER.pipelineLabel(" \u2192 "))
    }

    @Test
    fun `only the first Aether is bracketed`() {
        // Defensive: `replaceFirst` must not touch a second occurrence if a label
        // ever contains one.
        assertEquals("Aether(MASQUE)", TransportBackend.AETHER.pipelineLabel("MASQUE"))
    }
}
