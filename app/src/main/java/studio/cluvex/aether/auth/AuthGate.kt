package studio.cluvex.aether.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import studio.cluvex.aether.R
import studio.cluvex.aether.ui.components.AetherMark
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import java.text.DateFormat
import java.util.Date

/**
 * Shows [LoginScreen] until somebody is signed in, then [content].
 *
 * [content] receives the account and a `signOut` callback; call it AFTER doing
 * whatever must happen first (MainActivity disconnects the VPN).
 */
@Composable
fun AuthGate(
    repo: AuthRepository,
    content: @Composable (account: Account, signOut: () -> Unit) -> Unit,
) {
    var account by remember { mutableStateOf(repo.currentAccount()) }
    val current = account
    if (current == null) {
        LoginScreen(repo = repo, onSignedIn = { account = it })
    } else {
        content(current) {
            repo.signOut()
            account = null
        }
    }
}

@Composable
fun LoginScreen(
    repo: AuthRepository,
    onSignedIn: (Account) -> Unit,
    modifier: Modifier = Modifier,
) {
    var username by rememberSaveable { mutableStateOf("") }
    // Deliberately NOT rememberSaveable: a password must never reach the
    // saved-instance-state bundle.
    var password by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<AuthFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    fun submit() {
        if (busy) return
        focus.clearFocus()
        busy = true
        failure = null
        scope.launch {
            repo.signIn(username, password)
                .onSuccess {
                    password = ""
                    onSignedIn(it)
                }
                .onFailure { failure = (it as? AuthException)?.failure ?: AuthFailure.NETWORK }
            busy = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AetherMark(Modifier.size(88.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.auth_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.auth_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))

        LtrOutlinedTextField(
            value = username,
            onValueChange = { username = it; failure = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.auth_username)) },
            keyboardType = KeyboardType.Email,
            enabled = !busy,
        )
        Spacer(Modifier.height(12.dp))
        LtrOutlinedTextField(
            value = password,
            onValueChange = { password = it; failure = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.auth_password)) },
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
        )

        failure?.let { f ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    when (f) {
                        AuthFailure.INVALID_INPUT -> R.string.auth_error_invalid_input
                        AuthFailure.WRONG_CREDENTIALS -> R.string.auth_error_wrong
                        AuthFailure.NETWORK -> R.string.auth_error_network
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = ::submit,
            enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.auth_sign_in))
            }
        }
    }
}

/** Compact account card shown at the top of the home screen. */
@Composable
fun AccountHeader(account: Account, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val planLine = account.expiresAtMillis?.let { ms ->
        stringResource(
            R.string.account_plan_expiry,
            account.plan,
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms)),
        )
    } ?: stringResource(R.string.account_plan, account.plan)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = account.displayName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = planLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
