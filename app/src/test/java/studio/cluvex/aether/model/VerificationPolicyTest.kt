package studio.cluvex.aether.model

import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.core.ProfileCodec

class VerificationPolicyTest {
    @Test fun `all six ordinary modes default on and can independently skip`() {
        assertEquals(6, TransportBackend.entries.size)
        TransportBackend.entries.forEach { backend ->
            val p = ConnectionProfile(backend = backend)
            assertTrue(VerificationPolicy.enabled(p, null))
            assertFalse(VerificationPolicy.enabled(p.copy(verifyConnection = false), null))
        }
    }
    @Test fun `custom gate is independent of ordinary gate`() {
        assertTrue(VerificationPolicy.enabled(ConnectionProfile(verifyConnection = false), true))
        assertFalse(VerificationPolicy.enabled(ConnectionProfile(), false))
    }
    @Test fun `old and malformed service profiles default on`() {
        assertTrue(ProfileCodec.decode("backend=AETHER\nquick=true").verifyConnection)
        assertTrue(ProfileCodec.decode("verifyConnection=bad").verifyConnection)
    }
    @Test fun `disabled gate roundtrips for every ordinary backend`() {
        TransportBackend.entries.forEach { backend ->
            val p = ConnectionProfile(backend = backend, verifyConnection = false)
            assertEquals(p, ProfileCodec.decode(ProfileCodec.encode(p)))
        }
    }
    @Test fun `verification setting does not mutate engine cli`() {
        val p = ConnectionProfile()
        assertEquals(p.toArgs(), p.copy(verifyConnection = false).toArgs())
    }
    @Test fun `selecting is busy cancellable and not connected`() {
        assertTrue(ConnectionState.Selecting.isBusy)
        assertFalse(ConnectionState.Selecting.isConnected)
    }
}
