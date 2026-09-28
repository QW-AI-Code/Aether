package studio.cluvex.aether.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AccessToken
import studio.cluvex.aether.core.IdentityVault
import studio.cluvex.aether.core.TeamMembership
import studio.cluvex.aether.core.TeamSignInHandoff
import studio.cluvex.aether.core.TeamSignInRecord
import studio.cluvex.aether.core.ZeroTrustSignIn
import studio.cluvex.aether.core.ZeroTrustWeb
import studio.cluvex.aether.data.TeamSignInStore
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.ui.components.LtrOutlinedTextField
import studio.cluvex.aether.ui.theme.AetherViolet
import studio.cluvex.aether.ui.theme.Navy850

/**
 * Settings -> Zero Trust: "sign in first, then connect" (1.4.0-r9, issue #12).
 *
 * ## What this group answers
 *
 * The one question a user has before tapping connect: *will this connect need me?*
 *
 *  * **Already a member** - the engine holds an identity for this team and will
 *    load it; no method is consulted at all ([TeamMembership]). The group says so
 *    and offers the one action that makes sense then: removing the membership
 *    (revoked device, joining as someone else).
 *  * **E-mail code, not a member** - the user can sign in HERE, while disconnected
 *    ([ZeroTrustSignInDialog] drives [ZeroTrustSignIn]); the resulting token is
 *    sealed ([TeamSignInStore]) and the VPN service hands it to the engine on the
 *    next connect instead of the address, so the connect never stops for a code.
 *    Skipping the sign-in keeps the old in-connect dialog as the fallback.
 *  * **Service token, not a member** - runs unattended anyway; the group offers a
 *    test that performs the engine's exact exchange, so a wrong secret shows up
 *    here rather than as a failed connect.
 *  * **Enrolment token, not a member** - the paste above is already checked as it
 *    is typed; the group only states what the next connect will do.
 *
 * The status line and the service agree by construction: both ask
 * [TeamSignInHandoff.decide].
 *
 * ## Only while disconnected
 *
 * Every action that talks to Cloudflare or touches the identity is enabled only
 * when [editable] (tunnel down). The sign-in goes out on the direct network from
 * the app's own process, and removing a membership under a running engine would
 * be undone by that engine writing its identity back.
 */
@Composable
internal fun ZeroTrustMembershipGroup(profile: ConnectionProfile, editable: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val team = ZeroTrustWeb.normalizeTeam(profile.team)

    // Bumped after every action that changes what is on disk, so the two reads
    // below run again. `editable` is a key too: a connect that just ended may
    // have enrolled the device or spent the sign-in.
    var refresh by remember { mutableIntStateOf(0) }

    // null = still reading. File checks and a keystore decrypt: off the main thread.
    val enrolled by produceState<Boolean?>(null, team, editable, refresh) {
        value = if (team == null) {
            false
        } else {
            withContext(Dispatchers.IO) { TeamMembership.isEnrolled(context.filesDir, team) }
        }
    }
    val record by produceState<TeamSignInRecord?>(null, editable, refresh) {
        value = withContext(Dispatchers.IO) { TeamSignInStore(context).load() }
    }

    var showSignIn by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    SettingsGroup {
        when {
            team == null -> SettingsNoticeRow(stringResource(R.string.zt_need_team), Icons.Rounded.Info)
            enrolled == null -> SettingsNoticeRow(stringResource(R.string.zt_checking), Icons.Rounded.Info)
            enrolled == true -> {
                SettingsNoticeRow(stringResource(R.string.zt_enrolled, team), Icons.Rounded.CheckCircle)
                RowDivider(inset = false)
                SettingsActionRow(
                    title = stringResource(R.string.zt_forget),
                    summary = stringResource(
                        if (editable) R.string.zt_forget_desc else R.string.zt_disconnect_first,
                    ),
                    icon = Icons.Rounded.Delete,
                    enabled = editable,
                    destructive = true,
                    onClick = { confirmForget = true },
                )
            }
            else -> when (profile.teamAuth) {
                TeamAuth.EMAIL -> EmailSignInRows(
                    profile = profile,
                    record = record,
                    editable = editable,
                    onSignIn = { showSignIn = true },
                    onSignOut = {
                        scope.launch {
                            withContext(Dispatchers.IO) { TeamSignInStore(context).clear() }
                            refresh++
                            toast(context, context.getString(R.string.zt_signed_out))
                        }
                    },
                )
                TeamAuth.SERVICE_TOKEN -> ServiceTokenRows(profile, team, editable)
                TeamAuth.TOKEN -> SettingsNoticeRow(stringResource(R.string.zt_token_pending), Icons.Rounded.Info)
                TeamAuth.OFF -> Unit
            }
        }
    }

    if (showSignIn && team != null) {
        ZeroTrustSignInDialog(
            rawTeam = profile.team,
            email = profile.accessEmail.trim(),
            onDismiss = { showSignIn = false },
            onSignedIn = {
                showSignIn = false
                refresh++
                toast(context, context.getString(R.string.zt_signed_in))
            },
        )
    }

    if (confirmForget && team != null) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            icon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.zt_forget_title)) },
            text = { Text(stringResource(R.string.zt_forget_body, team)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        scope.launch {
                            // Checked at the moment of the action, not when the
                            // row was drawn: a connect may have started since.
                            if (IdentityVault.running) {
                                toast(context, context.getString(R.string.zt_forget_busy))
                            } else {
                                withContext(Dispatchers.IO) {
                                    TeamMembership.forget(context.filesDir, team)
                                }
                                refresh++
                                toast(context, context.getString(R.string.zt_forget_done))
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.zt_forget_ok), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.zt_cancel)) }
            },
            containerColor = Navy850,
        )
    }
}

// ------------------------------------------------------------ e-mail method

@Composable
private fun EmailSignInRows(
    profile: ConnectionProfile,
    record: TeamSignInRecord?,
    editable: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    // The expiry line counts down; a coarse tick is plenty for minutes.
    val now by produceState(initialValue = System.currentTimeMillis() / 1000L) {
        while (true) {
            delay(STATUS_TICK_MS)
            value = System.currentTimeMillis() / 1000L
        }
    }
    // Not enrolled here (the caller handled that), so the lambda is constant.
    val decision = TeamSignInHandoff.decide(record, profile.team, profile.accessEmail, { false }, now)

    val (statusIcon, status) = when (decision) {
        TeamSignInHandoff.Decision.NoSignIn ->
            Icons.Rounded.MailOutline to stringResource(R.string.zt_signin_none)
        TeamSignInHandoff.Decision.OtherProfile ->
            Icons.Rounded.WarningAmber to stringResource(
                R.string.zt_signin_other,
                record?.email.orEmpty(),
                record?.team.orEmpty(),
            )
        // Not reachable with `{ false }` above; listed so the `when` stays
        // exhaustive if the decision ever grows.
        TeamSignInHandoff.Decision.AlreadyEnrolled ->
            Icons.Rounded.CheckCircle to stringResource(R.string.zt_signin_none)
        TeamSignInHandoff.Decision.Unusable ->
            Icons.Rounded.WarningAmber to stringResource(R.string.zt_signin_expired)
        is TeamSignInHandoff.Decision.Use ->
            Icons.Rounded.CheckCircle to (
                stringResource(R.string.zt_signin_ready, decision.record.email) + "\n" +
                    expiryLine(decision.record.secondsLeft(now))
                )
    }
    SettingsNoticeRow(status, statusIcon)

    val emailReady = ZeroTrustWeb.plausibleEmail(profile.accessEmail)
    RowDivider(inset = false)
    SettingsActionRow(
        title = stringResource(if (record == null) R.string.zt_signin else R.string.zt_signin_again),
        summary = stringResource(
            when {
                !editable -> R.string.zt_disconnect_first
                !emailReady -> R.string.zt_signin_need_email
                else -> R.string.zt_signin_desc
            },
        ),
        icon = Icons.Rounded.Key,
        enabled = editable && emailReady,
        onClick = onSignIn,
    )

    if (record != null) {
        RowDivider(inset = false)
        SettingsActionRow(
            title = stringResource(R.string.zt_signout),
            summary = stringResource(R.string.zt_signout_desc),
            icon = Icons.Rounded.Close,
            onClick = onSignOut,
        )
    }
}

/** How long the stored sign-in has left, in the coarsest unit that is honest. */
@Composable
private fun expiryLine(secondsLeft: Long?): String = when {
    secondsLeft == null -> stringResource(R.string.zt_signin_left_nodate)
    secondsLeft < 60L -> stringResource(R.string.zt_signin_left_soon)
    secondsLeft < 3_600L -> (secondsLeft / 60L).toInt().let {
        pluralStringResource(R.plurals.zt_signin_left_minutes, it, it)
    }
    secondsLeft < 86_400L -> (secondsLeft / 3_600L).toInt().let {
        pluralStringResource(R.plurals.zt_signin_left_hours, it, it)
    }
    else -> (secondsLeft / 86_400L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let {
        pluralStringResource(R.plurals.zt_signin_left_days, it, it)
    }
}

// ------------------------------------------------------- service-token method

private sealed interface ServiceTest {
    data object Idle : ServiceTest
    data object Running : ServiceTest
    data object Passed : ServiceTest
    data class Failed(val failure: ZeroTrustSignIn.Failure) : ServiceTest
}

@Composable
private fun ServiceTokenRows(profile: ConnectionProfile, team: String, editable: Boolean) {
    val scope = rememberCoroutineScope()
    // Keyed to the credentials: a verdict about a secret that has since been
    // edited is not a verdict about the one in the field.
    var result by remember(profile.team, profile.accessClientId, profile.accessClientSecret) {
        mutableStateOf<ServiceTest>(ServiceTest.Idle)
    }
    val complete = profile.accessClientId.isNotBlank() && profile.accessClientSecret.isNotBlank()

    SettingsNoticeRow(stringResource(R.string.zt_service_pending), Icons.Rounded.Info)
    RowDivider(inset = false)
    SettingsActionRow(
        title = stringResource(R.string.zt_svc_test),
        summary = when (val r = result) {
            ServiceTest.Running -> stringResource(R.string.zt_svc_testing)
            ServiceTest.Passed -> stringResource(R.string.zt_svc_ok)
            is ServiceTest.Failed -> failureText(r.failure, ZeroTrustWeb.teamHost(team))
            ServiceTest.Idle -> stringResource(
                when {
                    !editable -> R.string.zt_disconnect_first
                    !complete -> R.string.zt_svc_need_both
                    else -> R.string.zt_svc_test_desc
                },
            )
        },
        icon = Icons.Rounded.Science,
        enabled = editable && complete && result != ServiceTest.Running,
        onClick = {
            val rawTeam = profile.team
            val id = profile.accessClientId
            val secret = profile.accessClientSecret
            result = ServiceTest.Running
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    ZeroTrustSignIn().testServiceToken(rawTeam, id, secret)
                }
                result = when (outcome) {
                    is ZeroTrustSignIn.Result.Ok -> ServiceTest.Passed
                    is ZeroTrustSignIn.Result.Failed -> ServiceTest.Failed(outcome.failure)
                }
            }
        },
    )
}

// ------------------------------------------------------------ sign-in dialog

/** Why the dialog stopped, beyond what [ZeroTrustSignIn.Failure] covers. */
private sealed interface Problem {
    data class Remote(val failure: ZeroTrustSignIn.Failure) : Problem

    /** Cloudflare's token was already past `exp` on arrival: the phone's clock. */
    data object ExpiredOnArrival : Problem

    /** The sealed write did not read back; nothing usable was stored. */
    data object StoreFailed : Problem
}

private sealed interface SignInStep {
    /** Opening the enrolment page and asking for the e-mail. */
    data object Sending : SignInStep

    /** A code is out; waiting for the user. */
    data class Code(
        val session: ZeroTrustSignIn.Session,
        /** Codes Cloudflare rejected since the last one was sent. */
        val rejected: Int,
        /** When the current code was requested, for the resend cool-down. */
        val sentAtMs: Long,
        val problem: Problem? = null,
        val resent: Boolean = false,
    ) : SignInStep

    /** Talking to Cloudflare about [from]'s session (confirming or resending). */
    data class Busy(val from: SignInStep.Code, val resending: Boolean) : SignInStep

    /** Stopped before a code could be asked for, or after a local failure. */
    data class Failed(val problem: Problem) : SignInStep
}

/**
 * The pre-connect e-mail sign-in, start to finish.
 *
 * ## Shape
 *
 * It opens by asking Cloudflare for a code (so there is no "Send code" button to
 * press first - the user already asked for exactly that by tapping Sign in), then
 * takes the code, confirms it and stores the result. The rules the engine applies
 * to the in-connect prompt apply here too: [MAX_CODE_ATTEMPTS] rejected codes and
 * the session needs a fresh code, which the user can request after a short
 * cool-down so a stuck finger cannot flood the inbox.
 *
 * ## Safety
 *
 *  * `SecureFlagPolicy.SecureOn`: a dialog is its own window and does not inherit
 *    the page's FLAG_SECURE, and this one holds a one-time code.
 *  * Tapping outside does not dismiss (a typed code must not be lost by accident);
 *    back and Cancel do, at any time. Leaving cancels the coroutine scope, so an
 *    exchange still in flight finishes on its I/O thread and is thrown away - its
 *    token is never stored.
 *  * The session - which holds Cloudflare's cookies - lives only in this dialog's
 *    state and is gone with it.
 */
@Composable
internal fun ZeroTrustSignInDialog(
    rawTeam: String,
    email: String,
    onDismiss: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { ZeroTrustSignIn() }
    val team = remember(rawTeam) { ZeroTrustWeb.normalizeTeam(rawTeam) ?: rawTeam.trim() }
    val host = remember(team) { ZeroTrustWeb.teamHost(team) }
    val signedIn by rememberUpdatedState(onSignedIn)

    var step by remember { mutableStateOf<SignInStep>(SignInStep.Sending) }
    var code by remember { mutableStateOf("") }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun requestCode() {
        step = SignInStep.Sending
        code = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) { client.requestCode(rawTeam, email) }
            step = when (result) {
                is ZeroTrustSignIn.Result.Ok ->
                    SignInStep.Code(result.value, rejected = 0, sentAtMs = System.currentTimeMillis())
                is ZeroTrustSignIn.Result.Failed -> SignInStep.Failed(Problem.Remote(result.failure))
            }
        }
    }

    /** Checks and seals Cloudflare's token. Null on success. */
    suspend fun store(token: String): Problem? {
        val nowSeconds = System.currentTimeMillis() / 1000L
        if (AccessToken.inspect(token, nowSeconds) !is AccessToken.Verdict.Usable) {
            return Problem.ExpiredOnArrival
        }
        val record = TeamSignInRecord.of(rawTeam, email, token, nowSeconds)
            ?: return Problem.Remote(ZeroTrustSignIn.Failure.BadTeam)
        val readBack = withContext(Dispatchers.IO) {
            val vault = TeamSignInStore(context)
            vault.save(record)
            vault.load()?.token
        }
        return if (readBack == record.token) null else Problem.StoreFailed
    }

    fun submit() {
        val current = step as? SignInStep.Code ?: return
        val typed = code.trim()
        if (typed.isEmpty() || current.rejected >= MAX_CODE_ATTEMPTS) return
        step = SignInStep.Busy(current, resending = false)
        scope.launch {
            val result = withContext(Dispatchers.IO) { client.submitCode(current.session, typed) }
            // null = signed in and stored.
            val next: SignInStep? = when (result) {
                is ZeroTrustSignIn.Result.Failed ->
                    current.copy(problem = Problem.Remote(result.failure), resent = false)
                is ZeroTrustSignIn.Result.Ok -> when (val outcome = result.value) {
                    is ZeroTrustSignIn.CodeOutcome.Rejected -> {
                        code = ""
                        current.copy(rejected = current.rejected + 1, problem = null, resent = false)
                    }
                    is ZeroTrustSignIn.CodeOutcome.Token ->
                        store(outcome.token)?.let { SignInStep.Failed(it) }
                }
            }
            if (next != null) {
                step = next
            } else {
                signedIn()
            }
        }
    }

    fun resend() {
        val current = step as? SignInStep.Code ?: return
        step = SignInStep.Busy(current, resending = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { client.resendCode(current.session) }
            step = when (result) {
                is ZeroTrustSignIn.Result.Ok -> {
                    code = ""
                    current.copy(rejected = 0, sentAtMs = System.currentTimeMillis(), problem = null, resent = true)
                }
                is ZeroTrustSignIn.Result.Failed ->
                    current.copy(problem = Problem.Remote(result.failure), resent = false)
            }
        }
    }

    LaunchedEffect(Unit) { requestCode() }

    // Resend cool-down clock: ticks only while a cool-down is running.
    val sentAt = (step as? SignInStep.Code)?.sentAtMs
    LaunchedEffect(sentAt) {
        if (sentAt == null) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            if (nowMs - sentAt >= RESEND_COOLDOWN_MS) break
            delay(1_000L)
        }
    }

    val current = step
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            securePolicy = SecureFlagPolicy.SecureOn,
        ),
        icon = { Icon(Icons.Rounded.MailOutline, contentDescription = null, tint = AetherViolet) },
        title = { Text(stringResource(R.string.zt_dlg_title, team)) },
        text = {
            Column {
                when (current) {
                    SignInStep.Sending -> Working(stringResource(R.string.zt_dlg_sending, email))
                    is SignInStep.Failed -> Text(
                        text = problemText(current.problem, host),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is SignInStep.Code, is SignInStep.Busy -> {
                        val codeStep = if (current is SignInStep.Code) current else (current as SignInStep.Busy).from
                        val busy = current is SignInStep.Busy
                        val exhausted = codeStep.rejected >= MAX_CODE_ATTEMPTS
                        Text(
                            text = stringResource(R.string.zt_dlg_code_body, email),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (codeStep.resent) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.zt_dlg_resent),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val warning: String? = when {
                            codeStep.problem != null -> problemText(codeStep.problem, host)
                            exhausted -> stringResource(R.string.zt_dlg_exhausted)
                            codeStep.rejected > 0 -> stringResource(
                                R.string.zt_dlg_rejected,
                                codeStep.rejected + 1,
                                MAX_CODE_ATTEMPTS,
                            )
                            else -> null
                        }
                        if (warning != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = warning,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LtrOutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            enabled = !busy && !exhausted,
                            singleLine = true,
                            label = { Text(stringResource(R.string.zt_code_label)) },
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        if (busy) {
                            Working(
                                stringResource(
                                    if ((current as SignInStep.Busy).resending) {
                                        R.string.zt_dlg_resending
                                    } else {
                                        R.string.zt_dlg_verifying
                                    },
                                ),
                            )
                        } else {
                            val waitMs = RESEND_COOLDOWN_MS - (nowMs - codeStep.sentAtMs)
                            val waitSeconds = ((waitMs + 999L) / 1_000L).toInt()
                            TextButton(onClick = { resend() }, enabled = waitSeconds <= 0) {
                                Text(
                                    if (waitSeconds > 0) {
                                        pluralStringResource(R.plurals.zt_dlg_resend_wait, waitSeconds, waitSeconds)
                                    } else {
                                        stringResource(R.string.zt_dlg_resend)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (current) {
                is SignInStep.Failed -> if (current.problem.retryable()) {
                    TextButton(onClick = { requestCode() }) { Text(stringResource(R.string.zt_dlg_retry)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.zt_dlg_close)) }
                }
                is SignInStep.Code -> TextButton(
                    onClick = { submit() },
                    enabled = code.isNotBlank() && current.rejected < MAX_CODE_ATTEMPTS,
                ) { Text(stringResource(R.string.zt_dlg_confirm)) }
                else -> TextButton(onClick = {}, enabled = false) { Text(stringResource(R.string.zt_dlg_confirm)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.zt_cancel)) }
        },
        containerColor = Navy850,
    )
}

/** A spinner and a line of text, for the steps that wait on Cloudflare. */
@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AetherViolet)
        Spacer(Modifier.width(12.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Whether asking Cloudflare again could plausibly give a different answer. */
private fun Problem.retryable(): Boolean = when (val p = this) {
    Problem.ExpiredOnArrival, Problem.StoreFailed -> true
    is Problem.Remote -> when (p.failure) {
        ZeroTrustSignIn.Failure.BadTeam,
        ZeroTrustSignIn.Failure.BadEmail,
        ZeroTrustSignIn.Failure.NoEmailCode -> false
        else -> true
    }
}

@Composable
private fun problemText(problem: Problem, host: String): String = when (problem) {
    Problem.ExpiredOnArrival -> stringResource(R.string.zt_err_expired_on_arrival)
    Problem.StoreFailed -> stringResource(R.string.zt_err_store)
    is Problem.Remote -> failureText(problem.failure, host)
}

/** One sentence per [ZeroTrustSignIn.Failure], naming what the user can do. */
@Composable
private fun failureText(failure: ZeroTrustSignIn.Failure, host: String): String = when (failure) {
    ZeroTrustSignIn.Failure.BadTeam -> stringResource(R.string.zt_err_team)
    ZeroTrustSignIn.Failure.BadEmail -> stringResource(R.string.zt_err_email)
    ZeroTrustSignIn.Failure.NoEmailCode -> stringResource(R.string.zt_err_no_otp)
    ZeroTrustSignIn.Failure.NoNonce -> stringResource(R.string.zt_err_no_nonce)
    is ZeroTrustSignIn.Failure.Refused -> stringResource(R.string.zt_err_refused, failure.status)
    is ZeroTrustSignIn.Failure.NoToken -> stringResource(R.string.zt_svc_no_token, failure.status)
    is ZeroTrustSignIn.Failure.Network -> stringResource(R.string.zt_err_network, host, failure.detail)
}

private fun toast(context: Context, text: String) =
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

/** `zerotrust::CODE_ATTEMPTS`: the engine's own limit per mailed code. */
private const val MAX_CODE_ATTEMPTS = 3

/** Between two "send a new code" requests. */
private const val RESEND_COOLDOWN_MS = 30_000L

/** How often the expiry line is recomputed. */
private const val STATUS_TICK_MS = 30_000L
