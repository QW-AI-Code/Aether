package studio.cluvex.aether.custom

/** Pure selection policy, tested without an Android service or network. */
object AutoSelectPolicy {
    private const val FRESH_MS = 120_000L

    /** Standard switch threshold (health checks): 50 ms and 20 %. */
    const val STEADY_GAIN_MS = 50L
    const val STEADY_GAIN_DIVISOR = 5L

    /**
     * Threshold after a run the user asked for, or after the full run that follows
     * a fast first connect: 20 ms and 10 %. Lower than the steady one, because the
     * user explicitly wants the fastest server, but still above measurement noise.
     */
    const val EAGER_GAIN_MS = 20L
    const val EAGER_GAIN_DIVISOR = 10L

    fun choose(configs: List<CustomConfig>, reports: Map<String, ConfigPing.Report>,
        currentId: String?, now: Long = System.currentTimeMillis(),
        minGainMs: Long = STEADY_GAIN_MS, minGainDivisor: Long = STEADY_GAIN_DIVISOR): CustomConfig? {
        val healthy = configs.filter { c ->
            val r = reports[c.id]
            !c.isExpired(now) && r != null && r.ok && r.ms >= 1 && now - r.at in 0..FRESH_MS
        }
        val best = healthy.minWithOrNull(compareBy<CustomConfig> { reports.getValue(it.id).ms }.thenBy { it.id })
            ?: return null
        val current = healthy.firstOrNull { it.id == currentId } ?: return best
        val old = reports.getValue(current.id).ms
        val next = reports.getValue(best.id).ms
        // Avoid oscillation: improvement must exceed both thresholds.
        return if (old - next >= maxOf(minGainMs, old / minGainDivisor.coerceAtLeast(1L))) best else current
    }

    /**
     * 1.7.0 fast first connect: the fastest config that has ALREADY answered in the
     * run that started at [since]. Used to connect as soon as the first servers
     * reply instead of waiting for the slowest (or a dead) one; the full result is
     * applied afterwards with [choose].
     */
    fun fastestSoFar(configs: List<CustomConfig>, reports: Map<String, ConfigPing.Report>,
        since: Long, now: Long = System.currentTimeMillis()): CustomConfig? =
        configs.filter { c ->
            val r = reports[c.id]
            !c.isExpired(now) && r != null && r.ok && r.ms >= 1 && r.at >= since
        }.minWithOrNull(compareBy<CustomConfig> { reports.getValue(it.id).ms }.thenBy { it.id })
}
