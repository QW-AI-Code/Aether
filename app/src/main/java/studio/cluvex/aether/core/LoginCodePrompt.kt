package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream

/**
 * The Zero Trust e-mail login code: engine asks, user answers, engine continues.
 *
 * ## Why Zero Trust never worked (issue #12)
 *
 * The app treated enrolment as part of connecting: it put `AETHER_ACCESS_EMAIL`
 * into the engine's environment and started a tunnel. But an e-mail one-time code
 * cannot be resolved by a connect — Cloudflare mails a code and somebody has to
 * type it back. Nobody was there to type it, so the engine waited and the session
 * died with no explanation the UI could show. The user report is exact: *"no code
 * is sent to the e-mail and the app never asks for one, so this section is simply
 * ignored"* — and *"you cannot do the authentication inside the connection; it has
 * to be signed in beforehand."*
 *
 * ## What the engine already does
 *
 * Core 2.0.0 was built for a non-interactive parent. `zerotrust::prompt_login_code`
 * checks `stdin().is_terminal()`, and when it is NOT a terminal — which is exactly
 * how this app spawns the engine — it does not print a human prompt. It writes one
 * machine-readable line to stdout:
 *
 * ```
 * [zerotrust] login-code-needed attempt=1 email=someone@example.com
 * ```
 *
 * and then reads a line from **stdin**, with a 300 s timeout and three attempts
 * (`CODE_WAIT`, `CODE_ATTEMPTS`). So the whole protocol is: watch for that line,
 * ask the user, write the code plus a newline back. No JNI, no engine change.
 *
 * The app simply never listened. [ingest] is wired into the engine's existing
 * stdout loop in [AetherProcess]; [submit] writes to the process's stdin, which
 * nothing had ever used.
 *
 * ## Scope, honestly
 *
 * This covers the E-MAIL path only. The other two are unaffected because they need
 * no interaction: a service token is fetched by the engine on its own, and an
 * enrolment token pasted into Settings is already a credential.
 *
 * Since 1.4.0-r9 this prompt is the FALLBACK, not the primary path. Settings ->
 * Zero Trust can sign in before any connect ([ZeroTrustSignIn], the same four
 * HTTPS exchanges `zerotrust.rs` makes, no JNI needed), and when that sign-in is
 * valid the service hands the engine `AETHER_ACCESS_TOKEN` instead of the address
 * ([TeamSignInHandoff]), so the engine never prints the marker. This prompt still
 * runs for a user who skipped the sign-in, let it expire, or can reach Cloudflare
 * Access only through the upstream proxy (the engine honours it; the app's own
 * sign-in does not). See `docs/ZERO_TRUST.md`.
 */
object LoginCodePrompt {

    /** The marker the engine prints; must match `zerotrust::CODE_PROMPT_MARKER`. */
    private const val MARKER = "[zerotrust] login-code-needed"

    /** Engine-side limits, mirrored so the UI can describe them truthfully. */
    const val CODE_WAIT_SECONDS = 300
    const val MAX_ATTEMPTS = 3

    private const val TAG = "zerotrust"

    /** An outstanding request for a login code. */
    data class Request(
        val email: String,
        /** 1-based, as the engine counts it. Attempt > 1 means a code was rejected. */
        val attempt: Int,
        /** When the engine started waiting, for the UI's countdown. */
        val askedAtMs: Long = System.currentTimeMillis(),
    )

    private val _pending = MutableStateFlow<Request?>(null)

    /** Non-null while the engine is waiting for a code. Drives the dialog. */
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** The engine's stdin. Set by [AetherProcess] for the life of one engine. */
    @Volatile
    private var sink: OutputStream? = null

    /** Called by [AetherProcess] when an engine starts; clears any stale request. */
    fun attach(stdin: OutputStream?) {
        sink = stdin
        _pending.value = null
    }

    /**
     * Called when an engine exits.
     *
     * Clearing [pending] matters: a dialog left on screen after the engine is gone
     * would take a code that has nowhere to go, and the user would be told the code
     * was wrong when in truth nothing read it.
     */
    fun detach() {
        sink = null
        _pending.value = null
    }

    /**
     * Inspects one line of engine output. Returns true when it was the marker.
     *
     * Deliberately tolerant about the rest of the line: the app's own log format
     * prefixes it, so this matches on content rather than position.
     */
    fun ingest(line: String): Boolean {
        val at = line.indexOf(MARKER)
        if (at < 0) return false
        val tail = line.substring(at + MARKER.length)
        val email = field(tail, "email=") ?: ""
        val attempt = field(tail, "attempt=")?.toIntOrNull() ?: 1
        DiagnosticsLog.i(
            TAG,
            "The organization emailed a login code to ${if (email.isBlank()) "your address" else email} " +
                "(attempt $attempt of $MAX_ATTEMPTS). Waiting up to ${CODE_WAIT_SECONDS}s for it.",
        )
        _pending.value = Request(email = email, attempt = attempt)
        return true
    }

    /**
     * Sends [code] to the waiting engine. Returns false when there is nothing to
     * answer or the write fails.
     *
     * The newline is what the engine's `read_line` is blocked on, so it is not
     * optional. Flushing is not optional either: `Process.outputStream` is buffered,
     * and an unflushed code is indistinguishable from a user who never answered.
     */
    fun submit(code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return false
        val out = sink ?: run {
            DiagnosticsLog.w(TAG, "A login code was entered but the engine is no longer running.")
            _pending.value = null
            return false
        }
        return runCatching {
            out.write((trimmed + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()
            // NOT logged: the code itself is a single-use credential.
            DiagnosticsLog.i(TAG, "Login code sent to the engine.")
            _pending.value = null
            true
        }.getOrElse { error ->
            DiagnosticsLog.e(TAG, "Could not hand the login code to the engine: $error")
            false
        }
    }

    /**
     * Gives up on the current request.
     *
     * Nothing is written, so the engine keeps waiting until its own timeout and
     * then reports that no code arrived. That is the truthful outcome: the app
     * cannot cancel a prompt the engine owns.
     */
    fun dismiss() {
        if (_pending.value == null) return
        // r7: an empty line is a real cancel. The engine's prompt_login_code
        // trims the line and returns "no login code was entered" at once, so the
        // connect ends now instead of hanging for CODE_WAIT_SECONDS.
        val out = sink
        val cancelled = out != null && runCatching {
            out.write("\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }.isSuccess
        DiagnosticsLog.w(
            TAG,
            if (cancelled) {
                "The login code prompt was cancelled; the engine ends this sign-in now."
            } else {
                "The login code prompt was dismissed. The engine waits up to " +
                    "${CODE_WAIT_SECONDS}s and then gives up on this attempt."
            },
        )
        _pending.value = null
    }

    /** `key=value` up to the next space, or null. */
    private fun field(text: String, key: String): String? {
        val at = text.indexOf(key)
        if (at < 0) return null
        val from = at + key.length
        val end = text.indexOf(' ', from).let { if (it < 0) text.length else it }
        return text.substring(from, end).trim().takeIf { it.isNotEmpty() }
    }
}
