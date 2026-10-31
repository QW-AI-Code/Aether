package studio.cluvex.aether.core

import studio.cluvex.aether.model.ConnectionState

/** No launch chime, no duplicate Connected chime, no false disconnect during reconnect. */
internal class ConnectionSoundPolicy {
    enum class Cue { CONNECT, DISCONNECT }
    private var connected = false
    fun accept(state: ConnectionState): Cue? = when (state) {
        is ConnectionState.Connected -> if (connected) null else { connected = true; Cue.CONNECT }
        is ConnectionState.Idle, is ConnectionState.Error, is ConnectionState.Disconnecting ->
            if (!connected) null else { connected = false; Cue.DISCONNECT }
        else -> null
    }
}
