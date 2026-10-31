package studio.cluvex.aether.ui.settings

import android.content.ComponentName
import androidx.activity.ComponentActivity
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Register the test-only Activity in Robolectric, never in the release manifest. */
class ComposeHostRule : ExternalResource() {
    override fun before() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app,ComponentActivity::class.java))
    }
}
