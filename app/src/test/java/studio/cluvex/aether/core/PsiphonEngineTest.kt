package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PsiphonEngineTest {
    @Test
    fun readinessRegionsAndFailureAreReadFromTheEngineLog() {
        PsiphonEngine.reset()
        PsiphonEngine.ingest("[2026-09-25T10:00:00Z INFO  aether::psiphon] [*] psiphon can leave from: AT DE SE")
        assertEquals(listOf("AT", "DE", "SE"), PsiphonEngine.snapshot.regions)
        assertFalse(PsiphonEngine.snapshot.ready)
        PsiphonEngine.ingest("[2026-09-25T10:00:01Z INFO  aether] [+] psiphon is ready; 127.0.0.1:1827 leaves through psiphon")
        assertTrue(PsiphonEngine.snapshot.ready)
        PsiphonEngine.ingest("[2026-09-25T10:00:02Z WARN  aether::psiphon] [-] psiphon: a routine warning")
        assertNull(PsiphonEngine.snapshot.failure)
        PsiphonEngine.ingest("[2026-09-25T10:00:03Z ERROR aether] [-] psiphon: psiphon stopped: exit status: 1")
        assertFalse(PsiphonEngine.snapshot.ready)
        assertEquals("psiphon stopped: exit status: 1", PsiphonEngine.snapshot.failure)
        PsiphonEngine.reset()
    }
}
