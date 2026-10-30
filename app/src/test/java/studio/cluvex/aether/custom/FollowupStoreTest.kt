package studio.cluvex.aether.custom

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import studio.cluvex.aether.core.KeyVault
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class FollowupStoreTest {
    @Test fun cascadeIsDurableAndLatePingCannotResurrectDeletedRecord() {
        val ctx=RuntimeEnvironment.getApplication()
        val field=KeyVault::class.java.getDeclaredField("cached").apply{isAccessible=true}
        val prior=field.get(null);field.set(null,KeyGenerator.getInstance("AES").apply{init(256)}.generateKey())
        try {
            CustomConfigStore.load(ctx);CustomConfigStore.deleteAll(ctx);ConfigPingStore.clear()
            val configs=(0 until 40).map{CustomConfig("id-$it","fixture-$it",ConfigProtocol.VLESS,ConfigFormat.URI,ConfigSource.FILE,"{}")}
            CustomConfigStore.add(ctx,configs);CustomConfigStore.setEnabled(ctx,true)
            val publish=ConfigPingStore::class.java.getDeclaredMethod("publish",String::class.java,ConfigPing.Report::class.java).apply{isAccessible=true}
            val pool=Executors.newFixedThreadPool(4)
            val jobs=configs.map { c -> pool.submit { publish.invoke(ConfigPingStore,c.id,ConfigPing.Report(20,ConfigPing.PingStatus.OK)) } }
            jobs.forEach{it.get(5,TimeUnit.SECONDS)};pool.shutdown()
            assertEquals(40,ConfigPingStore.results.value.size)
            CustomConfigStore.deleteTab(ctx,ConfigCatalog.V2RAY)
            assertTrue(CustomConfigStore.state.value.configs.isEmpty());assertNull(CustomConfigStore.state.value.activeId)
            assertFalse(CustomConfigStore.state.value.enabled);assertTrue(ConfigPingStore.results.value.isEmpty())
            publish.invoke(ConfigPingStore,"id-0",ConfigPing.Report(10,ConfigPing.PingStatus.OK))
            assertTrue(ConfigPingStore.results.value.isEmpty())
            CustomConfigStore::class.java.getDeclaredField("loaded").apply{isAccessible=true}.setBoolean(null,false)
            val reloaded=CustomConfigStore.load(ctx)
            assertTrue(reloaded.configs.isEmpty());assertNull(reloaded.activeId)
            println("CASCADE durable_reload=PASS late_ping_guard=PASS concurrent_publish=PASS hardware_keystore=NOT_TESTED")
        } finally {CustomConfigStore.deleteAll(ctx);ConfigPingStore.clear();field.set(null,prior)}
    }
}
