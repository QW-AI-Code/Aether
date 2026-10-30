package studio.cluvex.aether.scanner

import android.util.Base64
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import studio.cluvex.aether.core.KeyVault
import studio.cluvex.aether.custom.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/** Actual loopback HTTP + actual encrypted app store. NOT a Cloudflare or Xray network test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CleanIpLoopbackIntegrationTest {
    private fun target(id: String, ip: String) = CustomConfig(id, "Local fixture", ConfigProtocol.VLESS,
        ConfigFormat.URI, ConfigSource.LINK,
        """{"protocol":"vless","settings":{"vnext":[{"address":"$ip","port":443,"users":[{"id":"00000000-0000-4000-8000-000000000001"}]}]},"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"example.invalid"},"wsSettings":{"path":"/fixture","headers":{"Host":"example.invalid"}}}}""",
        server = "$ip:443")
    private fun exercise(count: Int) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyVault::class.java.getDeclaredField("cached").apply { isAccessible = true }
        val previous = key.get(null)
        key.set(null, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val requests = AtomicInteger()
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val server = Thread {
            while (!listener.isClosed) {
                try {
                    listener.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { /* bounded local fixture HTTP header */ }
                        socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                        requests.incrementAndGet()
                    }
                } catch (_: Exception) { if (listener.isClosed) break }
            }
        }.apply { isDaemon = true; start() }
        try {
            CustomConfigStore.load(context); CustomConfigStore.deleteAll(context)
            val initial = (1..count).map { target("loopback-$it", "104.16.0.${it + 10}") }
            CustomConfigStore.add(context, initial)
            val backend = object : CleanIpScanEngine.Backend {
                override suspend fun currentConfigs() = CustomConfigStore.state.value.configs
                override suspend fun discover(config: CustomConfig, excluded: Set<String>, progress: (Int) -> Unit): List<CleanIpValidation.Candidate> {
                    progress(100)
                    // Only candidate labels are synthetic. All sockets below are hard-pinned to loopback.
                    return listOf("104.16.0.1", "104.16.0.2").filter { it !in excluded }
                        .map { CleanIpValidation.Candidate(it, 443) }
                }
                override suspend fun measure(config: CustomConfig, candidates: List<CleanIpValidation.Candidate>): List<Long> =
                    withContext(Dispatchers.IO) { candidates.map {
                        val start = System.nanoTime()
                        Socket(InetAddress.getByName("127.0.0.1"), listener.localPort).use { socket ->
                            socket.soTimeout = 2000
                            socket.getOutputStream().write("GET /generate_204 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                            check(socket.getInputStream().bufferedReader().readLine().contains("204"))
                        }
                        ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1L)
                    } }
                override suspend fun commit(expected: CustomConfig, pick: CleanIpValidation.Pick) {
                    val outbound = CleanIpEndpoint.replace(expected.outbound, pick.ip, pick.port)
                    CustomConfigStore.replaceScanned(context, expected, expected.copy(
                        outbound = outbound, server = "${pick.ip}:${pick.port}", format = ConfigFormat.XRAY_JSON,
                        raw = JSONObject().put("outbounds", JSONArray().put(JSONObject(outbound))).toString()))
                }
            }
            val reports = mutableListOf<CleanIpScanEngine.Report>()
            CleanIpScanEngine(backend).run(initial, 2000, {}, { reports += it })
            assertEquals(count, reports.count { it.outcome == CleanIpScanEngine.Outcome.APPLIED })
            assertEquals(count, CustomConfigStore.state.value.configs.map { it.server }.toSet().size)
            assertTrue(requests.get() >= count * 4)
            assertTrue(reports.all { it.pingMs?.let { ms -> ms in 1L..2000L } == true })
            assertTrue(initial.zip(CustomConfigStore.state.value.configs).all { (old, new) -> old.id == new.id && old.server != new.server })
            val encrypted = context.getSharedPreferences("aether_custom_configs", 0).getString("state", null)!!
            val restored = CustomConfigStore.decode(String(KeyVault.open(Base64.decode(encrypted, Base64.NO_WRAP))!!))!!
            assertEquals(CustomConfigStore.state.value.configs, restored.configs)
        } finally {
            listener.close(); server.join(2000)
            CustomConfigStore.deleteAll(context); key.set(null, previous)
        }
    }
    @Test fun singleScanUsesRealLocalHttpAndPersistsTheConfirmedEndpoint() = exercise(1)
    @Test fun groupScanPersistsDistinctEndpointsWithRealLocalHttpConfirmations() = exercise(2)
}
