package studio.cluvex.aether.data

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import studio.cluvex.aether.ai.AiModelPolicy

/**
 * Its own DataStore, deliberately not [ProfileStore]'s.
 *
 * The connection profile is handed to the engine on every connect and is wiped
 * by "reset all settings". The AI configuration is neither: it is an account
 * credential plus three preferences that have nothing to do with the tunnel, and
 * losing the key because somebody reset their MTU would be infuriating. Separate
 * file, separate lifetime - the same reasoning [OnboardingStore] uses.
 */
private val Context.aiDataStore by preferencesDataStore(name = "aether_ai")

/**
 * Everything the AI layer needs to know about how the user has configured it.
 *
 * [Immutable] for the same reason [studio.cluvex.aether.model.ConnectionProfile]
 * is: without it the Compose compiler infers the class as unstable because of the
 * `List` member, and then it cannot skip a single composable that takes it - so
 * one keystroke in the API-key field would recompose every AI row on the page.
 */
@Immutable
data class AiSettings(
    /** The user's Gemini API key. Read back from [SecretStore], never from prefs. */
    val apiKey: String = "",
    /** Bare model id the user picked, e.g. `gemini-2.0-flash`. Blank = not chosen. */
    val model: String = "",
    /**
     * Model ids the last successful discovery found for THIS key.
     *
     * Cached because discovery needs the tunnel: without a cache the model picker
     * would be empty every time the user opened it while disconnected, which
     * reads as "the app lost my settings" rather than "we cannot reach Google
     * right now".
     */
    val discoveredModels: List<String> = emptyList(),
    /**
     * True when [model] was picked by the user in the model sheet, false when the
     * app picked it. 1.4.0-r5: only an APP pick is replaced by the default
     * (Gemini 3.1 Flash-Lite) when a key is entered or the list is refreshed; a
     * deliberate user choice is never overridden while the key can still use it.
     */
    val modelUserPicked: Boolean = false,
    /**
     * Analyse the log on every connect - and, 1.4.0-r5, give the chat assistant a
     * redacted excerpt of the current log with every question.
     *
     * 1.4.0-r5: default OFF. Sending the log to a remote model is opt-in: the user
     * turns it on when they want it, and nothing leaves the device before that.
     */
    val autoOptimize: Boolean = false,
    /**
     * Write a proposal straight into the profile instead of waiting for a tap.
     *
     * Defaults OFF on purpose. The proposal comes from a remote model, and a
     * setting that changes itself behind the user's back is indistinguishable
     * from a bug when the next connect behaves differently. Opt in explicitly.
     */
    val autoApply: Boolean = false,
    /** Show the AI icon next to individual options. */
    val showHints: Boolean = true,
) {
    val hasKey: Boolean get() = apiKey.isNotBlank()

    /**
     * The model to actually call: the chosen one, or - 1.4.0-r5 - the default
     * (Gemini 3.1 Flash-Lite when the key can see it) rather than simply the
     * first discovered id, which was the newest and lowest-allowance model.
     */
    val effectiveModel: String
        get() = model.ifBlank {
            AiModelPolicy.pickDefaultId(discoveredModels)
                ?: discoveredModels.firstOrNull().orEmpty()
        }
}

/** Persists [AiSettings]; the key itself goes to the Keystore-sealed vault. */
class GeminiStore(private val context: Context) {

    private object Keys {
        val model = stringPreferencesKey("model")
        val models = stringPreferencesKey("models")
        val modelUserPicked = booleanPreferencesKey("modelUserPicked")
        // 1.4.0-r5: a NEW key name. The old "autoOptimize" key defaulted to ON, so
        // every existing install has it stored as true without the user ever
        // having chosen it. Reading a fresh key makes the new default (OFF) apply
        // to everyone once; from then on the user's own choice is stored here.
        val autoOptimize = booleanPreferencesKey("autoOptimize_v2")
        val autoApply = booleanPreferencesKey("autoApply")
        val showHints = booleanPreferencesKey("showHints")
    }

    private val secrets = SecretStore(context)

    val settings: Flow<AiSettings> = context.aiDataStore.data.map { prefs ->
        AiSettings(
            apiKey = secrets.read(SecretStore.GEMINI_KEY),
            model = prefs[Keys.model] ?: "",
            discoveredModels = prefs[Keys.models]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            modelUserPicked = prefs[Keys.modelUserPicked] ?: false,
            autoOptimize = prefs[Keys.autoOptimize] ?: false,
            autoApply = prefs[Keys.autoApply] ?: false,
            showHints = prefs[Keys.showHints] ?: true,
        )
    }

    /**
     * Stores the key sealed with a hardware-backed AES-GCM key.
     *
     * Trimmed first: an API key pasted from a web page almost always arrives with
     * a trailing newline or a stray space, and a key with whitespace in it fails
     * authentication with a 400 that says nothing useful about why.
     */
    suspend fun saveKey(key: String) {
        val trimmed = key.trim()
        val previous = secrets.read(SecretStore.GEMINI_KEY)
        secrets.write(SecretStore.GEMINI_KEY, trimmed)
        // 1.4.0-r5: a different key starts on the default model (3.1 Flash-Lite).
        if (trimmed != previous) resetModelChoice()
        // Touch the store so the settings flow re-emits: the key lives outside
        // DataStore, so nothing else would tell a collector it changed.
        context.aiDataStore.edit { it[Keys.model] = it[Keys.model] ?: "" }
    }

    /**
     * @param byUser true when the user tapped the model in the picker; false for
     *   the app's own default pick. See [AiSettings.modelUserPicked].
     */
    suspend fun saveModel(model: String, byUser: Boolean = true) {
        context.aiDataStore.edit {
            it[Keys.model] = model
            it[Keys.modelUserPicked] = byUser && model.isNotBlank()
        }
    }

    /**
     * 1.4.0-r5: a newly entered key starts from the default model again. The
     * previous key's choice (and its cached list) says nothing about what the new
     * key may use, and a stale pick would skip the default the user was promised.
     */
    suspend fun resetModelChoice() {
        context.aiDataStore.edit { prefs ->
            prefs[Keys.model] = ""
            prefs[Keys.models] = ""
            prefs[Keys.modelUserPicked] = false
        }
    }

    suspend fun saveDiscovered(models: List<String>) {
        context.aiDataStore.edit { it[Keys.models] = models.joinToString(",") }
    }

    suspend fun saveAutoOptimize(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.autoOptimize] = enabled }
    }

    suspend fun saveAutoApply(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.autoApply] = enabled }
    }

    suspend fun saveShowHints(enabled: Boolean) {
        context.aiDataStore.edit { it[Keys.showHints] = enabled }
    }

    /** Forgets the key, the model choice and the cached model list. */
    suspend fun forget() {
        secrets.write(SecretStore.GEMINI_KEY, "")
        context.aiDataStore.edit { prefs ->
            prefs[Keys.model] = ""
            prefs[Keys.models] = ""
            prefs[Keys.modelUserPicked] = false
        }
    }
}
