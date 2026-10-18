package studio.cluvex.aether.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.cluvex.aether.R
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionProfile

/**
 * Export and import of the tunnel settings as one small JSON file.
 *
 * ```json
 * { "format": "aether-settings", "version": 1,
 *   "settings": { "protocol": "MASQUE", "mtu": 1280, "bypassIran": true, ... } }
 * ```
 *
 * ## Why it copies the stored preferences instead of listing fields
 *
 * [ProfileStore] already knows every setting and how to turn the stored values
 * back into a [ConnectionProfile] with defaults and clamping. A second list of
 * fields here would be a second place to forget a new setting, so the export
 * copies whatever the store holds and the import runs the file through
 * [ProfileStore.fromPrefs], the same validation as a normal load. A setting added
 * later is exported and imported without touching this file.
 *
 * ## What is deliberately not in the file
 *
 * The Zero Trust sign-in (team, auth mode, client id, e-mail) and the upstream
 * proxy are identity and credentials, not tuning. They are never written, an
 * imported file is ignored for those keys, and an import keeps the values the
 * phone already has. The Zero Trust secrets, custom configs and the AI key live
 * in other stores and are never read here.
 *
 * ## How the screen works
 *
 * It uses the system file picker (`CreateDocument` / `OpenDocument`), so no
 * storage permission is needed. A file can still change DNS servers, endpoints and
 * routes, so the import asks before it replaces anything. It replaces the settings
 * through [onProfileChange], the path every other settings row uses, and is
 * disabled while connected like the rest of the settings.
 */
@Composable
internal fun BackupPage(
    profile: ConnectionProfile,
    editable: Boolean,
    onProfileChange: (ConnectionProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ProfileStore(context.applicationContext) }
    // A parsed file waiting for the user's confirmation.
    var pending by remember { mutableStateOf<ConnectionProfile?>(null) }

    // The outcome of the last export / import. It is shown on the page and also
    // spoken: a Toast is easy to miss for a screen-reader user, and most of all
    // right after the system file picker closes, which is when these appear.
    var status by remember { mutableStateOf<Int?>(null) }
    val view = LocalView.current
    fun report(@StringRes message: Int) {
        status = message
        view.announceForAccessibility(context.getString(message))
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching { writeExport(context, store, profile, uri) }
                }
                report(if (saved.isSuccess) R.string.backup_exported else R.string.backup_export_failed)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = withContext(Dispatchers.IO) { readImport(context, store, uri) }) {
                    is ImportResult.Ok -> pending = keepPrivate(result.profile, profile)
                    ImportResult.Newer -> report(R.string.backup_err_newer)
                    ImportResult.Invalid -> report(R.string.backup_err_invalid)
                }
            }
        }
    }

    SettingsScaffold(stringResource(R.string.backup_title), onBack, modifier) {
        // First on the page, so it is the first thing a screen reader reaches.
        status?.let { message ->
            settingsSection {
                SettingsGroup {
                    SettingsNoticeRow(text = stringResource(message), icon = Icons.Rounded.Info)
                }
            }
        }
        if (!editable) {
            settingsSection {
                SettingsGroup {
                    SettingsNoticeRow(
                        text = stringResource(R.string.backup_locked),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
        }
        settingsSection {
            SettingsGroup {
                ButtonSemantics(enabled = true) {
                    SettingsActionRow(
                        title = stringResource(R.string.backup_export),
                        summary = stringResource(R.string.backup_export_desc),
                        icon = Icons.Rounded.Save,
                        onClick = { exportLauncher.launch(EXPORT_FILE_NAME) },
                    )
                }
                RowDivider()
                ButtonSemantics(enabled = editable) {
                    SettingsActionRow(
                        title = stringResource(R.string.backup_import),
                        summary = stringResource(R.string.backup_import_desc),
                        icon = Icons.Rounded.FolderOpen,
                        enabled = editable,
                        onClick = { importLauncher.launch(IMPORT_MIME_TYPES) },
                    )
                }
            }
            GroupFooter(stringResource(R.string.backup_footer))
        }
    }

    pending?.let { incoming ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onProfileChange(incoming)
                    report(R.string.backup_imported)
                }) { Text(stringResource(R.string.backup_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/**
 * Gives a settings row the semantics a screen reader needs for a button.
 *
 * The shared action row is a plain `clickable` with no role, so TalkBack reads its
 * text and "double tap to activate" without saying it is a button, and a row that
 * is switched off is not clickable at all, so it is read as ordinary text with no
 * hint that it is unavailable. The wrapper merges the row into one focus stop,
 * marks it as a button, and marks it disabled when it is.
 */
@Composable
private fun ButtonSemantics(enabled: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
        },
    ) {
        content()
    }
}

// ---------------------------------------------------------------- the file

private const val EXPORT_FILE_NAME = "aether-settings.json"

/** Some file managers label a .json file as plain text or as an unknown binary, so accept anything. */
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/plain", "*/*")

private const val FORMAT = "aether-settings"
private const val VERSION = 1

/** Larger than any real settings file (a long app list is about 10 KB). */
private const val MAX_BYTES = 256 * 1024

/** Stored keys that identify the user or carry credentials. Never exported, never imported. */
private val PRIVATE_KEYS = setOf("team", "teamAuth", "accessId", "accessEmail", "upstreamProxy")

private sealed interface ImportResult {
    class Ok(val profile: ConnectionProfile) : ImportResult
    object Newer : ImportResult
    object Invalid : ImportResult
}

/**
 * Writes the settings file to [uri].
 *
 * The in-memory profile is saved first: the settings screen keeps its own copy and
 * persists it a moment after each change, so exporting straight after a change
 * would otherwise miss it.
 */
private suspend fun writeExport(
    context: Context,
    store: ProfileStore,
    profile: ConnectionProfile,
    uri: Uri,
) {
    store.save(profile)
    val settings = JSONObject()
    store.snapshot().asMap().entries
        .map { it.key.name to it.value }
        .filter { (name, _) -> name !in PRIVATE_KEYS }
        .sortedBy { (name, _) -> name }
        .forEach { (name, value) ->
            // The profile only stores strings, booleans and ints.
            if (value is String || value is Boolean || value is Int) settings.put(name, value)
        }
    val json = JSONObject()
        .put("format", FORMAT)
        .put("version", VERSION)
        .put("settings", settings)
        .toString(2)
    val stream = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw java.io.IOException("no output stream")
    stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
}

/** Reads at most [MAX_BYTES] from [uri] and turns it into a profile. Never throws. */
private fun readImport(context: Context, store: ProfileStore, uri: Uri): ImportResult {
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return ImportResult.Invalid
        val bytes = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                if (bytes.size() + n > MAX_BYTES) return ImportResult.Invalid
                bytes.write(buffer, 0, n)
            }
        }
        val root = JSONObject(String(bytes.toByteArray(), Charsets.UTF_8))
        if (root.optString("format") != FORMAT) return ImportResult.Invalid
        val version = root.optInt("version", 0)
        if (version < 1) return ImportResult.Invalid
        if (version > VERSION) return ImportResult.Newer
        val settings = root.optJSONObject("settings") ?: return ImportResult.Invalid

        val prefs = mutablePreferencesOf()
        for (name in settings.keys()) {
            if (name in PRIVATE_KEYS) continue
            when (val value = settings.opt(name)) {
                is String -> prefs[stringPreferencesKey(name)] = value
                is Boolean -> prefs[booleanPreferencesKey(name)] = value
                is Int -> prefs[intPreferencesKey(name)] = value
                else -> Unit // not part of the format
            }
        }
        // A value of the wrong type for its key (a string where a number belongs)
        // makes the mapper throw, which is "not a usable file", not a crash.
        ImportResult.Ok(store.fromPrefs(prefs))
    } catch (_: Exception) {
        ImportResult.Invalid
    }
}

/** [imported] with this phone's own sign-in and upstream proxy put back. */
private fun keepPrivate(imported: ConnectionProfile, current: ConnectionProfile): ConnectionProfile =
    imported.copy(
        team = current.team,
        teamAuth = current.teamAuth,
        accessClientId = current.accessClientId,
        accessClientSecret = current.accessClientSecret,
        accessEmail = current.accessEmail,
        accessToken = current.accessToken,
        accessSignInToken = current.accessSignInToken,
        upstreamProxy = current.upstreamProxy,
    )
