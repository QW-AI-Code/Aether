package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live connection metadata for the desktop-parity info row (1.2.4): the
 * protocol actually in use and the endpoint the engine picked.
 *
 * The endpoint is ground truth from the engine itself: [AetherProcess]
 * mirrors every engine stdout line into [ingest], which picks out the
 * selection lines the core prints:
 *   - "[+] selected WireGuard endpoint 162.159.195.96:946 (rtt ...)"
 *   - "[+] selected WireGuard endpoint 162.159.195.96:946 using aethernoize ..."
 *   - "[+] selected MASQUE gateway 162.159.198.1:443 (rtt ...)"
 *   - "[+] using cloudflare edge 162.159.198.1:443"  (every path, see [EDGE_MARKER])
 *   - "[+] masque-in-masque ready: ... (outer) and ... (inner)"
 * The protocol comes from the RESOLVED profile (Smart Auto resolves AUTO to
 * a concrete protocol before launch), published via [setProtocol].
 */
object EngineMeta {

    data class Snapshot(
        val protocol: String? = null,
        val endpoint: String? = null,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Called by the VPN service once the winning strategy is known. */
    fun setProtocol(protocol: String) {
        _state.value = _state.value.copy(protocol = protocol)
    }

    /** Called for a pinned (manual) peer, which never logs a selection line. */
    fun setEndpoint(endpoint: String) {
        _state.value = _state.value.copy(endpoint = endpoint)
    }

    /** Clears both fields (new connect, disconnect, lockdown). */
    fun reset() {
        _state.value = Snapshot()
    }

    /**
     * Scans one engine stdout line for the endpoint-selection messages.
     * Cheap string work on the log-drain thread; everything else is ignored.
     */
    fun ingest(line: String) {
        val endpoint = WG_MARKER.find(line)?.groupValues?.get(1)
            ?: MASQUE_MARKER.find(line)?.groupValues?.get(1)
            ?: EDGE_MARKER.find(line)?.groupValues?.get(1)
            ?: MIM_MARKER.find(line)?.groupValues?.get(1)
            ?: return
        _state.value = _state.value.copy(endpoint = endpoint)
    }

    private val WG_MARKER = Regex("selected WireGuard endpoint (\\S+:\\d+)")
    private val MASQUE_MARKER = Regex("selected MASQUE gateway (\\S+:\\d+)")

    /**
     * 1.3.1-r2 FIX for "the endpoint row just shows an ellipsis".
     *
     * The two `selected …` lines above are printed by the SCAN, and only by the
     * scan. Every other way the engine arrives at a peer is silent as far as these
     * two patterns are concerned:
     *
     * ```
     *  [+] cached gateway 162.159.198.1:443 still works; skipping scan
     *  [+] cached endpoint 162.159.195.92:1701 still works (rtt 412ms); skipping scan
     *  [*] retrying last known-good gateway 162.159.198.1:443 before rescanning
     *  [+] using forced peer 162.159.198.1:443 (probe skipped)
     * ```
     *
     * That is why the row was usually empty in 1.3.1 and not in 1.3.0: this
     * release stopped discarding a working cached endpoint (the quick-reconnect
     * RTT budget is off by default now), so the reuse path became the ordinary
     * one on every reconnect — and the reuse path never prints `selected …`.
     *
     * The fix is to read the line the engine prints on EVERY path instead, right
     * before it brings the tunnel up, in `run_masque`, `run_wireguard` and
     * `run_warp_in_warp`:
     *
     * ```
     *  [+] using cloudflare edge 162.159.198.1:443
     *  [+] using cloudflare edge 162.159.198.1:443 (outer) and 162.159.192.1:443 (inner)
     * ```
     *
     * MASQUE-in-MASQUE has no such line and announces itself when both hops are
     * up, so it gets its own pattern. Both capture the OUTER peer — the address
     * the device actually dials, which is what the row is asked to name.
     */
    private val EDGE_MARKER = Regex("using cloudflare edge (\\S+:\\d+)")
    private val MIM_MARKER = Regex("masque-in-masque ready: (\\S+:\\d+)")
}
