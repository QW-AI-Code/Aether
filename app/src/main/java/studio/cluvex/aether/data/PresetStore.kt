package studio.cluvex.aether.data

import java.util.Base64
import studio.cluvex.aether.core.KeyVault
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import studio.cluvex.aether.custom.CustomConfigStore
import studio.cluvex.aether.model.ConnectionPreset
import studio.cluvex.aether.model.ConnectionProfile

// Separate file from "aether_profile": deleting it can never touch the live profile.
private val Context.presetDataStore: DataStore<Preferences> by preferencesDataStore(name = "aether_presets")

class PresetStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.presetDataStore)

    private val json = Json { ignoreUnknownKeys = true }

    data class State(val presets: List<ConnectionPreset> = emptyList(), val activeId: String? = null) {
        val active: ConnectionPreset? get() = presets.firstOrNull { it.id == activeId }
    }

    data class DeleteResult(val newActiveId: String?, val deletedWasActive: Boolean)

    private object Keys {
        val presets = stringPreferencesKey("presets_json")
        val active = stringPreferencesKey("active_preset")
    }

    val state: Flow<State> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { readState(it) }
        .flowOn(Dispatchers.IO)

    suspend fun snapshot(): State = state.first()

    /**
     * Installs an immutable factory baseline, independently of the live settings.
     * Legacy records (even ones called Default or renamed/deleted by the user)
     * remain user records. Never infer system identity from a localized name.
     */
    suspend fun ensureMigrated(
        current: ConnectionProfile,
        custom: CustomConfigStore.State,
        defaultName: String,
        now: Long = System.currentTimeMillis(),
        preservedName: String = "Saved settings",
    ) {
        dataStore.edit { prefs ->
            val st = readState(prefs)
            if (st.presets.any { it.isSystemDefault }) return@edit
            val baseline = capture(ConnectionPreset.SYSTEM_DEFAULT_ID, defaultName,
                ConnectionProfile(), CustomConfigStore.State(), now)
            // Preserve an existing installation's current settings, not just its
            // stored presets. First installation with untouched settings needs no copy.
            val live = capture(newId(), preservedName, current, custom, now)
            val activeMatches = st.active?.sameContentAs(live) == true
            val preserved = live.takeUnless { activeMatches || it.sameContentAs(baseline) }
            prefs[Keys.presets] = encode(listOf(baseline) + st.presets + listOfNotNull(preserved))
            prefs[Keys.active] = preserved?.id ?: st.activeId?.takeIf { activeMatches } ?: baseline.id
        }
    }

    /** Keeps user presets equal to live settings; the factory baseline is never synchronized. */
    suspend fun syncActive(
        profile: ConnectionProfile,
        custom: CustomConfigStore.State,
        now: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { prefs ->
            val st = readState(prefs)
            val active = st.active ?: return@edit
            if (active.isSystemDefault) return@edit
            val updated = capture(active.id, active.name, profile, custom, now)
            if (updated.sameContentAs(active)) return@edit
            prefs[Keys.presets] = encode(st.presets.map { if (it.id == active.id) updated else it })
        }
    }

    /** Copies the live settings into a new preset and makes it active (settings stay identical). */
    suspend fun saveAsNew(
        name: String,
        profile: ConnectionProfile,
        custom: CustomConfigStore.State,
        now: Long = System.currentTimeMillis(),
    ): ConnectionPreset? {
        val clean = ConnectionPreset.cleanName(name)
        if (clean.isEmpty()) return null
        var created: ConnectionPreset? = null
        dataStore.edit { prefs ->
            val st = readState(prefs)
            if (st.presets.count { !it.isSystemDefault } >= MAX_USER_PRESETS) return@edit
            val p = capture(newId(), clean, profile, custom, now)
            prefs[Keys.presets] = encode(st.presets + p)
            prefs[Keys.active] = p.id
            created = p
        }
        return created
    }

    /** Rename the record in-place; selection and settings are not changed. */
    suspend fun rename(id: String, name: String) {
        require(id != ConnectionPreset.SYSTEM_DEFAULT_ID) { "System default is read-only" }
        val clean = ConnectionPreset.cleanName(name)
        require(clean.isNotEmpty()) { "Preset name is empty" }
        dataStore.edit { prefs ->
            val st = readState(prefs)
            require(st.presets.any { it.id == id }) { "Preset no longer exists" }
            prefs[Keys.presets] = encode(st.presets.map { if (it.id == id) it.copy(name = clean) else it })
        }
    }

    suspend fun setActive(id: String): ConnectionPreset? {
        var target: ConnectionPreset? = null
        dataStore.edit { prefs ->
            val p = readState(prefs).presets.firstOrNull { it.id == id } ?: return@edit
            prefs[Keys.active] = p.id
            target = p
        }
        return target
    }

    /** Refuses to delete the system default or the last legacy record. */
    suspend fun delete(id: String): DeleteResult? {
        if (id == ConnectionPreset.SYSTEM_DEFAULT_ID) return null
        var result: DeleteResult? = null
        dataStore.edit { prefs ->
            val st = readState(prefs)
            if (st.presets.size <= 1 || st.presets.none { it.id == id }) return@edit
            val remaining = st.presets.filterNot { it.id == id }
            val wasActive = st.activeId == id
            val newActive = if (wasActive) remaining.first().id else st.activeId
            prefs[Keys.presets] = encode(remaining)
            if (newActive != null) prefs[Keys.active] = newActive
            result = DeleteResult(newActive, wasActive)
        }
        return result
    }

    /** Export live settings without ever writing them into the system baseline. */
    suspend fun captureActiveForExport(
        profile: ConnectionProfile,
        custom: CustomConfigStore.State,
        now: Long = System.currentTimeMillis(),
    ): ConnectionPreset? {
        val active = snapshot().active ?: return null
        return capture(active.id, active.name, profile, custom, now)
    }

    private fun capture(
        id: String,
        name: String,
        profile: ConnectionProfile,
        custom: CustomConfigStore.State,
        now: Long,
    ) = ConnectionPreset(
        id = id,
        name = ConnectionPreset.cleanName(name),
        profile = PresetProfileCodec.encode(profile),
        customConfigId = custom.activeId,
        customConfigName = custom.configs.firstOrNull { it.id == custom.activeId }?.name,
        customEnabled = custom.enabled,
        chainMode = custom.mode.name,
        updatedAt = now,
    )

    private fun readState(prefs: Preferences): State {
        val list = decode(prefs[Keys.presets])
        val active = prefs[Keys.active]?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
        return State(list, active)
    }

    private fun decode(raw: String?): List<ConnectionPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        // Fail closed: a corrupt/unreadable store must not be overwritten by migration.
        require(raw.startsWith("sealed1:")) { "Unsupported preset storage" }
        val sealed = Base64.getDecoder().decode(raw.removePrefix("sealed1:"))
        val plain = requireNotNull(KeyVault.open(sealed)) { "Preset vault unavailable" }
        val arr = try { json.parseToJsonElement(plain.toString(Charsets.UTF_8)).jsonArray }
            finally { plain.fill(0) }
        return arr.mapNotNull { (it as? JsonObject)?.let(ConnectionPreset::fromJson) }.distinctBy { it.id }
    }

    private fun encode(list: List<ConnectionPreset>): String {
        val plain = JsonArray(list.map { it.toJson() }).toString().toByteArray(Charsets.UTF_8)
        val sealed = try { requireNotNull(KeyVault.seal(plain)) { "Preset vault unavailable" } }
            finally { plain.fill(0) }
        return "sealed1:" + Base64.getEncoder().encodeToString(sealed)
    }

    /**
     * Validation completed before one atomic DataStore commit. The imported records keep
     * their exact names and settings. When [activateFirst] is set, the first imported
     * record becomes the active one in the same commit (the caller then applies it), so
     * an import never leaves the user looking at the previous profile.
     * Returns the stored records (with their new ids) in import order.
     */
    suspend fun importPresets(
        incoming: List<ConnectionPreset>,
        activateFirst: Boolean = false,
        custom: CustomConfigStore.State = CustomConfigStore.State(),
    ): List<ConnectionPreset> {
        require(incoming.isNotEmpty())
        var stored: List<ConnectionPreset> = emptyList()
        dataStore.edit { prefs ->
            val st = readState(prefs)
            require(st.presets.count { !it.isSystemDefault } + incoming.size <= MAX_USER_PRESETS) {
                "Too many user presets (maximum 20)"
            }
            val now = System.currentTimeMillis()
            val fresh = incoming.map {
                // Re-link the custom config on THIS device: exact id, else a unique name
                // match. No link => custom routing stays off (never a foreign id).
                val target = custom.configs.firstOrNull { c -> c.id == it.customConfigId }
                    ?: it.customConfigName?.let { n -> custom.configs.filter { c -> c.name == n }.singleOrNull() }
                it.copy(
                    id = newId(), customConfigId = target?.id,
                    customEnabled = target != null && it.customEnabled,
                    chainMode = if (target != null) it.chainMode else "SOLO", updatedAt = now,
                )
            }
            prefs[Keys.presets] = encode(st.presets + fresh)
            if (activateFirst) prefs[Keys.active] = fresh.first().id
            stored = fresh
        }
        return stored
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val MAX_USER_PRESETS = 20
    }
}
