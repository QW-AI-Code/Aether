package studio.cluvex.aether.custom

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.cluvex.aether.core.KeyVault

/**
 * The saved custom configs and the chain the user picked.
 *
 * One sealed blob in its own preferences file: the configs hold server
 * credentials, so they are encrypted with the app's Keystore-backed vault key
 * ([KeyVault], AES-256-GCM, non-exportable) exactly like the other secrets, and
 * the backup rules already exclude every preferences file from any backup.
 * Deliberately NOT inside the connection profile: "Reset settings" resets the
 * tunnel's settings, it does not delete a user's imported servers.
 */
object CustomConfigStore {

    data class Group(val id: String, val name: String)
    data class Subscription(val id: String, val name: String, val url: String, val updatedAt: Long = 0, val groupId: String? = null)

    data class State(
        val configs: List<CustomConfig> = emptyList(),
        val activeId: String? = null,
        val mode: ChainMode = ChainMode.SOLO,
        /** Master switch: off = every session runs exactly as before this feature. */
        val enabled: Boolean = false,
        val groups: List<Group> = emptyList(),
        val subscriptions: List<Subscription> = emptyList(),
        val verifyConnection: Boolean = true,
        val autoSelect: Boolean = false,
        val healthIntervalSeconds: Long = 300,
        val tabNames: Map<String, String> = emptyMap(),
        /** Persisted independently of page/navigation lifetime; never inferred from activeId. */
        val activeTab: String = ConfigCatalog.ALL,

    ) {
        val active: CustomConfig?
            get() = configs.firstOrNull { it.id == activeId && ConfigCatalog.contains(activeTab, it) }
                ?: configs.firstOrNull { ConfigCatalog.contains(activeTab, it) }

        /** The next session can only use a member of the persisted user scope. */
        val selection: CustomSelection?
            get() = if (enabled) active?.let { CustomSelection(it, mode) } else null

    }

    private const val PREFS = "aether_custom_configs"
    private const val KEY = "state"
    private const val MAX_CONFIGS = 500

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var loaded = false
    @Volatile private var deletionEpoch = 0L

    @Synchronized
    fun load(context: Context): State {
        if (loaded) return _state.value
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY, null)
        val decoded = stored?.let { s ->
            runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()
                ?.let { KeyVault.open(it) }
                ?.let { decode(String(it, Charsets.UTF_8)) }
        }
        _state.value = decoded ?: State()
        loaded = true
        return _state.value
    }

    /** The selection a session should run, read fresh from storage. */
    fun selection(context: Context): CustomSelection? = load(context).selection

    fun add(context: Context, configs: List<CustomConfig>): State = update(context) { s ->
        configs.forEach { ConfigCatalog.requireGroup(s, it.groupId) }
        val merged = (s.configs + configs).distinctBy { it.id }
        require(merged.size <= MAX_CONFIGS) { "Too many configs (maximum $MAX_CONFIGS)" }
        // The first import of an empty list selects it, so the user sees it in use.
        s.copy(configs = merged, activeId = s.activeId ?: configs.firstOrNull()?.id)
    }

    fun replace(context: Context, config: CustomConfig): State = update(context) { s ->
        s.copy(configs = s.configs.map { if (it.id == config.id) config else it })
    }

    internal fun replaceScanned(context: Context, expected: CustomConfig, replacement: CustomConfig): State =
        update(context, persistSync = true) { s ->
            val current = s.configs.firstOrNull { it.id == expected.id }
            require(current == expected && !expected.locked) { "Config changed during scan" }
            require(replacement.id == expected.id && replacement.groupId == expected.groupId &&
                replacement.subscriptionId == expected.subscriptionId) { "Scan cannot change ownership" }
            s.copy(configs = s.configs.map { if (it.id == expected.id) replacement else it })
        }

    fun delete(context: Context, id: String): State = update(context) { s ->
        val rest = s.configs.filterNot { it.id == id }
        val active = if (s.activeId == id) null else s.activeId
        s.copy(configs = rest, activeId = active, enabled = s.enabled && active != null)
    }

    fun deleteAll(context: Context): State = update(context) {
        it.copy(configs = emptyList(), subscriptions = emptyList(), activeId = null, enabled = false)
    }

    fun putGroup(context: Context, id: String?, name: String): State = update(context) { s ->
        require(id == null || s.groups.any { it.id == id }) { "Tab no longer exists" }
        val clean = name.trim().take(80)
        require(clean.isNotEmpty()) { "Group name is empty" }
        require(s.groups.none { it.id != id && it.name.equals(clean, true) }) { "Group already exists" }
        val group = Group(id ?: java.util.UUID.randomUUID().toString(), clean)
        s.copy(groups = if (s.groups.any { it.id == group.id })
            s.groups.map { if (it.id == group.id) group else it } else s.groups + group)
    }

    /** One sealed transaction: remove the tab's members and invalidate cached pings. */
    fun deleteGroup(context: Context, id: String): State = deleteTab(context, ConfigCatalog.customKey(id))

    fun deleteTab(context: Context, key: String): State = update(context, persistSync = true) { s ->
        ConfigCatalog.deleteTab(s, key).also { deletionEpoch++ }
    }
        .also { ConfigPingStore.retain(it.configs.map { c -> c.id }.toSet()) }

    fun move(context: Context, id: String, groupId: String?): State = update(context) { s ->
        ConfigCatalog.moveConfig(s, id, groupId)
    }

    fun moveSubscription(context: Context, id: String, groupId: String?): State = update(context) { s ->
        ConfigCatalog.moveSubscription(s, id, groupId)
    }

    fun renameAutomaticTab(context: Context, key: String, name: String): State = update(context) {
        ConfigCatalog.renameAutomaticTab(it, key, name)
    }

    fun setActiveTab(context: Context, key: String): State = update(context, persistSync = true) {
        ConfigCatalog.selectTab(it, key)
    }

    /** Atomic handoff: a ping race cannot select a row from a tab changed meanwhile. */
    fun selectInTab(context: Context, id: String, expectedTab: String): Boolean {
        var selected = false
        update(context) { s ->
            if (s.activeTab != expectedTab || s.configs.none { it.id == id && ConfigCatalog.contains(expectedTab, it) }) s
            else s.copy(activeId = id).also { selected = true }
        }
        return selected
    }

    fun setVerify(context: Context, on: Boolean): State = update(context) { it.copy(verifyConnection = on) }
    fun setAutoSelect(context: Context, on: Boolean): State = update(context) { it.copy(autoSelect = on) }
    fun setHealthInterval(context: Context, seconds: Long): State = update(context) {
        it.copy(healthIntervalSeconds = seconds.coerceIn(60, 3600))
    }

    /** Download first; commit once. Failure leaves the previous subscription intact. */
    suspend fun refreshSubscription(context: Context, sub: Subscription, groupId: String? = sub.groupId,
        password: String? = null): State {
        val existed = load(context).subscriptions.any { it.id == sub.id }
        val epoch = deletionEpoch
        val request = sub.copy(groupId = groupId)
        val incoming = ConfigImporter.fromText(context, sub.url, ConfigSource.LINK, password)
        return update(context) { s ->
            require(epoch == deletionEpoch) { "Tabs changed during update; retry the refresh" }
            require(!existed || s.subscriptions.any { it.id == sub.id }) { "Subscription was deleted during update" }
            ConfigCatalog.mergeSubscription(s, request, incoming, System.currentTimeMillis())
        }
    }

    fun deleteSubscription(context: Context, id: String): State = update(context) { s ->
        val rest = s.configs.filterNot { it.subscriptionId == id }
        val active = s.activeId?.takeIf { a -> rest.any { it.id == a } }
        s.copy(configs = rest, subscriptions = s.subscriptions.filterNot { it.id == id },
            activeId = active, enabled = s.enabled && active != null)
    }


    fun select(context: Context, id: String): State = update(context) { s ->
        require(s.configs.any { it.id == id }); s.copy(activeId = id)
    }

    fun setMode(context: Context, mode: ChainMode): State = update(context) { it.copy(mode = mode) }

    fun setEnabled(context: Context, enabled: Boolean): State =
        update(context) { it.copy(enabled = enabled && it.active != null) }

    @Synchronized
    private fun update(context: Context, persistSync: Boolean = false, change: (State) -> State): State {
        load(context)
        val proposed = change(_state.value)
        val next = proposed.copy(activeId = proposed.active?.id)
        val sealed = KeyVault.seal(encode(next).toByteArray(Charsets.UTF_8))
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (persistSync) {
            check(sealed != null) { "Cannot persist selection: vault unavailable" }
            check(prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) { "Cannot persist selection" }
        } else if (sealed != null) {
            prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }
        _state.value = next
        // No vault key (a broken keystore): the state lives for this process only,
        // rather than writing server credentials to disk in the clear.
        return next
    }

    // ------------------------------------------------------------- codec

    internal fun encode(s: State): String = buildJsonObject {
        put("v", 4)
        put("activeTab", s.activeTab)
        put("tabNames", buildJsonObject { s.tabNames.forEach { (key, value) -> put(key, value) } })
        put("verifyConnection", s.verifyConnection)
        put("autoSelect", s.autoSelect)
        put("healthIntervalSeconds", s.healthIntervalSeconds)
        put("groups", buildJsonArray { s.groups.forEach { g -> add(buildJsonObject {
            put("id", g.id); put("name", g.name)
        }) } })
        put("subscriptions", buildJsonArray { s.subscriptions.forEach { sub -> add(buildJsonObject {
            put("id", sub.id); put("name", sub.name); put("url", sub.url); put("updatedAt", sub.updatedAt)
            sub.groupId?.let { put("groupId", it) }
        }) } })
        s.activeId?.let { put("active", it) }
        put("mode", s.mode.name)
        put("enabled", s.enabled)
        put("configs", buildJsonArray {
            s.configs.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id)
                    put("name", c.name)
                    put("protocol", c.protocol.name)
                    put("format", c.format.name)
                    put("source", c.source.name)
                    put("outbound", c.outbound)
                    put("raw", c.raw)
                    put("server", c.server)
                    put("locked", c.locked)
                    put("note", c.note)
                    put("expiresAt", c.expiresAt)
                    put("mobileOnly", c.mobileOnly)
                    put("addedAt", c.addedAt)
                    c.groupId?.let { put("groupId", it) }
                    c.subscriptionId?.let { put("subscriptionId", it) }
                })
            }
        })
    }.toString()

    internal fun decode(text: String): State? {
        val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
        fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
        fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L
        val configs = (o["configs"] as? JsonArray).orEmpty().mapNotNull { e ->
            val c = e as? JsonObject ?: return@mapNotNull null
            CustomConfig(
                id = c.s("id") ?: return@mapNotNull null,
                name = c.s("name").orEmpty(),
                protocol = ConfigProtocol.entries.firstOrNull { it.name == c.s("protocol") } ?: ConfigProtocol.OTHER,
                format = ConfigFormat.entries.firstOrNull { it.name == c.s("format") } ?: ConfigFormat.URI,
                source = ConfigSource.entries.firstOrNull { it.name == c.s("source") } ?: ConfigSource.LINK,
                outbound = c.s("outbound") ?: return@mapNotNull null,
                raw = c.s("raw").orEmpty(),
                server = c.s("server").orEmpty(),
                locked = c.b("locked"),
                note = c.s("note").orEmpty(),
                expiresAt = c.l("expiresAt"),
                mobileOnly = c.b("mobileOnly"),
                addedAt = c.l("addedAt"),
                groupId = c.s("groupId"),
                subscriptionId = c.s("subscriptionId"),
            )
        }
        val active = o.s("active")?.takeIf { id -> configs.any { it.id == id } }
        return State(
            configs = configs,
            activeTab = o.s("activeTab") ?: ConfigCatalog.ALL,
            tabNames = (o["tabNames"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.trim()?.take(80)
                    ?.takeIf { it.isNotEmpty() && key in ConfigCatalog.AUTOMATIC_KEYS }?.let { key to it }
            }.toMap(),
            groups = (o["groups"] as? JsonArray).orEmpty().mapNotNull { e ->
                val g = e as? JsonObject ?: return@mapNotNull null
                Group(g.s("id") ?: return@mapNotNull null, g.s("name") ?: return@mapNotNull null)
            },
            subscriptions = (o["subscriptions"] as? JsonArray).orEmpty().mapNotNull { e ->
                val sub = e as? JsonObject ?: return@mapNotNull null
                Subscription(sub.s("id") ?: return@mapNotNull null, sub.s("name").orEmpty(),
                    sub.s("url") ?: return@mapNotNull null, sub.l("updatedAt"),
                    if (((o["v"] as? JsonPrimitive)?.longOrNull ?: 1L) >= 3L) sub.s("groupId")
                    else configs.filter { it.subscriptionId == sub.s("id") }.map { it.groupId }.distinct().singleOrNull())
            },
            verifyConnection = (o["verifyConnection"] as? JsonPrimitive)?.booleanOrNull ?: true,
            autoSelect = o.b("autoSelect"),
            healthIntervalSeconds = (o["healthIntervalSeconds"] as? JsonPrimitive)?.longOrNull
                ?.coerceIn(60, 3600) ?: 300,
            activeId = active,
            mode = ChainMode.fromStoredName(o.s("mode")),
            enabled = o.b("enabled") && active != null,
        )
    }
}

/**
 * What the running custom session looks like, for the home screen's pipeline row.
 * Published by the VPN service, cleared when the session ends.
 */
object CustomSession {
    data class Info(val configLabel: String, val mode: ChainMode)

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info.asStateFlow()

    fun set(info: Info?) {
        _info.value = info
    }
}