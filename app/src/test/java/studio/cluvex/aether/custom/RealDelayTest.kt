package studio.cluvex.aether.custom

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The real-delay test: the probe config, the SOCKS5/HTTP measurement and the
 * batch fallback. Fake servers stand in for the network; with `XRAY_BIN` set the
 * generated probe config is also checked by the real core.
 */
class RealDelayTest {

    private fun outboundOf(link: String) =
        ConfigParser.parse(link, ConfigSource.LINK, { CustomConfigTestSupport.tables }).single().outbound

    @Test
    fun `every config gets its own inbound routed to its own outbound`() {
        val items = listOf(
            RealDelay.Item("a", outboundOf(CustomConfigTestSupport.REALITY_VISION)),
            RealDelay.Item("b", outboundOf(CustomConfigTestSupport.REALITY_FIREFOX)),
        )
        val json = Json.parseToJsonElement(RealDelay.buildConfig(items.zip(listOf(20001, 20002)), listOf("192.0.2.53")))
            .jsonObject
        val inbounds = json["inbounds"]!!.jsonArray.map { it.jsonObject }
        val outbounds = json["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(20001, 20002), inbounds.map { it["port"]!!.jsonPrimitive.content.toInt() })
        assertTrue(inbounds.all { it["listen"]!!.jsonPrimitive.content == "127.0.0.1" })
        assertEquals(3, outbounds.size) // two configs + direct (for Xray's DNS)
        val rules = json["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        for (i in 0..1) {
            val rule = rules.single { (it["inboundTag"] as JsonArray).first().jsonPrimitive.content == "ping-in-$i" }
            assertEquals("ping-out-$i", rule["outboundTag"]!!.jsonPrimitive.content)
        }
        // The outbound is the one a direct session dials: same stream settings, IPv4-first dialer.
        val sockopt = outbounds[0]["streamSettings"]!!.jsonObject["sockopt"]!!.jsonObject
        assertEquals("UseIPv4v6", sockopt["domainStrategy"]!!.jsonPrimitive.content)
        assertEquals("reality", outbounds[0]["streamSettings"]!!.jsonObject["security"]!!.jsonPrimitive.content)
        val dns = json["dns"]!!.jsonObject["servers"]!!.jsonArray.map { (it as JsonPrimitive).content }
        assertEquals("192.0.2.53", dns.first())
        assertEquals("localhost", dns.last())
    }

    @Test
    fun `a request answered through the proxy is a delay`() {
        FakeSocks(reply = "HTTP/1.1 204 No Content\r\n\r\n").use { fake ->
            val result = RealDelay.measurePort(fake.port, timeoutMs = 2_000, user = "u", pass = "p", useTls = false)
            assertTrue(result.toString(), result is RealDelay.Result.Ok)
            assertTrue((result as RealDelay.Result.Ok).ms >= 1)
        }
    }

    @Test
    fun `a proxy that accepts and then closes is no reply, not a delay`() {
        FakeSocks(reply = null).use { fake ->
            val result = RealDelay.measurePort(fake.port, timeoutMs = 2_000, user = "u", pass = "p", useTls = false)
            assertTrue(result.toString(), result is RealDelay.Result.NoReply)
        }
    }

    @Test
    fun `HTTP errors captive portals and redirects are not successful delay`() {
        for (status in listOf("200 OK", "302 Found", "403 Forbidden", "500 Error", "526 Invalid SSL")) {
            FakeSocks(reply = "HTTP/1.1 $status\r\n\r\n").use { fake ->
                val result = RealDelay.measurePort(fake.port, timeoutMs = 1000, user = "u", pass = "p", useTls = false)
                assertTrue("$status: $result", result is RealDelay.Result.NoReply)
            }
        }
    }

    @Test
    fun `plaintext endpoint cannot impersonate HTTPS delay target`() {
        FakeSocks(reply = "HTTP/1.1 204 No Content\r\n\r\n").use { fake ->
            val result = RealDelay.measurePort(fake.port, timeoutMs = 1000, user = "u", pass = "p")
            assertTrue(result.toString(), result is RealDelay.Result.NoReply)
        }
    }

    @Test
    fun `remote CONNECT cost is included in measured delay`() {
        FakeSocks(reply = "HTTP/1.1 204 No Content\r\n\r\n", connectDelayMs = 250).use { fake ->
            val result = RealDelay.measurePort(fake.port, timeoutMs = 2000, user = "u", pass = "p", useTls = false)
            assertTrue(result.toString(), result is RealDelay.Result.Ok)
            assertTrue("CONNECT time was excluded", (result as RealDelay.Result.Ok).ms >= 220)
        }
    }

    @Test
    fun `slow drip cannot extend the absolute request budget`() {
        FakeSocks(reply = "HTTP/1.1 204 No Content\r\n\r\n", dripMs = 100).use { fake ->
            val before = System.nanoTime()
            val result = RealDelay.measurePort(fake.port, timeoutMs = 350, user = "u", pass = "p", useTls = false)
            val elapsed = (System.nanoTime() - before) / 1_000_000
            assertTrue(result.toString(), result is RealDelay.Result.NoReply)
            assertTrue("Slow-drip request exceeded its absolute deadline: $elapsed ms", elapsed < 1500)
        }
    }

    @Test
    fun `wrong probe credentials are refused`() {
        FakeSocks(reply = "HTTP/1.1 204 No Content\r\n\r\n").use { fake ->
            val result = RealDelay.measurePort(fake.port, timeoutMs = 2_000, user = "x", pass = "y", useTls = false)
            assertTrue(result.toString(), result is RealDelay.Result.NoReply)
        }
    }

    @Test
    fun `probe inbounds require a password`() {
        val items = listOf(RealDelay.Item("a", outboundOf(CustomConfigTestSupport.REALITY_VISION)))
        val inbound = Json.parseToJsonElement(RealDelay.buildConfig(items.zip(listOf(20001)), emptyList()))
            .jsonObject["inbounds"]!!.jsonArray.single().jsonObject
        val settings = inbound["settings"]!!.jsonObject
        assertEquals("password", settings["auth"]!!.jsonPrimitive.content)
        assertEquals(1, settings["accounts"]!!.jsonArray.size)
    }

    @Test
    fun `a proxy that never answers times out as no reply`() {
        FakeSocks(reply = null, hang = true).use { fake ->
            val started = System.nanoTime()
            val result = RealDelay.measurePort(fake.port, timeoutMs = 700, user = "u", pass = "p", useTls = false)
            assertTrue(result.toString(), result is RealDelay.Result.NoReply)
            // A timeout ends the measurement; it is not repeated for every target and round.
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
    }

    @Test
    fun `a batch the core will not start is retried one config at a time`() = runBlocking {
        val items = listOf(
            RealDelay.Item("good", outboundOf(CustomConfigTestSupport.REALITY_VISION)),
            RealDelay.Item("bad", outboundOf(CustomConfigTestSupport.REALITY_FIREFOX)),
        )
        val starts = mutableListOf<Int>()
        val launcher = RealDelay.Launcher { json, _ ->
            val inbounds = Json.parseToJsonElement(json).jsonObject["inbounds"]!!.jsonArray.size
            synchronized(starts) { starts += inbounds }
            object : RealDelay.Core {
                override fun isAlive() = false
                override fun stop() = Unit
            }
        }
        val results = mutableMapOf<String, RealDelay.Result>()
        RealDelay.measureAll(items, launcher, emptyList(), { id, r -> synchronized(results) { results[id] = r } })
        assertEquals(listOf(2, 1, 1), starts)
        assertTrue(results.values.all { it is RealDelay.Result.Invalid })
        assertEquals(setOf("good", "bad"), results.keys)
    }

    @Test
    fun `a stored outbound that is not json is invalid without starting anything`() = runBlocking {
        var started = false
        val results = mutableMapOf<String, RealDelay.Result>()
        RealDelay.measureAll(
            listOf(RealDelay.Item("x", "not json")),
            { _, _ -> started = true; error("must not start") },
            emptyList(),
            { id, r -> results[id] = r },
        )
        assertTrue(!started)
        assertTrue(results["x"] is RealDelay.Result.Invalid)
    }

    @Test
    fun `a poisoned answer for a public name is recognised`() {
        for (a in listOf("10.10.34.36", "10.10.34.35", "d0::11", "192.168.1.1", "172.20.0.1", "0.0.0.0", "::1")) {
            assertTrue(a, ConfigPing.isSinkhole(a))
        }
        for (a in listOf("31.77.13.19", "104.16.1.1", "172.67.1.1", "2606:4700::1111")) {
            assertTrue(a, !ConfigPing.isSinkhole(a))
        }
    }

    @Test
    fun `a server that accepts TCP but carries nothing is no reply, never ok`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val ep = ConfigEndpoint("127.0.0.1", server.localPort)
            assertEquals(ConfigPing.PingStatus.NO_REPLY, ConfigPing.diagnose(ep, networkUp = true))
            assertEquals(ConfigPing.PingStatus.NO_NETWORK, ConfigPing.diagnose(ep, networkUp = false))
            assertEquals(ConfigPing.PingStatus.INVALID, ConfigPing.diagnose(null, networkUp = true))
        }
    }

    /** With `XRAY_BIN` set, the real core must accept a generated probe config. */
    @Test
    fun `xray accepts the probe config`() {
        val bin = System.getenv("XRAY_BIN")?.let(::File)
        assumeTrue("XRAY_BIN not set", bin != null && bin.canExecute())
        val links = listOf(
            CustomConfigTestSupport.REALITY_VISION,
            CustomConfigTestSupport.REALITY_FIREFOX,
            CustomConfigTestSupport.TLS_VISION,
            CustomConfigTestSupport.XHTTP_CDN,
        )
        val items = links.mapIndexed { i, l -> RealDelay.Item("$i", outboundOf(l)) }
        val file = File.createTempFile("probe", ".json")
        file.writeText(RealDelay.buildConfig(items.zip(RealDelay.freeLoopbackPorts(items.size)), emptyList()))
        val proc = ProcessBuilder(bin!!.path, "run", "-test", "-c", file.path).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
        assertEquals(out, 0, proc.exitValue())
        file.delete()
    }

    /**
     * Live check against real servers: `XRAY_BIN` plus `AETHER_PING_SAMPLES`, a
     * list of files (links or `.npvt`) separated by the path separator. Prints
     * every config's result; never part of CI (no real server lives in this repo).
     */
    @Test
    fun `live samples are measured through the real core`() = runBlocking {
        val bin = System.getenv("XRAY_BIN")?.let(::File)
        val samples = System.getenv("AETHER_PING_SAMPLES")
        assumeTrue("live samples not configured", bin != null && bin.canExecute() && !samples.isNullOrBlank())
        val configs = samples!!.split(File.pathSeparator).flatMap { path ->
            val f = File(path)
            val source = if (f.name.endsWith(".npvt")) ConfigSource.FILE else ConfigSource.LINK
            ConfigParser.parse(f.readText(), source, { CustomConfigTestSupport.tables })
        }
        val launcher = RealDelay.Launcher { json, tag ->
            val cfg = File.createTempFile("probe-$tag", ".json").apply { writeText(json) }
            val proc = ProcessBuilder(bin!!.path, "run", "-c", cfg.path).redirectErrorStream(true).start()
            thread(isDaemon = true) { proc.inputStream.bufferedReader().forEachLine { } }
            object : RealDelay.Core {
                override fun isAlive() = proc.isAlive
                override fun stop() {
                    proc.destroy(); proc.waitFor(1, TimeUnit.SECONDS); cfg.delete()
                }
            }
        }
        val results = mutableMapOf<String, RealDelay.Result>()
        RealDelay.measureAll(configs.map { RealDelay.Item(it.id, it.outbound) }, launcher, emptyList(), { id, r ->
            synchronized(results) { results[id] = r }
        })
        for (cfg in configs) {
            val r = results[cfg.id]
            val verdict = if (r is RealDelay.Result.Ok) "OK" else ConfigPing.diagnose(ConfigEndpoints.of(cfg), true).name
            println("LIVE ${cfg.protocol} ${cfg.name.take(24)}: $r -> $verdict")
        }
        assertEquals(configs.size, results.size)
    }

    @Test fun `second attempt recovers from transient first connection failure`() {
        ReusableSocks(failFirst=true).use { fake ->
            val r=RealDelay.measurePort(fake.port,2000,"u","p",false)
            assertTrue(r.toString(),r is RealDelay.Result.Ok)
            assertEquals(2,fake.connections.get())
        }
    }
    @Test fun `second request reuses authenticated channel and excludes cold remote setup`() {
        ReusableSocks(coldMs=200).use { fake ->
            val r=RealDelay.measurePort(fake.port,2000,"u","p",false)
            assertTrue(r.toString(),r is RealDelay.Result.Ok)
            assertEquals(1,fake.connections.get());assertEquals(2,fake.requests.get())
            assertTrue("Warm sample unexpectedly includes cold setup",(r as RealDelay.Result.Ok).ms < 150)
        }
    }
    private class ReusableSocks(val failFirst:Boolean=false,val coldMs:Long=0):AutoCloseable {
        val connections=java.util.concurrent.atomic.AtomicInteger()
        val requests=java.util.concurrent.atomic.AtomicInteger()
        val server=ServerSocket(0,50,InetAddress.getByName("127.0.0.1"))
        val port get()=server.localPort
        val worker=thread(isDaemon=true) {
            while(!server.isClosed) {
                val sock=runCatching{server.accept()}.getOrNull()?:break
                val index=connections.incrementAndGet()
                thread(isDaemon=true) { runCatching { sock.use { s ->
                    if(failFirst && index==1)return@use
                    val i=s.getInputStream();val o=s.getOutputStream()
                    i.readNBytes(3);o.write(byteArrayOf(5,2));o.flush()
                    i.read();val ul=i.read();i.readNBytes(ul);val pl=i.read();i.readNBytes(pl)
                    o.write(byteArrayOf(1,0));o.flush()
                    val head=i.readNBytes(5);i.readNBytes(head[4].toInt()+2)
                    Thread.sleep(coldMs);o.write(byteArrayOf(5,0,0,1,0,0,0,0,0,0));o.flush()
                    while(true) {
                        var tail=""
                        while(!tail.endsWith("\r\n\r\n")) {
                            val b=i.read();if(b<0)return@use
                            tail=(tail+b.toChar()).takeLast(4)
                        }
                        requests.incrementAndGet();Thread.sleep(15)
                        o.write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".toByteArray());o.flush()
                    }
                } } }
            }
        }
        override fun close(){server.close();worker.join(500)}
    }

    /** A loopback SOCKS5 server that answers CONNECT and then replies (or not) to the request. */
    private class FakeSocks(private val reply: String?, private val hang: Boolean = false,
        private val connectDelayMs: Long = 0, private val dripMs: Long = 0) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        private val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(s) }
            }
        }

        private fun serve(s: Socket) = runCatching {
            s.use {
                val i = s.getInputStream()
                val o = s.getOutputStream()
                i.readNBytes(3); o.write(byteArrayOf(5, 2))
                // RFC 1929: ver, ulen, user, plen, pass
                i.read(); val ul = i.read(); val u = String(i.readNBytes(ul)); val pl = i.read(); val p = String(i.readNBytes(pl))
                if (u != "u" || p != "p") { o.write(byteArrayOf(1, 1)); return@runCatching }
                o.write(byteArrayOf(1, 0))
                val head = i.readNBytes(5)
                i.readNBytes(head[4].toInt() + 2)
                if (connectDelayMs > 0) Thread.sleep(connectDelayMs)
                o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                val buf = ByteArray(1024)
                i.read(buf)
                if (hang) Thread.sleep(5_000)
                if (reply != null) {
                    if (dripMs == 0L) o.write(reply.toByteArray())
                    else reply.toByteArray().forEach { byte -> o.write(byte.toInt()); o.flush(); Thread.sleep(dripMs) }
                }
                o.flush()
            }
        }

        override fun close() {
            server.close()
            worker.join(500)
        }
    }
}

class XrayLogRedactionTest {
    @Test
    fun `server names, addresses and ids never reach the log`() {
        val line = "2026/10/10 13:05:30.057277 [Warning] [1] app/dispatcher: failed > proxy/vless/outbound: " +
            "dial tcp srv.example.net:443 via 31.77.13.19 [2001:db8::1]:443 user aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val out = XrayProcess.redact(line)
        org.junit.Assert.assertFalse(out, out.contains("example.net"))
        org.junit.Assert.assertFalse(out, out.contains("13.19"))
        org.junit.Assert.assertFalse(out, out.contains("2001:db8"))
        org.junit.Assert.assertFalse(out, out.contains("aaaaaaaa-bbbb"))
        // Timestamps, component paths, file names and loopback stay readable.
        assertTrue(out, out.startsWith("2026/10/10 13:05:30.057277 [Warning]"))
        assertTrue(XrayProcess.redact("Reading config config-session.json").contains("config-session.json"))
        assertTrue(XrayProcess.redact("listening TCP on 127.0.0.1:18830").contains("127.0.0.1:18830"))
    }
}
