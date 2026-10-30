package studio.cluvex.aether.ai
import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.model.*
import studio.cluvex.aether.custom.*
class AiCustomEndpointTest {
    @Test fun finalEndpointWinsOverBackendDefault() {
        for (backend in TransportBackend.entries)
            assertEquals(18831,AiAvailability.socksPort(backend,ConnectionState.Connected("127.0.0.1:18831")))
    }
    @Test fun remoteEndpointIsNeverUsedForAiTraffic() {
        assertThrows(IllegalArgumentException::class.java) {
            AiAvailability.socksPort(TransportBackend.AETHER,ConnectionState.Connected("evil.example:443"))
        }
    }
    @Test fun customExitsCanUseExistingAiClientButNeedKeyAndConnection() {
        assertEquals(AiGate.READY,AiAvailability.evaluate(ConnectionState.Connected("127.0.0.1:18831"),
            TransportBackend.AETHER,true,true,ChainMode.SOLO))
        assertEquals(AiGate.READY,AiAvailability.evaluate(ConnectionState.Connected("127.0.0.1:18831"),
            TransportBackend.AETHER,true,true,ChainMode.AETHER_CONFIG))
        assertEquals(AiGate.WRONG_MODE,AiAvailability.evaluate(ConnectionState.Connected("127.0.0.1:1819"),
            TransportBackend.AETHER,true,true,ChainMode.CONFIG_AETHER))
        assertEquals(AiGate.NO_KEY,AiAvailability.evaluate(ConnectionState.Idle,TransportBackend.AETHER,false,true,ChainMode.SOLO))
        assertEquals(AiGate.DISCONNECTED,AiAvailability.evaluate(ConnectionState.Idle,TransportBackend.AETHER,true,true,ChainMode.SOLO))
    }
}
