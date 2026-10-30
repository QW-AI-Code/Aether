package studio.cluvex.aether.core

import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.model.ConnectionState

class ConnectionSoundPolicyTest {
    @Test fun initialIdleAndFailedAttemptAreSilent() {
        val p = ConnectionSoundPolicy()
        assertNull(p.accept(ConnectionState.Idle))
        assertNull(p.accept(ConnectionState.Connecting))
        assertNull(p.accept(ConnectionState.Error("test failure")))
    }
    @Test fun connectedAndDisconnectedAreEmittedOnce() {
        val p = ConnectionSoundPolicy()
        assertEquals(ConnectionSoundPolicy.Cue.CONNECT, p.accept(ConnectionState.Connected("127.0.0.1:1")))
        assertNull(p.accept(ConnectionState.Connected("127.0.0.1:1")))
        assertEquals(ConnectionSoundPolicy.Cue.DISCONNECT, p.accept(ConnectionState.Disconnecting))
        assertNull(p.accept(ConnectionState.Idle))
        assertNull(p.accept(ConnectionState.Error("test")))
    }
    @Test fun transientReconnectDoesNotPlayFalseDisconnectOrDuplicateConnect() {
        val p = ConnectionSoundPolicy()
        p.accept(ConnectionState.Connected("127.0.0.1:1"))
        assertNull(p.accept(ConnectionState.Reconnecting(1, 5)))
        assertNull(p.accept(ConnectionState.Verifying))
        assertNull(p.accept(ConnectionState.Connected("127.0.0.1:1")))
        assertEquals(ConnectionSoundPolicy.Cue.DISCONNECT, p.accept(ConnectionState.Error("test")))
    }
}
