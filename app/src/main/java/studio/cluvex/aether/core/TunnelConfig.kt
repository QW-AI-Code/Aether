package studio.cluvex.aether.core

/**
 * Single source of truth for the tunnel plumbing constants shared between the
 * VpnService (which builds the TUN + hev config) and the UI/diagnostics layer
 * (which talks to the local SOCKS5 proxy to probe connectivity and geolocation).
 *
 * IMPORTANT: [TUN_IPV4] MUST be written into BOTH the VpnService interface
 * address AND the hev-socks5-tunnel `tunnel.ipv4` field. v2rayNG always sets
 * `tunnel.ipv4` in its hev config; omitting it leaves hev's internal lwIP netif
 * without an address, so packets are read from TUN but never routed to the
 * SOCKS5 proxy -> the classic "connected but no site loads" symptom.
 */
object TunnelConfig {
    /** Local SOCKS5 proxy the Aether engine exposes. */
    const val SOCKS_HOST = "127.0.0.1"
    const val SOCKS_PORT = 1819

    /** Point-to-point TUN addressing (matches hev tunnel.ipv4 / tunnel.ipv6). */
    const val TUN_IPV4 = "10.10.14.1"
    const val TUN_IPV4_PREFIX = 30
    const val TUN_IPV6 = "fc00::10:10:14:1"
    const val TUN_IPV6_PREFIX = 126

    /**
     * Fallback TUN MTU. The live value now comes from the user's
     * [studio.cluvex.aether.model.ConnectionProfile.mtu]; this constant is only
     * used when no profile MTU is available. Lowered from 8500 to 1280 because
     * the oversized 8500 MTU caused path-MTU/fragmentation failures on Iranian
     * mobile networks ("connected but some sites/Telegram won't open").
     */
    const val MTU = 1280

    /**
     * SOCKS5 port the CHAINED second stage exposes (Aether -> Psiphon).
     *
     * The Aether engine owns [SOCKS_PORT] as stage 1, so the DNS-aware front of
     * stage 2 binds here, and THIS is the port tun2socks, the share bridge and
     * the self-test all talk to. The chained mode puts a front in front of its
     * transport ([studio.cluvex.aether.transport.PsiphonSocksFront]) instead of
     * exposing the transport's own listener.
     */
    const val CHAIN_SOCKS_PORT = 1825

    /**
     * Psiphon's own `LocalSocksProxyPort`, BEHIND the front.
     *
     * Never the port tun2socks talks to. psiphon-tunnel-core's SOCKS proxy is
     * CONNECT-only and refuses `UDP ASSOCIATE` (`command was 0x03, not 0x01` in
     * the field log, 636 times in one session), which killed every DNS query the
     * device made. [studio.cluvex.aether.transport.PsiphonSocksFront] binds
     * [CHAIN_SOCKS_PORT] and chains onto this one.
     *
     * 1.2.7 note: the two ports Tor used to own here (`SocksPort` 1822 and
     * `DNSPort` 1823) are gone with the Tor runtime. Nothing in the app binds
     * them any more.
     */
    const val PSIPHON_SOCKS_PORT = 1827

    /**
     * The engine's Tor listener when Tor rides INSIDE the tunnel (`--tor`).
     *
     * In that mode [SOCKS_PORT] keeps the WARP exit and Tor gets its own
     * listener, exactly as core 2.0.0 documents `--tor-bind`. With `--tor-only`
     * there is no tunnel to keep, so Tor is on [SOCKS_PORT] itself and this port
     * is unused - see [studio.cluvex.aether.model.TransportBackend.torSocksPort].
     */
    const val TOR_SOCKS_PORT = 1820

    /**
     * The port tun2socks talks to in the two modes whose traffic leaves through
     * Tor, owned by [studio.cluvex.aether.transport.TorSocksFront].
     *
     * It exists for one reason: **Tor carries TCP only**, in every
     * implementation, and hev-socks5-tunnel sends every UDP flow - so every DNS
     * query on the device - as SOCKS5 `UDP ASSOCIATE`. Pointing hev straight at a
     * Tor listener is a session that connects and opens nothing, which is the
     * failure the retired 1.2.7 Tor backend shipped. The front answers that
     * command itself and resolves DNS over TCP inside Tor.
     */
    const val TOR_FRONT_PORT = 1821

    /** DNS resolvers advertised on the TUN interface. */
    val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
}
