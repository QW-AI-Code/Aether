package studio.cluvex.aether.model

/** The single source of truth for what the UI shows. */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Launching : ConnectionState
    data object Connecting : ConnectionState

    /**
     * Tunnel/proxy is up but the 5-step end-to-end self-test is still
     * running. The UI must NOT present this as ready — Connected is only
     * reported once all four checks pass.
     */
    data object Verifying : ConnectionState
    data class Connected(val socksAddr: String) : ConnectionState
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : ConnectionState
    /* REMOVED in 1.3.1-r4: a `Repairing` state.
     *
     * It was truthful - the supervisor does know when stage 2 carries nothing -
     * but publishing it broke something worse than the lie it fixed. MainActivity
     * buckets every state into connected / busy / idle to decide what the exit-IP
     * pill shows, and anything busy CLEARS the pill. So each repair blanked the IP
     * and the flag ("IP not available"), and the refill afterwards waits for the
     * self-test's value and otherwise fetches through PortLease.socks - the
     * ENGINE's port, not the chained pipeline's front - so on a chained session
     * whose second stage was still broken it could not come back at all. The field
     * report was exactly that: the repair notice appeared, then the IP and flag
     * were gone and the session stayed dead.
     *
     * Telling the user the truth here needs the IP pill to survive a transient
     * state and the fallback fetch to use the pipeline's own port. Until that is
     * built, the previous behaviour - which the same user had been running without
     * this complaint - is the better of the two.
     */
    /*
     * The session is up but its SECOND stage is currently carrying nothing, and
     * the app is waiting for it to come back rather than rebuilding anything.
     *
     * ## The report this exists for
     *
     * "Aether alone is perfect; with Aether + Psiphon the first minutes are great,
     * then the ping goes over 900 ms, the connection drops in and out, and
     * sometimes it says connected and nothing moves at all."
     *
     * The field log explains it and clears the app of the cause: the Psiphon tunnel
     * itself dies and psiphon-tunnel-core re-establishes on a different server
     * (`Tunnels: {"count":0}` → `no active tunnels` → a new `ActiveTunnel`), and the
     * replacement server may refuse the udpgw port forward and outbound TCP/53.
     * The Aether stage never reconnected once in that log and its netstack reported
     * zero tail-drops throughout.
     *
     * What WAS the app's fault is that it kept saying **Connected** through all of
     * it. `PsiphonTransport.onConnecting` only wrote a line to the log - deliberately,
     * because psiphon-tunnel-core emits it for its own internal re-establish and
     * clearing the connected flag would have made the supervisor tear a healthy
     * session down. So the supervisor knew, the log knew, and the only thing that
     * did not know was the user, who was looking at a green badge over a pipeline
     * carrying nothing.
     *
     * This state is the honest answer to that: the session is not Connected and it
     * is not being rebuilt either. Nothing about the supervisor's behaviour changes
     * - it still gives stage 2 its confirmations before rebuilding, and a
     * deliberate exit rotation still gets its settling grace.
     */
    data object Disconnecting : ConnectionState
    data class Error(val message: String) : ConnectionState
}

val ConnectionState.isConnected: Boolean
    get() = this is ConnectionState.Connected

val ConnectionState.isBusy: Boolean
    get() = this is ConnectionState.Launching ||
        this is ConnectionState.Connecting ||
        this is ConnectionState.Verifying ||
        this is ConnectionState.Reconnecting ||
        this is ConnectionState.Disconnecting
