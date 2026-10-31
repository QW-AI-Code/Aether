package studio.cluvex.aether

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.ui.components.copySensitive
import studio.cluvex.aether.ui.theme.AetherTheme
import java.io.File

/**
 * Shows the fatal JVM exception saved by [AetherApp]'s crash handler on the
 * previous run. Ported from the merged CrashReportScreen, kept as a separate
 * Activity (launched on the next cold start) so the report is reachable even
 * when the crash broke the home UI's state.
 */
class CrashReportActivity : ComponentActivity() {

    override fun attachBaseContext(base: android.content.Context?) {
        super.attachBaseContext(
            base?.let { studio.cluvex.aether.data.LanguagePrefs.wrap(it) } ?: base,
        )
    }

    /**
     * Same reason as in [MainActivity]: the window has to be mirrored from the
     * stored language choice, because the framework resolves the decor view's
     * direction from a default locale it rewrites behind our back. See
     * [studio.cluvex.aether.data.LanguagePrefs].
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        studio.cluvex.aether.data.LanguagePrefs.applyLayoutDirection(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AUDIT F-3: a crash dump names classes, endpoints and whatever the engine
        // was doing when it died. Set on the window itself rather than through
        // SecureSurface, because this Activity has exactly one screen and it is
        // sensitive from the first frame.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        studio.cluvex.aether.data.LanguagePrefs.applyLayoutDirection(this)
        val crashFile = File(filesDir, "last_crash.txt")
        val details = runCatching { crashFile.readText() }.getOrDefault("")

        setContent {
            AetherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.crash_title),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.crash_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            Text(
                                text = details.ifBlank { "—" },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(16.dp),
                            )
                        }
                        val context = LocalContext.current
                        var copied by remember { mutableStateOf(false) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    // AUDIT F-4: flagged sensitive, so the dump does
                                    // not show up in the clipboard preview.
                                    copySensitive(context, details, "aether-crash")
                                    copied = true
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    stringResource(
                                        if (copied) R.string.crash_copied else R.string.crash_copy,
                                    ),
                                )
                            }
                            Button(
                                onClick = {
                                    runCatching { crashFile.delete() }
                                    finish()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.crash_dismiss))
                            }
                        }
                    }
                }
            }
        }
    }
}
