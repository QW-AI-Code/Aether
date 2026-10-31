package studio.cluvex.aether.transport

import android.net.VpnService
import studio.cluvex.aether.core.PortLease
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ExternalKind

interface ExternalTransport {
    /** Brings the transport up and returns the local SOCKS5 port it listens on. */
    suspend fun start(): Int
    fun isAlive(): Boolean
    fun stop()
}

object ExternalTransportFactory {
    /**
     * Builds the transport for [profile].
     *
     * 1.4.0: Psiphon runs INSIDE the engine (core 2.1.0, `--psiphon`), so the
     * wiring is:
     *
     * ```
     *   stage 1  Aether engine      -> SOCKS5 127.0.0.1:1819 (WARP, or Tor with --tor-only)
     *            + engine's Psiphon -> SOCKS5 127.0.0.1:1827, dialling via stage 1
     *   front    PsiphonSocksFront  -> SOCKS5 127.0.0.1:1825   <- tun2socks talks here
     * ```
     *
     * The transport returns the FRONT's port, never Psiphon's own: the front is
     * the only listener that answers SOCKS5 `UDP ASSOCIATE`, which hev needs for
     * every DNS query the device makes.
     */
    fun create(
        service: VpnService,
        profile: ConnectionProfile,
        engineAlive: () -> Boolean,
    ): ExternalTransport =
        when (profile.backend.externalKind) {
            ExternalKind.PSIPHON -> PsiphonTransport(
                service = service,
                region = profile.exitRegion,
                engineAlive = engineAlive,
                localSocksPort = PortLease.psiphon,
                frontPort = PortLease.chain,
            )
            null -> error("${profile.backend} is not an external transport")
        }
}
