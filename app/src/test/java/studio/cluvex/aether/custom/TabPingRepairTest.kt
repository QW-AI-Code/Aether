package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.ai.*
import studio.cluvex.aether.core.NetProbe
import studio.cluvex.aether.model.*

class TabPingRepairTest {
    @Test fun pingBoundaryColorsAreExact() {
        for ((ms, expected) in listOf(0L to PingQuality.EXCELLENT,149L to PingQuality.EXCELLENT,
            150L to PingQuality.FAIR,300L to PingQuality.FAIR,301L to PingQuality.POOR,9000L to PingQuality.POOR))
            assertEquals(expected,PingQuality.of(ConfigPing.Report(ms,ConfigPing.PingStatus.OK)))
    }
    @Test fun failuresCannotBecomeGoodPing() {
        assertEquals(PingQuality.UNAVAILABLE,PingQuality.of(null))
        ConfigPing.PingStatus.entries.forEach { status ->
            assertEquals(PingQuality.UNAVAILABLE,PingQuality.of(ConfigPing.Report(-1,status)))
            if(status!=ConfigPing.PingStatus.OK)
                assertEquals(PingQuality.UNAVAILABLE,PingQuality.of(ConfigPing.Report(30,status)))
        }
    }
    @Test fun customTcpPolicyIsNotEnabledForOtherModes() {
        val source=ConnectionProfile()
        ChainMode.entries.forEach { mode ->
            val profile=CustomChainPolicy.engineProfile(source,mode)
            val env=CustomChainPolicy.runtimeEnvironment(profile,mode)
            assertEquals(mode.placement==ChainPlacement.FIRST && CustomChainPolicy.isWarpFirstBase(mode.base),
                env["AETHER_CUSTOM_TCP_UPSTREAM"]=="1")
        }
        assertTrue(CustomChainPolicy.runtimeEnvironment(source,null).isEmpty())
    }
    @Test fun independentIpProviderParsesOnlyLiteralAddresses() {
        assertEquals("192.0.2.1",NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n{\"ip\":\"192.0.2.1\"}")?.ip)
        assertEquals("2001:db8::1",NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n{\"ip\":\"2001:db8::1\"}")?.ip)
        listOf("evil.example","999.1.1.1","1.1.1","::bogus"," ").forEach {
            assertNull(NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n{\"ip\":\"$it\"}"))
        }
    }
    @Test fun topicsAreGroundedAndNotVaultMutationPatches() {
        for(topic in listOf(AiTopic.CUSTOM_CONFIGS,AiTopic.CUSTOM_CHAIN,AiTopic.CUSTOM_PING,AiTopic.CUSTOM_VERIFY)) {
            assertEquals(topic,AiTopic.byId(topic.id));assertNull(topic.profileKey)
            assertTrue(AiPrompts.explain(false,topic,null).contains(topic.detail))
        }
    }
}
