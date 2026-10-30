package studio.cluvex.aether.scanner

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import studio.cluvex.aether.custom.*
import studio.cluvex.aether.core.KeyVault

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CleanIpStoreTest {
    private fun withStore(test: (android.content.Context) -> Unit) {
        val field = KeyVault::class.java.getDeclaredField("cached").apply { isAccessible = true }
        val prior = field.get(null)
        field.set(null, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val context = RuntimeEnvironment.getApplication()
        try { CustomConfigStore.load(context); CustomConfigStore.deleteAll(context); test(context) }
        finally { CustomConfigStore.deleteAll(context); field.set(null, prior) }
    }
    private fun config() = CustomConfig("scan-fixture", "Server", ConfigProtocol.VLESS, ConfigFormat.URI,
        ConfigSource.LINK, "{}", server = "old.example:443")
    @Test fun staleScanCannotResurrectDeletedServer() = withStore { context ->
        val original = config(); CustomConfigStore.add(context, listOf(original)); CustomConfigStore.delete(context, original.id)
        assertThrows(IllegalArgumentException::class.java) {
            CustomConfigStore.replaceScanned(context, original, original.copy(server = "104.16.0.1:443"))
        }
        assertTrue(CustomConfigStore.state.value.configs.isEmpty())
    }
    @Test fun concurrentEditIsNotOverwrittenAndLockedRecordCannotChange() = withStore { context ->
        val original = config(); CustomConfigStore.add(context, listOf(original))
        CustomConfigStore.replace(context, original.copy(name = "User renamed"))
        assertThrows(IllegalArgumentException::class.java) {
            CustomConfigStore.replaceScanned(context, original, original.copy(server = "104.16.0.1:443"))
        }
        val locked = original.copy(locked = true)
        CustomConfigStore.replace(context, locked)
        assertThrows(IllegalArgumentException::class.java) { CustomConfigStore.replaceScanned(context, locked, locked) }
        assertEquals(locked, CustomConfigStore.state.value.configs.single())
    }
}
