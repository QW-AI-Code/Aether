package studio.cluvex.aether.core

import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.GoolMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import java.security.MessageDigest
import java.util.Locale

/**
 * 1.7.0 (same version): the SMART BRAIN of Smart mode - reads what every failed
 * attempt said, works out WHAT the operator's DPI is doing, and re-plans the rest
 * of the race on the spot. Pure (no Android types, no I/O): every rule is covered
 * by `DpiBrainTest`.
 *
 * ## Why a fixed list was not enough
 *
 * Nine out of ten users never open a settings page; they press Connect in Smart
 * mode and expect the app to deal with their operator. The 1.7.0 race already
 * tried light tactics before heavy ones, but in a FIXED order decided before the
 * first packet: when WireGuard's UDP was silently dropped in Tehran, the MASQUE
 * lane still spent 20 s on MASQUE over HTTP/3 - the same UDP - before it reached
 * HTTP/2; when TLS handshakes were reset, the plain HTTP/2 tactic was tried before
 * the fragmented one. Each wasted rung is 20-25 s of "Connecting".
 *
 * ## What the brain does
 *
 *  1. **Listens** ([signalsOf]): every engine output line is matched against the
 *     symptoms filtering leaves behind - UDP handshakes that time out, TLS
 *     handshakes reset mid-way, a scan that found no gateway, an account API that
 *     cannot be reached, a damaged identity file, a missing ECH key.
 *  2. **Diagnoses** ([diagnose]): turns those signals plus HOW the attempt ended
 *     (died at once, served nothing, timed out) into one [Verdict].
 *  3. **Learns** ([Knowledge], shared by both lanes through a [Board]): two UDP
 *     strikes (or one, when the fingerprint already saw UDP/QUIC struggle) mean
 *     "UDP is blocked here"; one TLS reset means "TLS is being interfered with".
 *  4. **Adapts** ([adapt]): the tactics still to come are re-planned with what was
 *     learned - UDP carriers move behind the TCP ones (never removed: a verdict
 *     can be wrong), TCP carriers get TLS ClientHello fragmentation and a stronger
 *     obfuscation floor when TLS is interfered with, WireGuard gets heavy
 *     obfuscation when its gateways stay silent. Equivalent tactics are never
 *     tried twice ([signature]).
 *  5. **Gives up early on hopeless rungs** ([shouldAbandon]): a UDP tactic that is
 *     still not serving after [EARLY_ABANDON_MS] on a network already known to
 *     drop UDP is stopped instead of burning its whole budget.
 *  6. **Remembers per operator** ([operatorKey]): what finally worked is also
 *     stored under the operator, so the first connect on a NEW network of the
 *     same operator (another Wi-Fi hotspot, another cell) starts with it.
 */
object DpiBrain {

    // ------------------------------------------------------------------ signals

    const val S_UDP_SILENT = 1
    const val S_TLS_RESET = 1 shl 1
    const val S_SCAN_EMPTY = 1 shl 2
    const val S_API_BLOCKED = 1 shl 3
    const val S_IDENTITY = 1 shl 4
    const val S_ECH = 1 shl 5
    const val S_TCP_BLOCKED = 1 shl 6

    /** An engine that dies sooner than this after spawn did not meet the network. */
    const val FAST_DEATH_MS = 3_000L

    /** A UDP tactic on a UDP-hostile network gets this long to start serving. */
    const val EARLY_ABANDON_MS = 7_000L

    /** Id suffix of a tactic the brain hardened on the fly. */
    const val BOOST_SUFFIX = "+dpi"

    /** Symptom bits found in one engine output line (0 = nothing of interest). */
    fun signalsOf(line: String): Int {
        val l = line.lowercase(Locale.ROOT)
        var bits = 0
        // A line saying the engine already set a damaged identity aside and is
        // provisioning a new one is a RECOVERY, not a symptom: counting it would
        // make a later, network-caused failure look like a damaged identity.
        val recovered = "provisioning a fresh identity" in l
        if (!recovered && ("valid utf-8" in l || "config parse" in l || "damaged file was moved" in l ||
                "fresh identity can be provisioned" in l)
        ) {
            bits = bits or S_IDENTITY
        }
        if ("ech is on but" in l || "no ech key" in l) bits = bits or S_ECH
        if ("could not reach the account api" in l ||
            (("register" in l || "enrol" in l || "provision" in l) && ("failed" in l || "error" in l)) ||
            ("api.cloudflareclient.com" in l && ("timed out" in l || "error" in l || "refused" in l))
        ) {
            bits = bits or S_API_BLOCKED
        }
        if ("scan deadline reached with no" in l || ("no usable" in l && "found" in l) ||
            "found no data-plane endpoint" in l
        ) {
            bits = bits or S_SCAN_EMPTY
        }
        if ("connection reset" in l || "reset by peer" in l || "broken pipe" in l || "unexpected eof" in l ||
            ("tls" in l && ("alert" in l || "handshake failure" in l || "handshake eof" in l)) ||
            ("handshake" in l && "reset" in l)
        ) {
            bits = bits or S_TLS_RESET
        }
        if (("handshake" in l && ("timed out" in l || "timeout" in l)) || "idle timeout" in l ||
            ("no response" in l && ("udp" in l || "quic" in l || "wireguard" in l || "endpoint" in l))
        ) {
            bits = bits or S_UDP_SILENT
        }
        if ("network is unreachable" in l || "connection refused" in l ||
            ("tcp" in l && "connect" in l && ("timed out" in l || "timeout" in l))
        ) {
            bits = bits or S_TCP_BLOCKED
        }
        return bits
    }

    private fun Int.has(bit: Int): Boolean = (this and bit) != 0

    // ----------------------------------------------------------------- verdicts

    enum class Verdict {
        /** The engine's identity file is damaged: heal it and retry the same tactic. */
        IDENTITY_DAMAGED,

        /** ECH was requested but no key exists here - not a network verdict. */
        ECH_UNAVAILABLE,

        /** The WARP account API is unreachable (registration / enrolment). */
        API_BLOCKED,

        /** The engine stopped before it met the network (configuration / binary fault). */
        ENGINE_FAULT,

        /** A UDP carrier got nowhere: the operator drops or throttles UDP. */
        UDP_BLOCKED,

        /** TLS handshakes are reset or alerted: SNI / ClientHello based DPI. */
        TLS_INTERFERENCE,

        /** The scan answered nothing on a TCP carrier: gateways are IP-filtered. */
        GATEWAYS_FILTERED,

        /** Nothing specific, it just ran out of time. */
        TOO_SLOW,

        NONE,
    }

    fun diagnose(signals: Int, died: Boolean, aliveMs: Long, ridesUdp: Boolean, fatal: String?): Verdict = when {
        signals.has(S_IDENTITY) -> Verdict.IDENTITY_DAMAGED
        signals.has(S_ECH) -> Verdict.ECH_UNAVAILABLE
        signals.has(S_API_BLOCKED) -> Verdict.API_BLOCKED
        died && aliveMs < FAST_DEATH_MS && fatal != null -> Verdict.ENGINE_FAULT
        signals.has(S_TLS_RESET) && !ridesUdp -> Verdict.TLS_INTERFERENCE
        ridesUdp && (signals.has(S_UDP_SILENT) || signals.has(S_SCAN_EMPTY) || !died) -> Verdict.UDP_BLOCKED
        signals.has(S_SCAN_EMPTY) || signals.has(S_TCP_BLOCKED) -> Verdict.GATEWAYS_FILTERED
        signals.has(S_TLS_RESET) -> Verdict.TLS_INTERFERENCE
        !died -> Verdict.TOO_SLOW
        else -> Verdict.NONE
    }

    // ---------------------------------------------------------------- knowledge

    data class Knowledge(
        val udpStrikes: Int = 0,
        val tlsStrikes: Int = 0,
        val filteredStrikes: Int = 0,
        val apiBlocked: Boolean = false,
        /** The fingerprint already saw UDP or QUIC struggle on this network. */
        val udpWeakHint: Boolean = false,
    ) {
        val udpBlocked: Boolean get() = udpStrikes >= 2 || (udpStrikes >= 1 && udpWeakHint)
        val tlsInterference: Boolean get() = tlsStrikes >= 1
        val gatewaysFiltered: Boolean get() = filteredStrikes >= 1

        fun learn(v: Verdict): Knowledge = when (v) {
            Verdict.UDP_BLOCKED -> copy(udpStrikes = udpStrikes + 1)
            Verdict.TLS_INTERFERENCE -> copy(tlsStrikes = tlsStrikes + 1)
            Verdict.GATEWAYS_FILTERED -> copy(filteredStrikes = filteredStrikes + 1)
            Verdict.API_BLOCKED -> copy(apiBlocked = true)
            else -> this
        }

        fun describe(): String = buildString {
            append("udp=").append(if (udpBlocked) "blocked" else "ok?").append("(").append(udpStrikes).append(")")
            append(" tls=").append(if (tlsInterference) "interfered" else "ok?")
            append(" gateways=").append(if (gatewaysFiltered) "filtered" else "ok?")
            if (apiBlocked) append(" api=blocked")
        }
    }

    /** What both lanes learned so far; thread-safe. */
    class Board(initial: Knowledge = Knowledge()) {
        @Volatile
        private var k: Knowledge = initial

        val now: Knowledge get() = k

        @Synchronized
        fun learn(v: Verdict): Knowledge {
            k = k.learn(v)
            return k
        }
    }

    /** Starting knowledge from the direct probes, before any attempt. */
    fun initialKnowledge(fp: NetworkFingerprint): Knowledge = Knowledge(
        udpWeakHint = !fp.udpOk || fp.quicReachable == false ||
            fp.dpiClass == DpiClass.UDP_THROTTLED || fp.dpiClass == DpiClass.HOSTILE,
        tlsStrikes = if (fp.dpiClass == DpiClass.SNI_FILTERING || fp.dpiClass == DpiClass.HOSTILE) 1 else 0,
    )

    // ------------------------------------------------------------------ carriers

    /** Whether [t]'s outer carrier is UDP (WireGuard, QUIC). */
    fun ridesUdp(t: SmartPlusPlan.Tactic): Boolean = when (t.protocol) {
        Protocol.WIREGUARD -> true
        Protocol.GOOL -> t.family == SmartPlusPlan.Family.WARP || !t.http2
        Protocol.MASQUE, Protocol.MIM -> !t.http2
        Protocol.AUTO -> false
    }

    /** Same question for a concrete profile (ladder rungs). */
    fun ridesUdp(p: ConnectionProfile): Boolean = when (p.protocol) {
        Protocol.WIREGUARD -> true
        Protocol.GOOL -> p.goolMode == GoolMode.CLASSIC || !p.masqueHttp2
        Protocol.MASQUE, Protocol.MIM -> !p.masqueHttp2
        Protocol.AUTO -> false
    }

    /** What makes two tactics the same attempt, whatever their ids. */
    fun signature(t: SmartPlusPlan.Tactic): String =
        listOf(t.family.name, t.protocol.name, t.noize.name, t.http2, t.fragment).joinToString("|")

    private fun floor(current: Noize, atLeast: Noize): Noize =
        if (current.ordinal >= atLeast.ordinal) current else atLeast

    /** [t] hardened for what is known; the same instance when nothing changes. */
    fun boost(t: SmartPlusPlan.Tactic, k: Knowledge): SmartPlusPlan.Tactic {
        var fragment = t.fragment
        var noize = t.noize
        if (!ridesUdp(t)) {
            if (k.tlsInterference || k.gatewaysFiltered) {
                fragment = true
                noize = floor(noize, Noize.FIREWALL)
            }
        } else if (k.gatewaysFiltered || k.udpBlocked) {
            // Silent UDP gateways: disguise the handshake as heavily as the engine can.
            noize = floor(noize, Noize.GFW)
        }
        if (fragment == t.fragment && noize == t.noize) return t
        return t.copy(
            id = if (t.id.endsWith(BOOST_SUFFIX)) t.id else t.id + BOOST_SUFFIX,
            fragment = fragment,
            noize = noize,
            budgetMs = maxOf(t.budgetMs, SmartPlusPlan.HARDENED_MS),
            overhead = t.overhead + 0.03,
        )
    }

    /**
     * Re-plans the tactics still to come with what is known. Nothing is ever
     * removed - a verdict can be wrong - only re-ordered and hardened.
     */
    fun adapt(remaining: List<SmartPlusPlan.Tactic>, k: Knowledge): List<SmartPlusPlan.Tactic> {
        var list = remaining.map { boost(it, k) }
        if (k.udpBlocked) {
            val (udp, tcp) = list.partition { ridesUdp(it) }
            list = tcp + udp
        }
        return list.distinctBy { signature(it) }
    }

    /** Same re-planning for the serial ladder (concrete profiles). */
    fun adaptLadder(remaining: List<AutoCandidate>, k: Knowledge): List<AutoCandidate> {
        var list = remaining.map { c ->
            val p = c.profile
            if (!ridesUdp(p) && (k.tlsInterference || k.gatewaysFiltered) && !p.fragment) {
                c.copy(
                    profile = p.copy(fragment = true, noize = floor(p.noize, Noize.FIREWALL)),
                    label = c.label + " · fragment (smart)",
                    tacticId = c.tacticId?.let { if (it.endsWith(BOOST_SUFFIX)) it else it + BOOST_SUFFIX },
                )
            } else if (ridesUdp(p) && (k.gatewaysFiltered || k.udpBlocked) && p.noize.ordinal < Noize.GFW.ordinal) {
                c.copy(
                    profile = p.copy(noize = Noize.GFW),
                    label = c.label + " · noize=gfw (smart)",
                    tacticId = c.tacticId?.let { if (it.endsWith(BOOST_SUFFIX)) it else it + BOOST_SUFFIX },
                )
            } else {
                c
            }
        }
        if (k.udpBlocked) {
            val (udp, tcp) = list.partition { ridesUdp(it.profile) && !it.lastResort }
            list = tcp + udp
        }
        return list.distinctBy { it.profile }
    }

    /** Whether a still-silent attempt should be stopped before its budget ends. */
    fun shouldAbandon(t: SmartPlusPlan.Tactic, k: Knowledge, elapsedMs: Long, serving: Boolean): Boolean =
        !serving && ridesUdp(t) && k.udpBlocked && elapsedMs >= EARLY_ABANDON_MS

    /**
     * Resolves a remembered tactic id - including one the brain hardened
     * ([BOOST_SUFFIX]) - against the tactics this network offers.
     */
    fun resolve(id: String?, offered: List<SmartPlusPlan.Tactic>): SmartPlusPlan.Tactic? {
        if (id == null) return null
        offered.firstOrNull { it.id == id }?.let { return it }
        if (!id.endsWith(BOOST_SUFFIX)) return null
        val base = offered.firstOrNull { it.id == id.removeSuffix(BOOST_SUFFIX) } ?: return null
        val full = Knowledge(udpStrikes = 2, tlsStrikes = 1, filteredStrikes = 1)
        return boost(base, full)
    }

    /**
     * Memory key of the OPERATOR (e.g. "IR-MCI"), so a new network of the same
     * operator starts with what worked on the others. A truncated hash, like the
     * network key - the operator name itself is never stored. Null when unknown.
     */
    fun operatorKey(operatorName: String, iranSim: Boolean): String? {
        val name = operatorName.trim().lowercase(Locale.ROOT)
        if (name.isEmpty() || name == "unknown") return null
        val digest = MessageDigest.getInstance("SHA-256").digest("op|$name|$iranSim".toByteArray(Charsets.UTF_8))
        return "op-" + digest.take(8).joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
    }
}
