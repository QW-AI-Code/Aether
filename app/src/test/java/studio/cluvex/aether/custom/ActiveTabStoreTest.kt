package studio.cluvex.aether.custom

import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import studio.cluvex.aether.core.KeyVault
import studio.cluvex.aether.scanner.CleanIpScanManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class ActiveTabStoreTest {
    @Test fun persistedSelectionReloadsAndDeletionClearsNoticeWithoutAnOpenPage() = runBlocking {
        val field = KeyVault::class.java.getDeclaredField("cached").apply { isAccessible = true }
        val prior = field.get(null)
        field.set(null, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val ctx = RuntimeEnvironment.getApplication()
        try {
            CustomConfigStore.load(ctx); CustomConfigStore.deleteAll(ctx); CustomConfigStore.setActiveTab(ctx, ConfigCatalog.ALL)
            val c = CustomConfig("tab-fixture", "fixture", ConfigProtocol.VLESS, ConfigFormat.URI, ConfigSource.FILE, "{}")
            CustomConfigStore.add(ctx, listOf(c, c.copy(id="npvt", format=ConfigFormat.NPVT)))
            CustomConfigStore.setActiveTab(ctx, ConfigCatalog.V2RAY)
            CustomConfigStore.setEnabled(ctx, true)
            CustomConfigStore::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
            val reloaded = CustomConfigStore.load(ctx)
            assertEquals(ConfigCatalog.V2RAY, reloaded.activeTab)
            assertEquals(c.id, reloaded.selection!!.config.id)
            assertFalse(CustomConfigStore.selectInTab(ctx,"npvt",ConfigCatalog.V2RAY))
            CustomConfigStore.setActiveTab(ctx, ConfigCatalog.NPVT)
            assertFalse(CustomConfigStore.selectInTab(ctx,c.id,ConfigCatalog.V2RAY))
            assertEquals("npvt",CustomConfigStore.state.value.selection!!.config.id)
            // No initialization sleeps: first collection may occur after deletion.
            CleanIpScanManager.postMessage("finished")
            CustomConfigStore.delete(ctx, "npvt")
            withTimeout(2000) { CleanIpScanManager.state.first { it.message.isEmpty() } }
            assertEquals(ConfigCatalog.NPVT, CustomConfigStore.state.value.activeTab)
            assertNull(CustomConfigStore.state.value.selection)
        } finally {
            CustomConfigStore.deleteAll(ctx); CustomConfigStore.setActiveTab(ctx, ConfigCatalog.ALL)
            CleanIpScanManager.clearMessage(); field.set(null, prior)
        }
    }
}
