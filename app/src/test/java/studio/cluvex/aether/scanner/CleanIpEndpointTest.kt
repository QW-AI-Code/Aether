package studio.cluvex.aether.scanner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [28], application = android.app.Application::class)
class CleanIpEndpointTest {
    private fun outbound(security: String = "tls", port: Int = 443) = """{"protocol":"vless","settings":{"vnext":[{"address":"worker.example.workers.dev","port":$port,"users":[{"id":"private-uuid","encryption":"none"}]}]},"streamSettings":{"network":"ws","security":"$security","tlsSettings":{"serverName":"worker.example.workers.dev","allowInsecure":false},"wsSettings":{"path":"/secret-path","headers":{"Host":"worker.example.workers.dev"}}}}"""
    @Test fun endpointOnlyChangePreservesCredentialsSniHostAndPath() {
        val original = JSONObject(outbound()); val edited = JSONObject(CleanIpEndpoint.replace(original.toString(), "104.16.0.1", 8443))
        assertEquals(original.getJSONObject("streamSettings").toString(), edited.getJSONObject("streamSettings").toString())
        val before = original.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        val after = edited.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals(before.getJSONArray("users").toString(), after.getJSONArray("users").toString())
        assertEquals("104.16.0.1", after.getString("address"));assertEquals(8443, after.getInt("port"))
    }
    @Test fun plaintextNeverSwitchesToTlsPort() {
        val endpoint = CleanIpEndpoint.describe(outbound("none", 80))!!
        assertEquals(80, CleanIpEndpoint.applicablePort(endpoint, 8443))
        assertEquals(80, CleanIpEndpoint.describe(CleanIpEndpoint.replace(outbound("none",80), "104.16.0.1",80))!!.port)
    }
    @Test fun incompatibleAndRealityConfigsAreRejected() {
        assertNull(CleanIpEndpoint.describe("{}"));assertNull(CleanIpEndpoint.describe(outbound("reality")))
        assertNull(CleanIpEndpoint.describe(outbound().replace("\"ws\"", "\"tcp\"")))
    }
    @Test fun nonCloudflareAddressAndPortFamilyChangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CleanIpEndpoint.replace(outbound(), "127.0.0.1",443) }
        assertThrows(IllegalArgumentException::class.java) { CleanIpEndpoint.replace(outbound(), "104.16.0.1",80) }
    }
}
