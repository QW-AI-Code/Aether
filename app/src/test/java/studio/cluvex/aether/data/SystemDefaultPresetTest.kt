package studio.cluvex.aether.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.cluvex.aether.core.KeyVault
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.model.ConnectionPreset
import studio.cluvex.aether.model.ConnectionProfile

/** Production DataStore/vault paths, using a disposable test store and JCE test key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemDefaultPresetTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun withStore(block: suspend (PresetStore, DataStore<Preferences>) -> Unit) = runBlocking {
        val field = KeyVault::class.java.getDeclaredField("cached").apply { isAccessible = true }
        val previousKey = field.get(null)
        field.set(null, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope,
                produceFile = { temporary.root.resolve("presets.preferences_pb") })
            block(PresetStore(dataStore), dataStore)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            field.set(null, previousKey)
        }
    }

    private suspend fun seed(
        dataStore: DataStore<Preferences>,
        presets: List<ConnectionPreset>,
        activeId: String,
    ) {
        val bytes = JsonArray(presets.map { it.toJson() }).toString().toByteArray()
        val sealed = try { requireNotNull(KeyVault.seal(bytes)) } finally { bytes.fill(0) }
        dataStore.edit {
            it[stringPreferencesKey("presets_json")] =
                "sealed1:" + Base64.getEncoder().encodeToString(sealed)
            it[stringPreferencesKey("active_preset")] = activeId
        }
    }

    @Test fun untouchedInstallCreatesOnlyOneFactoryBaseline() = withStore { store, _ ->
        store.ensureMigrated(ConnectionProfile(), CustomConfigStore.State(), "پیش‌فرض", 1)
        val baseline = store.snapshot().presets.single()
        assertTrue(baseline.isSystemDefault)
        assertEquals(ConnectionPreset.SYSTEM_DEFAULT_ID, store.snapshot().activeId)
        assertEquals(PresetProfileCodec.encode(ConnectionProfile()), baseline.profile)
        assertFalse(baseline.customEnabled)
        assertNull(baseline.customConfigId)
        assertEquals("SOLO", baseline.chainMode)
        store.ensureMigrated(ConnectionProfile(gameMode = true),
            CustomConfigStore.State(), "Default", 2)
        assertEquals(baseline, store.snapshot().presets.single())
    }

    @Test fun dirtyInstallPreservesLiveSettingsAsAnEditableUserCopy() = withStore { store, _ ->
        val dirty = ConnectionProfile(gameMode = true)
        val custom = CustomConfigStore.State(activeId = "existing-config", enabled = true)
        store.ensureMigrated(dirty, custom, "پیش‌فرض", 1, "تنظیمات ذخیره‌شده")
        val state = store.snapshot()
        assertEquals(2, state.presets.size)
        val active = state.active!!
        assertFalse(active.isSystemDefault)
        assertEquals("تنظیمات ذخیره‌شده", active.name)
        assertEquals(PresetProfileCodec.encode(dirty), active.profile)
        assertEquals("existing-config", active.customConfigId)
        assertTrue(active.customEnabled)
        assertFalse(state.presets.single { it.isSystemDefault }.customEnabled)
    }

    @Test fun legacyRenamedDefaultSurvivesAndCleanDefaultIsRestored() = withStore { store, dataStore ->
        val dirty = ConnectionProfile(gameMode = true)
        val custom = CustomConfigStore.State(activeId = "fixture-config", enabled = true)
        val legacy = ConnectionPreset("legacy-id", "Fixture", PresetProfileCodec.encode(dirty),
            custom.activeId, custom.enabled, custom.mode.name, 7)
        seed(dataStore, listOf(legacy), legacy.id)
        store.ensureMigrated(dirty, custom, "پیش‌فرض", 10)
        assertEquals(legacy.id, store.snapshot().activeId)
        assertEquals(legacy, store.snapshot().presets.last())
        val baseline = store.snapshot().presets.first()
        assertTrue(baseline.isSystemDefault)
        assertEquals(PresetProfileCodec.encode(ConnectionProfile()), baseline.profile)
        store.rename(legacy.id, "Renamed user profile")
        assertNotNull(store.delete(legacy.id))
        assertEquals(baseline, store.snapshot().active)
    }

    @Test fun legacyUnsyncedLiveSettingsAreNotDiscardedDuringMigration() = withStore { store, dataStore ->
        val legacy = ConnectionPreset("old", "Work", PresetProfileCodec.encode(ConnectionProfile()))
        seed(dataStore, listOf(legacy), legacy.id)
        val dirty = ConnectionProfile(gameMode = true)
        store.ensureMigrated(dirty, CustomConfigStore.State(), "Default", 1)
        assertEquals(3, store.snapshot().presets.size)
        assertEquals(legacy, store.snapshot().presets.single { it.id == legacy.id })
        assertEquals(PresetProfileCodec.encode(dirty), store.snapshot().active!!.profile)
    }

    @Test fun storeGuardsDefaultAgainstRenameDeleteAndAutomaticOverwrite() = withStore { store, _ ->
        store.ensureMigrated(ConnectionProfile(), CustomConfigStore.State(), "Default", 1)
        val baseline = store.snapshot().active!!
        val dirty = ConnectionProfile(gameMode = true)
        val custom = CustomConfigStore.State(activeId = "example-config", enabled = true)
        store.syncActive(dirty, custom, 2)
        assertNull(store.delete(baseline.id))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.rename(baseline.id, "Fixture") }
        }
        assertEquals(baseline, store.snapshot().active)
        val copied = store.saveAsNew("My settings", dirty, custom, 3)!!
        assertFalse(copied.isSystemDefault)
        store.rename(copied.id, "Work")
        store.syncActive(dirty.copy(gameMode = false), custom, 4)
        assertFalse(PresetProfileCodec.decode(store.snapshot().active!!.profile).gameMode)
        assertEquals(baseline, store.setActive(baseline.id))
        assertEquals(baseline, store.setActive(baseline.id))
        assertEquals(baseline, store.snapshot().presets.first())
    }

    @Test fun exportingLiveDefaultDoesNotModifyTheBaseline() = withStore { store, _ ->
        store.ensureMigrated(ConnectionProfile(), CustomConfigStore.State(), "Default", 1)
        val baseline = store.snapshot().active!!
        val dirty = ConnectionProfile(gameMode = true)
        val exported = store.captureActiveForExport(dirty, CustomConfigStore.State(), 2)!!
        assertTrue(PresetProfileCodec.decode(exported.profile).gameMode)
        val portable = PresetTransferCodec.decode(PresetTransferCodec.encodeSelected(listOf(exported), exported.id))
        assertTrue(PresetProfileCodec.decode(portable.single().profile).gameMode)
        assertEquals(baseline, store.snapshot().active)
        store.importPresets(portable)
        assertEquals(1, store.snapshot().presets.count { it.isSystemDefault })
        assertFalse(store.snapshot().presets.last().isSystemDefault)
        assertEquals(baseline, store.snapshot().active)
    }

    @Test fun importedSystemIdentityIsAlwaysReplacedWithAnEditableUserIdentity() = withStore { store, _ ->
        store.ensureMigrated(ConnectionProfile(), CustomConfigStore.State(), "Default", 1)
        val baseline = store.snapshot().active!!
        store.importPresets(listOf(baseline.copy(profile =
            PresetProfileCodec.encode(ConnectionProfile(gameMode = true)))))
        val imported = store.snapshot().presets.last()
        assertFalse(imported.isSystemDefault)
        assertNotEquals(baseline.id, imported.id)
        store.rename(imported.id, "Editable imported settings")
        assertNotNull(store.delete(imported.id))
        assertEquals(baseline, store.snapshot().presets.single())
    }

    @Test fun defaultReservesItsOwnSlotWithoutConsumingTheTwentyUserSlots() = withStore { store, _ ->
        store.ensureMigrated(ConnectionProfile(), CustomConfigStore.State(), "Default", 1)
        repeat(20) { i ->
            assertNotNull(store.saveAsNew("User $i", ConnectionProfile(), CustomConfigStore.State(), 2L + i))
        }
        assertEquals(21, store.snapshot().presets.size)
        assertNull(store.saveAsNew("Overflow", ConnectionProfile(), CustomConfigStore.State()))
        assertNull(store.delete(ConnectionPreset.SYSTEM_DEFAULT_ID))
        assertEquals(1, store.snapshot().presets.count { it.isSystemDefault })
    }
}