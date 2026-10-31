package studio.cluvex.aether.custom

import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.Protocol

/** The engine's carrier is inside this config, not on the directly fingerprinted network. */
object CustomChainPolicy {
    /**
     * Chains whose engine hop is a WARP tunnel dialled straight through the config:
     * `Config -> Aether` AND `Config -> Aether -> Psiphon` / `Config -> Aether -> Tor`.
     * Field log 2026-10-30: the two triple chains were left on the plain AUTO ladder,
     * so WireGuard / QUIC (UDP) were tried through a config that cannot carry UDP
     * (e.g. a Cloudflare-Worker VLESS/WS panel) and every rung died after 4 s.
     */
    fun isWarpFirstBase(base: ChainBase?): Boolean =
        base == ChainBase.AETHER || base == ChainBase.AETHER_PSIPHON || base == ChainBase.AETHER_TOR

    fun usesTcpCarrier(profile: ConnectionProfile, mode: ChainMode?): Boolean =
        mode?.placement == ChainPlacement.FIRST && isWarpFirstBase(mode.base) &&
            profile.protocol == Protocol.MASQUE && profile.masqueHttp2

    fun runtimeEnvironment(profile: ConnectionProfile, mode: ChainMode?): Map<String, String> =
        if (usesTcpCarrier(profile, mode)) mapOf("AETHER_CUSTOM_TCP_UPSTREAM" to "1") else emptyMap()

    // Registration + remote MASQUE scanning pay for another proxy handshake.
    // A direct-path 45-second ladder rung cannot be used as their total budget.
    fun connectBudgetMs(profile: ConnectionProfile): Long = profile.connectTimeoutMs().coerceAtLeast(180_000L)

    fun engineProfile(profile: ConnectionProfile, mode: ChainMode): ConnectionProfile {
        if (mode.placement != ChainPlacement.FIRST || !isWarpFirstBase(mode.base)) return profile
        // TCP success through VLESS/SS does not imply SOCKS UDP support. Never race
        // a remembered direct WireGuard/QUIC route behind an untested config.
        return when (profile.protocol) {
            Protocol.AUTO -> profile.copy(protocol = Protocol.MASQUE, masqueHttp2 = true)
            Protocol.MASQUE -> profile.copy(masqueHttp2 = true)
            else -> profile // Explicit UDP carrier remains the user's choice, not a silent fallback.
        }
    }
}
