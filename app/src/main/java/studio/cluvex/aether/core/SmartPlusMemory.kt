package studio.cluvex.aether.core

import android.content.Context
import org.json.JSONObject

/**
 * SMART PLUS — what worked on which network.
 *
 * The half of Smart Plus that makes the SECOND connect on a network quick: the
 * route that carried traffic here is tried first next time, as one short attempt,
 * before anything is raced.
 *
 * ## Keyed per network, and why that is the whole point
 *
 * A route proven on one network says nothing about another one. The reference
 * implementation learned this the expensive way and wrote it down: a single
 * install-wide "the engine worked once" flag made a phone that had connected on
 * home Wi-Fi open every later session on a filtered mobile network by waiting two
 * and a half minutes for a tunnel that network does not carry. So the key here is
 * the network ([NetworkIdentity]), and nothing is remembered globally.
 *
 * ## What is stored, and what deliberately is not
 *
 * ```json
 * { "v": 1, "n": { "<network key>": { "t": "masque.h2", "p": 1758... , "f": {"wg": 1758...} } } }
 * ```
 *
 * * `t` / `p` — the tactic id that connected and when.
 * * `f` — tactic ids that FAILED here, with the time. Kept because a failure is
 *   evidence too: it moves a route to the back of its lane instead of the front.
 * * The network key is already a truncated SHA-256 (see [NetworkIdentity]); no
 *   SSID, operator name, gateway or DNS address is written here.
 * * No endpoint, no gateway address, no credential. Those live in the engine's
 *   own sealed state; this file must not become a second copy of them.
 *
 * Plain `SharedPreferences`, on purpose: it is read on the VPN service's connect
 * path where a suspending DataStore read would have to be waited on anyway, and
 * the whole file is a few hundred bytes. The same reasoning as
 * [studio.cluvex.aether.widget.WidgetStateCache].
 *
 * ## Evidence expires
 *
 * A success is trusted for [PROVEN_TTL_MS] and a failure for [FAILED_TTL_MS].
 * Both numbers exist because the alternative — evidence that never expires — is
 * what turns a memory into a wrong guess that the user cannot clear. The map is
 * capped at [MAX_NETWORKS] entries, oldest first, so a phone that sees many
 * networks does not grow this file without bound.
 */
object SmartPlusMemory {

    private const val PREFS = "smart_plus_memory"
    private const val KEY = "networks"
    private const val VERSION = 1

    /** A proven route is trusted for two weeks. */
    const val PROVEN_TTL_MS = 14L * 24 * 60 * 60 * 1000

    /** A failure is remembered for six hours - long enough to matter on one trip. */
    const val FAILED_TTL_MS = 6L * 60 * 60 * 1000

    /** How many networks to keep. Beyond this the oldest are dropped. */
    const val MAX_NETWORKS = 32

    private const val TAG = "smart+"

    /**
     * What is known about one network.
     *
     * Immutable and free of Android types so the planner's rules can be tested
     * against it directly.
     */
    data class Entry(
        val tacticId: String?,
        val provenAtMs: Long,
        /** tactic id -> when it last failed here. */
        val failedAtMs: Map<String, Long> = emptyMap(),
    ) {
        fun isProvenAt(nowMs: Long): Boolean =
            tacticId != null && nowMs - provenAtMs in 0..PROVEN_TTL_MS

        fun failedIdsAt(nowMs: Long): Set<String> =
            failedAtMs.filterValues { nowMs - it in 0..FAILED_TTL_MS }.keys
    }

    // ------------------------------------------------------------------ reads

    /** What is remembered for [networkKey], or null. */
    fun recall(context: Context, networkKey: String): Entry? =
        read(context)[networkKey]

    // ----------------------------------------------------------------- writes

    /** Records that [tacticId] carried traffic on [networkKey]. */
    fun remember(context: Context, networkKey: String, tacticId: String, nowMs: Long = now()) {
        edit(context) { map ->
            val old = map[networkKey]
            map[networkKey] = Entry(
                tacticId = tacticId,
                provenAtMs = nowMs,
                // A route that just worked is no longer a failure, whatever it
                // did an hour ago.
                failedAtMs = old?.failedAtMs.orEmpty() - tacticId,
            )
        }
        DiagnosticsLog.d(TAG, "Remembered '$tacticId' for this network.")
    }

    /** Records that [tacticId] did not work on [networkKey]. */
    fun noteFailure(context: Context, networkKey: String, tacticId: String, nowMs: Long = now()) {
        edit(context) { map ->
            val old = map[networkKey]
            // Clearing a proven id that has now failed matters: otherwise the
            // next connect still puts it in the 45 s slot at the front.
            val stillProven = old?.tacticId?.takeIf { it != tacticId }
            map[networkKey] = Entry(
                tacticId = stillProven,
                provenAtMs = if (stillProven != null) old.provenAtMs else 0L,
                failedAtMs = old?.failedAtMs.orEmpty() + (tacticId to nowMs),
            )
        }
    }

    /** Forgets everything. Wired to "reset settings". */
    fun clear(context: Context) {
        runCatching {
            prefs(context).edit().remove(KEY).apply()
        }
        DiagnosticsLog.i(TAG, "Per-network Smart Plus memory cleared.")
    }

    // ------------------------------------------------------------- plumbing

    private fun now(): Long = System.currentTimeMillis()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): Map<String, Entry> = runCatching {
        val raw = prefs(context).getString(KEY, null) ?: return emptyMap()
        val root = JSONObject(raw)
        if (root.optInt("v", 0) != VERSION) return emptyMap()
        val networks = root.optJSONObject("n") ?: return emptyMap()
        buildMap {
            for (key in networks.keys()) {
                val o = networks.optJSONObject(key) ?: continue
                val failed = buildMap {
                    o.optJSONObject("f")?.let { f ->
                        for (id in f.keys()) put(id, f.optLong(id, 0L))
                    }
                }
                put(
                    key,
                    Entry(
                        tacticId = o.optString("t", "").takeIf { it.isNotEmpty() },
                        provenAtMs = o.optLong("p", 0L),
                        failedAtMs = failed,
                    ),
                )
            }
        }
    }.getOrElse {
        // A corrupt file is not worth a crash on the connect path, and the cost of
        // losing it is one slower connect.
        DiagnosticsLog.w(TAG, "Smart Plus memory unreadable - starting over.")
        emptyMap()
    }

    @Synchronized
    private fun edit(context: Context, block: (MutableMap<String, Entry>) -> Unit) {
        runCatching {
            val map = read(context).toMutableMap()
            block(map)
            val pruned = prune(map)
            val networks = JSONObject()
            for ((key, e) in pruned) {
                val o = JSONObject()
                e.tacticId?.let { o.put("t", it) }
                o.put("p", e.provenAtMs)
                if (e.failedAtMs.isNotEmpty()) {
                    val f = JSONObject()
                    for ((id, at) in e.failedAtMs) f.put(id, at)
                    o.put("f", f)
                }
                networks.put(key, o)
            }
            val root = JSONObject().put("v", VERSION).put("n", networks)
            prefs(context).edit().putString(KEY, root.toString()).apply()
        }.onFailure {
            DiagnosticsLog.w(TAG, "Could not write Smart Plus memory: ${it.message}")
        }
    }

    /**
     * Keeps the file bounded: drops stale failures, then the oldest networks.
     *
     * Visible for tests, and pure.
     */
    fun prune(
        map: Map<String, Entry>,
        nowMs: Long = now(),
        max: Int = MAX_NETWORKS,
    ): Map<String, Entry> {
        val cleaned = map.mapValues { (_, e) ->
            e.copy(failedAtMs = e.failedAtMs.filterValues { nowMs - it in 0..FAILED_TTL_MS })
        }.filterValues { it.tacticId != null || it.failedAtMs.isNotEmpty() }
        if (cleaned.size <= max) return cleaned
        return cleaned.entries
            .sortedByDescending { maxOf(it.value.provenAtMs, it.value.failedAtMs.values.maxOrNull() ?: 0L) }
            .take(max)
            .associate { it.key to it.value }
    }
}
