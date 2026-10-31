package studio.cluvex.aether.custom

/** One projection for tabs, counters, filters, moves and ping ordering. No Android/UI state. */
object ConfigCatalog {
    enum class PingSort { ASC, DESC }
    const val ALL = "all"
    const val V2RAY = "format:v2ray"
    const val NPVT = "format:npvt"
    val AUTOMATIC_KEYS = setOf(ALL, V2RAY, NPVT)
    fun renameAutomaticTab(state: CustomConfigStore.State, key: String, name: String): CustomConfigStore.State {
        require(key in AUTOMATIC_KEYS) { "Not an automatic tab" }
        val clean = name.trim().take(80)
        require(clean.isNotEmpty()) { "Tab name is empty" }
        return state.copy(tabNames = state.tabNames + (key to clean))
    }
    fun customKey(id: String) = "user:$id"
    fun groupFor(key: String): String? = key.takeIf { it.startsWith("user:") }?.removePrefix("user:")
    data class Tab(val key: String, val name: String, val count: Int, val editable: Boolean = false)
    enum class Filter { ALL, MANUAL, SUBSCRIPTIONS }
    data class Row(val key: String, val config: CustomConfig? = null,
        val subscription: CustomConfigStore.Subscription? = null)

    fun family(config: CustomConfig): String = if (config.format == ConfigFormat.NPVT) NPVT else V2RAY

    /** UI, connect and monitoring use the exact same membership rule. Unknown keys fail closed. */
    fun contains(tab: String, c: CustomConfig): Boolean = when (tab) {
        ALL -> true
        V2RAY, NPVT -> c.groupId == null && family(c) == tab
        else -> groupFor(tab)?.let { c.groupId == it } == true
    }

    fun selectTab(state: CustomConfigStore.State, key: String): CustomConfigStore.State {
        require(key in AUTOMATIC_KEYS || state.groups.any { customKey(it.id) == key }) { "Tab no longer exists" }
        val members = state.configs.filter { contains(key, it) }
        return state.copy(activeTab = key,
            activeId = state.activeId?.takeIf { id -> members.any { it.id == id } } ?: members.firstOrNull()?.id)
    }

    fun rows(
    state: CustomConfigStore.State,
    tab: String,
    filter: Filter = Filter.ALL,
    pings: Map<String, ConfigPing.Report> = emptyMap(),
    pingSort: PingSort = PingSort.ASC,
): List<Row> {

    fun validMs(c: CustomConfig): Long? =
        pings[c.id]?.takeIf { r -> r.ok && r.ms >= 0 }?.ms

    // Valid pings always first; only their internal order flips for DESC.
    // Failed/untested stay last in both directions; ties are stable by name, then id.
    val byPing = compareBy<CustomConfig> { if (validMs(it) == null) 1 else 0 }
        .thenBy { c -> validMs(c)?.let { ms -> if (pingSort == PingSort.DESC) -ms else ms } ?: 0L }
        .thenBy { it.name.lowercase(java.util.Locale.ROOT) }
        .thenBy { it.id }

    val configs = state.configs.filter {
        contains(tab, it) && when (filter) {
            Filter.ALL -> true
            Filter.MANUAL -> it.subscriptionId == null
            Filter.SUBSCRIPTIONS -> it.subscriptionId != null
        }
    }.sortedWith(byPing)

    val subscriptions = if (filter == Filter.MANUAL) emptyList() else state.subscriptions.filter { sub ->
        when (tab) {
            ALL -> true
            V2RAY, NPVT -> state.configs.any {
                it.subscriptionId == sub.id && it.groupId == null && family(it) == tab
            }
            else -> groupFor(tab)?.let { sub.groupId == it } == true
        }
    }.sortedWith(
        compareBy<CustomConfigStore.Subscription> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id }
    )

    // Measured servers first; subscriptions are management rows, not synthetic pings.
    return configs.map { Row("config:${it.id}", config = it) } +
        subscriptions.map { Row("subscription:${it.id}", subscription = it) }
}

    fun tabs(state: CustomConfigStore.State, allLabel: String): List<Tab> = buildList {
        fun tab(key: String, name: String, editable: Boolean = false) =
            Tab(key, state.tabNames[key] ?: name, rows(state, key).count { it.config != null }, editable)
        add(tab(ALL, allLabel))
        if (state.activeTab == V2RAY || state.configs.any { it.groupId == null && family(it) == V2RAY }) add(tab(V2RAY, "V2Ray"))
        if (state.activeTab == NPVT || state.configs.any { it.groupId == null && family(it) == NPVT }) add(tab(NPVT, "Npvt"))
        state.groups.forEach { add(tab(customKey(it.id), it.name, true)) }
    }

    fun requireGroup(state: CustomConfigStore.State, id: String?) {
        require(id == null || state.groups.any { it.id == id }) { "Destination tab no longer exists" }
    }

    fun moveConfig(state: CustomConfigStore.State, id: String, group: String?): CustomConfigStore.State {
        requireGroup(state, group)
        require(state.configs.any { it.id == id }) { "Config no longer exists" }
        return state.copy(configs = state.configs.map { if (it.id == id) it.copy(groupId = group) else it })
    }

    fun moveSubscription(state: CustomConfigStore.State, id: String, group: String?): CustomConfigStore.State {
        requireGroup(state, group)
        require(state.subscriptions.any { it.id == id }) { "Subscription no longer exists" }
        return state.copy(subscriptions = state.subscriptions.map { if (it.id == id) it.copy(groupId = group) else it },
            configs = state.configs.map { if (it.subscriptionId == id) it.copy(groupId = group) else it })
    }

    fun deleteGroup(state: CustomConfigStore.State, id: String): CustomConfigStore.State =
        deleteTab(state, customKey(id))

    /** Delete visible members, never the All tab. Mixed feeds keep their surviving members. */
    fun deleteTab(state: CustomConfigStore.State, key: String): CustomConfigStore.State {
        require(key != ALL) { "The All tab cannot be deleted" }
        val group = groupFor(key)
        require(key == NPVT || key == V2RAY || state.groups.any { it.id == group }) { "Tab no longer exists" }
        val removed = state.configs.filter { if (group != null) it.groupId == group else it.groupId == null && family(it) == key }
        val ids = removed.map { it.id }.toSet()
        val surviving = state.configs.filterNot { it.id in ids }
        val removedFeeds = state.subscriptions.filter { sub ->
            (group != null && sub.groupId == group) ||
                (removed.any { it.subscriptionId == sub.id } && surviving.none { it.subscriptionId == sub.id })
        }.map { it.id }.toSet()
        val remaining = surviving.map { if (it.subscriptionId in removedFeeds) it.copy(subscriptionId = null) else it }
        val active = state.activeId?.takeIf { id -> remaining.any { it.id == id } }
        return state.copy(configs = remaining, activeId = active, enabled = state.enabled && active != null,
            activeTab = if (state.activeTab == key) ALL else state.activeTab,
            groups = state.groups.filterNot { group != null && it.id == group },
            subscriptions = state.subscriptions.filterNot { it.id in removedFeeds }, tabNames = state.tabNames - key)
    }

    /** Pure transaction: downloads happen outside it; failed validation commits nothing. */
    fun mergeSubscription(state: CustomConfigStore.State, sub: CustomConfigStore.Subscription,
        incoming: List<CustomConfig>, updatedAt: Long): CustomConfigStore.State {
        val target = (state.subscriptions.firstOrNull { it.id == sub.id } ?: sub).groupId
        requireGroup(state, target)
        require(incoming.isNotEmpty()) { "Subscription is empty" }
        val old = state.configs.filter { it.subscriptionId == sub.id }.associateBy { it.outbound }
        val other = state.configs.filterNot { it.subscriptionId == sub.id }
        val fresh = incoming.distinctBy { it.outbound }.map { c ->
            val previous = old[c.outbound]
            c.copy(id = previous?.id ?: c.id,
                groupId = if (previous != null) previous.groupId else target,
                subscriptionId = sub.id, addedAt = previous?.addedAt ?: c.addedAt)
        }
        require(other.size + fresh.size <= 500) { "Too many configs (maximum 500)" }
        val active = state.activeId?.takeIf { id -> (other + fresh).any { it.id == id } }
            ?: fresh.firstOrNull()?.id
        return state.copy(configs = other + fresh, activeId = active,
            subscriptions = state.subscriptions.filterNot { it.id == sub.id } + sub.copy(
                groupId = target, updatedAt = updatedAt))
    }

    /** Never reveal endpoint metadata that an NPVT author locked. IPv6 is handled without splitting its host. */
    fun port(config: CustomConfig): String {
        if (config.locked) return "—"
        return config.server.substringAfterLast(':', "").toIntOrNull()
            ?.takeIf { it in 1..65535 }?.toString() ?: "—"
    }
}
