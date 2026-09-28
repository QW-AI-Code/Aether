package studio.cluvex.aether.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs the ordered connectivity self-test against the local SOCKS5 proxy and
 * records every step in [DiagnosticsLog]. The order is deliberate so a reader
 * can pinpoint WHERE the pipeline breaks:
 *
 *   port      -> is the engine even listening?
 *   handshake -> does it speak SOCKS5?
 *   tcp       -> can it open an outbound TCP connection (to an IP, no DNS)?
 *   dns_http  -> can it resolve a domain AND fetch over HTTP end-to-end?
 *   udp_dns   -> can the DEVICE resolve, over SOCKS5 UDP ASSOCIATE?
 *
 * Example: port+handshake+tcp PASS but dns_http FAIL => the tunnel works but
 * remote resolution on the proxy's TCP path is broken — the usual reason a
 * WARP-style tunnel "connects but no site loads".
 *
 * And the case that made the fifth step necessary: port+handshake+tcp+dns_http
 * ALL PASS while udp_dns FAILS => the app can resolve for itself (it asks the
 * proxy to resolve a hostname on a CONNECT) but nothing else on the phone can,
 * because hev-socks5-tunnel carries device DNS over UDP ASSOCIATE. That is
 * exactly how `Aether -> Psiphon` shipped: four green circles, a real exit IP,
 * and not one page that loads.
 *
 * SPEED (1.2.1 root-cause rework): this self-test is now the GATE for the
 * Connected state, so every second it wastes is a second the user stares at
 * "connecting". Three structural fixes cut the readiness time dramatically:
 *
 *   1. The TCP and DNS+HTTP checks run CONCURRENTLY. They are independent
 *      probes of the same proxy; running them back-to-back doubled the
 *      cold-start wait for no benefit.
 *   2. Retries fire every 750 ms instead of every 3 s. The engine's inner
 *      tunnel becomes ready at an unpredictable instant inside the warm-up
 *      window; a 3 s poll added up to ~3 s of pure detection latency (per
 *      check!) after the tunnel was already usable.
 *   3. The DNS+HTTP probe races ALL geolocation providers in parallel
 *      ([NetProbe.fetchIpInfoViaSocksRaced]) instead of trying them one by
 *      one. On Iranian networks individual providers are often filtered or
 *      slow in ways that differ per operator/region (DPI variance), so the
 *      serial fallback chain could burn 20-30 s of timeouts before reaching
 *      the provider that actually answers. The race always finishes as fast
 *      as the FASTEST provider for that user's network.
 */
object Diagnostics {
    const val C_PORT = "socks_port"
    const val C_HANDSHAKE = "socks_handshake"
    const val C_TCP = "tcp_via_proxy"
    const val C_DNS = "dns_http_via_tunnel"

    /**
     * DNS over SOCKS5 `UDP ASSOCIATE` — the path the DEVICE uses.
     *
     * Added in 1.2.7 after `Aether -> Psiphon` shipped connected and unable to
     * open a single site while all four existing checks stayed green. They stayed
     * green because [C_DNS] resolves through a `CONNECT` carrying a hostname,
     * which the proxy resolves remotely on the TCP path, whereas
     * hev-socks5-tunnel carries every real DNS query over `UDP ASSOCIATE` — the
     * command Psiphon's own SOCKS listener refuses. The one code path the whole
     * device depends on was the one path nothing tested.
     */
    const val C_UDP_DNS = "udp_dns_via_tunnel"

    private const val TAG = "diag"

    // How long we keep retrying the outbound checks after connect. Warp-in-warp
    // (GOOL) keeps building its INNER tunnel for a while after the SOCKS5 port
    // is already open; during that window every CONNECT is rejected with rep=1.
    // That is a COLD START, not a failure, so give the engine a grace window
    // instead of failing on the very first attempt.
    private const val OUTBOUND_GRACE_MS = 90_000L

    /**
     * Grace window for chained Psiphon sessions.
     *
     * Longer than the Aether one on purpose. Psiphon's own handshake and server
     * selection take seconds and its RTT is several times an Aether edge's, and a
     * chained `Aether -> Psiphon` session pays both hops' warm-up. The 90 s
     * Aether window was failing sessions that were merely slow, which is the
     * worst possible outcome: everything works, and the app throws it away.
     */
    const val EXTERNAL_GRACE_MS = 150_000L

    private const val OUTBOUND_RETRY_DELAY_MS = 750L
    private const val TCP_PROBE_TIMEOUT_MS = 4_000

    /** Gap between two SOCKS5 handshake attempts while a stage is still coming up. */
    private const val HANDSHAKE_RETRY_DELAY_MS = 1_500L
    private const val GEO_PROBE_TIMEOUT_MS = 6_000

    /**
     * Budget for one `UDP ASSOCIATE` DNS round trip.
     *
     * Generous on purpose: through a chained session the query crosses Aether,
     * then Psiphon, then reaches a resolver, and a first lookup on a cold tunnel
     * can take seconds. A tight timeout here would fail sessions that merely need
     * a moment, which is a mistake this project has already made once.
     */
    private const val UDP_DNS_TIMEOUT_MS = 8_000

    fun resetChecks(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = PortLease.socks,
    ) {
        DiagnosticsLog.setChecks(
            listOf(
                ComponentCheck(C_PORT, "SOCKS5 port $host:$port"),
                ComponentCheck(C_HANDSHAKE, "SOCKS5 handshake"),
                ComponentCheck(C_TCP, "TCP via proxy (1.1.1.1:80)"),
                ComponentCheck(C_DNS, "DNS + HTTP via tunnel"),
                ComponentCheck(C_UDP_DNS, "Device DNS (SOCKS5 UDP)"),
            )
        )
    }

    /** Runs all checks (steps 3+4 concurrently). Safe to call from any coroutine. */
    suspend fun run(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = PortLease.socks,
        graceMs: Long = OUTBOUND_GRACE_MS,
    ): Boolean = withContext(Dispatchers.IO) {
        resetChecks(host, port)
        DiagnosticsLog.i(TAG, "Starting connectivity self-test…")

        // 1. Port open
        DiagnosticsLog.updateCheck(C_PORT, CheckState.RUNNING)
        val portOpen = PortProbe.isOpen(host, port, 1500)
        DiagnosticsLog.updateCheck(
            C_PORT,
            if (portOpen) CheckState.PASS else CheckState.FAIL,
            if (portOpen) "listening" else "no listener",
        )
        DiagnosticsLog.log(TAG, if (portOpen) LogLevel.INFO else LogLevel.ERROR, "port open = $portOpen")
        if (!portOpen) {
            failRemaining(C_HANDSHAKE, C_TCP, C_DNS, C_UDP_DNS)
            return@withContext false
        }

        // 2. SOCKS5 handshake
        DiagnosticsLog.updateCheck(C_HANDSHAKE, CheckState.RUNNING)
        val handshake = NetProbe.checkSocksHandshake(host, port)
        DiagnosticsLog.updateCheck(C_HANDSHAKE, if (handshake) CheckState.PASS else CheckState.FAIL)
        DiagnosticsLog.log(TAG, if (handshake) LogLevel.INFO else LogLevel.ERROR, "socks5 handshake = $handshake")
        if (!handshake) {
            failRemaining(C_TCP, C_DNS, C_UDP_DNS)
            return@withContext false
        }

        // 3 + 4. TCP-via-proxy and DNS+HTTP end-to-end — CONCURRENT, each with
        // its own fast retry loop over the shared cold-start grace window.
        val deadline = System.currentTimeMillis() + graceMs
        val (tcp, info, udpDns) = coroutineScope {
            val tcpJob = async {
                DiagnosticsLog.updateCheck(C_TCP, CheckState.RUNNING)
                var ok = NetProbe.checkTcpViaProxy(host, port, "1.1.1.1", 80, TCP_PROBE_TIMEOUT_MS)
                while (!ok && System.currentTimeMillis() < deadline) {
                    delay(OUTBOUND_RETRY_DELAY_MS)
                    ok = NetProbe.checkTcpViaProxy(host, port, "1.1.1.1", 80, TCP_PROBE_TIMEOUT_MS)
                }
                DiagnosticsLog.updateCheck(C_TCP, if (ok) CheckState.PASS else CheckState.FAIL)
                DiagnosticsLog.log(TAG, if (ok) LogLevel.INFO else LogLevel.ERROR, "tcp via proxy = $ok")
                ok
            }
            val dnsJob = async {
                DiagnosticsLog.updateCheck(C_DNS, CheckState.RUNNING)
                var result = NetProbe.fetchIpInfoViaSocksRaced(host, port, GEO_PROBE_TIMEOUT_MS)
                while (result == null && System.currentTimeMillis() < deadline) {
                    delay(OUTBOUND_RETRY_DELAY_MS)
                    result = NetProbe.fetchIpInfoViaSocksRaced(host, port, GEO_PROBE_TIMEOUT_MS)
                }
                result
            }
            val udpJob = async {
                DiagnosticsLog.updateCheck(C_UDP_DNS, CheckState.RUNNING)
                var ok = NetProbe.checkDnsViaSocksUdp(host, port, timeoutMs = UDP_DNS_TIMEOUT_MS)
                while (!ok && System.currentTimeMillis() < deadline) {
                    delay(OUTBOUND_RETRY_DELAY_MS)
                    ok = NetProbe.checkDnsViaSocksUdp(host, port, timeoutMs = UDP_DNS_TIMEOUT_MS)
                }
                DiagnosticsLog.updateCheck(
                    C_UDP_DNS,
                    if (ok) CheckState.PASS else CheckState.FAIL,
                    if (ok) "resolver answered" else "no answer",
                )
                DiagnosticsLog.log(
                    TAG,
                    if (ok) LogLevel.INFO else LogLevel.ERROR,
                    "device dns via socks5 udp = $ok",
                )
                ok
            }
            Triple(tcpJob.await(), dnsJob.await(), udpJob.await())
        }

        val dnsOk = info != null
        DiagnosticsLog.updateCheck(
            C_DNS,
            if (dnsOk) CheckState.PASS else CheckState.FAIL,
            if (dnsOk) "exit ${info!!.ip} ${info.countryCode ?: "?"}" else "no response",
        )
        DiagnosticsLog.log(
            TAG,
            if (dnsOk) LogLevel.INFO else LogLevel.ERROR,
            if (dnsOk) "dns+http OK, exit ip=${info!!.ip} cc=${info.countryCode}" else "dns+http FAILED",
        )

        // The self-test already discovered the real exit IP through the tunnel.
        // Feed it straight into the badge so the UI never has to race a second,
        // independent lookup right after connect — the IP + flag is visible the
        // INSTANT the app reports Connected.
        if (dnsOk) {
            AetherController.offerTunnelIpInfo(IpEndpoint(info!!.ip, info.countryCode, true))
            AetherController.setIpLoading(false)
        }

        if (!dnsOk) {
            DiagnosticsLog.w(
                TAG,
                if (tcp) "TCP works but DNS/HTTP fails → likely broken remote DNS (SOCKS5 UDP ASSOCIATE)."
                else "Proxy cannot open outbound connections → engine has no upstream route.",
            )
        }
        if (dnsOk && !udpDns) {
            // The exact shape of the shipped Aether -> Psiphon bug: everything
            // green, nothing opens. Say so in the words a user would search for.
            DiagnosticsLog.e(
                TAG,
                "The tunnel carries TCP and resolves names on the proxy's own TCP path, but the " +
                    "device's own DNS (SOCKS5 UDP ASSOCIATE) got no answer. Apps would connect to " +
                    "nothing: this is the \"connected but no site opens\" failure, so the session is " +
                    "not being reported as Connected.",
            )
        }

        // Both have to pass. A tunnel that resolves only for the app's own
        // probe is not a tunnel the phone can browse through, and reporting it
        // Connected is what made this bug survive a whole release.
        dnsOk && udpDns
    }

    /**
     * Stage-1 gate for the CHAINED backend (`Aether -> Psiphon`).
     *
     * Deliberately NOT [run]. Stage 1 is only ever an intermediate hop: the
     * exit, the DNS and therefore the flag all belong to stage 2, so demanding a
     * geolocation round trip here would both add 5-30 s to every chained connect
     * and paint the FIRST hop's country into the UI badge. All this has to prove
     * is that the engine can open outbound TCP for Psiphon to ride on.
     *
     * It also leaves the four self-test circles alone: they describe the finished
     * pipeline, and stage 1 flipping them green and then back to running looked
     * exactly like the stale-circles bug this project already fixed once.
     */
    suspend fun runProxyStage(
        host: String = TunnelConfig.SOCKS_HOST,
        port: Int = PortLease.socks,
        graceMs: Long = OUTBOUND_GRACE_MS,
        handshakeGraceMs: Long = 0L,
        alive: () -> Boolean = { true },
        abort: () -> Boolean = { false },
    ): Boolean = withContext(Dispatchers.IO) {
        DiagnosticsLog.i(TAG, "Stage 1 check: is $host:$port a working SOCKS5 proxy?")
        if (!PortProbe.isOpen(host, port, 1500)) {
            DiagnosticsLog.e(TAG, "stage 1: nothing listening on $host:$port")
            return@withContext false
        }
        if (!awaitSocksHandshake(host, port, handshakeGraceMs, alive, abort)) {
            DiagnosticsLog.e(TAG, "stage 1: $host:$port does not speak SOCKS5")
            return@withContext false
        }
        val deadline = System.currentTimeMillis() + graceMs
        var ok = NetProbe.checkTcpViaProxy(host, port, "1.1.1.1", 80, TCP_PROBE_TIMEOUT_MS)
        while (!ok && System.currentTimeMillis() < deadline) {
            delay(OUTBOUND_RETRY_DELAY_MS)
            ok = NetProbe.checkTcpViaProxy(host, port, "1.1.1.1", 80, TCP_PROBE_TIMEOUT_MS)
        }
        DiagnosticsLog.log(
            TAG,
            if (ok) LogLevel.INFO else LogLevel.ERROR,
            "stage 1: outbound TCP via $host:$port = $ok",
        )
        ok
    }

    private fun failRemaining(vararg ids: String) {
        ids.forEach { DiagnosticsLog.updateCheck(it, CheckState.FAIL, "skipped") }
    }

    /**
     * SOCKS5 handshake with a WAITING period instead of a single attempt.
     *
     * 1.3.0 FIELD FIX (the "Tor does not connect on my network" report). A
     * tor-fronted stage 1 binds its port at launch but cannot answer a handshake
     * until Tor has reached the network -- a first bootstrap that downloads the
     * directory takes tens of seconds on a good network. The old single 4-second
     * attempt therefore failed *every* such connect at +4 s, with the engine's own
     * log showing the bootstrap at 30 % and climbing, and the app then reported the
     * generic "self-test failed" for a Tor that was simply not finished yet.
     *
     * [handshakeGraceMs] `<= 0` keeps the old single-shot behaviour, which is the
     * right one for a stage that is up the moment its port is: an Aether tunnel
     * that cannot handshake immediately is broken, and waiting on it would only
     * delay the ladder's next rung.
     *
     * [alive] lets a dead engine end the wait instantly, and [abort] is how the
     * caller stops waiting on a bootstrap that has stopped moving -- without it,
     * a network that silently drops Tor would hold the UI at "Connecting" for the
     * whole budget.
     */
    private suspend fun awaitSocksHandshake(
        host: String,
        port: Int,
        handshakeGraceMs: Long,
        alive: () -> Boolean,
        abort: () -> Boolean,
    ): Boolean {
        if (NetProbe.checkSocksHandshake(host, port)) return true
        if (handshakeGraceMs <= 0L) return false
        DiagnosticsLog.i(
            TAG,
            "stage 1: $host:$port is listening but not answering yet - waiting up to " +
                "${handshakeGraceMs / 1000}s for it (${TorBootstrap.describe()}).",
        )
        val deadline = System.currentTimeMillis() + handshakeGraceMs
        while (System.currentTimeMillis() < deadline) {
            if (!alive()) {
                DiagnosticsLog.e(TAG, "stage 1: the engine exited while its proxy was still coming up.")
                return false
            }
            if (abort()) {
                DiagnosticsLog.e(TAG, "stage 1: giving up early - ${TorBootstrap.describe()}.")
                return false
            }
            delay(HANDSHAKE_RETRY_DELAY_MS)
            if (NetProbe.checkSocksHandshake(host, port)) {
                DiagnosticsLog.i(TAG, "stage 1: proxy answered (${TorBootstrap.describe()}).")
                return true
            }
        }
        DiagnosticsLog.e(
            TAG,
            "stage 1: $host:$port never answered within ${handshakeGraceMs / 1000}s " +
                "(${TorBootstrap.describe()}).",
        )
        return false
    }
}
