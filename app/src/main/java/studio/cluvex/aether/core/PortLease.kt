package studio.cluvex.aether.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The loopback ports THIS session actually uses.
 *
 * ## Why this exists (issues #27 and #52)
 *
 * Every local port in [TunnelConfig] used to be a `const`, and every one of them
 * was bound unconditionally. Android's Private Space (and a work profile) runs the
 * app as a separate Android user but **shares the network namespace**, so two
 * Aether instances reach for the same loopback addresses. The second one loses:
 *
 * ```
 * the socks5 listener cannot use 127.0.0.1:1819:
 * Address already in use (os error 98); another program already listens there
 * ...
 * Engine exited before it opened the SOCKS5 port.
 * ```
 *
 * That is the whole of the field report: Main Space connects, Private Space fails
 * for every protocol and every fallback strategy, and it fails BEFORE a tunnel is
 * attempted, because the failure is a local bind. Other VPN apps manage it, and
 * the reporter's own workaround was to run a different app in one of the profiles.
 *
 * ## The rule: preferred, then the next free one
 *
 * The constants in [TunnelConfig] are now PREFERRED ports, not fixed ones.
 * [acquire] hands out the preferred value whenever it is free, so a normal
 * single-profile install keeps 1819 / 1820 / 1821 / 1825 / 1827 exactly as before
 * — the documented Proxy-only address does not move, and nothing a user has
 * written down stops working. Only when a port is already taken does the search
 * move on, in [STRIDE] steps, and the chosen set is guaranteed distinct.
 *
 * ## What this is not
 *
 * It is not a lock. Between the probe that finds a port free and the engine
 * binding it, another process can take it — the classic time-of-check /
 * time-of-use gap, and there is no way to hand a bound socket to a native child
 * here. What it removes is the CERTAIN collision of two instances both insisting
 * on the same number; what remains is a race that needs two processes to pick the
 * same free port in the same instant.
 *
 * Single instance per process: [acquire] is called once at the start of a session
 * and [reset] when it ends, both from
 * [studio.cluvex.aether.vpn.AetherVpnService], which is the only owner of a
 * session. The values are read from several threads, hence `@Volatile`.
 */
object PortLease {

    /** How far apart consecutive candidates for one role are. */
    private const val STRIDE = 10

    /** How many candidates to try per role before giving up on moving it. */
    private const val MAX_TRIES = 40

    private const val TAG = "ports"

    /** The engine's own SOCKS5 listener. Preferred [TunnelConfig.SOCKS_PORT]. */
    @Volatile
    var socks: Int = TunnelConfig.SOCKS_PORT
        private set

    /** The engine's Tor listener with `--tor` / `--tor-reverse`. */
    @Volatile
    var torSocks: Int = TunnelConfig.TOR_SOCKS_PORT
        private set

    /** The DNS-capable front in front of Tor. */
    @Volatile
    var torFront: Int = TunnelConfig.TOR_FRONT_PORT
        private set

    /** The chained second stage's front — what tun2socks talks to. */
    @Volatile
    var chain: Int = TunnelConfig.CHAIN_SOCKS_PORT
        private set

    /** Psiphon's own listener, behind [chain]. */
    @Volatile
    var psiphon: Int = TunnelConfig.PSIPHON_SOCKS_PORT
        private set

    /** True when any role had to move off its preferred port. */
    @Volatile
    var relocated: Boolean = false
        private set

    /**
     * Picks the five ports for a session. Call once, before the engine starts.
     *
     * Never fails: a role whose search finds nothing keeps its preferred port, so
     * the worst case is exactly the old behaviour rather than a refused connect.
     * Whether that happened is in the log and in [relocated].
     */
    @Synchronized
    fun acquire() {
        val taken = HashSet<Int>()
        socks = pick("engine socks5", TunnelConfig.SOCKS_PORT, taken)
        torSocks = pick("tor socks", TunnelConfig.TOR_SOCKS_PORT, taken)
        torFront = pick("tor front", TunnelConfig.TOR_FRONT_PORT, taken)
        chain = pick("chain front", TunnelConfig.CHAIN_SOCKS_PORT, taken)
        psiphon = pick("psiphon socks", TunnelConfig.PSIPHON_SOCKS_PORT, taken)

        relocated = socks != TunnelConfig.SOCKS_PORT ||
            torSocks != TunnelConfig.TOR_SOCKS_PORT ||
            torFront != TunnelConfig.TOR_FRONT_PORT ||
            chain != TunnelConfig.CHAIN_SOCKS_PORT ||
            psiphon != TunnelConfig.PSIPHON_SOCKS_PORT

        if (relocated) {
            DiagnosticsLog.i(
                TAG,
                "Some preferred local ports were busy - another Aether instance in a " +
                    "different Android profile is the usual reason. This session uses " +
                    "socks5=$socks tor=$torSocks torFront=$torFront chain=$chain " +
                    "psiphon=$psiphon.",
            )
        } else {
            DiagnosticsLog.d(
                TAG,
                "Local ports as preferred: socks5=$socks tor=$torSocks " +
                    "torFront=$torFront chain=$chain psiphon=$psiphon.",
            )
        }
    }

    /**
     * Ports for the lanes of a Smart Plus race.
     *
     * Deliberately NOT taken from the five session roles above. A racing engine is
     * a throwaway: it exists to answer "does this strategy work here", it is killed
     * either way, and the session the user ends up with is established afterwards
     * on the ordinary ports. Borrowing [socks] for a lane would mean the probe and
     * the real session fight over one number, which is the collision this whole
     * class exists to remove.
     *
     * [RACE_BASE] sits well above the documented ports so a relocated session
     * (Private Space) cannot walk into the race's range: the session roles step in
     * tens from 1819, and 40 tries can reach 2219 at the very most.
     *
     * Never fails and never returns a duplicate. Returns fewer ports than asked
     * for only if the search finds nothing, in which case the caller races fewer
     * lanes rather than two engines on one port.
     */
    @Synchronized
    fun leaseRacePorts(count: Int): List<Int> {
        val taken = HashSet<Int>(listOf(socks, torSocks, torFront, chain, psiphon))
        val out = ArrayList<Int>(count)
        var base = RACE_BASE
        repeat(count) {
            val port = pick("race lane ${out.size + 1}", base, taken)
            if (port !in out) {
                out += port
                taken += port
            }
            base += STRIDE
        }
        DiagnosticsLog.d(TAG, "Race lane ports: ${out.joinToString(", ")}")
        return out
    }

    /** First port considered for a race lane. See [leaseRacePorts]. */
    private const val RACE_BASE = 19_819

    /**
     * Returns to the preferred ports.
     *
     * Called at teardown so a next session starts from the documented numbers
     * again rather than inheriting a relocation whose cause is long gone.
     */
    @Synchronized
    fun reset() {
        socks = TunnelConfig.SOCKS_PORT
        torSocks = TunnelConfig.TOR_SOCKS_PORT
        torFront = TunnelConfig.TOR_FRONT_PORT
        chain = TunnelConfig.CHAIN_SOCKS_PORT
        psiphon = TunnelConfig.PSIPHON_SOCKS_PORT
        relocated = false
    }

    /**
     * The first free port at or after [preferred] that is not already in [taken].
     *
     * [taken] carries the numbers handed out earlier in the same [acquire], so two
     * roles can never be given the same port even when their searches overlap.
     */
    private fun pick(role: String, preferred: Int, taken: MutableSet<Int>): Int {
        var candidate = preferred
        repeat(MAX_TRIES) {
            if (candidate in 1..65535 && candidate !in taken && isFree(candidate)) {
                taken.add(candidate)
                if (candidate != preferred) {
                    DiagnosticsLog.d(TAG, "$role: $preferred is busy, using $candidate.")
                }
                return candidate
            }
            candidate += STRIDE
        }
        // Nothing free in the window. Keep the preferred port: the engine will
        // report the bind failure itself, which is more useful than a silent
        // substitution into a port we have no evidence about either.
        DiagnosticsLog.w(
            TAG,
            "$role: no free port found near $preferred after $MAX_TRIES tries; " +
                "keeping $preferred and letting the bind report the truth.",
        )
        taken.add(preferred)
        return preferred
    }

    /**
     * True when [port] can be bound on loopback right now.
     *
     * `reuseAddress` is deliberately NOT set: the question is whether a listener
     * is there, and SO_REUSEADDR on Linux would let this bind succeed alongside
     * one in TIME_WAIT, which is not the same question.
     */
    private fun isFree(port: Int): Boolean = runCatching {
        ServerSocket().use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getByName(TunnelConfig.SOCKS_HOST), port), 1)
            true
        }
    }.getOrDefault(false)
}
