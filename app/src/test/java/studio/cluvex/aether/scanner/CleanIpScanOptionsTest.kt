package studio.cluvex.aether.scanner

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Preference-only regressions: these tests never initiate an IP scan. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CleanIpScanOptionsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("clean-ip-scanner", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun freshInstallEnablesManualCeilingByDefault() {
        assertTrue(CleanIpScanManager.Options().manual)
        val options = CleanIpScanManager.options(context)
        assertTrue(options.manual)
        assertEquals(ScanProfile.DEFAULT, options.profile)
        assertEquals(2000, options.ceiling)
    }

    @Test fun missingManualPreferenceUsesNewDefaultWithoutLosingOtherSettings() {
        context.getSharedPreferences("clean-ip-scanner", Context.MODE_PRIVATE).edit()
            .putString("profile", ScanProfile.DEEP.name).putInt("ceiling", 1500).commit()
        val options = CleanIpScanManager.options(context)
        assertTrue(options.manual)
        assertEquals(ScanProfile.DEEP, options.profile)
        assertEquals(1500, options.ceiling)
    }

    @Test fun explicitlyDisabledManualCeilingSurvivesReload() {
        val saved = CleanIpScanManager.Options(profile = ScanProfile.FAST, manual = false, ceiling = 1700)
        CleanIpScanManager.saveOptions(context, saved)
        assertEquals(saved, CleanIpScanManager.options(context))
        assertFalse(CleanIpScanManager.options(context).manual)
    }

    @Test fun reenablingManualCeilingPersistsTheEditedValue() {
        CleanIpScanManager.saveOptions(context, CleanIpScanManager.Options(manual = false))
        val saved = CleanIpScanManager.options(context).copy(manual = true, ceiling = 2300)
        CleanIpScanManager.saveOptions(context, saved)
        assertEquals(saved, CleanIpScanManager.options(context))
    }
}
