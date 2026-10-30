package studio.cluvex.aether.ai

import studio.cluvex.aether.core.TunnelConfig
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.TransportBackend
import studio.cluvex.aether.model.isConnected

/**
 * Why the AI is or is not available right now.
 *
 * ## The whole reason this file exists
 *
 * `generativelanguage.googleapis.com` is not reachable from Iran, and it is not
 * reachable from this app by accident either: [studio.cluvex.aether.vpn.AetherVpnService]
 * calls `addDisallowedApplication` on our own package, so Aether's own sockets
 * DELIBERATELY bypass the tunnel it is building. A plain `HttpsURLConnection` to
 * Google from inside this app therefore leaves on the operator's network, in the
 * clear, and fails - and on the way it announces to the operator that this device
 * just tried to reach a blocked AI endpoint.
 *
 * So every AI request in this app is dialled through the tunnel's own local
 * SOCKS5 proxy, and the AI is only offered when there IS a tunnel to dial
 * through. That is not a policy decision that could be relaxed later: with the
 * tunnel down there is no path to Google at all.
 *
 * The mode requirement is the second half of the same fact. Plain `Aether` exits
 * through a Cloudflare WARP address, and Google's AI endpoints refuse or
 * challenge those - which is exactly the symptom 1.2.8 shipped release notes
 * about ("Gemini, ChatGPT and similar AI apps sometimes said there was no
 * internet connection"). The chained `Aether -> Psiphon` mode exits through a
 * Psiphon address instead, which those endpoints accept. Anything else would put
 * a feature in the app that fails for everyone who tries it, which is the mistake
 * the retired single-hop Psiphon backend already taught this project once (see
 * [TransportBackend]).
 */
enum class AiGate {
    /** Ready: key present, tunnel up, chained mode. */
    READY,

    /** No API key entered yet. */
    NO_KEY,

    /** No model chosen and none discovered for this key yet. */
    NO_MODEL,

    /** The tunnel is down (or still coming up), so Google is unreachable. */
    DISCONNECTED,

    /** Connected, but on plain Aether rather than the chained mode. */
    WRONG_MODE,
    ;

    val ready: Boolean get() = this == READY
}

/** Resolves the current gate state. */
object AiAvailability {

    fun evaluate(
        state: ConnectionState,
        backend: TransportBackend,
        hasKey: Boolean,
        hasModel: Boolean,
        customMode: studio.cluvex.aether.custom.ChainMode? = null,
    ): AiGate = when {
        !hasKey -> AiGate.NO_KEY
        // Order matters: a user on plain Aether who is not connected should be
        // told to connect first, because switching mode is only possible while
        // disconnected anyway.
        !state.isConnected -> AiGate.DISCONNECTED
        // A Psiphon exit is what Google's AI endpoints accept. Both chained modes
        // provide one; plain Aether exits through WARP (refused) and the two Tor
        // modes exit through a Tor address, which those endpoints challenge even
        // harder than WARP. So the gate asks "is there a Psiphon hop", which is
        // exactly what isChained means.
        !backend.isChained && !customExitEligible(customMode) -> AiGate.WRONG_MODE
        !hasModel -> AiGate.NO_MODEL
        else -> AiGate.READY
    }

    /**
     * The local SOCKS5 port that carries AI traffic for [backend].
     *
     * In the chained mode stage 1 (Aether) owns [TunnelConfig.SOCKS_PORT] and the
     * DNS-aware front of stage 2 owns [TunnelConfig.CHAIN_SOCKS_PORT] - and stage
     * 2 is the one whose exit IP Google sees. Talking to stage 1 would exit
     * through WARP and get refused, which is the failure this whole gate exists to
     * avoid, so the port is derived from the mode rather than hard-coded.
     */
    fun socksPort(backend: TransportBackend): Int = backend.exposedSocksPort

    internal fun customExitEligible(mode: studio.cluvex.aether.custom.ChainMode?): Boolean =
        mode != null && (mode.placement != studio.cluvex.aether.custom.ChainPlacement.FIRST ||
            mode.base == studio.cluvex.aether.custom.ChainBase.PSIPHON || mode.base?.backend?.isChained == true)

    /** Never send custom-session AI traffic to a backend's intermediate/default port. */
    fun socksPort(backend: TransportBackend, state: ConnectionState): Int =
        if (state is ConnectionState.Connected)
            studio.cluvex.aether.core.TunnelExitMonitor.endpoint(state.socksAddr)?.port
                ?: throw IllegalArgumentException("AI requires a valid loopback tunnel endpoint")
        else socksPort(backend)
}
