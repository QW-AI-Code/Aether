package studio.cluvex.aether.data

import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import studio.cluvex.aether.core.KeyVault
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.model.ConnectionProfile

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PresetStoreTest {
    @Test fun migrationSyncSwitchDeleteImportAndCiphertext()=runBlocking {
        val ctx=RuntimeEnvironment.getApplication()
        val f=KeyVault::class.java.getDeclaredField("cached").apply{isAccessible=true}
        val old=f.get(null)
        f.set(null,KeyGenerator.getInstance("AES").apply{init(256)}.generateKey())
        try {
            val store=PresetStore(ctx)
            val base=ConnectionProfile(upstreamProxy="socks5://secret-user:secret-pass@127.0.0.1:1080")
            val custom=CustomConfigStore.State(activeId="local-ref",enabled=true)
            store.ensureMigrated(base,custom,"پیش‌فرض",1)
            val original=store.snapshot().active!!
            val baseline=store.snapshot().presets.single { it.isSystemDefault }
            assertFalse(original.isSystemDefault)
            assertEquals(PresetProfileCodec.encode(ConnectionProfile()), baseline.profile)
            assertFalse(baseline.customEnabled)
            store.ensureMigrated(ConnectionProfile(),CustomConfigStore.State(),"ignored",2)
            assertEquals(original,store.snapshot().active)
            val game=base.copy(gameMode=true,gameDnsServers="1.1.1.1:53")
            store.syncActive(game,custom,3)
            assertTrue(ProfileCodec.decode(store.snapshot().active!!.profile).gameMode)
            val new=store.saveAsNew("بازی",game,custom,4)!!
            assertEquals(3,store.snapshot().presets.size)
            assertEquals(new.id,store.snapshot().activeId)
            store.setActive(original.id)
            assertFalse(store.delete(new.id)!!.deletedWasActive)
            assertTrue(store.delete(original.id)!!.deletedWasActive)
            assertEquals(baseline.id,store.snapshot().activeId)
            assertNull(store.delete(baseline.id))
            val imported=PresetTransferCodec.decode(PresetTransferCodec.encode(store.snapshot().presets))
            store.importPresets(imported)
            assertEquals(baseline.id,store.snapshot().activeId)
            assertEquals(2,store.snapshot().presets.size)
            assertNull(store.snapshot().presets.last().customConfigId)
            assertFalse(store.snapshot().presets.last().customEnabled)
            val named = store.snapshot().presets.last()
            store.rename(named.id, "نام واقعی پروفایل واردشده")
            assertEquals(baseline.id, store.snapshot().activeId)
            val renamed = store.snapshot().presets.last()
            val roundTrip = PresetTransferCodec.decode(PresetTransferCodec.encode(listOf(renamed)))
            assertEquals("نام واقعی پروفایل واردشده", roundTrip.single().name)
            store.importPresets(roundTrip)
            assertEquals("نام واقعی پروفایل واردشده", store.snapshot().presets.last().name)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.rename(named.id, "  ") } }
            val bytes=File(ctx.filesDir,"datastore/aether_presets.preferences_pb").readBytes()
            val disk=bytes.toString(Charsets.ISO_8859_1)
            assertTrue(disk.contains("sealed1:"))
            assertFalse(disk.contains("secret-pass"))
            assertFalse(disk.contains("gameDns="))
            println("PRESET_STORE migration=PASS sync=PASS switch=PASS delete_last_guard=PASS import_atomic=PASS encrypted=PASS test_key=JCE")
        } finally { f.set(null,old) }
    }
}
