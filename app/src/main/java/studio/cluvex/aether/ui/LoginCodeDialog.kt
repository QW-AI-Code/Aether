package studio.cluvex.aether.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import studio.cluvex.aether.R
import studio.cluvex.aether.core.LoginCodePrompt
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.Navy850
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Asks for the Zero Trust login code the engine is blocked on.
 *
 * ## Why this is a dialog on top of everything (issue #12)
 *
 * The engine has already e-mailed the code and is sitting on a `read_line` with a
 * five-minute fuse ([LoginCodePrompt.CODE_WAIT_SECONDS]). Nothing in the app can
 * make progress until the code is typed, and the user has no way to know that from
 * a status line — for the whole of 1.3.0 this state simply looked like a connect
 * that hung and then failed. So it interrupts: a dialog is the only control that
 * cannot be scrolled past.
 *
 * It is placed at the top of the composition in
 * [studio.cluvex.aether.MainActivity] rather than inside a screen, because the
 * prompt can arrive while the user is anywhere — the home screen, a settings page,
 * the AI chat — and a request that appears only on one screen is a request the
 * user misses.
 *
 * ## Details that matter
 *
 * `onDismissRequest` does NOT dismiss. Tapping outside is how a one-time code gets
 * lost by accident, and the cost is a failed connect plus a fresh code. Leaving is
 * deliberate, through the button.
 *
 * "Not now" writes nothing, and says so in the log rather than pretending to
 * cancel: the prompt belongs to the engine, which keeps waiting until its own
 * timeout. Claiming otherwise in the UI would be a lie about what happens next.
 *
 * The field is numeric-by-default but not numeric-only — Cloudflare's codes are
 * digits today and the app has no business enforcing that for the future.
 */
@Composable
fun LoginCodeDialog(modifier: Modifier = Modifier) {
    val request by LoginCodePrompt.pending.collectAsState()
    val pending = request ?: return

    // Keyed to the attempt so a rejected code does not stay in the box for the
    // next round, where the user would be re-sending exactly what just failed.
    var code by remember(pending.attempt) { mutableStateOf("") }

    AlertDialog(
        modifier = modifier,
        // Deliberately inert: see the class comment.
        onDismissRequest = {},
        icon = {
            Icon(
                imageVector = Icons.Rounded.MailOutline,
                contentDescription = null,
                tint = AetherViolet,
            )
        },
        title = { Text(stringResource(R.string.zt_code_title)) },
        text = {
            Column {
                Text(
                    text = if (pending.email.isBlank()) {
                        stringResource(R.string.zt_code_body_noaddr)
                    } else {
                        stringResource(R.string.zt_code_body, pending.email)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Only from the second attempt on: on the first one there is
                // nothing to report and the line would just read as a warning.
                if (pending.attempt > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.zt_code_retry,
                            pending.attempt,
                            LoginCodePrompt.MAX_ATTEMPTS,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LtrOutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.zt_code_label)) },
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // pluralStringResource, not stringResource: the string carries a
                    // count directly in front of a noun, which is a <plurals> in
                    // every language that inflects one. Lint names this
                    // PluralsCandidate and it is right.
                    text = pluralStringResource(
                        R.plurals.zt_code_wait,
                        LoginCodePrompt.CODE_WAIT_SECONDS,
                        LoginCodePrompt.CODE_WAIT_SECONDS,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { LoginCodePrompt.submit(code) },
                enabled = code.isNotBlank(),
            ) {
                Text(stringResource(R.string.zt_code_send))
            }
        },
        dismissButton = {
            TextButton(onClick = { LoginCodePrompt.dismiss() }) {
                Text(stringResource(R.string.zt_code_dismiss))
            }
        },
        containerColor = Navy850,
    )
}
