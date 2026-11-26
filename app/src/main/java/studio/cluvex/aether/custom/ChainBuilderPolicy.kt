package studio.cluvex.aether.custom

import studio.cluvex.aether.model.TransportBackend

/**
 * 1.7.0 (same version): the unified "connection chain" builder.
 *
 * The user picks how many nodes the chain has (1 to 3) and which core runs on
 * each node, in the order traffic leaves the device. Every chain offered here
 * maps onto exactly one thing the app already runs:
 *
 *  - no config node       -> a [TransportBackend] (custom configs off);
 *  - config node alone    -> custom configs on, [ChainMode.SOLO];
 *  - config first or last -> custom configs on, the [ChainMode] with that
 *    placement and engine part (the engine runs Tor/Psiphon itself, so the
 *    config always sits at one END of the chain).
 *
 * Pure: no Android, no UI - unit-tested.
 */
object ChainBuilderPolicy {

    enum class Core { CONFIG, AETHER, PSIPHON, TOR }

    data class Target(
        /** Engine mode to store in the profile; null keeps the current one. */
        val backend: TransportBackend?,
        val customEnabled: Boolean,
        /** Chain of the custom config; null when the chain has no config node. */
        val mode: ChainMode?,
    )

    fun coresOf(backend: TransportBackend): List<Core> = when (backend) {
        TransportBackend.AETHER -> listOf(Core.AETHER)
        TransportBackend.AETHER_PSIPHON -> listOf(Core.AETHER, Core.PSIPHON)
        TransportBackend.TOR -> listOf(Core.TOR)
        TransportBackend.AETHER_TOR -> listOf(Core.AETHER, Core.TOR)
        TransportBackend.TOR_PSIPHON -> listOf(Core.TOR, Core.PSIPHON)
        TransportBackend.TOR_AETHER -> listOf(Core.TOR, Core.AETHER)
    }

    fun coresOf(base: ChainBase): List<Core> = when (base) {
        ChainBase.AETHER -> listOf(Core.AETHER)
        ChainBase.PSIPHON -> listOf(Core.PSIPHON)
        ChainBase.TOR -> listOf(Core.TOR)
        ChainBase.AETHER_PSIPHON -> listOf(Core.AETHER, Core.PSIPHON)
        ChainBase.AETHER_TOR -> listOf(Core.AETHER, Core.TOR)
        ChainBase.TOR_PSIPHON -> listOf(Core.TOR, Core.PSIPHON)
        ChainBase.TOR_AETHER -> listOf(Core.TOR, Core.AETHER)
    }

    fun coresOf(mode: ChainMode): List<Core> {
        val base = mode.base ?: return listOf(Core.CONFIG)
        val engine = coresOf(base)
        return when (mode.placement) {
            ChainPlacement.FIRST -> listOf(Core.CONFIG) + engine
            ChainPlacement.LAST -> engine + Core.CONFIG
            ChainPlacement.SOLO -> listOf(Core.CONFIG)
        }
    }

    /** Every chain the app can run, shortest first. */
    val all: List<List<Core>> by lazy {
        (TransportBackend.entries.map { coresOf(it) } + ChainMode.entries.map { coresOf(it) })
            .distinct()
            .sortedWith(compareBy<List<Core>> { it.size }.thenBy { it.joinToString { c -> c.name } })
    }

    fun isSupported(chain: List<Core>): Boolean = chain in all

    /** The chain that is configured right now. */
    fun current(backend: TransportBackend, customEnabled: Boolean, mode: ChainMode): List<Core> =
        if (customEnabled) coresOf(mode) else coresOf(backend)

    /** Cores that can sit on [slot] of a [length]-node chain after [prefix]. */
    fun options(prefix: List<Core>, length: Int, slot: Int): List<Core> =
        all.filter { it.size == length && it.take(slot) == prefix.take(slot) }
            .map { it[slot] }.distinct()

    /**
     * A supported chain of [length] nodes starting with [prefix], keeping as much
     * of [wish] (the previous chain) as possible after the prefix.
     */
    fun complete(prefix: List<Core>, length: Int, wish: List<Core> = emptyList()): List<Core>? {
        val candidates = all.filter { it.size == length && it.take(prefix.size) == prefix }
        if (candidates.isEmpty()) return null
        // 1.7.0 (same version): a config node is never ADDED on the user's behalf.
        // Growing "Aether" to two nodes used to pick "Aether -> Config" (first in the
        // list) and then asked for a config the user never chose. A config node is
        // kept only when the user picked it (prefix) or already had it (wish).
        val keepConfig = Core.CONFIG in prefix || Core.CONFIG in wish
        return candidates.maxByOrNull { c ->
            val same = c.indices.count { i -> i < wish.size && wish[i] == c[i] }
            val forced = if (!keepConfig && Core.CONFIG in c) 100 else 0
            same * 2 - forced
        }
    }

    /** True for chain lengths that the app can only run with a config node (3 nodes). */
    fun lengthNeedsConfig(length: Int): Boolean =
        all.filter { it.size == length }.let { it.isNotEmpty() && it.all { c -> Core.CONFIG in c } }

    /** What to store for [chain]; null for a chain the app cannot run. */
    fun target(chain: List<Core>): Target? {
        if (!isSupported(chain)) return null
        if (Core.CONFIG !in chain) {
            val backend = TransportBackend.entries.firstOrNull { coresOf(it) == chain } ?: return null
            return Target(backend, customEnabled = false, mode = null)
        }
        val mode = ChainMode.entries.firstOrNull { coresOf(it) == chain } ?: return null
        return Target(mode.base?.backend, customEnabled = true, mode = mode)
    }
}
