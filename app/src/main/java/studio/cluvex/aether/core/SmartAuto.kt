package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * How hostile the current network's filtering (DPI) looks, derived from the
 * direct probes in [SmartAuto.fingerprint]:
 *
 *  - OPEN          : UDP answers and TLS-with-SNI completes — a mostly clean path.
 *  - SNI_FILTERING : UDP is fine but a TLS handshake carrying an SNI stalls or
 *                    resets — classic SNI-based DPI. Obfuscation (noize) matters.
 *  - UDP_THROTTLED : TLS works but UDP gets no answers — the operator drops or
 *                    throttles UDP, which starves WireGuard/QUIC. TCP-shaped
 *                    transports (MASQUE over HTTP/2) are the way in.
 *  - HOSTILE       : both are broken — bring everything: TCP transport, heavy
 *                    obfuscation, fragmentation and ECH.
 */
enum class DpiClass { OPEN, SNI_FILTERING, UDP_THROTTLED, HOSTILE }

/** Everything Smart Auto learned about the current network before connecting. */
data class NetworkFingerprint(
    val dpiClass: DpiClass,
    val udpOk: Boolean,
    val tlsSniOk: Boolean,
    val operatorName: String,
    /** True when the active transport is cellular AND the MCC is 432 (Iran). */
    val iranCellular: Boolean,
    /** WARP range CIDR -> TCP connect latency in ms (-1 = unreachable). */
    val edgeLatencyMs: Map<String, Long>,
)

/** One concrete, ready-to-launch strategy in the Smart Auto ladder. */
data class AutoCandidate(
    val profile: ConnectionProfile,
    val timeoutMs: Long,
    val label: String,
)

/**
 * ROOT-CAUSE FIX for "Auto never connects": the old AUTO simply passed NO
 * protocol flag to the engine and hoped its default worked — there was no
 * intelligence and no fallback, so on any filtered network it just hung while
 * every manually chosen protocol worked fine.
 *
 * Smart Auto instead works like an engineer would:
 *
 *  1. FINGERPRINT ([fingerprint]) — before the engine even launches, probe the
 *     real network DIRECTLY (the app is excluded from its own TUN, so these
 *     probes always see the raw operator path):
 *       - UDP health: real DNS queries over UDP/53 to 1.1.1.1 and 8.8.8.8.
 *       - SNI DPI: a full TLS handshake to 1.1.1.1:443 carrying the SNI
 *         "www.cloudflare.com" (with hostname verification, no data sent).
 *       - WARP edge reachability: TCP connect latency to one representative
 *         host in each built-in Cloudflare WARP range.
 *       - Operator: name + MCC (432 = Iran) + transport (cellular/Wi-Fi),
 *         read WITHOUT any extra permissions.
 *  2. CLASSIFY the DPI behaviour into a [DpiClass].
 *  3. PLAN ([buildPlan]) — build an ordered ladder of concrete strategies
 *     (protocol + noize + fragment/ECH + the ranges that actually answered),
 *     most-likely-to-succeed first, plus a full-range last resort.
 *  4. The VpnService then walks the ladder: each candidate gets a real connect
 *     attempt gated by the 5-step self-test; the first one that passes wins.
 *
 * Every probe result and every decision is written to the in-app log, so the
 * user can see exactly WHY Smart Auto picked what it picked.
 */
object SmartAuto {
    private const val TAG = "auto"
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val TLS_PROBE_TIMEOUT_MS = 4_000

    /** Built-in Cloudflare WARP ranges + one representative probe host each. */
    private val EDGES = listOf(
        "162.159.192.0/24" to "162.159.192.1",
        "162.159.195.0/24" to "162.159.195.1",
        "188.114.96.0/24" to "188.114.96.1",
        "188.114.97.0/24" to "188.114.97.1",
        "8.6.112.0/24" to "8.6.112.1",
    )

    // ---- Stage 1+2: probe the network and classify its DPI ----------------

    suspend fun fingerprint(context: Context): NetworkFingerprint = withContext(Dispatchers.IO) {
        val (operatorName, iranCellular) = readOperator(context)
        DiagnosticsLog.i(
            TAG,
            "Fingerprinting the network — operator=\"$operatorName\"" +
                if (iranCellular) " (Iranian cellular)" else "",
        )
        val started = System.currentTimeMillis()
        val fp = coroutineScope {
            // All probes run in PARALLEL — the whole stage costs one timeout at worst.
            val udpCf = async { udpDnsProbe("1.1.1.1") }
            val udpGoog = async { udpDnsProbe("8.8.8.8") }
            val tls = async { tlsSniProbe() }
            val edgeJobs = EDGES.map { (cidr, probeIp) ->
                async { cidr to tcpLatencyMs(probeIp, 443) }
            }
            val udpOk = udpCf.await() || udpGoog.await()
            val tlsOk = tls.await()
            val edges = edgeJobs.awaitAll().toMap()
            val cls = when {
                udpOk && tlsOk -> DpiClass.OPEN
                udpOk -> DpiClass.SNI_FILTERING
                tlsOk -> DpiClass.UDP_THROTTLED
                else -> DpiClass.HOSTILE
            }
            NetworkFingerprint(cls, udpOk, tlsOk, operatorName, iranCellular, edges)
        }
        val edgeSummary = fp.edgeLatencyMs.entries.joinToString(", ") { (range, ms) ->
            "$range=${if (ms < 0) "unreachable" else "${ms}ms"}"
        }
        DiagnosticsLog.i(
            TAG,
            "DPI fingerprint ready in ${System.currentTimeMillis() - started} ms: " +
                "udp=${fp.udpOk} tlsSni=${fp.tlsSniOk} → ${fp.dpiClass} | edges: $edgeSummary",
        )
        fp
    }

    // ---- Stage 3: turn the fingerprint into an ordered strategy ladder ----

    fun buildPlan(user: ConnectionProfile, fp: NetworkFingerprint): List<AutoCandidate> {
        // Prefer the ranges that actually answered, fastest first. Narrowing
        // the scan to live ranges is what makes each attempt FAST; the last
        // resort below still covers the full built-in ranges.
        val reachable = fp.edgeLatencyMs.filterValues { it >= 0 }.entries.sortedBy { it.value }
        val bestRanges = reachable.take(2).joinToString(", ") { it.key }
        // NEVER override an endpoint the user pinned manually in Settings.
        val keepUserEndpoint = user.endpointMode != EndpointMode.AUTO

        fun cand(
            proto: Protocol,
            noize: Noize,
            h2: Boolean = false,
            frag: Boolean = false,
            ech: Boolean = false,
        ): AutoCandidate {
            // Respect a stronger user-chosen obfuscation; bias bare profiles to
            // LIGHT noize on Iranian cellular where fingerprinting is routine.
            var mergedNoize = if (user.noize.ordinal >= noize.ordinal) user.noize else noize
            if (mergedNoize == Noize.OFF && fp.iranCellular) mergedNoize = Noize.LIGHT
            var p = user.copy(
                protocol = proto,
                noize = mergedNoize,
                masqueHttp2 = user.masqueHttp2 || (h2 && proto == Protocol.MASQUE),
                fragment = user.fragment || frag,
                ech = user.ech || ech,
                // TURBO per attempt: the ladder's speed comes from trying the
                // NEXT strategy quickly, not from one long exhaustive scan.
                scanMode = ScanMode.TURBO,
            )
            if (!keepUserEndpoint && bestRanges.isNotEmpty()) {
                p = p.copy(endpointMode = EndpointMode.MANUAL_RANGE, manualRange = bestRanges)
            }
            val label = buildString {
                append(proto.name)
                append(" · noize=").append(p.noize.name.lowercase())
                if (p.masqueHttp2) append(" · h2")
                if (p.fragment) append(" · fragment")
                if (p.ech) append(" · ech")
                if (!keepUserEndpoint && bestRanges.isNotEmpty()) append(" · ranges[").append(bestRanges).append("]")
                append(" · scan=turbo")
            }
            return AutoCandidate(p, p.connectTimeoutMs(), label)
        }

        val ladder = when (fp.dpiClass) {
            DpiClass.OPEN -> listOf(
                cand(Protocol.WIREGUARD, Noize.OFF),
                cand(Protocol.MASQUE, Noize.OFF),
                cand(Protocol.GOOL, Noize.LIGHT),
            )
            DpiClass.SNI_FILTERING -> listOf(
                cand(Protocol.WIREGUARD, Noize.BALANCED),
                cand(Protocol.GOOL, Noize.BALANCED),
                cand(Protocol.MASQUE, Noize.FIREWALL, frag = true, ech = true),
            )
            DpiClass.UDP_THROTTLED -> listOf(
                cand(Protocol.MASQUE, Noize.LIGHT, h2 = true, frag = true, ech = true),
                cand(Protocol.GOOL, Noize.AGGRESSIVE),
                cand(Protocol.WIREGUARD, Noize.GFW),
            )
            DpiClass.HOSTILE -> listOf(
                cand(Protocol.MASQUE, Noize.GFW, h2 = true, frag = true, ech = true),
                cand(Protocol.GOOL, Noize.AGGRESSIVE),
                cand(Protocol.WIREGUARD, Noize.AGGRESSIVE),
            )
        }

        // Last resort: the top strategy again, but scanning the engine's FULL
        // built-in ranges with the user's own scan mode — covers the rare case
        // where the probe-narrowed ranges themselves were the problem.
        val first = ladder.first()
        var fbProfile = first.profile.copy(scanMode = user.scanMode)
        if (!keepUserEndpoint) {
            fbProfile = fbProfile.copy(endpointMode = EndpointMode.AUTO, manualRange = user.manualRange)
        }
        val fallback = AutoCandidate(
            fbProfile,
            fbProfile.connectTimeoutMs(),
            "${fbProfile.protocol.name} · noize=${fbProfile.noize.name.lowercase()} · full built-in ranges " +
                "· scan=${user.scanMode.name.lowercase()} (last resort)",
        )

        val plan = (ladder + fallback).distinctBy { it.profile }
        DiagnosticsLog.i(TAG, "Strategy ladder for ${fp.dpiClass} (${plan.size} steps):")
        plan.forEachIndexed { i, c -> DiagnosticsLog.i(TAG, "  ${i + 1}. ${c.label}") }
        return plan
    }

    // ---- Probes ------------------------------------------------------------

    /**
     * Sends a REAL DNS query (A record for example.com) over UDP/53 and waits
     * for any well-formed answer. An answer proves UDP round-trips survive on
     * this network; silence from BOTH resolvers means UDP is dropped/throttled.
     */
    private fun udpDnsProbe(server: String, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean = runCatching {
        DatagramSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val query = byteArrayOf(
                0x1A, 0x2B, // transaction id
                0x01, 0x00, // standard query, recursion desired
                0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(),
                'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0,
                0x00, 0x01, // type A
                0x00, 0x01, // class IN
            )
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(512)
            sock.receive(DatagramPacket(buf, buf.size))
            val ok = buf[0] == 0x1A.toByte() && buf[1] == 0x2B.toByte()
            DiagnosticsLog.d(TAG, "udp53 probe $server → ${if (ok) "answered" else "bad reply"}")
            ok
        }
    }.getOrElse {
        DiagnosticsLog.d(TAG, "udp53 probe $server → no answer (${it.message})")
        false
    }

    /** TCP connect latency to [ip]:[port] in ms, or -1 when unreachable. */
    private fun tcpLatencyMs(ip: String, port: Int, timeoutMs: Int = PROBE_TIMEOUT_MS): Long = runCatching {
        val start = System.nanoTime()
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        (System.nanoTime() - start) / 1_000_000
    }.getOrDefault(-1L)

    /**
     * Completes a full TLS handshake to 1.1.1.1:443 with the SNI
     * "www.cloudflare.com" (no payload is sent). SNI-based DPI middleboxes
     * kill exactly this step, so a failure here — while plain TCP connects
     * fine — is a strong SNI-filtering signal. Hostname verification is
     * enforced, same as the geolocation probes.
     */
    private fun tlsSniProbe(timeoutMs: Int = TLS_PROBE_TIMEOUT_MS): Boolean = runCatching {
        Socket().use { raw ->
            raw.connect(InetSocketAddress("1.1.1.1", 443), timeoutMs)
            raw.soTimeout = timeoutMs
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(raw, "www.cloudflare.com", 443, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            val ok = HttpsURLConnection.getDefaultHostnameVerifier().verify("www.cloudflare.com", ssl.session)
            runCatching { ssl.close() }
            DiagnosticsLog.d(TAG, "tls-sni probe → ${if (ok) "handshake ok" else "hostname mismatch"}")
            ok
        }
    }.getOrElse {
        DiagnosticsLog.d(TAG, "tls-sni probe → failed (${it.message})")
        false
    }

    /**
     * Operator name + "is Iranian cellular" — no runtime permissions needed.
     *
     * ## Dual SIM (1.3.1, issue #7)
     *
     * `TelephonyManager.networkOperatorName` answers for the manager's OWN
     * subscription, and a manager obtained from `getSystemService` carries the
     * DEFAULT one — which on a dual-SIM phone is usually SIM 1 regardless of
     * which SIM carries mobile data. A user with IR-TCI in slot 1 and Irancell
     * as the data SIM was fingerprinted as `operator="IR-TCI"`, so Smart Auto
     * built its strategy ladder for the wrong carrier.
     *
     * Fixed by asking for the manager bound to the subscription that is actually
     * carrying data: `getActiveDataSubscriptionId()` on API 30+, falling back to
     * `getDefaultDataSubscriptionId()` on 24+. Both are static on
     * `SubscriptionManager` and neither needs a runtime permission — unlike the
     * subscription LIST, which needs READ_PHONE_STATE and is deliberately not
     * used here. If neither id is valid (no SIM, Wi-Fi only) the default manager
     * is used exactly as before.
     */
    private fun readOperator(context: Context): Pair<String, Boolean> = runCatching {
        val default = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val tm = default?.let { forActiveDataSim(it) } ?: default
        val name = tm?.networkOperatorName?.takeIf { it.isNotBlank() } ?: "unknown"
        val mcc = tm?.networkOperator?.take(3)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        name to (cellular && mcc == "432")
    }.getOrDefault("unknown" to false)

    /**
     * [TelephonyManager] for the subscription carrying mobile data, or null to
     * keep the default one.
     *
     * Never throws: every call here is best-effort, and an OEM build that
     * reports an unusable id must leave the fingerprint working rather than
     * failing the whole connect.
     */
    private fun forActiveDataSim(default: TelephonyManager): TelephonyManager? = runCatching {
        // minSdk is 26, so getDefaultDataSubscriptionId (API 24) is always
        // available; getActiveDataSubscriptionId (API 30) is the better answer
        // where it exists, because it follows a temporary data switch.
        val subId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SubscriptionManager.getActiveDataSubscriptionId()
        } else {
            SubscriptionManager.getDefaultDataSubscriptionId()
        }
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            null
        } else {
            default.createForSubscriptionId(subId)
        }
    }.getOrNull()
}
