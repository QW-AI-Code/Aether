package studio.cluvex.aether.custom

import android.content.Context
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

/** QWVPN's private-core, actual-bytes/time methodology, adapted to Aether's executable Xray. */
object ConfigSpeedTest {
    data class Report(val downloadBps: Long = -1, val uploadBps: Long = -1, val running: Boolean = false)
    private val mutableResults = MutableStateFlow<Map<String, Report>>(emptyMap())
    val results: StateFlow<Map<String, Report>> = mutableResults.asStateFlow()
    private val gate = Mutex()
    internal fun bytesPerSecond(bytes: Long, nanos: Long): Long =
        SpeedRate.bytesPerSecond(bytes, nanos)

    suspend fun measure(context: Context, config: CustomConfig) {
        if (!gate.tryLock()) return
        mutableResults.update { it + (config.id to Report(running = true)) }
        try {
            val result = withContext(Dispatchers.IO) { run(context.applicationContext, config) }
            mutableResults.update { it + (config.id to result) }
        } catch (e: CancellationException) {
            mutableResults.update { it + (config.id to Report()) }; throw e
        } catch (_: Exception) {
            mutableResults.update { it + (config.id to Report()) }
        } finally { gate.unlock() }
    }

    private suspend fun run(context: Context, config: CustomConfig): Report {
        val port = RealDelay.freeLoopbackPorts(1).single()
        val user = UUID.randomUUID().toString()
        val pass = UUID.randomUUID().toString()
        val process = XrayProcess(context.applicationInfo.nativeLibraryDir, context.noBackupFilesDir)
        val outbound = JSONObject(XrayConfigBuilder.directOutbound(config.outbound, "speed-out").toString())
        val inbound = JSONObject().put("listen", "127.0.0.1").put("port", port).put("protocol", "http")
            .put("settings", JSONObject().put("accounts", JSONArray().put(JSONObject().put("user", user).put("pass", pass))))
        val json = JSONObject().put("log", JSONObject().put("loglevel", "none"))
            .put("inbounds", JSONArray().put(inbound)).put("outbounds", JSONArray().put(outbound))
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, pass)).build()
            }.callTimeout(15, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS).writeTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        try {
            process.start(json.toString(), "speed-" + UUID.randomUUID())
            var listening = false
            repeat(60) {
                currentCoroutineContext().ensureActive()
                check(process.isAlive()) { "Measurement core stopped" }
                if (!listening) {
                    listening = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; true }.getOrDefault(false)
                    if (!listening) delay(100)
                }
            }
            check(listening) { "Measurement core not ready" }
            var down = -1L
            for (url in listOf("https://fsn1-speed.hetzner.com/100MB.bin", "https://speed.cloudflare.com/__down?bytes=8388608")) {
                currentCoroutineContext().ensureActive()
                down = runCatching { download(client, url) }.getOrDefault(-1)
                if (down > 0) break
            }
            currentCoroutineContext().ensureActive()
            val owner = currentCoroutineContext()
            // Acknowledged response required: buffered socket writes are not called upload speed.
            val up = runCatching {
                val body = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = 2L * 1024 * 1024
                    override fun writeTo(sink: BufferedSink) {
                        val chunk = ByteArray(32 * 1024)
                        repeat(64) { owner.ensureActive(); sink.write(chunk) }
                    }
                }
                val start = System.nanoTime()
                client.newCall(Request.Builder().url("https://speed.cloudflare.com/__up").post(body).build())
                    .execute().use { response ->
                        if (!response.isSuccessful) -1L else bytesPerSecond(body.contentLength(), System.nanoTime() - start)
                    }
            }.getOrDefault(-1)
            currentCoroutineContext().ensureActive()
            return Report(down, up)
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            process.stop()
        }
    }

    private suspend fun download(client: OkHttpClient, url: String): Long {
        val owner = currentCoroutineContext()
        return client.newCall(Request.Builder().url(url).header("Range", "bytes=0-8388607").build()).execute().use { response ->
            if (!response.isSuccessful) return@use -1L
            val input = response.body?.byteStream() ?: return@use -1L
            val start = System.nanoTime()
            var count = 0L
            input.use {
                val buffer = ByteArray(32 * 1024)
                while (count < 8L * 1024 * 1024 && System.nanoTime() - start < 5_000_000_000L) {
                    owner.ensureActive()
                    val n = it.read(buffer, 0, minOf(buffer.size.toLong(), 8L * 1024 * 1024 - count).toInt())
                    if (n < 0) break
                    count += n
                }
            }
            bytesPerSecond(count, System.nanoTime() - start)
        }
    }
}
