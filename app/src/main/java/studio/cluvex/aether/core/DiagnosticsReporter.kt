package studio.cluvex.aether.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.R
import studio.cluvex.aether.ai.AiRedaction
import studio.cluvex.aether.ui.components.copySensitive
import java.net.URLEncoder

/**
 * 1.7.0 (same version): "Send diagnostics".
 *
 * Crashes and failed connections can be reported to the developers' GitHub
 * repository with one tap. No server of our own and no secret token are
 * involved - a token inside a public APK would let anyone write to the repo -
 * so the report is filed as a GitHub issue from the user's own browser:
 *
 *  1. the full report is built and REDACTED (IPs, hostnames, UUIDs, keys are
 *     removed by the same [AiRedaction] the AI assistant uses);
 *  2. it is copied to the clipboard, flagged sensitive;
 *  3. the repository's "new issue" page opens with a short pre-filled title and
 *     summary, and the user pastes the full report and submits.
 *
 * Nothing is sent without that last tap. The switch in Settings -> App turns
 * the whole feature off, including the prompt after a failed connection.
 */
object DiagnosticsReporter {

    /** Where reports are filed. Change this to your own repository. */
    const val ISSUES_URL = "https://github.com/QW-AI-Code/Aether/issues/new"

    enum class Kind(val tag: String) { CRASH("crash"), CONNECTION_FAILED("connection failed"), MANUAL("report") }

    private const val PREFS = "aether_diagnostics_share"
    private const val KEY_ENABLED = "enabled"
    private const val LOG_LINES = 400
    /** GitHub rejects very long URLs; the pre-filled summary stays far below that. */
    private const val SUMMARY_CHARS = 1_200

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** The redacted report: environment, what happened, and the recent log. */
    fun buildReport(kind: Kind, detail: String): String {
        val log = runCatching { DiagnosticsLog.exportRedactedText() }.getOrDefault("")
            .lines().takeLast(LOG_LINES).joinToString("\n")
        return buildString {
            appendLine("### Aether diagnostics report")
            appendLine("- kind: ${kind.tag}")
            appendLine("- app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- abi: ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}")
            appendLine()
            appendLine("#### What happened")
            appendLine(AiRedaction.redactAll(detail.ifBlank { "-" }))
            appendLine()
            appendLine("#### Recent log (redacted)")
            appendLine("```")
            appendLine(log.ifBlank { "-" })
            appendLine("```")
        }
    }

    /** The new-issue URL with a short, redacted summary. */
    fun issueUri(kind: Kind, detail: String, pasteHint: String): Uri {
        val short = AiRedaction.redactAll(detail).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80)
        val title = "[${kind.tag}] Aether ${BuildConfig.VERSION_NAME}" + if (short.isNotEmpty()) ": $short" else ""
        val body = buildReport(kind, detail).take(SUMMARY_CHARS) + "\n\n---\n" + pasteHint + "\n\n"
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        return Uri.parse("$ISSUES_URL?title=${enc(title)}&body=${enc(body)}")
    }

    /**
     * Copies the full report and opens the new-issue page. Returns false when
     * reporting is off or no browser could be opened (the report is still copied).
     */
    fun send(context: Context, kind: Kind, detail: String): Boolean {
        if (!isEnabled(context)) return false
        val report = buildReport(kind, detail)
        copySensitive(context, report, "aether-diagnostics")
        val uri = issueUri(kind, detail, context.getString(R.string.diag_share_paste_hint))
        DiagnosticsLog.i("diag", "Diagnostics report prepared (${kind.tag}); opening the issue page")
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
