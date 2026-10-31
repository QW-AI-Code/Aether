package studio.cluvex.aether.transport

import studio.cluvex.aether.core.DiagnosticsLog
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The SOCKS5 server tun2socks talks to whenever the device's traffic leaves
 * through Tor (`Tor` and `Aether -> Tor`).
 *
 * ## Why this class has to exist, and why 1.2.7 removed Tor without it
 *
 * Tor carries **TCP only**. There is no UDP in Tor, in any implementation,
 * including arti - it is a property of the protocol, not of the software. The
 * forwarder this app ships is **hev-socks5-tunnel**, configured with
 * `udp: 'udp'`, so it hands every UDP flow the device produces to the SOCKS5
 * proxy as `UDP ASSOCIATE` (command `0x03`). A Tor SOCKS proxy answers
 * `CONNECT` (`0x01`) and refuses that command.
 *
 * Every DNS query on Android is UDP. So pointing tun2socks straight at the
 * engine's Tor listener produces the exact failure this project has already
 * shipped once and diagnosed at length for Psiphon (see [PsiphonSocksFront]):
 * a tunnel that establishes, reports a healthy Tor exit, passes the self-test -
 * because the self-test resolves through a `CONNECT` with a HOSTNAME - and opens
 * not one page, because name resolution never had a path at all.
 *
 * ## What this does instead
 *
 * ```
 *   hev ──CONNECT <host|ip:port>──► relayed to Tor's SOCKS5, hostname INTACT
 *       └─UDP ASSOCIATE──► answered HERE:
 *             a DNS query  -> resolved over TCP *inside Tor* and answered
 *             anything else-> dropped, counted, reported once
 * ```
 *
 * Three details matter more than the plumbing:
 *
 * 1. **A hostname is never resolved locally.** `ATYP=DOMAIN` is passed to Tor
 *    verbatim so the name is resolved at the exit. That is what keeps DNS off
 *    the local network entirely and what makes `.onion` addresses work at all -
 *    resolving them here would be impossible and leaking them to the operator's
 *    resolver would be worse.
 * 2. **DNS is answered over TCP through Tor**, by opening a `CONNECT` to a
 *    resolver's TCP/53 through Tor and framing the query with the standard
 *    two-byte length prefix (RFC 1035 §4.2.2). The answer comes back through the
 *    same circuit as everything else, so the resolver a censor can see is Tor's
 *    exit, not the phone.
 * 3. **Non-DNS UDP is dropped, loudly.** QUIC (UDP/443) is the common case, and
 *    a browser that gets no answer on QUIC falls back to TCP within a second, so
 *    dropping is the correct behaviour rather than a lie. It is counted and
 *    surfaced in [dropSummary] so a slow-video report can be explained instead
 *    of guessed at.
 *
 * Nothing here talks to the network directly. Every byte goes through the SOCKS5
 * proxy given to [start], which is the engine's Tor listener - port 1819 in
 * `--tor-only` mode, 1820 when Tor rides inside the tunnel.
 */
object TorSocksFront {

    private const val TAG = "TorSocksFront"

    private const val SOCKS_VERSION = 5
    private const val CMD_CONNECT = 1
    private const val CMD_UDP_ASSOCIATE = 3
    private const val ATYP_IPV4 = 1
    private const val ATYP_DOMAIN = 3
    private const val ATYP_IPV6 = 4

    private const val REP_SUCCESS = 0
    private const val REP_GENERAL_FAILURE = 1
    private const val REP_HOST_UNREACHABLE = 4
    private const val REP_CMD_NOT_SUPPORTED = 7

    private const val RELAY_BUFFER = 32 * 1024
    private const val UDP_BUFFER = 4096
    private const val DNS_PORT = 53

    /**
     * Resolvers used for DNS-over-TCP inside Tor.
     *
     * Addresses rather than names, because resolving the resolver is a chicken
     * and egg problem, and Tor reaches an address as happily as a name. Two
     * independent operators so one being unreachable from a given exit is not a
     * dead device.
     */
    private val DNS_OVER_TCP = listOf("1.1.1.1", "8.8.8.8")

    /** How long one DNS query may take end to end, including the Tor circuit. */
    private const val DNS_TIMEOUT_MS = 12_000

    /** How long a CONNECT through Tor may take before it is given up on. */
    private const val CONNECT_TIMEOUT_MS = 45_000

    /** How many times one CONNECT is offered to Tor before the caller is told it failed. */
    private const val CONNECT_ATTEMPTS = 3

    /** Backoff between those attempts; multiplied by the attempt number. */
    private const val CONNECT_RETRY_BACKOFF_MS = 150L

    /**
     * SOCKS5 replies that mean "this circuit did not work out", not "the
     * destination said no": general failure, network and host unreachable, and
     * TTL expired. Tor returns reply 1 for a circuit that failed to build.
     */
    private val RETRYABLE_REPLIES = setOf(REP_GENERAL_FAILURE, 3, REP_HOST_UNREACHABLE, 6)

    /**
     * How long a UDP association is kept after the last datagram.
     *
     * hev opens one association per flow and never closes it explicitly, so this
     * is what stops a long session accumulating threads and sockets.
     */
    private const val UDP_IDLE_MS = 60_000L

    private val running = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    private var pool: ExecutorService? = null

    /** Where the engine's Tor SOCKS5 listener is, as handed to [start]. */
    @Volatile
    private var torHost: String = "127.0.0.1"

    @Volatile
    private var torPort: Int = 0

    private val txBytes = AtomicLong(0)
    private val rxBytes = AtomicLong(0)
    private val dnsQueries = AtomicLong(0)
    private val dnsFailures = AtomicLong(0)
    private val udpDropped = AtomicLong(0)
    private val quicDropped = AtomicLong(0)

    /**
     * Datagrams that were not a valid SOCKS5 UDP request at all.
     *
     * Counted because a malformed-packet flood is otherwise indistinguishable from
     * silence: both look like a front that answers nothing.
     */
    private val malformed = AtomicLong(0)

    /** CONNECTs that only succeeded because they were retried on a fresh circuit. */
    private val connectRetries = AtomicLong(0)

    /** Live count of associations, so a leak is visible instead of theoretical. */
    private val associations = ConcurrentHashMap<Int, Long>()

    /**
     * Binds [frontPort] and relays into the Tor SOCKS5 proxy at [proxyPort].
     *
     * Returns the port actually bound. Synchronous: the caller (the VPN service)
     * must not build the TUN before this listener exists, or the first packets
     * off the interface hit a closed port.
     */
    fun start(frontPort: Int, proxyHost: String, proxyPort: Int): Int {
        if (running.get()) stop()

        torHost = proxyHost
        torPort = proxyPort
        txBytes.set(0)
        rxBytes.set(0)
        dnsQueries.set(0)
        dnsFailures.set(0)
        udpDropped.set(0)
        quicDropped.set(0)
        malformed.set(0)
        connectRetries.set(0)
        associations.clear()

        val server = ServerSocket()
        server.reuseAddress = true
        // Loopback only. This front speaks for Tor and has no authentication of
        // its own; a world-reachable copy would be an open proxy on the LAN.
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), frontPort))
        listener = server
        val executor = Executors.newCachedThreadPool { r ->
            Thread(r, "tor-front").apply { isDaemon = true }
        }
        pool = executor
        running.set(true)

        val bound = server.localPort
        DiagnosticsLog.i(
            TAG,
            "SOCKS5 front on 127.0.0.1:$bound -> Tor on $proxyHost:$proxyPort " +
                "(CONNECT relayed with the hostname intact, DNS answered over TCP inside Tor)",
        )

        executor.execute {
            while (running.get()) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                executor.execute { serve(client) }
            }
        }
        return bound
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { listener?.close() }
        listener = null
        pool?.shutdownNow()
        pool = null
        associations.clear()
        DiagnosticsLog.i(TAG, "SOCKS5 front stopped.")
    }

    fun isRunning(): Boolean = running.get()

    /** Bytes carried, for the traffic row. */
    fun tx(): Long = txBytes.get()

    fun rx(): Long = rxBytes.get()

    /**
     * One line about what this front had to refuse, or null when it refused
     * nothing.
     *
     * Shown in diagnostics rather than kept internal: "video is slow in Tor
     * mode" and "QUIC is being dropped because Tor has no UDP" are the same
     * sentence, and only one of them can be acted on.
     */
    fun dropSummary(): String? {
        val quic = quicDropped.get()
        val other = udpDropped.get()
        val failed = dnsFailures.get()
        val bad = malformed.get()
        val retried = connectRetries.get()
        if (quic == 0L && other == 0L && failed == 0L && bad == 0L && retried == 0L) return null
        return buildString {
            append("Tor front: ")
            append("${dnsQueries.get()} DNS queries over TCP")
            if (failed > 0) append(" ($failed failed)")
            if (quic > 0) append(", $quic QUIC datagrams dropped (Tor has no UDP; apps fall back to TCP)")
            if (other > 0) append(", $other other UDP datagrams dropped")
            if (bad > 0) append(", $bad malformed datagrams")
            if (retried > 0) append(", $retried stream(s) rebuilt on another circuit")
        }
    }

    // ------------------------------------------------------------------ SOCKS5

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = DataInputStream(client.getInputStream().buffered())
            val output = client.getOutputStream().buffered()

            // ---- greeting -------------------------------------------------
            if (input.read() != SOCKS_VERSION) return
            val methods = input.read()
            if (methods <= 0) return
            repeat(methods) { input.read() }
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), 0)) // no authentication
            output.flush()

            // ---- request --------------------------------------------------
            if (input.read() != SOCKS_VERSION) return
            val command = input.read()
            input.read() // reserved
            val target = readAddress(input) ?: run {
                reply(output, REP_GENERAL_FAILURE)
                return
            }

            when (command) {
                CMD_CONNECT -> serveConnect(client, input, output, target)
                CMD_UDP_ASSOCIATE -> serveUdpAssociate(client, output)
                else -> reply(output, REP_CMD_NOT_SUPPORTED)
            }
        } catch (_: Exception) {
            // A client that goes away mid-handshake is normal traffic, not an event.
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * A destination as the client asked for it: either a name (which must stay a
     * name all the way into Tor) or a literal address.
     */
    private data class Target(val host: String, val port: Int, val isName: Boolean)

    /**
     * Reads the ATYP + address + port of a SOCKS5 request, or null when the client
     * asked for something this front does not speak.
     *
     * A BLOCK body, deliberately: the early `return null` paths below are not legal
     * in an expression body (`= when (...)`), and rewriting them as nested
     * expressions would bury the one thing that matters here - that a malformed
     * address is refused rather than half-read, leaving the stream out of step with
     * the client for every byte that follows.
     *
     * The port is read AFTER the address in every branch, because the wire order is
     * ATYP, address, port and `input` is a stream: reading them out of order would
     * silently swap two bytes of a plausible-looking port.
     */
    private fun readAddress(input: DataInputStream): Target? {
        return when (input.read()) {
            ATYP_IPV4 -> {
                val raw = ByteArray(4).also { input.readFully(it) }
                val host = InetAddress.getByAddress(raw).hostAddress ?: return null
                Target(host, input.readUnsignedShort(), false)
            }
            ATYP_DOMAIN -> {
                val len = input.read()
                if (len <= 0) return null
                val raw = ByteArray(len).also { input.readFully(it) }
                Target(String(raw, Charsets.US_ASCII), input.readUnsignedShort(), true)
            }
            ATYP_IPV6 -> {
                val raw = ByteArray(16).also { input.readFully(it) }
                val host = InetAddress.getByAddress(raw).hostAddress ?: return null
                Target(host, input.readUnsignedShort(), false)
            }
            else -> null
        }
    }

    private fun serveConnect(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        target: Target,
    ) {
        val upstream = try {
            openThroughTorWithRetry(target)
        } catch (e: Exception) {
            DiagnosticsLog.d(TAG, "CONNECT ${label(target)} refused by Tor: ${e.message}")
            reply(output, REP_HOST_UNREACHABLE)
            return
        }

        reply(output, REP_SUCCESS)
        try {
            val up = upstream.getOutputStream()
            val down = upstream.getInputStream()
            val pump = Thread({
                copy(input, up, txBytes)
                runCatching { upstream.shutdownOutput() }
            }, "tor-front-tx").apply { isDaemon = true }
            pump.start()
            copy(down, output, rxBytes)
            runCatching { client.shutdownOutput() }
            pump.join(2_000)
        } catch (_: Exception) {
        } finally {
            runCatching { upstream.close() }
        }
    }

    /**
     * [openThroughTor], but a transient refusal is retried on a fresh circuit.
     *
     * ROOT CAUSE this answers (field log, `Tor -> Npvt`, and every other
     * `... -> Config` chain): Tor answers `SOCKS5 reply 1` whenever the circuit it
     * would have used failed to build or an exit refused the stream - a normal,
     * transient event on a loaded network. The front turned every one of those into
     * a hard `host unreachable` for the caller, so the LOG filled with dozens of
     * identical "refused by Tor (SOCKS5 reply 1)" lines while the config's core
     * rebuilt the same connection over and over, each attempt paying a full
     * handshake. That retry loop - not Tor itself - is what the user reads as
     * "it connects, but it is unusably slow".
     *
     * Retrying HERE is what makes it cheap: Tor picks another circuit for the new
     * stream, and the caller never sees the failure at all. Only the general
     * failure / TTL-expired replies are retried; a connection REFUSED by the
     * destination (reply 5) is the truth and is passed straight back.
     */
    private fun openThroughTorWithRetry(target: Target): Socket {
        var last: Exception? = null
        for (attempt in 1..CONNECT_ATTEMPTS) {
            try {
                val socket = openThroughTor(target)
                if (attempt > 1) connectRetries.incrementAndGet()
                return socket
            } catch (e: TorStreamRefused) {
                last = e
                if (!e.retryable || attempt == CONNECT_ATTEMPTS) throw e
                Thread.sleep(CONNECT_RETRY_BACKOFF_MS * attempt)
            } catch (e: Exception) {
                // The local hop to Tor itself failed (it is restarting, or gone).
                // One more try costs a millisecond; a third would just delay the
                // caller's own error handling.
                last = e
                if (attempt >= 2) throw e
                Thread.sleep(CONNECT_RETRY_BACKOFF_MS)
            }
        }
        throw last ?: IllegalStateException("Tor could not open the stream")
    }

    /** Tor refused one stream. [retryable] tells a transient circuit failure from a real refusal. */
    private class TorStreamRefused(message: String, val retryable: Boolean) : IllegalStateException(message)

    /**
     * Opens one stream through Tor, keeping a hostname a hostname.
     *
     * This is the whole reason the front does its own SOCKS5 client instead of
     * using [Socket] with a proxy: Java's SOCKS support resolves the name
     * locally first, which is exactly the leak this mode exists to prevent, and
     * it cannot express a `.onion` address at all.
     */
    private fun openThroughTor(target: Target): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(torHost, torPort), 10_000)
        socket.soTimeout = CONNECT_TIMEOUT_MS

        val out = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        out.write(byteArrayOf(SOCKS_VERSION.toByte(), 1, 0))
        out.flush()
        if (input.read() != SOCKS_VERSION || input.read() != 0) {
            socket.close()
            throw IllegalStateException("Tor's SOCKS5 listener refused the greeting")
        }

        val request = ArrayList<Byte>(262)
        request += SOCKS_VERSION.toByte()
        request += CMD_CONNECT.toByte()
        request += 0
        if (target.isName) {
            val name = target.host.toByteArray(Charsets.US_ASCII)
            request += ATYP_DOMAIN.toByte()
            request += name.size.toByte()
            name.forEach { request += it }
        } else {
            val address = InetAddress.getByName(target.host)
            val raw = address.address
            request += (if (raw.size == 16) ATYP_IPV6 else ATYP_IPV4).toByte()
            raw.forEach { request += it }
        }
        request += ((target.port shr 8) and 0xFF).toByte()
        request += (target.port and 0xFF).toByte()
        out.write(request.toByteArray())
        out.flush()

        if (input.read() != SOCKS_VERSION) {
            socket.close()
            throw IllegalStateException("Tor answered something that is not SOCKS5")
        }
        val status = input.read()
        input.read() // reserved
        // Consume the bound address Tor reports; its value is of no use here.
        when (input.read()) {
            ATYP_IPV4 -> input.readFully(ByteArray(4))
            ATYP_DOMAIN -> input.read().let { if (it > 0) input.readFully(ByteArray(it)) }
            ATYP_IPV6 -> input.readFully(ByteArray(16))
        }
        input.readUnsignedShort()
        if (status != REP_SUCCESS) {
            socket.close()
            throw TorStreamRefused(
                "Tor could not open the stream (SOCKS5 reply $status)",
                retryable = status in RETRYABLE_REPLIES,
            )
        }
        // Streams live as long as the flow does; the connect budget was for the
        // circuit, not for an idle browser tab.
        socket.soTimeout = 0
        return socket
    }

    // -------------------------------------------------------------------- UDP

    /**
     * Answers `UDP ASSOCIATE` locally and serves DNS out of Tor.
     *
     * The relay socket lives as long as the TCP control connection, which is how
     * SOCKS5 defines the lifetime of an association, so a client that goes away
     * releases it without a timer.
     */
    private fun serveUdpAssociate(control: Socket, output: OutputStream) {
        val relay = DatagramSocket(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        relay.soTimeout = 1_000
        associations[relay.localPort] = System.currentTimeMillis()

        // The address the client must send its datagrams to.
        val reply = ByteArray(10)
        reply[0] = SOCKS_VERSION.toByte()
        reply[1] = REP_SUCCESS.toByte()
        reply[3] = ATYP_IPV4.toByte()
        reply[4] = 127; reply[5] = 0; reply[6] = 0; reply[7] = 1
        reply[8] = ((relay.localPort shr 8) and 0xFF).toByte()
        reply[9] = (relay.localPort and 0xFF).toByte()
        output.write(reply)
        output.flush()

        val worker = Thread({ pumpUdp(relay) }, "tor-front-udp").apply { isDaemon = true }
        worker.start()
        try {
            // Block until the client closes the control connection: that, and
            // nothing else, ends the association.
            val probe = control.getInputStream()
            while (running.get() && probe.read() >= 0) { /* client keeps it open */ }
        } catch (_: Exception) {
        } finally {
            runCatching { relay.close() }
            associations.remove(relay.localPort)
        }
    }

    private fun pumpUdp(relay: DatagramSocket) {
        val buffer = ByteArray(UDP_BUFFER)
        var lastSeen = System.currentTimeMillis()
        while (running.get() && !relay.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                relay.receive(packet)
            } catch (_: Exception) {
                if (System.currentTimeMillis() - lastSeen > UDP_IDLE_MS) return
                continue
            }
            lastSeen = System.currentTimeMillis()

            // Parsed by TorSocksWire, which is a pure function with a test suite
            // behind it. It used to be inline here, where one byte of drift silently
            // turned a DNS query into a dropped QUIC attempt or the reverse - a
            // failure that presents as "connected, nothing loads" and points at
            // everything except this loop.
            val request = TorSocksWire.parseUdpRequest(packet.data, packet.length)
            if (request == null) {
                malformed.incrementAndGet()
                continue
            }

            if (!TorSocksWire.isDnsQuery(request)) {
                // Tor has no UDP. Dropping is honest and recoverable: a browser
                // whose QUIC attempt gets no answer retries over TCP.
                if (request.port == TorSocksWire.QUIC_PORT) {
                    quicDropped.incrementAndGet()
                } else {
                    udpDropped.incrementAndGet()
                }
                continue
            }

            val answer = resolveOverTor(request.payload)
            if (answer == null) {
                dnsFailures.incrementAndGet()
                continue
            }
            dnsQueries.incrementAndGet()

            // The client's own header back, byte for byte: hev matches the reply
            // against what it sent, so a rebuilt header can be discarded even when
            // every field in it is right.
            val out = TorSocksWire.buildUdpReply(packet.data, request.headerLength, answer)
            runCatching {
                relay.send(DatagramPacket(out, out.size, packet.address, packet.port))
            }
            rxBytes.addAndGet(answer.size.toLong())
            txBytes.addAndGet(request.payload.size.toLong())
            // request.host is deliberately unused: whatever resolver the device asked
            // for, the query is answered through Tor. Honouring it would mean
            // carrying UDP, which is the one thing Tor cannot do.
        }
    }

    /**
     * Sends one DNS query over TCP through Tor and returns the raw answer.
     *
     * The two-byte length prefix is what makes this DNS-over-TCP rather than a
     * UDP payload on a stream (RFC 1035 §4.2.2); a resolver that gets the
     * framing wrong answers nothing at all, which is why it is written out here
     * rather than assumed.
     */
    private fun resolveOverTor(query: ByteArray): ByteArray? {
        for (resolver in DNS_OVER_TCP) {
            val socket = try {
                openThroughTor(Target(resolver, DNS_PORT, isName = false))
            } catch (_: Exception) {
                continue
            }
            try {
                socket.soTimeout = DNS_TIMEOUT_MS
                val out = socket.getOutputStream()
                out.write(TorSocksWire.frameDnsQuery(query))
                out.flush()

                val input = DataInputStream(socket.getInputStream())
                val size = input.readUnsignedShort()
                if (size <= 0 || size > UDP_BUFFER * 4) continue
                return ByteArray(size).also { input.readFully(it) }
            } catch (_: Exception) {
                continue
            } finally {
                runCatching { socket.close() }
            }
        }
        return null
    }

    // ----------------------------------------------------------------- helpers

    private fun reply(output: OutputStream, status: Int) {
        runCatching {
            output.write(
                byteArrayOf(SOCKS_VERSION.toByte(), status.toByte(), 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0),
            )
            output.flush()
        }
    }

    private fun copy(from: InputStream, to: OutputStream, counter: AtomicLong) {
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

    private fun label(target: Target): String =
        if (target.isName) "${target.host}:${target.port}" else "[${target.host}]:${target.port}"
}
