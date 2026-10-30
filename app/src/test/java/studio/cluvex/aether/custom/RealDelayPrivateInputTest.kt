package studio.cluvex.aether.custom

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real network test. Never ships real configs, endpoints or credentials. */
class RealDelayPrivateInputTest {
    @Test fun originalNpvoAndV2raySubscriptionThroughRealXray() = runBlocking {
        val core=System.getenv("XRAY_BIN")?.let(::File)
        val npvo=System.getenv("AETHER_PRIVATE_NPVO_FILE")?.let(::File)
        val v2ray=System.getenv("AETHER_PRIVATE_V2RAY_FILE")?.let(::File)
        assumeTrue("Live inputs are private and opt-in",core?.canExecute()==true && npvo?.isFile==true && v2ray?.isFile==true)
        val cases=listOf("NPVO1" to npvo!!, "V2Ray" to v2ray!!)
        for ((kind,input) in cases) {
            val parsed=ConfigParser.parseBytes(input.readBytes(),ConfigSource.FILE,{CustomConfigTestSupport.tables},{error("Binary private sample not supplied")})
            assertTrue("$kind empty input",parsed.isNotEmpty())
            val items=parsed.mapIndexed { i,c -> RealDelay.Item("$kind-$i",c.outbound) }
            val launcher=RealDelay.Launcher { json, _ ->
                val config=File.createTempFile("aether-live-", ".json").apply { writeText(json) }
                val process=ProcessBuilder(core!!.path,"run","-c",config.path).redirectErrorStream(true).start()
                thread(isDaemon=true) { process.inputStream.bufferedReader().useLines { it.forEach { /* private core log discarded */ } } }
                object:RealDelay.Core {
                    override fun isAlive()=process.isAlive
                    override fun stop() { process.destroy(); if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();config.delete() }
                }
            }
            val results=java.util.concurrent.ConcurrentHashMap<String,RealDelay.Result>()
            RealDelay.measureAll(items,launcher,emptyList(),{id,result->results[id]=result})
            assertEquals(items.size,results.size)
            results.toSortedMap().forEach { (id,r) ->
                // Opaque index only. No config names, UUIDs, endpoint addresses or URLs.
                println("REAL_INPUT id=$id result="+when(r) {
                    is RealDelay.Result.Ok -> "OK ms=${r.ms}"
                    is RealDelay.Result.NoReply -> "NO_REPLY"
                    is RealDelay.Result.Invalid -> "CORE_INVALID"
                })
            }
            println("REAL_INPUT kind=$kind parsed=${items.size} measured=${results.size} ok=${results.values.count { it is RealDelay.Result.Ok }}")
        }
    }
}
