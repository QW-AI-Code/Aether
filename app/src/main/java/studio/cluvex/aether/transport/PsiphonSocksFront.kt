package studio.cluvex.aether.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.math.min
import studio.cluvex.aether.core.NameMemory
import studio.cluvex.aether.core.RouteRules

/**
 * The SOCKS5 server that tun2socks talks to in `Aether -> Psiphon` mode.
 *
 * ## ROOT CAUSE this class exists to fix
 *
 * `Aether -> Psiphon` connected and then opened NOTHING. The field log says why,
 * 636 times in a 50-second session:
 *
 * ```
 * 18:15:13.861 Psiphon: Warning: {"message":"SOCKS proxy accept error:
 *              socks5ReadCommand: SOCKS message field command was 0x03, not 0x01"}
 * ...
 * 18:16:03.878 Psiphon: TotalBytesTransferred: {"received":267,"sent":939}
 * ```
 *
 * `0x03` is `UDP ASSOCIATE`. The forwarder this app ships is
 * **hev-socks5-tunnel**, configured with `udp: 'udp'`, so it carries every UDP
 * flow — and therefore **every DNS query the device makes** — over standard
 * SOCKS5 `UDP ASSOCIATE`. psiphon-tunnel-core's local SOCKS proxy implements
 * `CONNECT` only (`socks5ReadCommand` rejects anything that is not `0x01`), so
 * tun2socks was pointed straight at a proxy that refuses the one command name
 * resolution depends on. The result was a fully established Psiphon tunnel with
 * a healthy exit (`exit ip=37.46.121.91 cc=SE`), 267 bytes of real traffic, and
 * not one page that loads. TCP was never broken — DNS never had a path at all.
 *
 * That also explains why the self-test passed: it resolves through a SOCKS5
 * `CONNECT` with a HOSTNAME, which Psiphon resolves remotely, so it never
 * touched the UDP path the rest of the device uses. `Diagnostics` now probes
 * `UDP ASSOCIATE` directly for exactly this reason.
 *
 * The retired Tor backend hit the identical wall and got a front of its own; it
 * went with the rest of the Tor runtime in 1.2.7. Psiphon kept
 * pointing tun2socks at psiphon's own listener because that WAS correct back
 * when this app drove **badvpn tun2socks**, which reaches UDP through a plain
 * `CONNECT` to the udpgw address rather than `UDP ASSOCIATE`. Moving to hev
 * silently removed the only UDP path Psiphon mode had. This class closes that
 * regression.
 *
 * ## What this does instead
 *
 * ```
 *   hev ──CONNECT <ip:port>──────────► relay ──► Psiphon SOCKS ──► tunnel
 *       └─UDP ASSOCIATE──► udp relay ──► udpgw frame ──┘
 *                              (one shared stream, multiplexed by conid)
 *       └─CONNECT 127.0.0.1:7300──► relayed verbatim (badvpn's own udpgw path)
 * ```
 *
 * ### Why udpgw, and not "answer DNS and drop the rest"
 *
 * Tor genuinely could not carry a UDP datagram, so its front had to answer DNS
 * out of Tor's `DNSPort` and drop everything else. **Psiphon can.** Its server
 * intercepts a port forward aimed at exactly `127.0.0.1:7300`
 * (`UDPInterceptUdpgwServerAddress` in the server's `tunnelServer.go`) and does
 * the UDP forwarding remotely — that is how the official Psiphon Android client
 * has always carried UDP. So rather than crippling the mode, this front opens
 * ONE udpgw stream through Psiphon and multiplexes every association onto it by
 * connection id, exactly as badvpn's `SocksUdpGwClient` does. The framing is
 * byte-identical to what the Psiphon server already speaks.
 *
 * ### QUIC is carried, prioritised inside that one stream
 *
 * 1.2.7-r2 dropped UDP/443 outright, because one shared udpgw stream let a video
 * flow head-of-line-block every DNS query behind it and melt down against the
 * tunnel's own congestion control. That protected the tunnel and broke every app
 * that will not fall back from HTTP/3 - Gemini, ChatGPT, CapCut, TikTok. r3 then
 * gave DNS a SECOND udpgw stream, which a Psiphon tunnel cannot hold at the same
 * time as the first: the two evicted each other 219 times in 18 minutes (see
 * [UdpgwLane]). 1.2.8-r2 keeps ONE stream and does the prioritising in the one
 * place that works - a priority lane in front of a single non-blocking writer
 * thread ([UDPGW_PRIORITY_QUEUE]) - while bulk frames DROP rather than buffer so
 * QUIC's congestion controller sees the loss and backs off. See [CARRY_QUIC].
 *
 * ### And a fallback, because a server may refuse the intercept
 *
 * If that port forward comes back refused (the same log shows Psiphon answering
 * `ssh: rejected: administratively prohibited` for other targets), udpgw is
 * marked unavailable for the rest of the session and port-53 datagrams are
 * answered over **DNS-over-TCP** through the same tunnel (RFC 1035 §4.2.2
 * framing) - and when the server does not allow outbound TCP/53 either, over
 * **DNS-over-HTTPS on 443**, which no Psiphon server blocks. Non-DNS UDP is then
 * dropped. Name resolution therefore never depends on anything the server can
 * refuse, so this mode can no longer fail the way it did.
 *
 * ## Ports
 *
 * Psiphon's own listener moved off the port tun2socks uses. In a chained session
 * the Aether engine owns 1819, this front owns
 * [studio.cluvex.aether.core.TunnelConfig.CHAIN_SOCKS_PORT] (what hev, the share
 * bridge and the self-test all talk to), and Psiphon binds
 * [studio.cluvex.aether.core.TunnelConfig.PSIPHON_SOCKS_PORT] behind it.
 */
object PsiphonSocksFront {

    private const val TAG = "PsiphonSocksFront"

    /** udpgw flags, from badvpn's `protocol/udpgw_proto.h` and psiphon's `server/udpgw.go`. */
    private const val FLAG_KEEPALIVE = 1 shl 0
    private const val FLAG_DNS = 1 shl 2
    private const val FLAG_IPV6 = 1 shl 3

    /**
     * The address the Psiphon server intercepts as a udpgw session.
     *
     * NOT arbitrary and NOT ours to pick: the server compares the requested
     * port-forward address against its configured
     * `UDPInterceptUdpgwServerAddress`, and `127.0.0.1:7300` is the value the
     * Psiphon network runs. Change it and every UDP flow becomes a real dial to
     * a loopback address, which the server rightly refuses.
     */
    private const val UDPGW_HOST = "127.0.0.1"
    private const val UDPGW_PORT = 7300

    private const val SOCKS_VERSION = 5
    private const val CMD_CONNECT = 1
    private const val CMD_UDP_ASSOCIATE = 3
    private const val ATYP_IPV4 = 1
    private const val ATYP_DOMAIN = 3
    private const val ATYP_IPV6 = 4

    private const val REP_SUCCESS = 0
    private const val REP_GENERAL_FAILURE = 1

    /** SOCKS5 `connection not allowed by ruleset`: what a BLOCK rule answers (as the engine does). */
    private const val REP_NOT_ALLOWED = 2

    /**
     * SOCKS5 `host unreachable`. Used to fail an IPv6 flow INSTANTLY once this
     * exit has proven it has no IPv6 (see [ipv6Usable]): lwIP turns it into an
     * immediate reset, so the app's Happy-Eyeballs timer fires in milliseconds
     * and retries over IPv4 instead of waiting out a Psiphon dial.
     */
    private const val REP_HOST_UNREACHABLE = 4
    private const val REP_CMD_NOT_SUPPORTED = 7

    /**
     * The port QUIC / HTTP-3 uses. Singled out because it is both what made this
     * mode collapse during video playback and what the AI apps refuse to work
     * without - see [CARRY_QUIC] and `docs/PSIPHON_MEDIA_STALL.md`.
     */
    private const val QUIC_PORT = 443

    private const val RELAY_BUFFER = 32 * 1024

    /**
     * 1.4.0 smart routing (#66). How long a flow to a bare address may take to
     * send its first bytes (TLS ClientHello / HTTP request) before it is routed
     * by its address alone. Same default as the engine's `AETHER_ROUTE_SNIFF_MS`:
     * a client-speaks-first protocol answers in microseconds, and a
     * server-speaks-first one (SMTP, SSH banners) only pays this once.
     */
    private const val ROUTE_SNIFF_MS = 400

    /** Bytes read for the sniff; the engine's `sniff::PEEK_BUDGET`. */
    private const val ROUTE_SNIFF_BUDGET = 4096

    /** Connect budget for a flow a DIRECT rule sends out of the real uplink. */
    private const val DIRECT_CONNECT_TIMEOUT_MS = 15_000

    /**
     * `SO_SNDBUF` / `SO_RCVBUF` for the two loopback legs this front sits on.
     *
     * ## 1.2.8-r8. Why a loopback socket needs a buffer limit at all
     *
     * The engine's netstack now admits app->network data against the rate the
     * peer is actually acknowledging (`FlowCredit`, `netstack.rs`), so an upload
     * can no longer park ~8 seconds of queue inside it. Backpressure has to land
     * SOMEWHERE, and without this it lands here: the two legs of this front
     *
     *   hev-socks5-tunnel -> [client]  ->  this front  ->  [upstream] -> Psiphon
     *
     * are ordinary TCP sockets, and Android autotunes a loopback socket's buffers
     * into the megabytes because on loopback that costs nothing and gains
     * throughput. It gains nothing here - the far side of this relay is a mobile
     * uplink - and what it actually does is re-create the exact queue r8 just
     * removed one layer higher up, where no counter in this project can see it.
     *
     * That is not a hypothetical: it is the same mistake, in a different kernel
     * buffer, that r6 found on the WireGuard socket (`SO_SNDBUF` 7 MB, so the
     * writer never waited and every throttle above it was decorative).
     *
     * 64 KB per direction per leg is ~0.6 s at the 107 KB/s the field log
     * measured, and a relay that copies in [RELAY_BUFFER] chunks still saturates
     * loopback at gigabytes per second, so nothing is lost by bounding it.
     */
    private const val LOOPBACK_QUEUE = 64 * 1024
    private const val PSIPHON_CONNECT_TIMEOUT_MS = 30_000

    /**
     * How long Psiphon's local SOCKS listener may take to answer a CONNECT.
     *
     * Bounded because the handshake used to be read with no timeout at all, and a
     * listener that accepts but never replies then parked the calling thread for
     * the life of the session - a udpgw lane that never came up, or a DNS worker
     * gone for good. Generous, because the reply only arrives once Psiphon has
     * actually opened the channel through the tunnel.
     */
    private const val PSIPHON_HANDSHAKE_TIMEOUT_MS = 20_000
    private const val DNS_TIMEOUT_MS = 10_000
    private const val DNS_BUFFER = 4096

    /**
     * DNS-over-HTTPS resolver used when this server refuses outbound TCP/53.
     *
     * Sent as a HOSTNAME so PSIPHON resolves it inside the tunnel (this front
     * never resolves anything itself - that would leak the name to the carrier's
     * resolver). Port 443 is the one port every Psiphon server allows out, which
     * is the entire point: it removes the last way for name resolution to fail in
     * this mode.
     */
    private val DOH_HOSTS = listOf("cloudflare-dns.com", "dns.google")
    private const val DOH_PORT = 443
    private const val DOH_PATH = "/dns-query"

    /**
     * How long an idle DoH connection may be reused. 1.2.7-r3.
     *
     * ## ROOT CAUSE this fixes (the "only Telegram and Instagram open, browsers
     * open nothing" report)
     *
     * The r2 fallback sent `Connection: close` and therefore paid a **full TCP
     * port forward plus a full TLS handshake through the tunnel for EVERY SINGLE
     * name lookup**. Through a chained Aether -> Psiphon session that is 1-3 s
     * per lookup, and only [DNS_POOL_SIZE] of them can be in flight at once. A
     * cold page load wants twenty to thirty names, so the browser times out
     * before its first request is even addressed - while Telegram (hard-coded
     * datacentre IPs, no DNS at all) and an already-warm Instagram keep working.
     * That is the exact signature the report describes, on every ISP, because it
     * has nothing to do with the ISP.
     *
     * Connections are pooled and reused instead: one warm connection answers a
     * whole page load in one round trip per name. Cloudflare and Google both keep
     * an idle DoH connection for about a minute, so this is comfortably inside
     * what the resolver allows.
     */
    private const val DOH_IDLE_MS = 45_000L

    /** Warm DoH connections kept for reuse. Matches a page load's name fan-out. */
    private const val DOH_POOL_MAX = 6
    private const val UDP_BUFFER = 8192
    private const val UDP_POLL_MS = 1_000

    /**
     * Concurrent in-flight DNS-over-TCP lookups.
     *
     * Fallback path only, and each task holds one Psiphon port forward for up to
     * [DNS_TIMEOUT_MS]. Sized like the retired Tor front's pool: a cold page load
     * easily wants a dozen names at once, and queueing them behind each other
     * times the page out.
     */
    private const val DNS_POOL_SIZE = 16

    /**
     * How many udpgw connection ids are remembered.
     *
     * badvpn reuses a conid for as long as a flow lives and the server keeps its
     * remote socket bound to it, so the mapping has to outlive a single
     * request/response pair. Bounded because a long session on a busy device
     * would otherwise keep one entry per UDP flow forever.
     */
    private const val MAX_UDPGW_FLOWS = 2048

    /** Keeps the shared udpgw port forward from being reaped as idle. */
    private const val UDPGW_KEEPALIVE_MS = 20_000L

    /**
     * QUIC (UDP/443) IS carried again. 1.2.7-r3.
     *
     * ## ROOT CAUSE this replaces (the "the app says you have no internet" report)
     *
     * 1.2.7-r2 dropped every UDP/443 datagram from the first packet, on the
     * theory that the client falls back to HTTP/2 over TCP. Browsers do. **The
     * apps people actually complained about do not.**
     *
     *  - Gemini, ChatGPT and every other Cronet / `TTNet` based app pins HTTP/3
     *    for its own origins and reads a **black hole** - datagrams that vanish
     *    with no signal whatsoever - as "this network has no internet", which is
     *    literally the error text the user sees. SOCKS5 `UDP ASSOCIATE` has no
     *    way to return an ICMP port-unreachable, so a silent drop is
     *    indistinguishable from a dead link and the app reports it as one.
     *  - CapCut's effect/template CDN and TikTok's feed are QUIC-first in
     *    practice: the app opens and its content never arrives.
     *  - The field log confirms the shape of it - `udp/443 (QUIC) dropped=48,
     *    IPv6 flows refused locally=65` in a two-minute session whose entire
     *    purpose was opening Gemini - and so does the reporter's own control
     *    experiment: the SAME session, USB-tethered to a laptop, opens
     *    `gemini.google.com` immediately, because a tether path is TCP-only and
     *    IPv4-only and therefore never touches either black hole.
     *
     * ## Why carrying it is safe now (r2's meltdown is fixed at its source)
     *
     * r2 was right about the mechanism and wrong about the cure. Both failure
     * modes are addressed where they actually live:
     *
     *  - **Head-of-line blocking** came from every frame being written straight
     *    from the datagram pump under one lock, so a congested tunnel blocked the
     *    only reader of the forwarder's UDP socket and UDP stopped device-wide.
     *    The pump now only enqueues; a dedicated writer thread does the blocking
     *    I/O; DNS rides a priority queue in front of it and bulk frames are
     *    dropped oldest-first at [UDPGW_BULK_QUEUE]. r3 tried to solve this with
     *    a second port forward instead and made it far worse - see [UdpgwLane].
     *  - **Congestion control fighting itself** came from a reliable tunnel
     *    hiding loss from QUIC. The bulk lane's queue is bounded and **drops**
     *    rather than buffers ([UDPGW_BULK_QUEUE]), which is precisely the loss
     *    signal QUIC's congestion controller needs: it backs off instead of
     *    inflating the RTT without bound.
     *  - And if it degrades anyway, [noteBulkDrop] trips a breaker: UDP/443 is
     *    suppressed for [QUIC_COOLDOWN_MS] and re-enabled automatically. r2's
     *    protection, without permanently breaking the apps.
     */
    private const val CARRY_QUIC = true

    /**
     * Bulk-lane drops inside [QUIC_TRIP_WINDOW_MS] that trip the QUIC breaker.
     *
     * A handful of drops is the bounded queue doing its job - QUIC reads them as
     * congestion and slows down, which is the entire design. A sustained storm
     * means this tunnel cannot carry the flow at all, and then suppressing
     * UDP/443 for a cooldown really is better for the rest of the device.
     */
    private const val QUIC_TRIP_DROPS = 96L
    private const val QUIC_TRIP_WINDOW_MS = 10_000L

    /** How long UDP/443 stays suppressed once the breaker trips. Self-expiring. */
    private const val QUIC_COOLDOWN_MS = 60_000L

    /**
     * Frames the udpgw writer may hold before it starts dropping.
     *
     * Bulk UDP is dropped OLDEST-FIRST, which is the correct behaviour for a
     * datagram service and the whole reason the queue exists: the datagram pump
     * must never block on a congested tunnel, because it is the only reader of
     * the forwarder's UDP socket and blocking it stalls UDP for the entire
     * device - DNS included.
     */
    private const val UDPGW_BULK_QUEUE = 256

    /** Priority (DNS + keepalive) frames queued before dropping. Never reached in practice. */
    private const val UDPGW_PRIORITY_QUEUE = 512

    /**
     * Minimum gap between two udpgw dials, and the ceiling the backoff grows to.
     *
     * 1.2.8-r2: a dial is an SSH channel open across every hop of the pipeline.
     * Retrying it on the next datagram - which is what "dial when there is no
     * live session" degenerates into when the dial keeps failing - turns a dead
     * udpgw into a self-inflicted flood. The gap is short enough that a real
     * recovery is invisible to the user and long enough that a refusing server
     * costs one channel every few seconds instead of hundreds.
     */
    private const val UDPGW_DIAL_MIN_GAP_MS = 400L
    private const val UDPGW_DIAL_MAX_GAP_MS = 5_000L

    /** Dials per session that get logged individually before the log goes quiet. */
    private const val UDPGW_DIAL_LOG_BUDGET = 8L

    /**
     * IPv6 dials allowed to fail before IPv6 is declared dead for this exit.
     *
     * ## ROOT CAUSE this fixes (the rotation storm)
     *
     * The TUN advertises an IPv6 address and a `::/0` route (IPv6 leak
     * protection), so the device happily prefers AAAA records. Psiphon's exit
     * servers are IPv4-only in practice, so every one of those flows comes back
     * `ssh: rejected: administratively prohibited`. Ordinary browsing barely
     * notices. Opening a video does: the player fans out dozens of segment
     * connections at once, and the field log shows 26 refusals in 4.2 s the
     * moment playback started - which the Psiphon health watchdog (removed in 1.4.0) then read as "this server
     * censors" and answered by tearing the tunnel down.
     *
     * Two flows are spent proving it (one is not evidence, a single destination
     * can legitimately be down), then IPv6 is refused locally and instantly for
     * the rest of the session. Re-probed once after a server rotation, because
     * the verdict describes the EXIT, not the tunnel.
     */
    private const val IPV6_PROBE_BUDGET = 2

    /**
     * Where the up-front IPv6 capability probe dials. 1.2.7-r3.
     *
     * ## ROOT CAUSE this fixes
     *
     * [IPV6_PROBE_BUDGET] only latches the verdict AFTER two real app flows have
     * already been sent to their deaths, and the counter is reset on every
     * connect and every rotation. In practice that means the first seconds of
     * every session - exactly when an app is being opened and is deciding whether
     * this network works - are spent proving something that is true of nearly
     * every Psiphon exit. The field log shows the bill: 65 IPv6 flows refused in
     * one two-minute session.
     *
     * So the verdict is now established BEFORE any app traffic, once, on a
     * background thread, against an address that is always up (Cloudflare's
     * public resolver on 443). An explicit SOCKS refusal is proof and latches
     * immediately; no answer at all is inconclusive and leaves the optimistic
     * assumption in place for [noteIpv6Refusal] to settle.
     */
    private const val IPV6_PROBE_TARGET = "2606:4700:4700::1111"
    private const val IPV6_PROBE_PORT = 443

    /** DNS `QTYPE` for AAAA. See [isAaaaQuery]. */
    private const val DNS_TYPE_AAAA = 28

    private val running = AtomicBoolean(false)

    /**
     * 1.4.0 smart routing (#66): the block/direct rules for THIS session - the
     * user's own rules plus the built-in lists, built by
     * [studio.cluvex.aether.core.SmartLists.frontRules] and handed over with
     * [setRoutes] before [start].
     *
     * ## Why the rules are applied here and not in the engine
     *
     * In `Aether -> Psiphon` the engine only ever carries Psiphon's own
     * connections to its servers; the user's destination is visible in exactly
     * one place, this front. So this is where block and direct are decided, with
     * the same grammar, precedence and sniffing as the engine's `socks.rs`.
     *
     * [RouteRules.EMPTY] (the default) leaves every code path below exactly as it
     * was before 1.4.0: the rule checks are skipped outright.
     */
    @Volatile private var routes: RouteRules = RouteRules.EMPTY

    /** Session counters for the routing summary in the log. */
    private val routedDirect = AtomicLong(0)
    private val routedBlocked = AtomicLong(0)
    private val dnsSinkholed = AtomicLong(0)

    /** Per-association socket for UDP a DIRECT rule sends out of the real uplink. */
    private val directUdp = ConcurrentHashMap<DatagramSocket, DirectUdp>()

    /**
     * Session byte counters, kept for the same reason the Tor front kept
     * them: Psiphon's `onBytesTransferred` reports the tunnel's own totals, not
     * what this pipeline carried, and a Connected badge over a tunnel moving 0 B
     * is precisely the failure this class exists to make impossible to miss.
     */
    private val txBytes = AtomicLong(0)
    private val rxBytes = AtomicLong(0)

    val sessionTx: Long get() = txBytes.get()
    val sessionRx: Long get() = rxBytes.get()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var psiphonSocksPort: Int = 0
    @Volatile private var connPool: ExecutorService? = null
    @Volatile private var dnsPool: ExecutorService? = null

    /**
     * THE udpgw stream. Exactly one, for the whole device.
     *
     * ## ROOT CAUSE this fixes (1.2.8-r2) - the udpgw ping-pong
     *
     * 1.2.7-r3 opened TWO port forwards to the udpgw address, one for DNS and
     * one for bulk UDP, so that a saturated video flow could not head-of-line
     * block a name lookup in the kernel send buffer. The reasoning was sound and
     * the result was a disaster, because **a Psiphon tunnel carries exactly one
     * udpgw port forward**: opening the second one tears the first one down.
     *
     * The field logs prove it beyond argument. Over 18 minutes, `loge1` contains
     * 219 "stream up" lines and 220 "session closed" lines, and every single one
     * of the 218 consecutive pairs of "stream up" lines ALTERNATES lane:
     *
     * ```
     *   +bulk  -  +dns  -  -  +bulk  +dns  -  +bulk  -  -  +dns  -  +bulk ...
     * ```
     *
     * Two independent, merely congested streams cannot produce a perfect
     * alternation 218 times in a row. Two mutually exclusive ones cannot produce
     * anything else: each lane's dial killed the other lane, whose next datagram
     * re-dialled it, which killed the first. Under load that ran at up to 48
     * re-dials a minute, and each one costs a full SSH channel open across BOTH
     * hops of a chained session (~300-900 ms here).
     *
     * The consequences are exactly the report:
     *  - for most of the session there was no live udpgw stream, so every
     *    non-DNS datagram was silently DROPPED (QUIC, WebRTC, VoIP, games) and
     *    every lookup fell back to the slow DNS-over-HTTPS path;
     *  - the constant channel churn is itself load on the tunnel it is trying to
     *    use, which is where a large part of the 2000 ms came from.
     *
     * So: one stream. DNS is protected the way r2 intended - a **priority queue
     * inside the session** ([UDPGW_PRIORITY_QUEUE]) that a saturated bulk flow
     * cannot get in front of, plus a bounded, oldest-first bulk queue
     * ([UDPGW_BULK_QUEUE]) so the writer thread never parks. That is the layer
     * where prioritisation actually works, because there is exactly one socket
     * to prioritise onto and one writer feeding it.
     */
    private class UdpgwLane(val label: String) {
        @Volatile var session: UdpgwSession? = null

        /** True while a dial is in flight, so it is dialled once, not once per datagram. */
        val dialing = AtomicBoolean(false)
        val lock = Any()

        /** Monotonic ms before which no new dial may be attempted. */
        @Volatile var nextDialAt: Long = 0

        /** Consecutive failed dials, for the backoff. Reset on success. */
        val failures = AtomicInteger(0)

        /** Total dials this session, so churn shows up in the log if it ever returns. */
        val dials = AtomicLong(0)
    }

    /**
     * The one and only udpgw lane. Kept as a class rather than inlined so the
     * dial/backoff bookkeeping stays in one place.
     */
    private val udpLane = UdpgwLane("udp")

    /**
     * The server refused to intercept the udpgw address.
     *
     * A property of the SERVER, not of the stream, so it is not re-earned once
     * per datagram.
     */
    private val udpgwRefused = AtomicBoolean(false)

    /**
     * How many servers in THIS session have refused the udpgw port forward.
     *
     * Survives a rotation, which is the whole point: [udpgwRefused] describes one
     * server and is cleared when the next one arrives, and that is right for DNS.
     * It is wrong for QUIC, because a transport that appears and disappears is
     * worse than one that is never offered. Reset only in [stop].
     */
    private val quicRefusedThisSession = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Non-DNS UDP associations ended because this server carries no UDP.
     *
     * For the session summary, and to log the reason exactly once: a device with the
     * microphone open opens such a flow many times a minute.
     */
    private val udpAssociationsEnded = AtomicLong(0)

    /**
     * Where udpgw streams are dialled and where the IPv6 probe runs.
     *
     * ## ROOT CAUSE this fixes (device-wide UDP stalls, "browsers open nothing")
     *
     * The old `udpgwSession()` dialled Psiphon **on the datagram pump thread**,
     * holding a lock every other association's pump needed. The pump is the only
     * reader of the forwarder's UDP socket, so for as long as that dial took -
     * and a SOCKS handshake against an unhappy tunnel could take the full connect
     * budget - **UDP stopped for the whole device, DNS included**. That is the
     * same class of bug r2 fixed for the udpgw WRITER and missed for the dial.
     *
     * Dialling moved off the pump entirely. A pump that finds no live lane gets
     * `null` immediately: DNS falls through to the resolver path (which answers
     * it for real, over the tunnel), non-DNS UDP loses a datagram, and the lane
     * comes up in the background a few hundred milliseconds later.
     */
    @Volatile private var dialPool: ExecutorService? = null

    /** Set while UDP/443 is suppressed by [noteBulkDrop]'s breaker. Monotonic ms. */
    private val quicSuppressedUntil = AtomicLong(0)
    private val bulkDropWindowStart = AtomicLong(0)
    private val bulkDropsInWindow = AtomicLong(0)

    /**
     * False once this exit has proven it cannot dial IPv6 ([IPV6_PROBE_BUDGET]
     * refusals). While false, IPv6 flows are refused HERE, in microseconds,
     * instead of being sent on a round trip that ends in a refusal anyway.
     */
    private val ipv6Usable = AtomicBoolean(true)
    private val ipv6Refusals = AtomicInteger(0)

    /**
     * True once this server has refused a TCP dial to port 53.
     *
     * A great many Psiphon servers do not allow port 53 out (it is not in their
     * `AllowTCPPorts` traffic rule), so the DNS-over-TCP fallback turns every
     * single lookup into one refused port forward. In the field log that is the
     * 110-refusals-in-73-seconds storm on the second server, and it fed straight
     * back into the Psiphon health watchdog (removed in 1.4.0) as fake evidence of censorship. Once this is set,
     * DNS goes over DNS-over-HTTPS on 443 instead - a port no Psiphon server
     * blocks.
     */
    private val dnsPort53Refused = AtomicBoolean(false)

    /** Latched once TCP/853 (Android Private DNS) has been refused. Logged once. */    private val privateDnsBlocked = AtomicBoolean(false)

    /** Session counters for the diagnostics panel; cheap and worth having. */
    private val quicDropped = AtomicLong(0)
    private val bulkUdpDropped = AtomicLong(0)
    private val ipv6Refused = AtomicLong(0)
    private val aaaaSuppressed = AtomicLong(0)

    val isRunning: Boolean get() = running.get()

    /** True when UDP is riding a real udpgw session, so DNS and VoIP work, not only DNS. */
    val udpgwActive: Boolean get() = udpLane.session?.isAlive == true

    /** True while this exit is still believed to be able to dial IPv6. */
    val ipv6Reachable: Boolean get() = ipv6Usable.get()

    /** `null` when nothing was dropped, else a one-line summary for the log/UI. */
    fun dropSummary(): String? {
        val quic = quicDropped.get()
        val bulk = bulkUdpDropped.get()
        val v6 = ipv6Refused.get()
        val aaaa = aaaaSuppressed.get()
        val ended = udpAssociationsEnded.get()
        if (quic == 0L && bulk == 0L && v6 == 0L && aaaa == 0L && ended == 0L) return null
        return "udp/443 (QUIC) dropped=$quic, non-DNS UDP flows ended (server carries " +
            "no UDP)=$ended, congested udpgw frames dropped=$bulk, " +
            "IPv6 flows refused locally=$v6, AAAA answered empty locally=$aaaa"
    }

    /**
     * Installs the routing rules for the next session (#66). Called by the VPN
     * service before [start]; [stop] resets them to [RouteRules.EMPTY].
     *
     * The rules arrive already stripped of every DIRECT entry when the session
     * shares to the LAN (zero-leak sharing, see `ShareLeakGuard` point 1), so
     * nothing here can send a shared device's traffic out of the real uplink.
     */
    fun setRoutes(rules: RouteRules) {
        routes = rules
        NameMemory.clear()
        routedDirect.set(0)
        routedBlocked.set(0)
        dnsSinkholed.set(0)
    }

    /** `null` when no rule matched anything, else a one-line summary for the log. */
    fun routeSummary(): String? {
        val direct = routedDirect.get()
        val blocked = routedBlocked.get()
        val sinkholed = dnsSinkholed.get()
        if (direct == 0L && blocked == 0L && sinkholed == 0L) return null
        return "routing: direct=$direct, blocked=$blocked, DNS names blocked=$sinkholed"
    }

    /**
     * Bind the front-end.
     *
     * @param listenPort what tun2socks / the share bridge dials as its SOCKS server.
     * @param socksPort Psiphon's own `LocalSocksProxyPort`.
     */
    @Synchronized
    fun start(listenPort: Int, socksPort: Int): Boolean {
        if (running.get()) {
            ConnectionLog.record("$TAG already running")
            return true
        }
        psiphonSocksPort = socksPort

        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), listenPort))
            }
        } catch (e: Exception) {
            ConnectionLog.record("$TAG could not bind 127.0.0.1:$listenPort: ${e.message}")
            return false
        }

        serverSocket = server
        connPool = Executors.newCachedThreadPool()
        dnsPool = Executors.newFixedThreadPool(DNS_POOL_SIZE)
        // Two threads so the bulk lane never waits behind the DNS lane's dial.
        dialPool = Executors.newFixedThreadPool(3)
        closeLanes()
        udpgwRefused.set(false)
        ipv6Usable.set(true)
        ipv6Refusals.set(0)
        dnsPort53Refused.set(false)
        privateDnsBlocked.set(false)
        quicDropped.set(0)
        bulkUdpDropped.set(0)
        ipv6Refused.set(0)
        aaaaSuppressed.set(0)
        quicSuppressedUntil.set(0)
        bulkDropWindowStart.set(0)
        bulkDropsInWindow.set(0)
        drainDohPool()
        // Zeroed per session so the service's traffic deltas start from a known
        // baseline; a restart must not look like a sudden burst.
        txBytes.set(0)
        rxBytes.set(0)
        running.set(true)
        closeProbeConn()
        governor.start()

        Thread({
            // The accept loop outlives anything a single connection can do. It
            // is a bare thread, so an escape here would reach the process-wide
            // handler and kill the app rather than merely stop accepting.
            try {
                while (running.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running.get()) ConnectionLog.record("$TAG accept failed: ${e.message}")
                        break
                    }
                    val pool = connPool
                    if (pool == null) {
                        closeQuietly(client)
                        break
                    }
                    try {
                        pool.execute {
                            // A pool task's uncaught exception reaches the worker
                            // thread's default handler and kills the process,
                            // exactly like a bare Thread. handleClient guards
                            // itself, but the guarantee belongs here so it cannot
                            // be lost by an edit inside it.
                            try {
                                handleClient(client)
                            } catch (_: Throwable) {
                                closeQuietly(client)
                            }
                        }
                    } catch (e: Exception) {
                        // Pool shut down between accept and submit.
                        closeQuietly(client)
                    }
                }
            } catch (t: Throwable) {
                ConnectionLog.record("$TAG accept loop ended: ${t.message}")
            }
        }, "psiphon-front-accept").apply { isDaemon = true }.start()

        ConnectionLog.record(
            "$TAG listening on 127.0.0.1:$listenPort → Psiphon SOCKS $socksPort, " +
                "UDP via udpgw $UDPGW_HOST:$UDPGW_PORT"
        )
        // PRE-WARM, 1.2.7-r3. The front is only ever started once Psiphon has a
        // tunnel, so both lanes and the IPv6 verdict can be established BEFORE
        // the TUN comes up and the first app packet arrives. Previously the first
        // DNS query paid for the dial and the first two IPv6 flows paid for the
        // verdict - which is precisely the window in which a user opens an app and
        // decides the network is broken.
        warmUp()
        return true
    }

    /**
     * Re-points the front at a new Psiphon `LocalSocksProxyPort`.
     *
     * A server rotation restarts the Psiphon library in place, and the restarted
     * controller may bind a different local port (the old one can still be in
     * `TIME_WAIT`). The front's own listener must NOT be rebuilt for that:
     * tun2socks holds an open connection to it, and closing it is exactly the
     * disconnect the rotation exists to avoid. So only the upstream target moves.
     *
     * Existing relays keep the socket they already have; every new connection
     * uses the new port. A no-op when the port has not changed or the front is
     * not running.
     */
    @Synchronized
    fun retarget(socksPort: Int) {
        if (!running.get()) return
        if (socksPort <= 0 || socksPort == psiphonSocksPort) return
        val previous = psiphonSocksPort
        psiphonSocksPort = socksPort
        closeLanes()
        udpgwRefused.set(false)
        closeProbeConn()
        governor.onPathChanged()
        ConnectionLog.record(
            "$TAG upstream Psiphon SOCKS moved $previous -> $socksPort; the front keeps its own " +
                "listener so tun2socks never sees a break",
        )
    }

    /**
     * Called after the Psiphon health watchdog (removed in 1.4.0) moves the session to a different server.
     *
     * The udpgw refusal is remembered for a whole session on purpose - retrying
     * a refused intercept per datagram means one doomed port forward per UDP
     * packet. But that memory describes the SERVER that refused it, not the
     * tunnel: keeping it after a rotation would leave a perfectly capable
     * replacement server permanently downgraded to DNS-over-TCP with all non-DNS
     * UDP dropped, so QUIC would stay broken for the rest of the session for no
     * reason at all.
     *
     * The dead session's socket is closed and the flag cleared; the next datagram
     * opens a fresh udpgw stream through the new server. Safe to call at any
     * time: with the front stopped this is a no-op.
     */
    @Synchronized
    fun onServerRotated() {
        if (!running.get()) return
        closeLanes()
        val wasRefused = udpgwRefused.getAndSet(false)
        // The IPv6 and port-53 verdicts describe the EXIT SERVER, not the
        // tunnel, so a rotation earns the replacement one fresh probe each.
        // Without this a v6-capable or 53-capable server would stay permanently
        // downgraded because of its predecessor's limitations.
        ipv6Usable.set(true)
        ipv6Refusals.set(0)
        dnsPort53Refused.set(false)
        // A rotation invalidates every warm connection through the old server.
        drainDohPool()
        // ...and the uplink measurement: a new server is a new path.
        closeProbeConn()
        governor.onPathChanged()
        quicSuppressedUntil.set(0)
        bulkDropsInWindow.set(0)
        warmUp()
        ConnectionLog.record(
            if (wasRefused) {
                // DNS only. QUIC is decided by [quicAllowed], which remembers
                // refusals for the session.
                "$TAG server rotated - clearing the udpgw refusal so the new server gets " +
                    "a fresh chance at real UDP for DNS"
            } else {
                "$TAG server rotated - udpgw session dropped; the next datagram reopens it"
            },
        )
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        closeQuietly(serverSocket)
        serverSocket = null
        closeLanes()
        drainDohPool()
        governor.stop()
        closeProbeConn()
        udpgwRefused.set(false)
        ipv6Usable.set(true)
        ipv6Refusals.set(0)
        dnsPort53Refused.set(false)
        quicSuppressedUntil.set(0)
        quicRefusedThisSession.set(0)
        dropSummary()?.let { ConnectionLog.record("$TAG session drops: $it") }
        routeSummary()?.let { ConnectionLog.record("$TAG $it") }
        udpAssociationsEnded.set(0)
        routes = RouteRules.EMPTY
        NameMemory.clear()
        directUdp.values.forEach { it.close() }
        directUdp.clear()
        connPool?.shutdownNow()
        connPool = null
        dnsPool?.shutdownNow()
        dnsPool = null
        dialPool?.shutdownNow()
        dialPool = null
        ConnectionLog.record("$TAG stopped")
    }

    // ---------------------------------------------------------------- SOCKS5

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            // 1.2.8-r8: keep the upload from re-queueing here. See LOOPBACK_QUEUE.
            boundLoopbackQueue(client)
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = BufferedOutputStream(client.getOutputStream())

            // Greeting. Both forwarders offer "no authentication" only, because
            // no credentials are ever passed to a loopback front.
            val version = input.read()
            if (version != SOCKS_VERSION) {
                closeQuietly(client)
                return
            }
            val methodCount = input.read()
            if (methodCount <= 0) {
                closeQuietly(client)
                return
            }
            val methods = ByteArray(methodCount)
            input.readFully(methods)
            if (methods.none { it.toInt() == 0 }) {
                // 0xFF = no acceptable method.
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0xFF.toByte()))
                output.flush()
                closeQuietly(client)
                return
            }
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0x00))
            output.flush()

            // Request.
            if (input.read() != SOCKS_VERSION) {
                closeQuietly(client)
                return
            }
            val command = input.read()
            input.read() // reserved
            val addressType = input.read()

            val host: String
            when (addressType) {
                ATYP_IPV4 -> {
                    val raw = ByteArray(4).also { input.readFully(it) }
                    host = InetAddress.getByAddress(raw).hostAddress ?: ""
                }
                ATYP_IPV6 -> {
                    val raw = ByteArray(16).also { input.readFully(it) }
                    host = InetAddress.getByAddress(raw).hostAddress ?: ""
                }
                ATYP_DOMAIN -> {
                    val length = input.read()
                    if (length <= 0) {
                        closeQuietly(client)
                        return
                    }
                    val raw = ByteArray(length).also { input.readFully(it) }
                    host = String(raw, Charsets.US_ASCII)
                }
                else -> {
                    replyFailure(output, REP_GENERAL_FAILURE)
                    closeQuietly(client)
                    return
                }
            }
            val port = ((input.read() and 0xFF) shl 8) or (input.read() and 0xFF)

            if (command == CMD_UDP_ASSOCIATE) {
                // hev's UDP path — the command Psiphon's own listener refuses,
                // and the whole reason this front exists.
                serveUdpAssociate(client, input, output)
                return
            }

            if (command != CMD_CONNECT) {
                // BIND: no forwarder this app uses asks for it, and Psiphon
                // cannot offer it.
                replyFailure(output, REP_CMD_NOT_SUPPORTED)
                closeQuietly(client)
                return
            }

            // 1.4.0 smart routing (#66). Skipped outright when the session has no
            // rules, so a session without rules takes exactly the path below.
            // The udpgw address is Psiphon's own control target, never a user
            // destination, so no rule may touch it.
            val rules = routes
            if (!rules.isEmpty && !(host == UDPGW_HOST && port == UDPGW_PORT)) {
                routeConnect(client, input, output, host, port, rules)
                return
            }

            // FAST-FAIL IPv6 once this exit has proven it has none. Sending the
            // flow anyway costs a full round trip to the Psiphon server and comes
            // back refused, and a video player opens dozens of those at once -
            // which is what the field log recorded as 26 refusals in 4.2 s and
            // what made the watchdog convict an innocent server. Answering here
            // instead lets the app's Happy-Eyeballs fallback pick IPv4 at once.
            if (isIpv6Literal(host) && !ipv6Usable.get()) {
                ipv6Refused.incrementAndGet()
                replyFailure(output, REP_HOST_UNREACHABLE)
                closeQuietly(client)
                return
            }

            // A CONNECT to the udpgw address needs no special case: relaying it
            // verbatim is exactly what badvpn's own udpgw client wants, and the
            // Psiphon server intercepts the port forward on arrival.
            relayThroughPsiphon(client, host, port, output)
        } catch (e: Exception) {
            closeQuietly(client)
        }
    }

    private fun replySuccess(output: OutputStream) {
        // BND.ADDR/BND.PORT are ignored by tun2socks for CONNECT, so a zero
        // IPv4 address is fine and is what most SOCKS servers emit.
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(), REP_SUCCESS.toByte(), 0, ATYP_IPV4.toByte(),
                0, 0, 0, 0, 0, 0,
            )
        )
        output.flush()
    }

    private fun replyFailure(output: OutputStream, code: Int) {
        try {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), code.toByte(), 0, ATYP_IPV4.toByte(),
                    0, 0, 0, 0, 0, 0,
                )
            )
            output.flush()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------- TCP relay

    /**
     * A live port forward through Psiphon.
     *
     * The buffered streams travel WITH the socket and are never re-derived: the
     * SOCKS5 reply was read through a BufferedInputStream, so a fresh
     * `getInputStream()` would resume after whatever that buffer already
     * swallowed and silently lose the first bytes of the payload.
     */
    private class PsiphonStream(
        val socket: Socket,
        val input: DataInputStream,
        val output: BufferedOutputStream,
    )

    private fun isIpLiteral(host: String): Boolean =
        host.indexOf(':') >= 0 || // bare IPv6
            Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) // IPv4

    /** A bare IPv6 literal. tun2socks is transparent, so this is the only form seen. */
    private fun isIpv6Literal(host: String): Boolean = host.indexOf(':') >= 0

    /**
     * Records one refused IPv6 dial and latches the verdict once the probe budget
     * is spent.
     *
     * Deliberately NOT reported to the Psiphon health watchdog (removed in 1.4.0): a refused IPv6 dial says the
     * exit has no IPv6, which is true of nearly every Psiphon server and is not
     * censorship. Counting it as censorship is precisely how a healthy tunnel got
     * torn down mid-video.
     */
    private fun noteIpv6Refusal(host: String, port: Int) {
        ipv6Refused.incrementAndGet()
        if (!ipv6Usable.get()) return
        if (ipv6Refusals.incrementAndGet() < IPV6_PROBE_BUDGET) return
        latchIpv6Unusable("$host:$port was refused")
    }

    /**
     * Decides whether one Psiphon refusal is evidence of a FILTERING server.
     *
     * Three refusal classes are structural rather than political, and reporting
     * them as censorship is what produced the rotation storm in the field log:
     *
     *  - **IPv6** - the exit has no IPv6. Handled by [noteIpv6Refusal].
     *  - **The udpgw intercept** - the server simply is not configured for it,
     *    which the front already handles by falling back.
     *  - **TCP port 53** - most servers do not allow it out at all, so the
     *    DNS-over-TCP fallback would convict every server it ever ran on.
     */
    private fun reportRefusal(host: String, port: Int) {
        if (isIpv6Literal(host)) {
            noteIpv6Refusal(host, port)
            return
        }
        if (host == UDPGW_HOST && port == UDPGW_PORT) return
        if (port == 853) {
            // ANDROID PRIVATE DNS (DNS-over-TLS), 1.2.7-r3.
            //
            // `netd` resolves through the user's Private DNS setting, not through
            // this front, and a good many Psiphon servers do not allow TCP/853
            // out. In "Automatic" mode Android falls back to plain DNS by itself
            // and nothing is lost. In STRICT mode (a hostname typed into the
            // setting) there IS no fallback, so the device resolves nothing at all
            // while the tunnel is perfectly healthy - "connected, browsers open
            // nothing, Telegram works". No app can turn that setting off on the
            // user's behalf, so the least this can do is name it in the log
            // instead of letting it look like the tunnel's fault.
            if (privateDnsBlocked.compareAndSet(false, true)) {
                ConnectionLog.record(
                    "$TAG this server does not allow outbound TCP/853, which is where Android's " +
                        "Private DNS (DNS-over-TLS) goes. If Private DNS is set to a specific " +
                        "hostname, set it back to Automatic or Off: in strict mode Android has no " +
                        "fallback and NOTHING on the device will resolve, however healthy the " +
                        "tunnel is. Not counted as censorship.",
                )
            }
            return
        }
        if (port == 53) {
            if (dnsPort53Refused.compareAndSet(false, true)) {
                ConnectionLog.record(
                    "$TAG this server does not allow outbound TCP/53 - DNS now goes over " +
                        "DNS-over-HTTPS on 443 through the same tunnel instead of burning one " +
                        "refused port forward per lookup.",
                )
            }
            return
        }
    }

    /**
     * SOCKS5 handshake + CONNECT against Psiphon's listener.
     *
     * @param refusal optional one-element array that receives Psiphon's own
     *   reply code, so a caller can tell "the server will not do this" (a real
     *   refusal worth remembering) apart from "the proxy is not reachable".
     */
    private fun openPsiphonStream(
        host: String,
        port: Int,
        refusal: IntArray? = null,
    ): PsiphonStream? {
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                // 1.2.8-r8: before connect(), so the bound is in force for the
                // whole life of the leg. See LOOPBACK_QUEUE.
                boundLoopbackQueue(this)
                connect(
                    InetSocketAddress("127.0.0.1", psiphonSocksPort),
                    PSIPHON_CONNECT_TIMEOUT_MS,
                )
                // 1.2.7-r3: the SOCKS exchange used to be read with NO timeout at
                // all, so a Psiphon that accepted the connection and never
                // answered parked the calling thread FOREVER. On a udpgw dial that
                // meant a lane that never came up and never retried; on a DNS
                // lookup it meant a worker held for the life of the session.
                soTimeout = PSIPHON_HANDSHAKE_TIMEOUT_MS
            }
        } catch (e: Exception) {
            return null
        }
        try {
            val upIn = DataInputStream(BufferedInputStream(upstream.getInputStream()))
            val upOut = BufferedOutputStream(upstream.getOutputStream())

            upOut.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0x00))
            upOut.flush()
            if (upIn.read() != SOCKS_VERSION || upIn.read() != 0x00) {
                closeQuietly(upstream)
                return null
            }

            // Hand the target over exactly as it arrived. An IP literal goes as
            // an IP (the common case: tun2socks is transparent and has no
            // names); a HOSTNAME goes as ATYP_DOMAIN so PSIPHON resolves it
            // inside the tunnel — resolving it here would leak the name to the
            // carrier's resolver and defeat the point of the hop.
            // ⚠️ The first three bytes are load-bearing and easy to lose in a
            // refactor: ByteArray() is zero-filled, so omitting them sends
            // `00 00 00 …`, which any SOCKS5 server reads as "version 0" and
            // rejects — for every single flow.
            val request: ByteArray = if (isIpLiteral(host)) {
                val addr = InetAddress.getByName(host).address
                val atyp = if (addr.size == 16) ATYP_IPV6 else ATYP_IPV4
                ByteArray(4 + addr.size + 2).apply {
                    this[0] = SOCKS_VERSION.toByte()
                    this[1] = CMD_CONNECT.toByte()
                    this[2] = 0
                    this[3] = atyp.toByte()
                    System.arraycopy(addr, 0, this, 4, addr.size)
                    this[4 + addr.size] = ((port shr 8) and 0xFF).toByte()
                    this[5 + addr.size] = (port and 0xFF).toByte()
                }
            } else {
                val name = host.toByteArray(Charsets.US_ASCII)
                if (name.size > 255) {
                    closeQuietly(upstream)
                    return null
                }
                ByteArray(5 + name.size + 2).apply {
                    this[0] = SOCKS_VERSION.toByte()
                    this[1] = CMD_CONNECT.toByte()
                    this[2] = 0
                    this[3] = ATYP_DOMAIN.toByte()
                    this[4] = name.size.toByte()
                    System.arraycopy(name, 0, this, 5, name.size)
                    this[5 + name.size] = ((port shr 8) and 0xFF).toByte()
                    this[6 + name.size] = (port and 0xFF).toByte()
                }
            }
            upOut.write(request)
            upOut.flush()

            // Psiphon's reply. Read it fully so the stream sits at the payload.
            if (upIn.read() != SOCKS_VERSION) {
                closeQuietly(upstream)
                return null
            }
            val reply = upIn.read()
            upIn.read() // reserved
            when (upIn.read()) {
                ATYP_IPV4 -> upIn.readFully(ByteArray(4))
                ATYP_IPV6 -> upIn.readFully(ByteArray(16))
                ATYP_DOMAIN -> {
                    val length = upIn.read()
                    if (length > 0) upIn.readFully(ByteArray(length))
                }
            }
            upIn.readFully(ByteArray(2)) // bound port

            refusal?.set(0, reply)
            if (reply != REP_SUCCESS) {
                closeQuietly(upstream)
                return null
            }
            // The handshake budget must not become a read budget: a relayed flow
            // (and a udpgw lane) is idle for minutes at a time by design.
            runCatching { upstream.soTimeout = 0 }
            return PsiphonStream(upstream, upIn, upOut)
        } catch (e: Exception) {
            closeQuietly(upstream)
            return null
        }
    }

    /**
     * @param clientIn where the app's bytes are read from. Null = the socket's
     *   own stream (the pre-1.4.0 path). The routing path passes the buffered
     *   stream it sniffed through, so nothing it already buffered is lost.
     * @param head bytes already read from the app for the sniff; sent first.
     * @param replied true when the SOCKS5 success reply already went out (the
     *   sniff needs it first); a failure can then only close the flow.
     */
    private fun relayThroughPsiphon(
        client: Socket,
        host: String,
        port: Int,
        clientOut: OutputStream,
        clientIn: InputStream? = null,
        head: ByteArray? = null,
        replied: Boolean = false,
    ) {
        val refusal = IntArray(1) { -1 }
        val stream = openPsiphonStream(host, port, refusal)
        if (stream == null) {
            // Pass Psiphon's own code back when there is one, so lwIP resets
            // this single flow instead of hammering a target the server will
            // not dial.
            if (refusal[0] > 0) {
                // A REPLY, not a dead proxy: this server declined to dial this
                // destination ("ssh: rejected: administratively prohibited").
                // One refusal is ordinary internet; a server that refuses many
                // DIFFERENT destinations is filtering, which is what makes
                // Telegram work while Google does not. the Psiphon health watchdog (removed in 1.4.0) decides -
                // but only for refusals that can actually MEAN censorship, see
                // [reportRefusal].
                reportRefusal(host, port)
            }
            if (!replied) replyFailure(clientOut, if (refusal[0] > 0) refusal[0] else REP_GENERAL_FAILURE)
            closeQuietly(client)
            return
        }
        val upstream = stream.socket

        try {
            if (!replied) replySuccess(clientOut)
            if (head != null && head.isNotEmpty()) {
                stream.output.write(head)
                stream.output.flush()
                txBytes.addAndGet(head.size.toLong())
            }

            // getInputStream() is resolved HERE, on the owning thread, and NOT
            // as the first statement of the pump lambda. Either direction
            // finishing closes both sockets and stop() closes all of them at
            // once, so that call can throw "Socket is closed" — and a bare
            // thread's uncaught throw reaches the process-wide handler and kills
            // the app. Resolved here it lands in the catch below instead: one
            // dead flow, and a tunnel that stays up.
            val appIn = clientIn ?: client.getInputStream()
            Thread({
                try {
                    pipeUp(appIn, stream.output)
                } catch (_: Throwable) {
                    // Per-flow and unreportable, but never fatal. A relay thread
                    // for one TCP flow must not be able to end the session.
                } finally {
                    closeQuietly(upstream)
                    closeQuietly(client)
                }
            }, "psiphon-front-up").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()

            pipe(stream.input, clientOut, rxBytes)
        } catch (e: Exception) {
            // Nothing useful to report per-flow; lwIP resets the stream.
        } finally {
            closeQuietly(upstream)
            closeQuietly(client)
        }
    }

    // ------------------------------------------------ smart routing (#66)

    /**
     * Routes one CONNECT by the session's rules: BLOCK refuses it, DIRECT sends
     * it out of the phone's real uplink, PROXY carries it through Psiphon as
     * before.
     *
     * Same flow as the engine's `handle_connect`: a flow to a bare address (the
     * only form tun2socks produces) is answered first and its opening bytes are
     * read for up to [ROUTE_SNIFF_MS] so a domain rule can match the TLS server
     * name or the HTTP `Host`. A hostname target (proxy mode) is decided by its
     * name straight away. Sniffing only happens while there are domain rules.
     *
     * DIRECT needs no socket protection: this package is always excluded from
     * the VPN ([studio.cluvex.aether.vpn.AetherVpnService.applyAppFilter]), so a
     * plain socket opened here already leaves through the real network.
     */
    private fun routeConnect(
        client: Socket,
        input: DataInputStream,
        output: OutputStream,
        host: String,
        port: Int,
        rules: RouteRules,
    ) {
        val literal = isIpLiteral(host)

        // An IPv6 flow on an exit that has proven it has no IPv6: a DIRECT or
        // BLOCK verdict by address still applies, but PROXY keeps the instant
        // refusal (no sniff: the flow is going to fail over the tunnel anyway,
        // and Happy Eyeballs needs the answer now, not after a sniff window).
        if (literal && isIpv6Literal(host) && !ipv6Usable.get()) {
            when (rules.decideTcp(host, null, false, port)) {
                RouteRules.Verdict.BLOCK -> blockFlow(client, output, replied = false)
                RouteRules.Verdict.DIRECT -> relayDirect(client, host, port, output, input, null, replied = false)
                RouteRules.Verdict.PROXY -> {
                    ipv6Refused.incrementAndGet()
                    replyFailure(output, REP_HOST_UNREACHABLE)
                    closeQuietly(client)
                }
            }
            return
        }

        var head: ByteArray? = null
        var sniffed: String? = null
        var replied = false
        if (literal && rules.hasDomainRules) {
            replySuccess(output)
            replied = true
            head = readSniffHead(client, input) ?: run {
                // The app closed the flow before sending anything.
                closeQuietly(client)
                return
            }
            if (head.isNotEmpty()) sniffed = RouteRules.sniffHost(head)
        }

        when (rules.decideTcp(host, sniffed, head?.isNotEmpty() == true, port)) {
            RouteRules.Verdict.BLOCK -> blockFlow(client, output, replied)
            RouteRules.Verdict.DIRECT -> relayDirect(client, host, port, output, input, head, replied)
            RouteRules.Verdict.PROXY -> relayThroughPsiphon(client, host, port, output, input, head, replied)
        }
    }

    /**
     * The flow's first bytes, read within [ROUTE_SNIFF_MS]. Empty when the app
     * sent nothing in time (a server-speaks-first protocol), null when it closed.
     */
    private fun readSniffHead(client: Socket, input: InputStream): ByteArray? {
        val buffer = ByteArray(ROUTE_SNIFF_BUDGET)
        return try {
            client.soTimeout = ROUTE_SNIFF_MS
            val read = input.read(buffer)
            if (read < 0) null else buffer.copyOf(read)
        } catch (_: java.net.SocketTimeoutException) {
            ByteArray(0)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { client.soTimeout = 0 }
        }
    }

    private fun blockFlow(client: Socket, output: OutputStream, replied: Boolean) {
        routedBlocked.incrementAndGet()
        if (!replied) replyFailure(output, REP_NOT_ALLOWED)
        closeQuietly(client)
    }

    /**
     * Carries one flow straight out of the real uplink (a DIRECT rule).
     *
     * A hostname (proxy mode only) is resolved by the system resolver on the real
     * network, which is what "direct" means. A loopback or wildcard destination
     * is refused, as the engine refuses it: a rule must never turn this front
     * into a way to reach the phone's own services.
     */
    private fun relayDirect(
        client: Socket,
        host: String,
        port: Int,
        clientOut: OutputStream,
        clientIn: InputStream,
        head: ByteArray?,
        replied: Boolean,
    ) {
        val target = try {
            RouteRules.literal(host) ?: InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
        if (target == null || target.isLoopbackAddress || target.isAnyLocalAddress) {
            if (!replied) replyFailure(clientOut, if (target == null) REP_HOST_UNREACHABLE else REP_NOT_ALLOWED)
            closeQuietly(client)
            return
        }
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(target, port), DIRECT_CONNECT_TIMEOUT_MS)
            }
        } catch (_: Exception) {
            if (!replied) replyFailure(clientOut, REP_HOST_UNREACHABLE)
            closeQuietly(client)
            return
        }
        routedDirect.incrementAndGet()
        try {
            if (!replied) replySuccess(clientOut)
            val upOut = upstream.getOutputStream()
            val upIn = upstream.getInputStream()
            if (head != null && head.isNotEmpty()) {
                upOut.write(head)
                upOut.flush()
                txBytes.addAndGet(head.size.toLong())
            }
            Thread({
                try {
                    pipe(clientIn, upOut, txBytes)
                } catch (_: Throwable) {
                } finally {
                    closeQuietly(upstream)
                    closeQuietly(client)
                }
            }, "psiphon-front-direct").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()
            pipe(upIn, clientOut, rxBytes)
        } catch (_: Exception) {
        } finally {
            closeQuietly(upstream)
            closeQuietly(client)
        }
    }

    /** Destination of one UDP datagram: (address, hostname) - exactly one is set. */
    private fun udpDestination(request: UdpRequest): Pair<InetAddress?, String?> {
        val raw = request.address
        if (raw != null) {
            return runCatching { InetAddress.getByAddress(raw.copyOfRange(0, raw.size - 2)) }.getOrNull() to null
        }
        val header = request.header
        if (header.size < 5) return null to null
        val length = header[4].toInt() and 0xFF
        if (header.size < 5 + length) return null to null
        return null to String(header, 5, length, Charsets.US_ASCII)
    }

    /**
     * Applies the session's rules to one UDP datagram. True when the datagram
     * was fully handled here (sinkholed, blocked or sent direct), false when it
     * should be carried through the tunnel as before.
     */
    private fun routeDatagram(
        rules: RouteRules,
        relay: DatagramSocket,
        from: InetSocketAddress,
        request: UdpRequest,
    ): Boolean {
        // DNS sinkhole: a blocked name gets NXDOMAIN at once, byte-identical to
        // the engine's answer, and never leaves the phone.
        if (request.port == 53) {
            val answer = rules.dnsBlockReply(request.payload)
            if (answer != null) {
                dnsSinkholed.incrementAndGet()
                sendUdpReply(relay, from, request, answer)
                return true
            }
        }
        val (ip, name) = udpDestination(request)
        if (ip == null && name == null) return false
        return when (rules.decideUdp(ip, name, request.port)) {
            RouteRules.Verdict.PROXY -> false
            RouteRules.Verdict.BLOCK -> {
                routedBlocked.incrementAndGet()
                true
            }
            RouteRules.Verdict.DIRECT -> {
                val direct = directUdp.getOrPut(relay) { DirectUdp(relay) }
                direct.client = from
                if (ip != null) {
                    direct.send(InetSocketAddress(ip, request.port), request.payload)
                } else {
                    // A hostname datagram (proxy mode only): resolved off the pump
                    // thread, which must never block - see [dialPool].
                    val pool = dnsPool
                    val port = request.port
                    val payload = request.payload
                    runCatching {
                        pool?.execute {
                            try {
                                val resolved = InetAddress.getByName(name)
                                direct.send(InetSocketAddress(resolved, port), payload)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                routedDirect.incrementAndGet()
                true
            }
        }
    }

    /** Feeds a DNS answer into the address -> name memory, while domain rules exist. */
    private fun learnDns(answer: ByteArray) {
        if (routes.hasDomainRules) NameMemory.learn(answer)
    }

    /** True when a SOCKS5 UDP header names port 53 (its last two bytes). */
    private fun headerIsDns(header: ByteArray): Boolean =
        header.size >= 2 &&
            (((header[header.size - 2].toInt() and 0xFF) shl 8) or (header[header.size - 1].toInt() and 0xFF)) == 53

    /**
     * The real-uplink UDP socket of ONE association, for datagrams a DIRECT
     * rule sends outside the tunnel. Created on the first such datagram and
     * closed with the association. Replies are wrapped in a SOCKS5 UDP header
     * naming the address they came from, like the engine's `build_udp_reply`.
     */
    private class DirectUdp(private val relay: DatagramSocket) {
        @Volatile var client: InetSocketAddress? = null
        private val socket = DatagramSocket().apply { soTimeout = UDP_POLL_MS }

        init {
            Thread({
                try {
                    readLoop()
                } catch (_: Throwable) {
                } finally {
                    runCatching { socket.close() }
                }
            }, "psiphon-front-direct-udp").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()
        }

        fun send(to: InetSocketAddress, payload: ByteArray) {
            val address = to.address ?: return
            if (address.isLoopbackAddress || address.isAnyLocalAddress) return
            try {
                socket.send(DatagramPacket(payload, payload.size, to))
                txBytes.addAndGet(payload.size.toLong())
            } catch (_: Exception) {
            }
        }

        private fun readLoop() {
            val buffer = ByteArray(UDP_BUFFER)
            while (running.get() && !relay.isClosed && !socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }
                val to = client ?: continue
                val raw = packet.address.address
                val header = ByteArray(4 + raw.size + 2)
                header[3] = (if (raw.size == 16) ATYP_IPV6 else ATYP_IPV4).toByte()
                System.arraycopy(raw, 0, header, 4, raw.size)
                header[4 + raw.size] = ((packet.port shr 8) and 0xFF).toByte()
                header[5 + raw.size] = (packet.port and 0xFF).toByte()
                val payload = buffer.copyOf(packet.length)
                if (packet.port == 53) learnDns(payload)
                val reply = ByteArray(header.size + payload.size)
                System.arraycopy(header, 0, reply, 0, header.size)
                System.arraycopy(payload, 0, reply, header.size, payload.size)
                rxBytes.addAndGet(payload.size.toLong())
                try {
                    relay.send(DatagramPacket(reply, reply.size, to.address, to.port))
                } catch (_: Exception) {
                    break
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    /**
     * Caps one loopback leg's kernel queue. See [LOOPBACK_QUEUE] for why.
     *
     * Best-effort by design: a kernel that refuses the request leaves the socket
     * exactly as it was, which is never worse than not trying.
     */
    private fun boundLoopbackQueue(socket: Socket) {
        try {
            socket.sendBufferSize = LOOPBACK_QUEUE
        } catch (_: Exception) {
        }
        try {
            socket.receiveBufferSize = LOOPBACK_QUEUE
        } catch (_: Exception) {
        }
    }

    /**
     * App -> Psiphon direction, through the uplink shaper (1.4.0-r3).
     *
     * While the governor is open this is exactly [pipe]. While it paces, a read
     * is released in [UplinkTuning.SLICE_BYTES] slices so fairness between bulk
     * flows has sub-second granularity even on a 30 KB/s uplink. Blocking here is
     * the intended backpressure: this leg's 64 KB [LOOPBACK_QUEUE] fills, hev's
     * window to the app closes, and the uploading app slows down at the source.
     */
    private fun pipeUp(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(RELAY_BUFFER)
        val flow = UplinkShaper.Flow()
        val shaper = governor.shaper
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                var off = 0
                while (off < read) {
                    val n = if (shaper.rateBps.isFinite()) min(UplinkTuning.SLICE_BYTES, read - off) else read - off
                    shaper.acquire(flow, n)
                    to.write(buffer, off, n)
                    to.flush()
                    off += n
                }
                txBytes.addAndGet(read.toLong())
            }
        } catch (_: Exception) {
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, counter: AtomicLong) {
        val buffer = ByteArray(RELAY_BUFFER)
        try {
            while (true) {
                val read = from.read(buffer)
                if (read < 0) break
                to.write(buffer, 0, read)
                to.flush()
                counter.addAndGet(read.toLong())
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------- SOCKS5 UDP ASSOCIATE

    /**
     * Serves one `UDP ASSOCIATE` association.
     *
     * Per RFC 1928 §7 the association lives exactly as long as the TCP control
     * connection that requested it, so this method holds that connection open
     * and tears the UDP socket down with it. The datagram pump runs on its own
     * thread; this one just holds the door.
     */
    private fun serveUdpAssociate(client: Socket, input: DataInputStream, output: OutputStream) {
        val relay = try {
            DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)).apply {
                soTimeout = UDP_POLL_MS
            }
        } catch (e: Exception) {
            ConnectionLog.record("$TAG could not open a UDP relay socket: ${e.message}")
            replyFailure(output, REP_GENERAL_FAILURE)
            closeQuietly(client)
            return
        }

        // BND.ADDR/BND.PORT is where the client must send its datagrams. Unlike
        // the CONNECT reply this one is NOT ignored — a zero address here means
        // the forwarder never sends a single packet.
        val bound = relay.localPort
        try {
            output.write(
                byteArrayOf(
                    SOCKS_VERSION.toByte(), REP_SUCCESS.toByte(), 0, ATYP_IPV4.toByte(),
                    127, 0, 0, 1,
                    ((bound shr 8) and 0xFF).toByte(), (bound and 0xFF).toByte(),
                )
            )
            output.flush()
        } catch (e: Exception) {
            runCatching { relay.close() }
            closeQuietly(client)
            return
        }

        val pump = Thread({
            try {
                pumpUdpAssociate(relay, client)
            } catch (_: Throwable) {
                // Per-association and never fatal.
            } finally {
                runCatching { relay.close() }
            }
        }, "psiphon-front-udp")
        pump.isDaemon = true
        pump.setUncaughtExceptionHandler { _, _ -> }
        pump.start()

        try {
            client.soTimeout = UDP_POLL_MS
            val scratch = ByteArray(512)
            while (running.get()) {
                try {
                    if (input.read(scratch) < 0) break
                } catch (_: java.net.SocketTimeoutException) {
                    // Expected: nothing is ever sent on the control connection.
                }
            }
        } catch (e: Exception) {
            // Control connection gone — the association is over.
        } finally {
            runCatching { relay.close() }
            udpLane.session?.releaseFlowsFor(relay)
            directUdp.remove(relay)?.close()
            closeQuietly(client)
        }
    }

    /**
     * Relays one association's datagrams, and ends the association when this
     * server cannot carry what it is being asked to carry.
     *
     * ## Why ending it is the fix (1.3.1-r5)
     *
     * Field report: browsing, video and downloads are fine on `Aether -> Psiphon`,
     * but the moment the microphone opens for live dubbing or a voice chat the ping
     * goes over 1000 ms and the session carries nothing; close the microphone and it
     * recovers. The log settles what it was:
     *
     * ```
     *   session drops: udp/443 (QUIC) dropped=272,
     *                  congested udpgw frames dropped=0
     * ```
     *
     * The bulk lane never overflowed, so this was not congestion. Those 272
     * datagrams were discarded BY POLICY, because both Psiphon servers in that
     * session had refused the udpgw port forward and real-time audio rides UDP.
     * A silently discarded datagram tells the app nothing, so it retried for as long
     * as the microphone was open - and the retry storm is the rest of that log: a
     * flow holding 48 KB with nothing acknowledged, stalling twice inside a minute,
     * and the Psiphon health watchdog (removed in 1.4.0) rotating off a server for "refusing 6 different
     * destinations" that was in truth refusing our own retries.
     *
     * SOCKS5 has no per-datagram error. It does have an end-of-association, and
     * `hev.yaml` asks hev for `udp: 'udp'` with a per-session timeout, which means
     * hev opens ONE ASSOCIATION PER UDP FLOW. Closing this association therefore
     * ends exactly one flow: the app's UDP socket fails, and both Gemini Live and
     * voice chat fall back to their own TCP path in about a second instead of
     * fighting for minutes and taking the tunnel down with them.
     *
     * ## The guard that makes this safe without hev's source
     *
     * hev is fetched at build time and is not in this tree, so "one association per
     * flow" is read off the config rather than proven. [sawDns] makes the change
     * safe either way: an association that has carried even one DNS query is NEVER
     * closed here.
     *
     *  * If the reading is right, audio associations are closed and DNS - which has
     *    its own associations - is untouched.
     *  * If hev multiplexes everything onto one association instead, that
     *    association sees a DNS query almost immediately and becomes permanently
     *    immune, leaving today's behaviour exactly as it is.
     *
     * So the worst case of this change is no improvement, never a broken resolver.
     * That matters here: DNS is answered by this front itself once udpgw is refused
     * (DNS-over-TCP, then DNS-over-HTTPS on 443), and it arrives through an
     * association like everything else.
     */
    private fun pumpUdpAssociate(relay: DatagramSocket, client: Socket) {
        // Per association, because the pump is per association.
        var sawDns = false
        val buffer = ByteArray(UDP_BUFFER)
        while (running.get() && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                break
            }
            // Copied out synchronously: the shared buffer is reused by the very
            // next receive, so handing a slice of it to a pool would race.
            val datagram = buffer.copyOf(packet.length)
            val from = InetSocketAddress(packet.address, packet.port)
            val request = parseUdpRequest(datagram) ?: continue
            if (request.payload.isEmpty()) continue

            // 1.4.0 smart routing (#66): the DNS sinkhole, BLOCK and DIRECT come
            // before every tunnel policy below, because none of them touches the
            // tunnel. Skipped outright when the session has no rules.
            val rules = routes
            if (!rules.isEmpty && routeDatagram(rules, relay, from, request)) {
                if (request.port == 53) sawDns = true
                continue
            }

            // POLICY, before anything is carried.
            if (request.isIpv6 && !ipv6Usable.get()) {
                // This exit has no IPv6 (see [latchIpv6Unusable]); the round trip
                // that proves it again costs the flow its Happy-Eyeballs timer.
                ipv6Refused.incrementAndGet()
                continue
            }
            if (request.port == 53) {
                // This association carries name resolution. It is answered locally
                // when udpgw is refused, so it must never be torn down below.
                sawDns = true
            } else if (udpgwRefused.get() && !sawDns) {
                if (udpAssociationsEnded.incrementAndGet() == 1L) {
                    ConnectionLog.record(
                        "$TAG this server carries no UDP, so a non-DNS UDP flow is ended " +
                            "at once (port ${request.port}) instead of having its datagrams " +
                            "dropped in silence. Real-time audio and video fall back to " +
                            "their own TCP path in about a second; DNS is unaffected.",
                    )
                }
                runCatching { relay.close() }
                closeQuietly(client)
                return
            }

            if (request.port == QUIC_PORT && !quicAllowed()) {
                // Only while the breaker is open or the server refuses udpgw
                // outright. QUIC is carried by default - see [CARRY_QUIC].
                quicDropped.incrementAndGet()
                continue
            }

            // AAAA SUPPRESSION, 1.2.7-r3.
            //
            // ROOT CAUSE: the TUN advertises an IPv6 address and a ::/0 route, so
            // the device sees working IPv6 and prefers AAAA - while the exit has
            // none. Refusing those flows fast (which the front does) still leaves
            // every app paying a failed connection attempt per destination, and
            // the apps that report "no internet" are exactly the ones that give
            // up instead of falling back. Removing the AAAA record removes the
            // reason the app ever tries: one empty NOERROR, answered here in
            // microseconds, and the whole device settles on IPv4 by itself.
            //
            // This is deliberately NOT a lie about IPv6 in general: it is only
            // done once the exit has PROVEN it cannot dial IPv6, and it is undone
            // for a server rotation that might be able to.
            if (request.port == 53 && !ipv6Usable.get()) {
                val nodata = nodataAnswerForAaaa(request.payload)
                if (nodata != null) {
                    aaaaSuppressed.incrementAndGet()
                    sendUdpReply(relay, from, request, nodata)
                    continue
                }
            }

            // Preferred path: real UDP through Psiphon's remote udpgw. DNS rides
            // its OWN stream so bulk UDP cannot delay a name lookup.
            if (request.address != null) {
                // 1.2.8-r2: ONE stream. DNS is prioritised inside it (see
                // [UdpgwSession.send] -> priority = isDns), which is where
                // prioritisation belongs now that there is a single socket and a
                // single writer thread. Two streams meant two port forwards, and
                // a Psiphon tunnel only ever keeps the newest one - see
                // [UdpgwLane].
                val session = laneSession(udpLane)
                if (session != null && session.send(relay, from, request)) {
                    txBytes.addAndGet(request.payload.size.toLong())
                    continue
                }
            }

            // Fallback: DNS must work even when the server refuses the
            // intercept, otherwise the device resolves nothing and this mode is
            // right back to "connected, opens nothing".
            if (request.port != 53) continue
            val pool = dnsPool ?: break
            try {
                pool.execute {
                    // Same reason as the connection pool in start(): an uncaught
                    // throw here would kill the process, not just this query.
                    try {
                        answerDnsQuery(relay, from, request)
                    } catch (_: Throwable) {
                    }
                }
            } catch (e: Exception) {
                break // pool shut down
            }
        }
    }

    /**
     * One parsed `UDP ASSOCIATE` datagram.
     *
     * [header] is kept VERBATIM rather than rebuilt: the reply has to echo the
     * destination the client asked for, and re-encoding an address is a needless
     * chance to get the byte order wrong. [address] is the raw destination
     * `addr || port` for the udpgw frame, or null for a hostname — which cannot
     * be udpgw'd, because udpgw carries addresses only.
     */
    private class UdpRequest(
        val header: ByteArray,
        val address: ByteArray?,
        val isIpv6: Boolean,
        val port: Int,
        val payload: ByteArray,
    )

    /** `RSV(2) FRAG(1) ATYP(1) ADDR PORT(2) payload` — RFC 1928 §7. */
    private fun parseUdpRequest(datagram: ByteArray): UdpRequest? {
        if (datagram.size < 10) return null
        // Fragmentation is optional in the RFC and implemented by nobody; a
        // non-zero FRAG is safer to drop than to guess at.
        if (datagram[2].toInt() != 0) return null
        val atyp = datagram[3].toInt() and 0xFF
        val addressLength = when (atyp) {
            ATYP_IPV4 -> 4
            ATYP_IPV6 -> 16
            ATYP_DOMAIN -> (datagram[4].toInt() and 0xFF) + 1
            else -> return null
        }
        val portOffset = 4 + addressLength
        if (datagram.size < portOffset + 2) return null
        val port = ((datagram[portOffset].toInt() and 0xFF) shl 8) or
            (datagram[portOffset + 1].toInt() and 0xFF)
        val headerLength = portOffset + 2
        // udpgw carries `addr || port`, both in NETWORK byte order, which is
        // exactly the layout already sitting in the SOCKS5 header — so it is
        // copied out rather than re-encoded. Byte-swapping it would make the
        // server's own address comparison reject every reply.
        val address = when (atyp) {
            ATYP_IPV4, ATYP_IPV6 -> datagram.copyOfRange(4, headerLength)
            else -> null
        }
        return UdpRequest(
            header = datagram.copyOfRange(0, headerLength),
            address = address,
            isIpv6 = atyp == ATYP_IPV6,
            port = port,
            payload = datagram.copyOfRange(headerLength, datagram.size),
        )
    }

    // ---------------------------------------------------- udpgw over Psiphon

    /** One UDP flow multiplexed onto the shared udpgw stream. */
    private class UdpgwFlow(
        val relay: DatagramSocket,
        val client: InetSocketAddress,
        val header: ByteArray,
    )

    /**
     * The single udpgw port forward, shared by every association.
     *
     * One stream for the whole device is what badvpn does and what the Psiphon
     * server expects. A stream per flow would mean a port forward per UDP flow,
     * which on a phone browsing normally is hundreds of them.
     *
     * Wire format, from `protocol/packetproto.h` + `protocol/udpgw_proto.h`:
     *
     * ```
     *   uint16 LE  length of everything that follows
     *   uint8      flags        (KEEPALIVE 1, REBIND 2, DNS 4, IPV6 8)
     *   uint16 LE  conid
     *   [ipv4] uint32 addr, uint16 port      <- both NETWORK byte order
     *   [ipv6] uint8[16] addr, uint16 port
     *   uint8[]    UDP payload
     * ```
     */
    private class UdpgwSession(
        private val socket: Socket,
        private val input: DataInputStream,
        private val output: OutputStream,
    ) {
        private val alive = AtomicBoolean(true)
        private val nextConid = AtomicInteger(1)

        /**
         * Outbound frames waiting for the writer thread.
         *
         * ## ROOT CAUSE this replaces
         *
         * Every frame used to be written STRAIGHT from the datagram pump, under a
         * single lock, with a blocking `write` + `flush`. A congested tunnel
         * therefore blocked the pump - and the pump is the only reader of the
         * forwarder's UDP socket, so UDP stopped for the WHOLE DEVICE, DNS
         * included, for as long as the tunnel stayed congested. Under a video
         * stream that is continuously. That is the mechanism behind "the ping
         * goes over 1000 and everything freezes".
         *
         * Now: the pump only ever enqueues (never blocks), one dedicated thread
         * does the blocking I/O, and DNS has its own lane that a saturated bulk
         * flow cannot get in front of. When [UDPGW_BULK_QUEUE] is full, bulk
         * frames are dropped OLDEST-first - correct for a datagram service, and
         * infinitely better than stalling name resolution.
         */
        private val queueLock = ReentrantLock()
        private val notEmpty = queueLock.newCondition()
        private val priorityFrames = ArrayDeque<ByteArray>()
        private val bulkFrames = ArrayDeque<ByteArray>()

        /** conid -> flow. Bounded and insertion-ordered; the oldest is evicted. */
        private val flows = Collections.synchronizedMap(
            object : LinkedHashMap<Int, UdpgwFlow>(256, 0.75f, false) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Int, UdpgwFlow>?,
                ): Boolean = size > MAX_UDPGW_FLOWS
            }
        )

        /**
         * Flow key -> conid, so a retransmit reuses the server's remote socket.
         *
         * BOUNDED, and bounded TOGETHER with [flows] (a plain ConcurrentHashMap
         * here grew one entry per UDP flow for the life of the session, and its
         * entries outlived the flow map they pointed into - so a long session
         * leaked, and an evicted conid could be handed out again while the server
         * still had a socket bound to it). Access-ordered, so the key that gets
         * dropped is the one nothing has used for longest, and dropping it also
         * drops the matching flow.
         */
        private val conids = Collections.synchronizedMap(
            object : LinkedHashMap<String, Int>(256, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Int>?,
                ): Boolean {
                    if (size <= MAX_UDPGW_FLOWS) return false
                    eldest?.value?.let { flows.remove(it) }
                    return true
                }
            }
        )

        val isAlive: Boolean get() = alive.get() && !socket.isClosed

        fun startReader() {
            Thread({
                try {
                    readLoop()
                } catch (_: Throwable) {
                    // Any failure here ends the session, never the app.
                } finally {
                    alive.set(false)
                    runCatching { socket.close() }
                    ConnectionLog.record("$TAG udpgw session closed")
                }
            }, "psiphon-front-udpgw").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()

            Thread({
                try {
                    writeLoop()
                } catch (_: Throwable) {
                    // Any failure here ends the session, never the app.
                } finally {
                    alive.set(false)
                    runCatching { socket.close() }
                    queueLock.lock()
                    try {
                        notEmpty.signalAll()
                    } finally {
                        queueLock.unlock()
                    }
                }
            }, "psiphon-front-udpgw-tx").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()

            Thread({
                try {
                    while (alive.get() && !socket.isClosed) {
                        Thread.sleep(UDPGW_KEEPALIVE_MS)
                        if (!enqueueFrame(byteArrayOf(FLAG_KEEPALIVE.toByte(), 0, 0), priority = true)) break
                    }
                } catch (_: Throwable) {
                }
            }, "psiphon-front-udpgw-ka").apply {
                isDaemon = true
                setUncaughtExceptionHandler { _, _ -> }
            }.start()
        }

        fun send(relay: DatagramSocket, client: InetSocketAddress, request: UdpRequest): Boolean {
            val address = request.address ?: return false
            if (!isAlive) return false
            // Keyed on the association's source port AND the destination, so two
            // apps talking to the same server get their own conid and cannot
            // receive each other's replies.
            val key = "${client.port}|" + address.joinToString("") { "%02x".format(it) }
            val conid = synchronized(conids) {
                conids[key] ?: allocateConid().also { conids[key] = it }
            }
            flows[conid] = UdpgwFlow(relay, client, request.header)

            val isDns = request.port == 53
            var flags = if (request.isIpv6) FLAG_IPV6 else 0
            // The DNS flag lets the Psiphon server answer out of its own
            // resolver rather than dialling whatever address the device happened
            // to be handed, which is both faster and harder to block.
            if (isDns) flags = flags or FLAG_DNS

            val body = ByteArray(3 + address.size + request.payload.size)
            body[0] = flags.toByte()
            body[1] = (conid and 0xFF).toByte()
            body[2] = ((conid shr 8) and 0xFF).toByte()
            System.arraycopy(address, 0, body, 3, address.size)
            System.arraycopy(request.payload, 0, body, 3 + address.size, request.payload.size)
            // DNS jumps the queue. A name lookup is one small datagram that the
            // whole page load is blocked on; a bulk datagram is one frame out of
            // thousands. Putting them in one FIFO is what made browsing crawl
            // while a video buffered.
            return enqueueFrame(body, priority = isDns)
        }

        /** Drops the conids belonging to a closed association. */
        fun releaseFlowsFor(relay: DatagramSocket) {
            synchronized(flows) {
                flows.entries.filter { it.value.relay === relay }
                    .map { it.key }
                    .forEach { flows.remove(it) }
            }
            // Both maps are synchronizedMap wrappers, so an iteration has to hold
            // the map's own monitor or it can throw ConcurrentModificationException
            // on a busy device. The previous version iterated [conids] unguarded.
            synchronized(conids) {
                val stale = conids.entries.filter { !flows.containsKey(it.value) }.map { it.key }
                stale.forEach { conids.remove(it) }
            }
        }

        fun close() {
            alive.set(false)
            queueLock.lock()
            try {
                priorityFrames.clear()
                bulkFrames.clear()
                notEmpty.signalAll()
            } finally {
                queueLock.unlock()
            }
            runCatching { socket.close() }
            flows.clear()
            conids.clear()
        }

        private companion object {
            /** Frames written between two flushes. */
            const val WRITE_BATCH = 32
        }

        /**
         * Next free connection id.
         *
         * `and 0xFFFF` alone was not enough: the counter wraps after 65535 flows
         * and would then hand out an id the server still has a remote socket
         * bound to, which delivers one flow's replies to a DIFFERENT flow. Zero
         * is skipped as well - badvpn treats it as a valid id but reusing the
         * very first id on wrap is the collision most likely to actually happen.
         * Caller holds the [conids] monitor.
         */
        private fun allocateConid(): Int {
            repeat(0x10000) {
                val raw = nextConid.getAndIncrement() and 0xFFFF
                val candidate = if (raw == 0) 1 else raw
                if (!flows.containsKey(candidate)) return candidate
            }
            // 65536 live flows is impossible with MAX_UDPGW_FLOWS, but never
            // return an undefined value.
            return 1
        }

        /**
         * Frames [body] and hands it to the writer thread. NEVER blocks.
         *
         * @return false only when the session is dead; a dropped bulk datagram
         *   still returns true, because from the caller's point of view the
         *   datagram was accepted by a lossy transport - which is exactly what
         *   UDP is.
         */
        private fun enqueueFrame(body: ByteArray, priority: Boolean): Boolean {
            if (body.size > 65535) return false
            if (!alive.get()) return false
            val frame = ByteArray(2 + body.size)
            frame[0] = (body.size and 0xFF).toByte()
            frame[1] = ((body.size shr 8) and 0xFF).toByte()
            System.arraycopy(body, 0, frame, 2, body.size)
            queueLock.lock()
            try {
                if (!alive.get()) return false
                if (priority) {
                    if (priorityFrames.size >= UDPGW_PRIORITY_QUEUE) priorityFrames.removeFirst()
                    priorityFrames.addLast(frame)
                } else {
                    while (bulkFrames.size >= UDPGW_BULK_QUEUE) {
                        bulkFrames.removeFirst()
                        bulkUdpDropped.incrementAndGet()
                        // The drop IS the loss signal QUIC needs. It is also the
                        // only evidence available that this tunnel cannot carry
                        // the flow at all, so the breaker counts it.
                        noteBulkDrop()
                    }
                    bulkFrames.addLast(frame)
                }
                notEmpty.signalAll()
            } finally {
                queueLock.unlock()
            }
            return true
        }

        /**
         * The ONLY place that blocks on the udpgw socket.
         *
         * Writes are batched and flushed once per batch: one `flush` per datagram
         * is one syscall per datagram, and under a busy DNS burst that alone was
         * measurable. The priority lane is always drained first.
         */
        private fun writeLoop() {
            while (alive.get()) {
                var frame: ByteArray? = null
                var priority = false
                queueLock.lock()
                try {
                    while (alive.get() && priorityFrames.isEmpty() && bulkFrames.isEmpty()) {
                        notEmpty.await()
                    }
                    if (!alive.get()) return
                    frame = priorityFrames.removeFirstOrNull()
                    priority = frame != null
                    if (frame == null) frame = bulkFrames.removeFirst()
                } finally {
                    queueLock.unlock()
                }
                try {
                    var batched = 0
                    var next: ByteArray? = frame
                    while (next != null) {
                        // 1.4.0-r3: every udpgw byte is paced by the uplink
                        // governor too. DNS rides the sparse path (never waits);
                        // bulk (QUIC / RTP) waits its fair turn. If the pacer
                        // is about to hold us, flush first so nothing already
                        // granted sits in the stream buffer.
                        if (!priority && governor.shaper.rateBps.isFinite() && batched > 0) {
                            output.flush()
                        }
                        governor.shaper.acquire(
                            if (priority) udpgwPriorityFlow else udpgwBulkFlow,
                            next.size,
                            priority,
                        )
                        output.write(next)
                        batched++
                        if (batched >= WRITE_BATCH) break
                        queueLock.lock()
                        try {
                            next = priorityFrames.removeFirstOrNull()
                            priority = next != null
                            if (next == null) next = bulkFrames.removeFirstOrNull()
                        } finally {
                            queueLock.unlock()
                        }
                    }
                    output.flush()
                } catch (e: Exception) {
                    alive.set(false)
                    return
                }
            }
        }

        private fun readLoop() {
            while (alive.get()) {
                val low = input.read()
                if (low < 0) break
                val high = input.read()
                if (high < 0) break
                val length = (high shl 8) or low
                if (length < 3 || length > 65535) break
                val body = ByteArray(length)
                input.readFully(body)

                val flags = body[0].toInt() and 0xFF
                val conid = ((body[2].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                if (flags and FLAG_KEEPALIVE != 0) continue

                val addressLength = if (flags and FLAG_IPV6 != 0) 18 else 6
                if (body.size < 3 + addressLength) continue
                val payload = body.copyOfRange(3 + addressLength, body.size)
                if (payload.isEmpty()) continue

                val flow = flows[conid] ?: continue
                if (headerIsDns(flow.header)) learnDns(payload)
                // The reply echoes the header the client sent rather than the
                // address the server reports: hev matches replies against the
                // destination it asked for, and the two are the same endpoint.
                val reply = ByteArray(flow.header.size + payload.size)
                System.arraycopy(flow.header, 0, reply, 0, flow.header.size)
                System.arraycopy(payload, 0, reply, flow.header.size, payload.size)
                rxBytes.addAndGet(payload.size.toLong())
                try {
                    flow.relay.send(
                        DatagramPacket(reply, reply.size, flow.client.address, flow.client.port)
                    )
                } catch (e: Exception) {
                    // Association torn down between request and reply.
                    flows.remove(conid)
                }
            }
        }
    }

    /**
     * The live session for [lane], or null - NEVER a dial.
     *
     * A missing lane schedules its own dial in the background and returns null at
     * once. See [dialPool] for why dialling here would stop UDP for the whole
     * device.
     */
    private fun laneSession(lane: UdpgwLane): UdpgwSession? {
        lane.session?.let { if (it.isAlive) return it }
        ensureLane(lane)
        return null
    }

    /**
     * Schedules one dial for [lane] if none is in flight. Never blocks.
     *
     * 1.2.8-r2: rate-limited. This is reached from the datagram pump, i.e. up to
     * thousands of times a second while the stream is down, and a dial is an SSH
     * channel open across the whole pipeline. Without the gap a udpgw that is
     * failing becomes a channel-open flood on the tunnel it needs.
     */
    private fun ensureLane(lane: UdpgwLane) {
        if (!running.get() || udpgwRefused.get()) return
        if (nowMs() < lane.nextDialAt) return
        if (!lane.dialing.compareAndSet(false, true)) return
        val pool = dialPool
        if (pool == null) {
            lane.dialing.set(false)
            return
        }
        try {
            pool.execute {
                try {
                    dialLane(lane)
                } catch (_: Throwable) {
                    // A failed dial is a retry, never a crash.
                } finally {
                    lane.dialing.set(false)
                }
            }
        } catch (_: Exception) {
            // Pool shut down between the check and the submit.
            lane.dialing.set(false)
        }
    }

    /**
     * Opens one udpgw port forward for [lane]. Runs on [dialPool] only.
     *
     * A refusal is remembered for the whole session ([udpgwRefused]) and shared by
     * both lanes: if this server will not intercept `127.0.0.1:7300`, retrying per
     * datagram would mean one doomed port forward per UDP packet, which is exactly
     * the churn that made the original field log unreadable.
     */
    private fun dialLane(lane: UdpgwLane) {
        synchronized(lane.lock) {
            lane.session?.let { if (it.isAlive) return }
            lane.session?.close()
            lane.session = null
            if (!running.get() || udpgwRefused.get()) return

            // Claim the next slot BEFORE dialling, so a dial that takes seconds
            // cannot be followed immediately by another one.
            lane.nextDialAt = nowMs() + UDPGW_DIAL_MIN_GAP_MS
            val refusal = IntArray(1) { -1 }
            val stream = openPsiphonStream(UDPGW_HOST, UDPGW_PORT, refusal)
            if (stream == null) {
                val failures = lane.failures.incrementAndGet()
                // Exponential, capped: 400 ms, 800, 1600, 3200, 5000, 5000...
                val gap = (UDPGW_DIAL_MIN_GAP_MS shl (failures - 1).coerceIn(0, 6))
                    .coerceAtMost(UDPGW_DIAL_MAX_GAP_MS)
                lane.nextDialAt = nowMs() + gap
                if (refusal[0] > 0) {
                    // A real answer from the server: it will not intercept.
                    // Falling back is the correct outcome, not an error.
                    if (udpgwRefused.compareAndSet(false, true)) {
                        // Session-wide, unlike [udpgwRefused] which a rotation
                        // clears: see [quicAllowed] for why QUIC must not be
                        // offered again after this.
                        val refusals = quicRefusedThisSession.incrementAndGet()
                        ConnectionLog.record(
                            "$TAG this server refused the udpgw port forward (SOCKS reply " +
                                "${refusal[0]}) - DNS moves to the resolver path (DNS-over-TCP, " +
                                "then DNS-over-HTTPS on 443) and non-DNS UDP is dropped",
                        )
                        if (refusals == QUIC_REFUSALS_BEFORE_LATCH) {
                            ConnectionLog.record(
                                "$TAG QUIC (udp/443) stays off for the rest of this session. " +
                                    "Apps fall back to HTTP/2 over TCP and stay there, which is " +
                                    "steady; QUIC that comes and going with every server " +
                                    "rotation is what stalls video and chat.",
                            )
                        }
                    }
                } else if (failures == 1 || failures % 10 == 0) {
                    ConnectionLog.record(
                        "$TAG could not open the udpgw stream ($failures in a row); " +
                            "retrying in ${gap} ms",
                    )
                }
                return
            }
            val session = UdpgwSession(stream.socket, stream.input, stream.output)
            if (!running.get()) {
                session.close()
                return
            }
            session.startReader()
            lane.session = session
            lane.failures.set(0)
            val dials = lane.dials.incrementAndGet()
            // The churn this replaces used to fill the log with 219 of these in
            // 18 minutes. Log the first few, then only every 50th - if the count
            // ever climbs again, the number itself is the diagnosis.
            if (dials <= UDPGW_DIAL_LOG_BUDGET || dials % 50 == 0L) {
                ConnectionLog.record(
                    "$TAG udpgw stream up (#$dials) - full UDP via Psiphon, " +
                        "DNS prioritised inside it",
                )
            }
        }
    }

    /** Closes the udpgw stream. Lock-free so a teardown can never park on a dial. */
    private fun closeLanes() {
        val session = udpLane.session
        udpLane.session = null
        udpLane.nextDialAt = 0
        udpLane.failures.set(0)
        session?.close()
    }

    /**
     * Establishes everything that can be known before the first app packet:
     * both udpgw lanes and the exit's IPv6 capability.
     */
    private fun warmUp() {
        ensureLane(udpLane)
        val pool = dialPool ?: return
        try {
            pool.execute {
                try {
                    probeIpv6Capability()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Settles "can this exit dial IPv6?" once, up front, on a background thread.
     *
     * Only an EXPLICIT refusal is proof. No answer at all can mean a busy tunnel,
     * so it leaves the optimistic assumption in place and lets
     * [noteIpv6Refusal] settle it from real traffic.
     */
    private fun probeIpv6Capability() {
        if (!running.get()) return
        val refusal = IntArray(1) { -1 }
        val stream = openPsiphonStream(IPV6_PROBE_TARGET, IPV6_PROBE_PORT, refusal)
        if (stream != null) {
            closeQuietly(stream.socket)
            ConnectionLog.record(
                "$TAG this exit can dial IPv6 - AAAA records are left untouched",
            )
            return
        }
        if (refusal[0] > 0) {
            latchIpv6Unusable(
                "the up-front probe to [$IPV6_PROBE_TARGET]:$IPV6_PROBE_PORT came back refused " +
                    "(SOCKS reply ${refusal[0]})",
            )
        }
    }

    /** Latches the "no IPv6 on this exit" verdict exactly once, with one log line. */
    private fun latchIpv6Unusable(why: String) {
        if (!ipv6Usable.compareAndSet(true, false)) return
        ConnectionLog.record(
            "$TAG this exit has no IPv6 ($why). AAAA queries are now answered locally with an " +
                "empty NOERROR so the device stops preferring IPv6 in the first place, IPv6 flows " +
                "are refused instantly instead of after a round trip, and none of it is counted " +
                "against the server - it is a property of the exit, not censorship.",
        )
    }

    // ------------------------------------------------------------ QUIC breaker

    /** Monotonic clock; wall time can jump and this drives a cooldown. */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * Whether UDP/443 is carried right now.
     *
     * False only when the server refuses udpgw altogether (nothing to carry it
     * on) or while [noteBulkDrop]'s breaker is open.
     */
    private fun quicAllowed(): Boolean {
        if (!CARRY_QUIC) return false
        if (udpgwRefused.get()) return false
        // 1.3.1-r3: QUIC STAYS OFF once this session has met servers that refuse
        // udpgw, instead of being offered again after every rotation.
        //
        // THE CONTRADICTION THIS RESOLVES, in this project's own words.
        // `docs/PSIPHON_MEDIA_STALL.md` §4 gave the reason UDP/443 was suppressed
        // from the first datagram: "Consistency is the point: intermittently
        // working QUIC is far worse than QUIC that never works, because Chromium
        // caches 'HTTP/3 works for this origin'." Then [CARRY_QUIC] turned QUIC
        // back on — correctly, because a server that intercepts udpgw carries it
        // well — and `onServerRotated` clears the refusal so each new server gets
        // a fresh chance. Put together, those two produce exactly the state §4
        // warned about: QUIC works, the tunnel dies, the replacement server
        // refuses udpgw, QUIC is dropped, the next rotation offers it again.
        //
        // The field log of that shape: `udpgw session closed` → `no active
        // tunnels` → `this server refused the udpgw port forward` → twice more
        // across six minutes, ending `udp/443 (QUIC) dropped=30`. The user's
        // report of it: "the first minutes are excellent, then the ping goes over
        // 900 ms and it cuts in and out."
        //
        // So a refusal is remembered for the SESSION now, not for one server. DNS
        // is unaffected — it has its own fallback chain (TCP/53, then DoH on 443)
        // and every rotation still gets a fresh attempt at real UDP for it, which
        // is what [udpgwRefused] alone controls. What is latched here is only
        // whether the device is offered a transport that may vanish again.
        if (quicRefusedThisSession.get() >= QUIC_REFUSALS_BEFORE_LATCH) return false
        return nowMs() >= quicSuppressedUntil.get()
    }

    /**
     * How many udpgw refusals a session tolerates before QUIC is left off.
     *
     * One. A single refusal already means this exit pool contains servers without
     * the intercept, and the cost of being wrong in this direction is that HTTP/3
     * is replaced by HTTP/2 for the rest of the session — which is what happens on
     * every network that blocks UDP anyway. The cost of being wrong in the other
     * direction is the stall this comment exists for.
     */
    private const val QUIC_REFUSALS_BEFORE_LATCH = 1

    /**
     * Counts one dropped bulk frame and trips the breaker on a sustained storm.
     *
     * Called from the writer's queue, so it must stay allocation-free and
     * non-blocking.
     */
    private fun noteBulkDrop() {
        val now = nowMs()
        if (now - bulkDropWindowStart.get() > QUIC_TRIP_WINDOW_MS) {
            bulkDropWindowStart.set(now)
            bulkDropsInWindow.set(1)
            return
        }
        if (bulkDropsInWindow.incrementAndGet() < QUIC_TRIP_DROPS) return
        bulkDropsInWindow.set(0)
        bulkDropWindowStart.set(now)
        val previous = quicSuppressedUntil.getAndSet(now + QUIC_COOLDOWN_MS)
        if (previous <= now) {
            ConnectionLog.record(
                "$TAG the bulk UDP lane is dropping faster than QUIC can back off - UDP/443 is " +
                    "suppressed for ${QUIC_COOLDOWN_MS / 1000}s so the rest of the device keeps " +
                    "its latency, then carried again automatically. TCP is untouched.",
            )
        }
    }

    // ------------------------------------------------------------ DNS helpers

    /** Wraps [answer] in the association's SOCKS5 UDP header and sends it back. */
    private fun sendUdpReply(
        relay: DatagramSocket,
        client: InetSocketAddress,
        request: UdpRequest,
        answer: ByteArray,
    ) {
        if (request.port == 53) learnDns(answer)
        val reply = ByteArray(request.header.size + answer.size)
        System.arraycopy(request.header, 0, reply, 0, request.header.size)
        System.arraycopy(answer, 0, reply, request.header.size, answer.size)
        rxBytes.addAndGet(answer.size.toLong())
        try {
            relay.send(DatagramPacket(reply, reply.size, client.address, client.port))
        } catch (e: Exception) {
            // Association torn down between the query and the answer.
        }
    }

    /** The single question of a query: its QTYPE and where the question ends. */
    private class DnsQuestion(val type: Int, val end: Int)

    /**
     * Parses the one question of a DNS QUERY, or null for anything else.
     *
     * Deliberately strict: a response, a multi-question message, a compression
     * pointer in a question (which is illegal) or a truncated buffer all return
     * null, and the datagram is then carried untouched. Nothing here is allowed
     * to guess.
     */
    private fun parseDnsQuestion(payload: ByteArray): DnsQuestion? {
        if (payload.size < 17) return null
        if (payload[2].toInt() and 0x80 != 0) return null // a response, not a query
        val questions = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        if (questions != 1) return null
        var at = 12
        var labels = 0
        while (at < payload.size && labels++ < 128) {
            val length = payload[at].toInt() and 0xFF
            if (length == 0) {
                at += 1
                if (at + 4 > payload.size) return null
                val type = ((payload[at].toInt() and 0xFF) shl 8) or (payload[at + 1].toInt() and 0xFF)
                return DnsQuestion(type, at + 4)
            }
            if (length and 0xC0 != 0) return null // pointer / reserved
            at += length + 1
        }
        return null
    }

    /**
     * An empty NOERROR answer to [query] when, and only when, it is an AAAA
     * query. Null for anything else, so the caller carries the datagram normally.
     *
     * This is the standard "NODATA" shape: the question is echoed, `QR` and `RA`
     * are set, `RCODE` is 0 and every count except QDCOUNT is zero. Every resolver
     * on Android reads that as "this name has no IPv6 address" and asks for the A
     * record instead - which is exactly the outcome wanted.
     */
    private fun nodataAnswerForAaaa(query: ByteArray): ByteArray? {
        val question = parseDnsQuestion(query) ?: return null
        if (question.type != DNS_TYPE_AAAA) return null
        if (question.end > query.size) return null
        val reply = query.copyOfRange(0, question.end)
        // QR=1, OPCODE=0, AA=0, TC=0, RD copied from the query.
        reply[2] = ((query[2].toInt() and 0x01) or 0x80).toByte()
        // RA=1, Z=0, RCODE=0 (NOERROR).
        reply[3] = 0x80.toByte()
        reply[6] = 0; reply[7] = 0 // ANCOUNT
        reply[8] = 0; reply[9] = 0 // NSCOUNT
        reply[10] = 0; reply[11] = 0 // ARCOUNT
        return reply
    }

    // --------------------------------------------------------- DNS over TCP

    /**
     * Answers one DNS query over TCP through Psiphon (RFC 1035 §4.2.2 framing: a
     * two-byte big-endian length in front of the message).
     *
     * Used only when udpgw is unavailable. TCP is the one thing Psiphon always
     * carries, so this is what makes "Psiphon connects but no site opens"
     * impossible rather than merely unlikely.
     */
    private fun answerDnsOverTcp(
        relay: DatagramSocket,
        client: InetSocketAddress,
        request: UdpRequest,
    ): Boolean {
        val host = request.address?.let { raw ->
            // The trailing two bytes are the port; only the address is wanted.
            val addr = raw.copyOfRange(0, raw.size - 2)
            runCatching { InetAddress.getByAddress(addr).hostAddress }.getOrNull()
        } ?: return false
        // The refusal code is captured NOW so a server that does not allow TCP/53
        // out is recognised on its FIRST refused lookup and every later query goes
        // straight to DoH. Without this the fallback re-earned one refused port
        // forward per lookup - 110 of them in 73 s in the field log - and fed the
        // watchdog fake evidence that the server was censoring.
        val refusal = IntArray(1) { -1 }
        val stream = openPsiphonStream(host, 53, refusal) ?: run {
            if (refusal[0] > 0) reportRefusal(host, 53)
            return false
        }
        val answer = try {
            stream.socket.soTimeout = DNS_TIMEOUT_MS
            stream.output.write((request.payload.size shr 8) and 0xFF)
            stream.output.write(request.payload.size and 0xFF)
            stream.output.write(request.payload)
            stream.output.flush()
            val hi = stream.input.read()
            val lo = stream.input.read()
            if (hi < 0 || lo < 0) return false
            val length = (hi shl 8) or lo
            if (length <= 0 || length > DNS_BUFFER) return false
            ByteArray(length).also { stream.input.readFully(it) }
        } catch (e: Exception) {
            // A failure here is one unanswered query: the caller falls through to
            // DNS-over-HTTPS, which is what makes this mode's name resolution
            // depend on nothing the server can refuse.
            return false
        } finally {
            closeQuietly(stream.socket)
        }

        learnDns(answer)
        val reply = ByteArray(request.header.size + answer.size)
        System.arraycopy(request.header, 0, reply, 0, request.header.size)
        System.arraycopy(answer, 0, reply, request.header.size, answer.size)
        rxBytes.addAndGet(answer.size.toLong())
        return try {
            relay.send(DatagramPacket(reply, reply.size, client.address, client.port))
            true
        } catch (e: Exception) {
            // Association torn down between the query and the answer. The answer
            // itself was fine, so this counts as handled.
            true
        }
    }

    /**
     * Answers one DNS query through the tunnel, over whichever transport this
     * server actually permits.
     *
     * TCP/53 first, because it is one round trip and no TLS. The moment a server
     * refuses it ([dnsPort53Refused], latched by [reportRefusal]) every later
     * lookup goes straight to DNS-over-HTTPS instead - which is both the fix for
     * "no site opens on this server" and the fix for the refusal storm that made
     * the watchdog convict it.
     */
    private fun answerDnsQuery(
        relay: DatagramSocket,
        client: InetSocketAddress,
        request: UdpRequest,
    ) {
        if (!dnsPort53Refused.get()) {
            if (answerDnsOverTcp(relay, client, request)) return
            // A failed :53 attempt is not proof the port is blocked (a resolver
            // can simply be slow), so nothing is latched here - only an explicit
            // SOCKS refusal, seen in [reportRefusal], flips the switch.
        }
        answerDnsOverHttps(relay, client, request)
    }

    /**
     * SOCKS5 CONNECT through Psiphon on a RAW socket, with nothing buffered.
     *
     * [openPsiphonStream] hands back a BufferedInputStream, and wrapping a
     * buffered stream in TLS loses whatever the buffer already swallowed. TLS
     * needs the socket itself, so the handshake here is read byte-exact off the
     * socket's own stream and the socket is returned untouched.
     */
    private fun openPsiphonRawStream(host: String, port: Int): Socket? {
        val upstream = try {
            Socket().apply {
                tcpNoDelay = true
                soTimeout = DNS_TIMEOUT_MS
                connect(
                    InetSocketAddress("127.0.0.1", psiphonSocksPort),
                    PSIPHON_CONNECT_TIMEOUT_MS,
                )
            }
        } catch (e: Exception) {
            return null
        }
        try {
            val out = upstream.getOutputStream()
            val input = upstream.getInputStream()

            fun readExactly(count: Int): ByteArray {
                val buffer = ByteArray(count)
                var read = 0
                while (read < count) {
                    val n = input.read(buffer, read, count - read)
                    if (n < 0) throw java.io.EOFException("Psiphon closed the SOCKS handshake")
                    read += n
                }
                return buffer
            }

            out.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0x00))
            out.flush()
            val greeting = readExactly(2)
            if (greeting[0].toInt() != SOCKS_VERSION || greeting[1].toInt() != 0x00) {
                closeQuietly(upstream)
                return null
            }

            val name = host.toByteArray(Charsets.US_ASCII)
            if (name.size > 255) {
                closeQuietly(upstream)
                return null
            }
            val request = ByteArray(5 + name.size + 2)
            request[0] = SOCKS_VERSION.toByte()
            request[1] = CMD_CONNECT.toByte()
            request[2] = 0
            request[3] = ATYP_DOMAIN.toByte()
            request[4] = name.size.toByte()
            System.arraycopy(name, 0, request, 5, name.size)
            request[5 + name.size] = ((port shr 8) and 0xFF).toByte()
            request[6 + name.size] = (port and 0xFF).toByte()
            out.write(request)
            out.flush()

            val head = readExactly(4)
            if (head[0].toInt() != SOCKS_VERSION) {
                closeQuietly(upstream)
                return null
            }
            val reply = head[1].toInt() and 0xFF
            when (head[3].toInt() and 0xFF) {
                ATYP_IPV4 -> readExactly(4)
                ATYP_IPV6 -> readExactly(16)
                ATYP_DOMAIN -> readExactly((readExactly(1)[0].toInt() and 0xFF))
            }
            readExactly(2) // bound port
            if (reply != REP_SUCCESS) {
                if (reply > 0) reportRefusal(host, port)
                closeQuietly(upstream)
                return null
            }
            return upstream
        } catch (e: Exception) {
            closeQuietly(upstream)
            return null
        }
    }

    /**
     * A warm DoH connection through Psiphon.
     *
     * The socket, its TLS layer and both streams travel together and are never
     * re-derived: a stream taken again after a buffered read resumes past
     * whatever the buffer already swallowed.
     */
    private class DohConn(
        val host: String,
        val socket: Socket,
        val tls: SSLSocket,
        val input: InputStream,
        val output: OutputStream,
    ) {
        @Volatile var idleSince: Long = 0
    }

    private val dohLock = Any()

    // ------------------------------------------------ uplink SQM (1.4.0-r3)

    /**
     * Paces every byte this front hands to Psiphon at the path's real
     * bottleneck rate, with sparse flows first. See [UplinkGovernor] for the
     * loge2 / loge3 root cause (home Wi-Fi + Gemini Live).
     */
    private val governor = UplinkGovernor(
        probe = { probeRoundTripMs() },
        log = { ConnectionLog.record("$TAG $it") },
    )

    /** One shaper flow for all udpgw bulk (QUIC / RTP) frames. */
    private val udpgwBulkFlow = UplinkShaper.Flow()
    private val udpgwPriorityFlow = UplinkShaper.Flow()

    /** The governor's own warm connection; never shared with the DNS pool. */
    private val probeLock = Any()
    private var probeConn: DohConn? = null

    private fun closeProbeConn() {
        val doomed = synchronized(probeLock) { probeConn.also { probeConn = null } }
        doomed?.let { closeDohConn(it) }
    }

    /**
     * One round trip through the FULL chain (front -> Psiphon SSH -> stage 1 ->
     * exit -> resolver), in ms. A cached-name DoH exchange on a warm, dedicated
     * connection: ~250 bytes each way, answered from the resolver's cache, so it
     * measures path + queue and nothing else. Connection setup is never timed.
     */
    private fun probeRoundTripMs(): Long? {
        if (!running.get()) return null
        val conn = synchronized(probeLock) { probeConn } ?: (openDohConn() ?: return null).also { fresh ->
            synchronized(probeLock) { probeConn = fresh }
        }
        val query = PROBE_QUERY.copyOf()
        val id = (System.nanoTime() and 0xFFFF).toInt()
        query[0] = (id shr 8).toByte()
        query[1] = id.toByte()
        val t0 = System.nanoTime()
        val answer = try {
            dohExchange(conn, query)
        } catch (e: Exception) {
            null
        }
        val elapsed = (System.nanoTime() - t0) / 1_000_000L
        if (answer == null || answer.size < 2 || answer[0] != query[0] || answer[1] != query[1]) {
            closeProbeConn()
            return null
        }
        return elapsed
    }

    /** DNS query header + "cloudflare.com" IN A; the id is rewritten per probe. */
    private val PROBE_QUERY: ByteArray = run {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in listOf("cloudflare", "com")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(byteArrayOf(0, 0, 1, 0, 1))
        out.toByteArray()
    }
    private val dohIdle = ArrayDeque<DohConn>()

    /** Most-recently-used first, so a warm connection is preferred over a cold one. */
    private fun takeDohConn(): DohConn? {
        val now = nowMs()
        while (true) {
            val conn = synchronized(dohLock) { dohIdle.removeLastOrNull() } ?: return null
            if (conn.socket.isClosed || now - conn.idleSince > DOH_IDLE_MS) {
                closeDohConn(conn)
                continue
            }
            return conn
        }
    }

    private fun releaseDohConn(conn: DohConn) {
        if (!running.get() || conn.socket.isClosed) {
            closeDohConn(conn)
            return
        }
        conn.idleSince = nowMs()
        val evicted = synchronized(dohLock) {
            dohIdle.addLast(conn)
            if (dohIdle.size > DOH_POOL_MAX) dohIdle.removeFirstOrNull() else null
        }
        evicted?.let { closeDohConn(it) }
    }

    private fun closeDohConn(conn: DohConn) {
        closeQuietly(conn.tls)
        closeQuietly(conn.socket)
    }

    /** Drops every pooled connection. A rotation or a teardown invalidates all of them. */
    private fun drainDohPool() {
        val doomed = synchronized(dohLock) {
            val all = dohIdle.toList()
            dohIdle.clear()
            all
        }
        doomed.forEach { closeDohConn(it) }
    }

    /**
     * Opens one DoH connection through Psiphon, trying each resolver in turn.
     *
     * TLS is verified properly: the system trust store plus an explicit hostname
     * check. `startHandshake()` validates the chain but does NOT check that the
     * certificate belongs to the host, so without the second check anyone holding
     * a certificate for any domain could answer the device's DNS - and DNS answers
     * steer every connection that follows.
     *
     * The resolver host is sent to Psiphon as a HOSTNAME so it is resolved INSIDE
     * the tunnel; resolving it here would leak the name to the carrier's resolver
     * and defeat the point of the hop.
     */
    private fun openDohConn(): DohConn? {
        for (host in DOH_HOSTS) {
            if (!running.get()) return null
            val raw = openPsiphonRawStream(host, DOH_PORT) ?: continue
            try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = factory.createSocket(raw, host, DOH_PORT, true) as SSLSocket
                tls.soTimeout = DNS_TIMEOUT_MS
                tls.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                    throw SSLPeerUnverifiedException("$host certificate mismatch (possible MitM)")
                }
                return DohConn(host, raw, tls, tls.inputStream, tls.outputStream)
            } catch (e: Exception) {
                closeQuietly(raw)
            }
        }
        return null
    }

    /**
     * One RFC 8484 exchange on [conn]. Throws on anything that makes the
     * connection unsafe to reuse, so the caller can close it and try a fresh one.
     *
     * The query and the answer are the SAME wire-format DNS message the device
     * sent and expects, so nothing has to be re-encoded and the transaction id
     * matches by construction.
     */
    private fun dohExchange(conn: DohConn, query: ByteArray): ByteArray? {
        val head = (
            "POST $DOH_PATH HTTP/1.1\r\n" +
                "Host: ${conn.host}\r\n" +
                "Accept: application/dns-message\r\n" +
                "Content-Type: application/dns-message\r\n" +
                "Content-Length: ${query.size}\r\n" +
                "Connection: keep-alive\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        conn.tls.soTimeout = DNS_TIMEOUT_MS
        conn.output.write(head)
        conn.output.write(query)
        conn.output.flush()
        return readDohBody(conn.input)
    }

    /**
     * Answers one DNS query over DNS-over-HTTPS (RFC 8484) through Psiphon.
     *
     * ## ROOT CAUSE this revision fixes
     *
     * This used to open a brand-new port forward AND a brand-new TLS handshake
     * for every single name, then close it (`Connection: close`). Through a
     * chained session that is seconds per lookup with only [DNS_POOL_SIZE] in
     * flight, so on any server that refuses udpgw the device effectively had no
     * working resolver: browsers opened nothing while Telegram - hard-coded IPs,
     * no DNS - kept working. Connections are pooled and reused now, so a whole
     * page load costs one round trip per name on an already-warm connection.
     */
    private fun answerDnsOverHttps(
        relay: DatagramSocket,
        client: InetSocketAddress,
        request: UdpRequest,
    ) {
        var conn = takeDohConn()
        var answer: ByteArray? = null
        if (conn != null) {
            answer = try {
                dohExchange(conn, request.payload)
            } catch (e: Exception) {
                null
            }
            if (answer == null || answer.isEmpty()) {
                // A pooled connection the resolver has since closed is the normal
                // case here, not an error. Retry once on a fresh one.
                closeDohConn(conn)
                conn = null
                answer = null
            }
        }
        if (answer == null) {
            val fresh = openDohConn() ?: return
            conn = fresh
            answer = try {
                dohExchange(fresh, request.payload)
            } catch (e: Exception) {
                // One unanswered query. Every resolver on earth drops a packet now
                // and then and every client retries, so this is a stall at worst -
                // never a failure mode of the mode itself.
                null
            }
        }
        val live = conn ?: return
        if (answer == null || answer.isEmpty()) {
            closeDohConn(live)
            return
        }
        releaseDohConn(live)
        sendUdpReply(relay, client, request, answer)
    }

    /**
     * Reads one HTTP/1.1 response and returns its body.
     *
     * `Content-Length` is REQUIRED, unlike the `Connection: close` version this
     * replaces: on a reused connection a close-delimited body is indistinguishable
     * from a response that has not finished arriving, and waiting for a close that
     * never comes would hang the lookup. A resolver that answered with chunked
     * encoding or no length is treated as unusable and its connection is dropped -
     * neither Cloudflare nor Google ever does for a DoH POST.
     */
    private fun readDohBody(input: InputStream): ByteArray? {
        val header = StringBuilder()
        var consecutive = 0
        while (consecutive < 2 && header.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            val c = b.toChar()
            if (c == '\n') consecutive++ else if (c != '\r') consecutive = 0
            header.append(c)
        }
        val head = header.toString()
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.contains(" 200")) return null
        if (head.contains("Transfer-Encoding: chunked", ignoreCase = true)) return null
        val declared = Regex("(?i)Content-Length:\\s*(\\d+)").find(head)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (declared <= 0 || declared > DNS_BUFFER) return null
        val body = ByteArray(declared)
        var read = 0
        while (read < declared) {
            val n = input.read(body, read, declared - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
