package studio.cluvex.aether.model
import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.core.ProfileCodec

class GameModeTest {
    @Test fun `off preserves the regular resolver list and on uses dedicated DNS`() {
        val base=ConnectionProfile(dnsServers="8.8.8.8",gameDnsServers="1.1.1.1")
        assertEquals(base.sanitizedDns(),base.effectiveDns())
        val on=base.copy(gameMode=true)
        assertEquals(listOf("1.1.1.1"),on.effectiveDns())
        assertTrue(on.toArgs().windowed(2).any { it == listOf("--dns","1.1.1.1") })
        assertEquals(on.gameDnsServers,ProfileCodec.decode(ProfileCodec.encode(on)).gameDnsServers)
        assertTrue(ProfileCodec.decode(ProfileCodec.encode(on)).gameMode)
    }
    @Test fun `invalid dedicated DNS falls back without changing regular DNS behavior`() {
        val p=ConnectionProfile(dnsServers="8.8.8.8",gameMode=true,gameDnsServers="999.1.1.1 1.1.1.1:99999 bad.invalid")
        assertTrue(p.gameDnsList().isEmpty())
        assertEquals(p.sanitizedDns(),p.effectiveDns())
        assertEquals(listOf("1.1.1.1:5353","[::1]:53"),p.copy(gameDnsServers="1.1.1.1:5353 [::1]:53").gameDnsList())
    }
}
