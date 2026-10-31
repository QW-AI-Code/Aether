// Ported from QWVPN-1.0.0 (GPL-3.0); see third_party/QWVPN/LICENSE.
package studio.cluvex.aether.scanner


/**
 * User-selectable scan depth/accuracy (Task 4). Every value the [CleanIpScanner] uses for a sweep is
 * read from the active profile, so the user trades speed for thoroughness from Settings:
 *
 *  - [candidateBudget]  total number of candidate IPs probed before giving up (single scan = samples,
 *                       pool scan = the cross-round budget).
 *  - [timeoutMs]        per-probe time budget (split into dial/handshake/http inside the scanner).
 *  - [probesPerIp]      how many times each IP is re-probed (more = steadier success/jitter numbers).
 *  - [parallelism]      how many probes run concurrently.
 *  - [neighborPerHit]   how many neighbour IPs are expanded around each healthy hit.
 *
 * Stored in [Settings.scanProfile] as the enum NAME; an unknown/blank value falls back to [DEFAULT].
 */
enum class ScanProfile(
    val candidateBudget: Int,
    val timeoutMs: Int,
    val probesPerIp: Int,
    val parallelism: Int,
    val neighborPerHit: Int,
) {
    /** Fast/shallow: fewest candidates, shortest timeout — quick but may miss working IPs. */
    FAST(candidateBudget = 120, timeoutMs = 3500, probesPerIp = 1, parallelism = 60, neighborPerHit = 6),

    /** Balanced (default): the proven middle ground. */
    BALANCED(candidateBudget = 300, timeoutMs = 5000, probesPerIp = 2, parallelism = 50, neighborPerHit = 12),

    /** Deep/accurate: most candidates, longest timeout — slowest, for when a scan finds nothing. */
    DEEP(candidateBudget = 600, timeoutMs = 6000, probesPerIp = 3, parallelism = 40, neighborPerHit = 16);

    companion object {
        val DEFAULT = BALANCED

        /** Resolve a stored profile name (case-insensitive) to a profile, defaulting to [DEFAULT]. */
        fun fromName(name: String?): ScanProfile =
            values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: DEFAULT

    }
}
