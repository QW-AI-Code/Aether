package studio.cluvex.aether.vpn

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.core.AutoCandidate
import studio.cluvex.aether.core.IpEndpoint
import studio.cluvex.aether.core.IpInfo
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.ScanMode

/** Isolated service logic, no onCreate/startCommand, child process or real network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AetherServiceIntegrationTest {
    private lateinit var service:AetherVpnService
    @Before fun prepare() {
        service=AetherVpnService()
        epoch().set(42L)
        AetherController.setState(ConnectionState.Connected("127.0.0.1:1819"))
        AetherController.setIpInfo(IpEndpoint("192.0.2.1",null,true))
    }
    @After fun reset() {
        AetherController.setState(ConnectionState.Idle);AetherController.setIpInfo(null)
        val field=AetherVpnService::class.java.getDeclaredField("scope");field.isAccessible=true
        (field.get(service) as CoroutineScope).cancel()
    }
    private fun epoch():AtomicLong {
        val field=AetherVpnService::class.java.getDeclaredField("sessionEpoch");field.isAccessible=true
        return field.get(service) as AtomicLong
    }
    private fun enrich(generation:Long,ip:String?,country:String?,alternate:String?) {
        val method=AetherVpnService::class.java.getDeclaredMethod("applyExitEnrichment",Long::class.javaPrimitiveType,String::class.java,String::class.java,String::class.java)
        method.isAccessible=true;method.invoke(service,generation,ip,country,alternate)
    }
    @Suppress("UNCHECKED_CAST") private fun plan(profile:ConnectionProfile):List<AutoCandidate> {
        val method=AetherVpnService::class.java.getDeclaredMethod("customUpstreamPlan",ConnectionProfile::class.java)
        method.isAccessible=true;return method.invoke(service,profile) as List<AutoCandidate>
    }
    @Test fun sameEpochAndIpReceiveCountryAndAlternateFamily() {
        enrich(42,"192.0.2.1","DE","2001:db8::1")
        assertEquals(IpEndpoint("192.0.2.1","DE",true,"2001:db8::1"),AetherController.ipInfo.value)
    }
    @Test fun oldSessionCallbackIsDroppedEvenWhenPortWasReused() {
        enrich(41,"192.0.2.1","DE","2001:db8::1")
        assertNull(AetherController.ipInfo.value!!.countryCode)
        assertNull(AetherController.ipInfo.value!!.alternateIp)
    }
    @Test fun differentIpCallbackDoesNotPaintWrongCountry() {
        enrich(42,"192.0.2.2","DE",null)
        assertNull(AetherController.ipInfo.value!!.countryCode)
    }
    @Test fun operatorIpCannotBeOverwrittenByTunnelEnrichment() {
        val direct=IpEndpoint("198.51.100.1","US",false);AetherController.setIpInfo(direct)
        enrich(42,"198.51.100.1","DE",null)
        assertEquals(direct,AetherController.ipInfo.value)
    }
    @Test fun missingNewAlternateDoesNotErasePreviouslyPublishedFamily() {
        AetherController.setIpInfo(IpEndpoint("192.0.2.1",null,true,"2001:db8::1"))
        enrich(42,"192.0.2.1","DE",null)
        assertEquals("2001:db8::1",AetherController.ipInfo.value!!.alternateIp)
        assertEquals("DE",AetherController.ipInfo.value!!.countryCode)
    }
    @Test fun typedCallbackReachesMetadataMerger() {
        val method=AetherVpnService::class.java.getDeclaredMethod("onExitEnriched",Long::class.javaPrimitiveType,String::class.java,Any::class.java)
        method.isAccessible=true;method.invoke(service,42L,"192.0.2.1",IpInfo("192.0.2.1","DE","2001:db8::1"))
        assertEquals("DE",AetherController.ipInfo.value!!.countryCode)
        assertEquals("2001:db8::1",AetherController.ipInfo.value!!.alternateIp)
    }
    @Test fun earlyEnrichmentWaitsForFirstPublicationInsteadOfBeingLost() = kotlinx.coroutines.runBlocking {
        AetherController.setIpInfo(null)
        val method=AetherVpnService::class.java.getDeclaredMethod("onExitEnriched",Long::class.javaPrimitiveType,String::class.java,Any::class.java)
        method.isAccessible=true; method.invoke(service,42L,null,IpInfo("192.0.2.1","DE"))
        AetherController.offerTunnelIpInfo(IpEndpoint("192.0.2.1",null,true))
        kotlinx.coroutines.withTimeout(2000) {
            AetherController.ipInfo.first { it?.countryCode == "DE" }
        }
        assertEquals("192.0.2.1",AetherController.ipInfo.value!!.ip)
    }
    @Test fun customBalancedPlanTriesTurboThenOriginalWithBoundedBudgets() {
        val p=plan(ConnectionProfile(scanMode=ScanMode.BALANCED))
        assertEquals(2,p.size);assertEquals(ScanMode.TURBO,p[0].profile.scanMode)
        assertEquals(ScanMode.BALANCED,p[1].profile.scanMode)
        assertTrue(p[0].timeoutMs<=75000L);assertTrue(p[1].timeoutMs>=180000L)
        assertEquals(EndpointMode.AUTO,p[0].profile.endpointMode)
    }
    @Test fun explicitlyTurboCustomPlanHasOneFullBudgetAttempt() {
        val p=plan(ConnectionProfile(scanMode=ScanMode.TURBO))
        assertEquals(1,p.size);assertEquals(ScanMode.TURBO,p[0].profile.scanMode)
        assertTrue(p[0].timeoutMs>=180000L)
    }
}
