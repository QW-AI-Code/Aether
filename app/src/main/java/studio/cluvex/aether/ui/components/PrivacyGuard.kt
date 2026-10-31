package studio.cluvex.aether.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screen-capture and clipboard privacy for the surfaces that display secrets.
 *
 * Threat model, same one the at-rest encryption in `KeyVault`/`SecretStore`
 * already answers: the phone is taken, unlocked, and looked at - or an app the
 * user granted screen-record/accessibility to is watching. Encrypting the API
 * key on disk and then leaving it legible in the recents thumbnail is not a
 * meaningful defence, and for this app's users the sensitive fact is often not
 * the key at all but the *endpoint*: a screenshot of the diagnostics log names
 * the bridge, the exit IP and the WARP enrolment handle.
 *
 * Two independent mechanisms, because they leak through different channels:
 *
 *  - [SecureSurface] sets `FLAG_SECURE` on the window, which blocks screenshots,
 *    screen recording and the recents thumbnail (the system draws a blank frame
 *    instead) and refuses non-secure external displays.
 *  - [copySensitive] marks the clip so Android 13+ does not render the value in
 *    the clipboard toast/preview, and clipboard-history utilities that honour the
 *    flag skip it.
 */

/**
 * Holds `FLAG_SECURE` for as long as this composable is in composition.
 *
 * REF-COUNTED on purpose. Several secure surfaces can be alive at once - the
 * settings host stays composed underneath the AI page, a dialog opens on top of
 * the share panel - and a naive `clearFlags` in `onDispose` would drop protection
 * from the screen that is still showing while the one leaving tidies up after
 * itself. The flag is set on the first claim and cleared only when the last claim
 * is released.
 */
@Composable
fun SecureSurface() {
    val view = LocalView.current
    // No-op in @Preview / any non-Activity host: there is no window to flag.
    val window = (LocalContext.current as? Activity)?.window
    DisposableEffect(window) {
        if (window == null || view.isInEditMode) {
            onDispose { }
        } else {
            if (claims.incrementAndGet() == 1) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            onDispose {
                if (claims.decrementAndGet() == 0) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

private val claims = AtomicInteger(0)

/**
 * Copies [text] to the clipboard, flagged as sensitive.
 *
 * Compose's `LocalClipboardManager.setText` cannot carry clip extras, so this
 * goes through the platform manager directly.
 *
 * [ClipDescription.EXTRA_IS_SENSITIVE] is API 33; on 26..32 the same string key
 * is written by hand, because several OEM clipboard implementations honoured it
 * before it was public API and an unknown extra is ignored everywhere else.
 */
fun copySensitive(context: Context, text: String, label: String = "aether") {
    val clip = ClipData.newPlainText(label, text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(SENSITIVE_KEY, true)
    }
    context.getSystemService(android.content.ClipboardManager::class.java)
        ?.setPrimaryClip(clip)
}

private val SENSITIVE_KEY: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ClipDescription.EXTRA_IS_SENSITIVE
    } else {
        "android.content.extra.IS_SENSITIVE"
    }
