package studio.cluvex.aether.custom

/** Pure selection policy, tested without an Android service or network. */
object AutoSelectPolicy {
    private const val FRESH_MS = 120_000L
    fun choose(configs: List<CustomConfig>, reports: Map<String, ConfigPing.Report>,
        currentId: String?, now: Long = System.currentTimeMillis()): CustomConfig? {
        val healthy = configs.filter { c ->
            val r = reports[c.id]
            !c.isExpired(now) && r != null && r.ok && r.ms >= 1 && now - r.at in 0..FRESH_MS
        }
        val best = healthy.minWithOrNull(compareBy<CustomConfig> { reports.getValue(it.id).ms }.thenBy { it.id })
            ?: return null
        val current = healthy.firstOrNull { it.id == currentId } ?: return best
        val old = reports.getValue(current.id).ms
        val next = reports.getValue(best.id).ms
        // Avoid oscillation: improvement must exceed both 50 ms and 20%.
        return if (old - next >= maxOf(50L, old / 5)) best else current
    }
}
