package studio.cluvex.aether.custom

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Verdicts for the custom-config ping, and the TCP reachability check behind them.
 *
 * The delay itself is measured THROUGH the config by [RealDelay] (the config's
 * own outbound in the Xray core, one HTTP request through it), the way v2rayNG's
 * "real delay" works. This object only explains a failure:
 *
 *  * [PingStatus.OFFLINE] - the server's name does not resolve, or its port is
 *    refused / unreachable. The config will not come back.
 *  * [PingStatus.BLOCKED] - the name resolves to a censor's sinkhole, or the TCP
 *    connection is dropped or reset: the server is there, this network blocks it.
 *    Chaining (`Aether -> Config`, `Tor -> Config`) is what helps.
 *  * [PingStatus.NO_REPLY] - the port accepts connections but the config carried
 *    nothing: expired or wrong credentials, or its protocol is filtered.
 *  * [PingStatus.INVALID] - the core refuses to run the config.
 *
 * A failure reports `-1` as the number, as v2rayNG and MahsaNG do.
 */
object ConfigPing {

    /** What a single measurement produced. */
    data class Report(
        /** Round trip in milliseconds, or -1 when the server did not answer (v2rayNG convention). */
        val ms: Long = -1L,
        val status: PingStatus = PingStatus.UNTESTED,
        /** Milliseconds spent resolving the name, or -1 when the config names an IP. */
        val resolveMs: Long = -1L,
        /** When the measurement was taken, epoch millis; 0 = never. */
        val at: Long = 0L,
    ) {
        val ok: Boolean get() = status == PingStatus.OK
    }

    /** How a measurement ended. Decided from the exception the socket layer raised. */
    enum class Outcome {
        /** The handshake(s) completed. */
        CONNECTED,

        /** The name does not resolve at all (NXDOMAIN, or no address records). */
        NAME_GONE,

        /** The name resolves to an address that cannot be a server: a censor's sinkhole. */
        NAME_SINKHOLED,

        /** The box answered the SYN with a RST: it is up, the port is not. */
        REFUSED,

        /** The network says there is no path to the address at all. */
        UNREACHABLE,

        /** Nothing came back within the budget: the classic shape of a dropped SYN. */
        TIMED_OUT,

        /** The TCP connection opened and was then torn down during the handshake. */
        RESET,
    }

    /**
     * The verdict shown on the config's card.
     *
     * Deliberately small: every extra state is one more thing the user has to
     * interpret, and these four are the only ones that lead to different actions.
     */
    enum class PingStatus {
        UNTESTED, TESTING, OK, OFFLINE, BLOCKED, NO_NETWORK,

        /** The server accepts connections, but the config carried no request (wrong key/user, expired, or its protocol is filtered). */
        NO_REPLY,

        /** The Xray core refuses to run this config at all. */
        INVALID,
    }

    /** Budget for one measurement, end to end. */
    const val DEFAULT_TIMEOUT_MS = 6_000

    /** The value a failed measurement reports, as v2rayNG and MahsaNG do. */
    const val FAILED_MS = -1L

    /**
     * Addresses a censor's resolver hands back instead of the real one.
     *
     * `10.10.34.0/24` is the Iranian block page's range and is the single most
     * common answer there; the unspecified and loopback addresses are what a
     * sinkholing resolver returns elsewhere. A config that "resolves" to one of
     * these is not offline, it is filtered, and saying so is the difference
     * between the user deleting a working config and chaining it.
     */
    internal fun isSinkhole(address: String): Boolean {
        val a = address.lowercase().substringBefore('%')
        if (a == "0.0.0.0" || a == "::" || a == "::1" || a.startsWith("127.") || a.startsWith("0.")) return true
        // A PUBLIC name that resolves into a private or link-local range is a
        // poisoned answer, not a server (Iran answers 10.10.34.34-36 and d0::11).
        if (a.startsWith("10.") || a.startsWith("192.168.") || a.startsWith("169.254.")) return true
        val octets = a.split('.')
        if (octets.size == 4) {
            val first = octets[0].toIntOrNull()
            val second = octets[1].toIntOrNull()
            if (first == 172 && second != null && second in 16..31) return true
            if (first == 100 && second != null && second in 64..127) return true
            if (first != null && first >= 224) return true
            return false
        }
        return a.startsWith("fc") || a.startsWith("fd") || a.startsWith("fe80") || a.startsWith("d0::")
    }

    /**
     * The verdict for an [outcome], given whether the phone has a working
     * internet path of its own.
     *
     * Split out from the socket code with no Android types in it, so the rules
     * that decide what the user is told are covered by unit tests instead of
     * being reachable only from a real filtered network.
     */
    internal fun classify(outcome: Outcome, networkUp: Boolean): PingStatus = when {
        outcome == Outcome.CONNECTED -> PingStatus.OK
        // Nothing works right now, so nothing can be said about THIS config.
        // Blaming the server here is how a list of good configs gets deleted on
        // the train.
        !networkUp -> PingStatus.NO_NETWORK
        outcome == Outcome.NAME_GONE || outcome == Outcome.REFUSED || outcome == Outcome.UNREACHABLE ->
            PingStatus.OFFLINE
        else -> PingStatus.BLOCKED
    }

    /** True when the phone itself has a validated internet path outside any VPN. */
    @Suppress("DEPRECATION")
    fun networkUp(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }.getOrDefault(true)

    /**
     * Why a config that did not carry a real-delay request failed.
     *
     * Runs only AFTER [RealDelay] reported no reply, and only says something the
     * real-delay test cannot: whether the server's name and port are there at all.
     * A server that accepts the TCP connection is [PingStatus.NO_REPLY], never
     * "OK": a connect alone proves nothing on a network whose middleboxes accept
     * every SYN.
     */
    fun diagnose(endpoint: ConfigEndpoint?, networkUp: Boolean, timeoutMs: Int = DIAGNOSE_TIMEOUT_MS): PingStatus {
        if (!networkUp) return PingStatus.NO_NETWORK
        if (endpoint == null) return PingStatus.INVALID
        val reach = measure(endpoint, networkUp, timeoutMs)
        return if (reach.status == PingStatus.OK) PingStatus.NO_REPLY else reach.status
    }

    /** Budget for the TCP reachability check behind a failed real-delay test. */
    const val DIAGNOSE_TIMEOUT_MS = 4_000

    /**
     * TCP reachability of one config's server (DNS + TCP connect, no handshake).
     *
     * [networkUp] is passed in rather than read here so a caller testing a whole
     * list reads it once instead of once per config.
     */
    fun measure(
        endpoint: ConfigEndpoint,
        networkUp: Boolean,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        now: () -> Long = System::currentTimeMillis,
    ): Report {
        if (endpoint.port !in 1..65535 || endpoint.host.isBlank()) {
            return Report(FAILED_MS, PingStatus.OFFLINE, at = now())
        }

        // ---- 1. resolve ---------------------------------------------------
        var resolveMs = -1L
        val address: InetAddress = if (endpoint.isIp) {
            runCatching { InetAddress.getByName(endpoint.host) }.getOrNull()
                ?: return Report(FAILED_MS, PingStatus.OFFLINE, at = now())
        } else {
            val started = System.nanoTime()
            val resolved = runCatching { InetAddress.getAllByName(endpoint.host).firstOrNull() }
                .getOrNull()
            resolveMs = elapsedMs(started)
            if (resolved == null) {
                return Report(FAILED_MS, classify(Outcome.NAME_GONE, networkUp), resolveMs, now())
            }
            val literal = resolved.hostAddress.orEmpty()
            if (isSinkhole(literal)) {
                return Report(FAILED_MS, classify(Outcome.NAME_SINKHOLED, networkUp), resolveMs, now())
            }
            resolved
        }

        // ---- 2. TCP connect (no handshake: see RealDelay for why) ---------
        val started = System.nanoTime()
        var socket: Socket? = null
        return try {
            val raw = Socket()
            socket = raw
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(address, endpoint.port), timeoutMs)
            Report(elapsedMs(started).coerceAtLeast(1L), PingStatus.OK, resolveMs, now())
        } catch (e: Exception) {
            Report(FAILED_MS, classify(outcomeOf(e), networkUp), resolveMs, now())
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Which [Outcome] an exception from the socket layer describes. */
    internal fun outcomeOf(e: Throwable): Outcome {
        val message = (e.message ?: "").lowercase()
        return when {
            e is UnknownHostException -> Outcome.NAME_GONE
            e is SocketTimeoutException -> Outcome.TIMED_OUT
            e is NoRouteToHostException -> Outcome.UNREACHABLE
            e is PortUnreachableException -> Outcome.UNREACHABLE
            e is ConnectException && message.contains("refused") -> Outcome.REFUSED
            e is ConnectException && message.contains("unreachable") -> Outcome.UNREACHABLE
            e is ConnectException && message.contains("timed out") -> Outcome.TIMED_OUT
            message.contains("connection refused") -> Outcome.REFUSED
            message.contains("unreachable") -> Outcome.UNREACHABLE
            message.contains("timed out") || message.contains("timeout") -> Outcome.TIMED_OUT
            // A reset during the TLS handshake is the signature of a middlebox
            // that read the ClientHello and did not like it.
            else -> Outcome.RESET
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000L

}

/**
 * Where a config's first hop actually is, pulled out of its stored Xray outbound.
 *
 * Read from the OUTBOUND, never from the display string, so it works for a locked
 * NapsternetV config too: the app can measure a locked config's latency without
 * ever showing - or logging - the address it measured.
 */
data class ConfigEndpoint(
    val host: String,
    val port: Int,
    /** True when the transport does a TLS (or REALITY) handshake. */
    val tls: Boolean = false,
    /** The name the handshake must carry, when it differs from [host]. */
    val sni: String = "",
) {
    val sniOrHost: String get() = sni.ifBlank { host }

    /** True when [host] is already an address, so no name has to be resolved. */
    val isIp: Boolean get() = IPV4.matches(host) || host.contains(':')

    private companion object {
        val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    }
}

/** Reads the [ConfigEndpoint] of a saved config. */
object ConfigEndpoints {

    /** The endpoint of [config], or null when its outbound names no reachable server. */
    fun of(config: CustomConfig): ConfigEndpoint? = ofOutbound(config.outbound)

    internal fun ofOutbound(outboundJson: String): ConfigEndpoint? {
        val outbound = outboundJson.toJsonObjectOrNull() ?: return null
        val (host, port) = ConfigParser.serverOf(outbound)
        if (host.isBlank() || port !in 1..65535) return null
        val stream = outbound["streamSettings"] as? JsonObject
        val security = (stream?.get("security") as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
        val tls = security == "tls" || security == "reality" || security == "xtls"
        val settings = when (security) {
            "reality" -> stream?.get("realitySettings") as? JsonObject
            else -> stream?.get("tlsSettings") as? JsonObject
        }
        val sni = (settings?.get("serverName") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return ConfigEndpoint(host = host, port = port, tls = tls, sni = sni)
    }
}
