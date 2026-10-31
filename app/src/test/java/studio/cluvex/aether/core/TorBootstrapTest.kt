package studio.cluvex.aether.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Tor bootstrap tracker.
 *
 * Every "real line" below is copied verbatim out of the 1.3.0 field log that
 * produced the "Tor does not connect on my network" report, including the ANSI
 * escapes the engine emits for some of its lines -- the parser has to survive
 * exactly this input, not a cleaned-up version of it.
 */
class TorBootstrapTest {

    private var now = 10_000L

    @Before
    fun setUp() {
        TorBootstrap.clock = { now }
        TorBootstrap.reset()
    }

    @After
    fun tearDown() {
        TorBootstrap.clock = { System.nanoTime() / 1_000_000L }
        TorBootstrap.reset()
    }

    @Test
    fun `nothing seen after reset`() {
        assertFalse(TorBootstrap.seen)
        assertEquals(-1, TorBootstrap.percent)
        assertFalse(TorBootstrap.done)
        assertEquals("no bootstrap progress reported", TorBootstrap.describe())
    }

    @Test
    fun `parses the zero percent line`() {
        TorBootstrap.ingest(
            "[2026-09-14T14:51:51.219Z INFO  aether::tor::with_tor] [*] tor reaching the " +
                "network: 0%: connecting to the internet; not downloading",
        )
        assertTrue(TorBootstrap.seen)
        assertEquals(0, TorBootstrap.percent)
        assertEquals("connecting to the internet; not downloading", TorBootstrap.detail)
        assertFalse(TorBootstrap.done)
    }

    @Test
    fun `parses percent and detail from the real field log line`() {
        TorBootstrap.ingest(
            "[2026-09-14T14:51:51.483Z INFO  aether::tor::with_tor] [*] tor reaching the " +
                "network: 21%: connecting to the internet; directory is fetching authority " +
                "certificates (0/7)",
        )
        assertEquals(21, TorBootstrap.percent)
        assertEquals(
            "connecting to the internet; directory is fetching authority certificates (0/7)",
            TorBootstrap.detail,
        )
    }

    @Test
    fun `one hundred percent means done`() {
        TorBootstrap.ingest("[*] tor reaching the network: 100%: done")
        assertEquals(100, TorBootstrap.percent)
        assertTrue(TorBootstrap.done)
        assertEquals("bootstrap complete", TorBootstrap.describe())
    }

    @Test
    fun `unrelated engine lines are ignored`() {
        listOf(
            "[INFO aether] Aether v2.0.0",
            "[INFO aether::sysprofile] [*] performance profile: High (cpus=8 mem=3751MB)",
            "[INFO tor_memquota::mtracker] Memory quota tracking initialised max=2.75 GiB",
            "[INFO aether::tor::with_tor] [*] bootstrapping tor with no tunnel underneath it",
            "stage 1: 127.0.0.1:1819 does not speak SOCKS5",
        ).forEach { TorBootstrap.ingest(it) }
        assertFalse(TorBootstrap.seen)
    }

    @Test
    fun `an impossible percentage is not accepted`() {
        TorBootstrap.ingest("[*] tor reaching the network: 999%: nonsense")
        assertFalse(TorBootstrap.seen)
    }

    @Test
    fun `a repeated percentage is not movement`() {
        TorBootstrap.ingest("[*] tor reaching the network: 30%: fetching certificates")
        now += 30_000L
        TorBootstrap.ingest("[*] tor reaching the network: 30%: fetching certificates")
        assertEquals(30_000L, TorBootstrap.idleMs())
    }

    @Test
    fun `a new percentage resets the idle timer`() {
        TorBootstrap.ingest("[*] tor reaching the network: 30%: fetching certificates")
        now += 30_000L
        TorBootstrap.ingest("[*] tor reaching the network: 45%: fetching descriptors")
        assertEquals(0L, TorBootstrap.idleMs())
    }

    @Test
    fun `going backwards counts as movement because a bridge retry restarts`() {
        TorBootstrap.ingest("[*] tor reaching the network: 55%: loading descriptors")
        now += 60_000L
        assertTrue(TorBootstrap.stalled(45_000L))
        // Tor gives up on the direct attempt and starts again through a bridge.
        TorBootstrap.ingest("[*] tor reaching the network: 5%: connecting to the internet")
        assertEquals(5, TorBootstrap.percent)
        assertEquals(0L, TorBootstrap.idleMs())
        assertFalse(TorBootstrap.stalled(45_000L))
    }

    @Test
    fun `a moving bootstrap is not stalled`() {
        TorBootstrap.ingest("[*] tor reaching the network: 10%: connecting")
        now += 10_000L
        TorBootstrap.ingest("[*] tor reaching the network: 30%: certificates")
        now += 10_000L
        assertFalse(TorBootstrap.stalled(45_000L))
    }

    @Test
    fun `a bootstrap that stops moving is stalled`() {
        TorBootstrap.ingest("[*] tor reaching the network: 30%: certificates (7/7)")
        now += 45_000L
        assertTrue(TorBootstrap.stalled(45_000L))
        assertEquals("stuck at 30% (certificates (7/7))", TorBootstrap.describe())
    }

    @Test
    fun `a finished bootstrap is never stalled`() {
        TorBootstrap.ingest("[*] tor reaching the network: 100%: done")
        now += 10 * 60_000L
        assertFalse(TorBootstrap.stalled(45_000L))
    }

    @Test
    fun `a silent engine counts as stalled from the moment it started`() {
        // Nothing ingested at all: the app must not wait out the whole budget for
        // an engine that never even reported reaching 0 %.
        now += 45_000L
        assertTrue(TorBootstrap.stalled(45_000L))
    }

    @Test
    fun `reset clears the previous run`() {
        TorBootstrap.ingest("[*] tor reaching the network: 100%: done")
        now += 5_000L
        TorBootstrap.reset()
        assertFalse(TorBootstrap.seen)
        assertFalse(TorBootstrap.done)
        assertEquals(0L, TorBootstrap.idleMs())
    }

    @Test
    fun `detail survives a percentage line that carries none`() {
        TorBootstrap.ingest("[*] tor reaching the network: 30%: fetching authority certificates")
        TorBootstrap.ingest("[*] tor reaching the network: 40%")
        assertEquals(40, TorBootstrap.percent)
        assertEquals("fetching authority certificates", TorBootstrap.detail)
    }
}
