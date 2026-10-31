package studio.cluvex.aether.model

import studio.cluvex.aether.core.PortLease
import studio.cluvex.aether.core.TunnelConfig

/** The external stack a backend needs, independent of which one is chained. */
enum class ExternalKind { PSIPHON }

/**
 * How the engine is asked to combine Tor with the tunnel.
 *
 * These map 1:1 onto core 2.0.0's own flags: [CHAIN] is `--tor`, [ONLY] is
 * `--tor-only`, [REVERSE] is `--tor-reverse`.
 *
 * The three differ in one question - **who reaches the network first** - and every
 * other difference follows from it:
 *
 * ```
 *  CHAIN    tunnel first, then Tor inside it   local network sees: Aether
 *  ONLY     Tor, no tunnel                    local network sees: Tor (or a bridge)
 *  REVERSE  Tor first, then the tunnel in it   local network sees: Tor (or a bridge)
 * ```
 *
 * So bridges matter in [ONLY] and [REVERSE], where Tor faces the local network
 * itself, and do nothing in [CHAIN], where it is dialled through the tunnel.
 *
 * [REVERSE] additionally constrains the transport, and not by this app's choice:
 * Tor carries TCP only, WARP's WireGuard endpoints answer on UDP alone, so the
 * engine runs MASQUE over HTTP/2 in this mode and **refuses `--wg` and `--gool`**.
 * The app therefore overrides the protocol selection here rather than sending a
 * combination the engine will reject - see [ConnectionProfile.effectiveProtocol].
 */
enum class TorMode { CHAIN, ONLY, REVERSE }

/**
 * Which network stack carries the session.
 *
 * ## The six modes, and what each one is for
 *
 * ```
 *  AETHER          you -> WARP -> internet                     exit: WARP
 *  AETHER_PSIPHON  you -> WARP -> Psiphon -> internet          exit: Psiphon
 *  AETHER_TOR      you -> WARP -> Tor -> internet              exit: Tor
 *  TOR             you -> Tor -> internet                      exit: Tor
 *  TOR_PSIPHON     you -> Tor -> Psiphon -> internet            exit: Psiphon
 *  TOR_AETHER      you -> Tor -> WARP -> internet              exit: WARP
 * ```
 *
 * [TOR_AETHER] is the reverse chain, and its point is not the exit - a WARP exit
 * is what plain [AETHER] gives in one hop. Its point is what the local network
 * sees: nothing but Tor, or a bridge that does not look like Tor either. It is the
 * mode for a network that blocks or throttles Cloudflare/WARP itself while Tor
 * still gets through, and the one mode here in which the operator cannot tell that
 * a WARP tunnel exists at all. It carries real UDP, unlike the Tor-exit modes,
 * because the traffic the device hands over travels inside the WARP tunnel.
 *
 * ## Why Tor is back in 1.3.0, having been removed in 1.2.7
 *
 * 1.2.7 did not remove Tor because Tor is a bad exit. It removed a Tor
 * *implementation*: a packaged `libtor.so` executable with its own build script,
 * its own DNS-aware SOCKS front, two local ports and a bootstrap that took long
 * enough through the Aether handshake that users read it as a hang.
 *
 * Core 2.0.0 changes the premise. Tor is now IN THE ENGINE (arti, behind the
 * `tor` feature this app's CI builds with), so there is no second executable, no
 * separate build and no second process to supervise; the engine also fetches its
 * own bridges from bridgedb and runs them through the pluggable transports
 * shipped beside it, so a blocked network needs nothing pasted in. And
 * [AETHER_TOR] answers the bootstrap problem outright: Tor's guards are dialled
 * THROUGH the tunnel, so a network that blocks Tor never sees it and the
 * bootstrap runs at tunnel speed.
 *
 * The one thing that has not changed is that **Tor carries TCP only**. That is
 * the 1.2.7 failure worth remembering: hev-socks5-tunnel sends every UDP flow -
 * and therefore every DNS query the device makes - over SOCKS5 `UDP ASSOCIATE`,
 * so pointing it at a Tor proxy produces a session that connects and opens
 * nothing. The two modes whose device traffic leaves through Tor
 * ([TOR], [AETHER_TOR]) therefore run behind
 * [studio.cluvex.aether.transport.TorSocksFront], which answers `UDP ASSOCIATE`
 * itself, resolves DNS over TCP inside Tor and drops the rest.
 * [TOR_PSIPHON] does not need it: Psiphon's own front already carries real UDP
 * over udpgw.
 *
 * Removing or adding enum values is safe for stored profiles because both
 * persistence paths ([studio.cluvex.aether.core.ProfileCodec] and
 * [studio.cluvex.aether.data.ProfileStore]) store the NAME and route it through
 * [fromStoredName].
 *
 * Ordering note: new values are APPENDED, never inserted.
 */
enum class TransportBackend {
    AETHER,
    AETHER_PSIPHON,
    TOR,
    AETHER_TOR,
    TOR_PSIPHON,
    TOR_AETHER;

    /** Which external transport this mode needs, or null when there is none. */
    val externalKind: ExternalKind?
        get() = when (this) {
            AETHER, TOR, AETHER_TOR, TOR_AETHER -> null
            AETHER_PSIPHON, TOR_PSIPHON -> ExternalKind.PSIPHON
        }

    /** How the engine should combine Tor with the tunnel, or null for no Tor. */
    val torMode: TorMode?
        get() = when (this) {
            AETHER, AETHER_PSIPHON -> null
            AETHER_TOR -> TorMode.CHAIN
            TOR, TOR_PSIPHON -> TorMode.ONLY
            TOR_AETHER -> TorMode.REVERSE
        }

    /** True when the bundled engine has to run for this mode. Always: it hosts Tor too. */
    val usesAetherEngine: Boolean
        get() = true

    /**
     * True when this mode brings up a WARP tunnel at all.
     *
     * False for the two `--tor-only` modes, and that is what makes every
     * WARP-shaped setting meaningless in them: there is no endpoint to scan, no
     * protocol to choose, no WARP identity to provision. The UI greys those rows
     * out on this property rather than on the backend name.
     */
    val usesWarp: Boolean
        get() = torMode != TorMode.ONLY

    /** True when Psiphon has to run for this mode. */
    val usesExternal: Boolean
        get() = externalKind != null

    /** True when Tor is involved in any shape. */
    val usesTor: Boolean
        get() = torMode != null

    /**
     * True when the device's traffic leaves through Tor and therefore needs the
     * DNS-capable front ([studio.cluvex.aether.transport.TorSocksFront]).
     *
     * Two Tor modes are excluded, each for its own reason, and getting either wrong
     * would produce a session that verifies and carries nothing:
     *
     *  * [TOR_PSIPHON] - its device traffic leaves through Psiphon, whose own front
     *    carries real UDP over udpgw.
     *  * [TOR_AETHER] - the reverse chain. Tor is the OUTERMOST hop there, carrying
     *    the tunnel; what the device hands over travels inside WARP, which carries
     *    UDP natively. Putting a TCP-only front in front of a full tunnel would
     *    break DNS in a mode where DNS was never the problem.
     */
    val needsTorFront: Boolean
        get() = !usesExternal && (torMode == TorMode.CHAIN || torMode == TorMode.ONLY)

    /**
     * True for any mode with a SECOND local stage in front of the engine.
     *
     * Used for the two-stage supervision, the IPv6 handling of a chained TUN and
     * the AI gate. Tor rides INSIDE the engine, so a Tor mode is not chained in
     * this sense unless Psiphon is in it too.
     */
    val isChained: Boolean
        get() = usesExternal

    /** The local SOCKS5 port the FINISHED pipeline exposes to tun2socks. */
    val exposedSocksPort: Int
        get() = when {
            usesExternal -> PortLease.chain
            needsTorFront -> PortLease.torFront
            else -> PortLease.socks
        }

    /**
     * The engine's own Tor listener for this mode, or null when Tor is off.
     *
     * With `--tor-only` the engine's single proxy IS Tor, so it is on the usual
     * [PortLease.socks]. With `--tor` the usual port keeps the WARP exit
     * and Tor gets its own listener on [PortLease.torSocks].
     */
    val torSocksPort: Int?
        get() = when (torMode) {
            null -> null
            TorMode.ONLY -> PortLease.socks
            // Both of these keep the main listener for the tunnel and put Tor on a
            // second one. In CHAIN that second listener is the device's path; in
            // REVERSE it is a bonus (a plain Tor proxy beside a WARP tunnel that is
            // already reached through Tor) and nothing in the app routes through it.
            TorMode.CHAIN, TorMode.REVERSE -> PortLease.torSocks
        }

    /**
     * Human-readable pipeline, used in the log and in the info row so a user
     * reading a diagnostics dump can tell which hop failed.
     */
    val pipelineLabel: String
        get() = when (this) {
            AETHER -> "Aether"
            AETHER_PSIPHON -> "Aether \u2192 Psiphon"
            TOR -> "Tor"
            AETHER_TOR -> "Aether \u2192 Tor"
            TOR_PSIPHON -> "Tor \u2192 Psiphon"
            TOR_AETHER -> "Tor \u2192 Aether"
        }

    /**
     * The pipeline with the engine's transport named inside the Aether hop, e.g.
     * `Aether(WireGuard) → Tor`.
     *
     * ## Why (1.3.1)
     *
     * The home screen's card used to show the engine's PROTOCOL on its own, read
     * out of the log by [studio.cluvex.aether.core.EngineMeta]. On every chained
     * mode that was simply the wrong answer to the question the row asks: a user
     * on `Aether → Tor` saw "WireGuard" and had no way to tell whether Tor was in
     * the path at all, and a user on `Tor → Psiphon` saw "Auto" — a protocol
     * belonging to a WARP tunnel that mode never builds.
     *
     * So the row now names the whole path, with the transport in brackets on the
     * hop it actually belongs to.
     *
     * The brackets appear only when this mode brings up a WARP tunnel ([usesWarp]).
     * In the two `--tor-only` modes there is no Aether hop and no transport to
     * name, which is exactly why "Auto" was misleading there; those render as
     * `Tor` and `Tor → Psiphon` with nothing added.
     *
     * [transport] is whatever the engine reported. Blank or null gives the plain
     * label, so the row degrades to the pipeline alone rather than to `Aether()`
     * while a connect is still deciding.
     */
    fun pipelineLabel(transport: String?): String {
        // Only the transport belongs inside the brackets. A caller that hands over
        // a composite - "WIREGUARD \u2192 PSIPHON", the shape both chained paths in
        // AetherVpnService published before 1.3.1-r2 - would otherwise render as
        // "Aether(WIREGUARD \u2192 PSIPHON) \u2192 Psiphon", naming the second hop twice.
        // The callers are fixed; this keeps the row correct if another one appears.
        val name = transport
            ?.substringBefore('\u2192')
            ?.substringBefore("->")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (name == null || !usesWarp) return pipelineLabel
        return pipelineLabel.replaceFirst("Aether", "Aether($name)")
    }

    companion object {
        /**
         * Decodes a persisted backend name.
         *
         * A saved single-hop `PSIPHON` profile becomes its chained equivalent:
         * the user asked for a Psiphon exit and the chained mode is the one that
         * delivers it on the networks this app exists for.
         *
         * The two names retired in 1.2.7 come back to life here. `AETHER_TOR` is
         * a value again, so it resolves to itself through the normal path. A bare
         * `TOR` profile, saved before 1.2.7, also resolves to itself now -
         * `--tor-only` is exactly what it asked for. Nothing is silently
         * redirected to a different exit any more, which was the reason those two
         * names used to be mapped onto plain [AETHER].
         *
         * Anything genuinely unrecognised falls back to [AETHER].
         */
        fun fromStoredName(raw: String?): TransportBackend? {
            val name = raw?.trim()?.uppercase() ?: return null
            return when (name) {
                "PSIPHON" -> AETHER_PSIPHON
                else -> entries.firstOrNull { it.name == name }
            }
        }
    }
}
