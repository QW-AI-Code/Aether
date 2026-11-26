package studio.cluvex.aether.core

import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.GoolMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TransportBackend

/**
 * Quality of a route that PROVED it carries traffic, measured through it.
 *
 * [downKbps] / [upKbps] are 0 when that transfer could not be measured.
 */
data class TunnelQuality(val rttMs: Long, val downKbps: Long, val upKbps: Long)

/**
 * SMART MODE 1.7.0 - what to try, in what order, what may run side by side, and
 * how the winner is chosen. Pure: no Android types, no I/O; every rule is covered
 * by `SmartPlusPlanTest`.
 *
 * ## What was wrong in 1.6.0 (Tehran field logs, 2026-11)
 *
 *  1. **Every attempt was killed after four seconds.** Not a planning problem but
 *     the readiness gate underneath it - fixed in [EngineReadiness]. With that
 *     gate, no order could have worked.
 *  2. **Unfair start.** The WARP (WireGuard) lane started first and MASQUE only
 *     7 s later; then a remembered route took a 45 s slot of its own before any
 *     race. Where WireGuard is filtered and only MASQUE passes (Tehran), Smart
 *     spent its time on the protocol that could not work.
 *  3. **Two engines on one identity.** Core 2.4.0's gool is WireGuard INSIDE
 *     MASQUE and uses `aether-masque.toml` and the MASQUE gateway cache - but it
 *     raced in the WARP lane, concurrently with the MASQUE lane, on the same
 *     identity and cache files.
 *  4. **First to answer won**, whatever its latency, speed or overhead.
 *  5. **Forced ECH.** The hardened MASQUE tactics turned ECH on. Where no ECH key
 *     can be fetched (Iran, today) the engine refuses to start (`ECH is on but
 *     there is no ECH key to offer`), so every hardened MASQUE attempt died in
 *     0.4 s. ECH is now only ever the user's own choice.
 *
 * ## The 1.7.0 model
 *
 *  * Two LANES, one per identity family ([Family]); a lane is serial because its
 *    tactics share identity files, the two lanes run at the same time, from the
 *    same instant, with the same per-tactic budgets: a fair start.
 *  * Inside a lane, tactics go from the cleanest/lightest (bare WireGuard, MASQUE
 *    over HTTP/3) to the heaviest (obfuscation, TLS fragmentation, two hops), so
 *    the first thing that works is also the cheapest thing that works there.
 *  * When the first tactic proves itself, the race keeps running for
 *    [QUALITY_WINDOW_MS] so the other lane can prove one too. Every proven route
 *    is then measured through itself ([TunnelQuality]) and [rank] picks the one
 *    with the best latency/throughput after an overhead factor per tactic.
 *  * Evidence from this network ([SmartPlusMemory]) only re-orders a lane - it
 *    never costs a separate, serial attempt any more.
 */
object SmartPlusPlan {

    /**
     * The engine state a route needs exclusively. Persisted by name inside
     * [SmartPlusMemory] entries via the tactic id, never by enum ordinal.
     */
    enum class Family { WARP, MASQUE }

    data class Tactic(
        val id: String,
        val family: Family,
        val protocol: Protocol,
        /** Obfuscation floor. The user's own choice wins when it is stronger. */
        val noize: Noize,
        val http2: Boolean = false,
        val fragment: Boolean = false,
        /** Kept for the profile copy only; Smart never turns ECH on by itself. */
        val ech: Boolean = false,
        val budgetMs: Long,
        /**
         * Relative cost of the encapsulation (1.0 = plain WireGuard). Used by
         * [score]: two routes with the same measured latency are not equal when one
         * of them wraps every packet twice.
         */
        val overhead: Double = 1.0,
    ) {
        /**
         * Applies this tactic to the user's profile. A tactic can only ever ADD
         * hardening: a stronger user obfuscation, a pinned endpoint and the user's
         * anti-DPI switches (including ECH) are kept.
         */
        fun applyTo(user: ConnectionProfile, bindPort: Int = 0): ConnectionProfile {
            val mergedNoize = if (user.noize.ordinal >= noize.ordinal) user.noize else noize
            return user.copy(
                protocol = protocol,
                noize = mergedNoize,
                masqueHttp2 = user.masqueHttp2 || (http2 && family == Family.MASQUE),
                fragment = user.fragment || fragment,
                ech = user.ech,
                // TURBO per attempt: a lane's speed comes from moving on quickly.
                scanMode = ScanMode.TURBO,
                bindOverride = bindPort,
            )
        }

        /** For the log: what a reader needs to tell two attempts apart. */
        val label: String
            get() = buildString {
                append(protocol.name)
                append(" · noize=").append(noize.name.lowercase())
                if (http2) append(" · h2")
                if (fragment) append(" · fragment")
                append(" · ").append(budgetMs / 1000).append("s")
            }
    }

    /** Tactics tried one after another, starting [startAfterMs] into the race. */
    data class Lane(val tactics: List<Tactic>, val startAfterMs: Long = 0L)

    // ---------------------------------------------------------------- budgets

    /**
     * Budget of a light, single-hop tactic. With the 1.7.0 readiness gate a route
     * that works answers in 2-8 s (cached gateway) or within ~15 s (fresh turbo
     * scan); 20 s is the point after which waiting longer on the SAME tactic is
     * worth less than trying the next one.
     */
    const val LIGHT_MS = 20_000L

    /** Hardened single-hop tactics: obfuscation / fragmentation slow the scan. */
    const val HARDENED_MS = 25_000L

    /** Two-hop tactics (gool, mim): two handshakes, two gateways. */
    const val TWO_HOP_MS = 40_000L

    /** Kept for callers of the 1.6 API: the ordinary single-tactic budget. */
    const val LANE_MS = LIGHT_MS

    /** Both lanes start together. Kept as a named constant for the tests. */
    const val SECOND_LANE_AFTER_MS = 0L

    /**
     * How long the race keeps running after the FIRST route proved itself, so the
     * other lane can prove one too and the two can be compared. Short on purpose:
     * a route that needs much longer than the winner is not going to beat it.
     */
    const val QUALITY_WINDOW_MS = 2_500L

    /**
     * Measurement budget per proven route (latency + download + upload).
     * 1.7.0 (same version): 7 s -> 4.5 s - two proven routes used to add 14 s of
     * measuring to a connect that had already succeeded.
     */
    const val MEASURE_MS = 4_500

    /** Longest a whole race may take before the mode hands over to its last resort. */
    const val RACE_CEILING_MS = 150_000L

    // ---------------------------------------------------------------- tactics

    /** Whether h2 (MASQUE over TCP) should be tried before h3 (QUIC). */
    fun h2First(dpi: DpiClass, onMobileData: Boolean, quicReachable: Boolean?, preferTcp: Boolean = false): Boolean =
        preferTcp || onMobileData || dpi == DpiClass.UDP_THROTTLED || dpi == DpiClass.HOSTILE || quicReachable == false

    fun tacticsFor(
        family: Family,
        dpi: DpiClass,
        onMobileData: Boolean,
        quicReachable: Boolean? = null,
        goolClassic: Boolean = false,
        preferTcp: Boolean = false,
    ): List<Tactic> = when (family) {
        Family.WARP -> warpTactics(dpi, goolClassic)
        Family.MASQUE -> masqueTactics(dpi, onMobileData, quicReachable, goolClassic, preferTcp)
    }

    private fun warpTactics(dpi: DpiClass, goolClassic: Boolean): List<Tactic> {
        val bare = Tactic("wg", Family.WARP, Protocol.WIREGUARD, Noize.OFF, budgetMs = LIGHT_MS, overhead = 1.00)
        val noize = when (dpi) {
            DpiClass.OPEN -> Noize.LIGHT
            DpiClass.SNI_FILTERING -> Noize.BALANCED
            DpiClass.UDP_THROTTLED -> Noize.GFW
            DpiClass.HOSTILE -> Noize.AGGRESSIVE
        }
        val hardened = Tactic("wg.noize", Family.WARP, Protocol.WIREGUARD, noize, budgetMs = HARDENED_MS, overhead = 1.03)
        val heavy = Tactic(
            "wg.heavy",
            Family.WARP,
            Protocol.WIREGUARD,
            if (noize == Noize.AGGRESSIVE) Noize.GFW else Noize.AGGRESSIVE,
            budgetMs = HARDENED_MS,
            overhead = 1.06,
        )
        val ordered = when (dpi) {
            DpiClass.OPEN, DpiClass.SNI_FILTERING -> listOf(bare, hardened, heavy)
            // UDP is shaped: bare WireGuard is the least likely to survive.
            DpiClass.UDP_THROTTLED, DpiClass.HOSTILE -> listOf(hardened, heavy, bare)
        }
        // Classic gool (WireGuard inside WireGuard) shares the WARP identity, so
        // it may only ever run in this lane - and last: it is two hops.
        val gool = Tactic(
            "gool",
            Family.WARP,
            Protocol.GOOL,
            if (dpi == DpiClass.OPEN) Noize.LIGHT else Noize.AGGRESSIVE,
            budgetMs = TWO_HOP_MS,
            overhead = 1.25,
        )
        return if (goolClassic) ordered + gool else ordered
    }

    private fun masqueTactics(
        dpi: DpiClass,
        onMobileData: Boolean,
        quicReachable: Boolean?,
        goolClassic: Boolean,
        preferTcp: Boolean,
    ): List<Tactic> {
        val floor = when (dpi) {
            DpiClass.OPEN -> Noize.OFF
            DpiClass.SNI_FILTERING -> Noize.FIREWALL
            DpiClass.UDP_THROTTLED -> Noize.LIGHT
            DpiClass.HOSTILE -> Noize.GFW
        }
        val h2Lead = h2First(dpi, onMobileData, quicReachable, preferTcp)
        val h3 = Tactic("masque.h3", Family.MASQUE, Protocol.MASQUE, floor, http2 = false, budgetMs = LIGHT_MS, overhead = 1.05)
        val h2 = Tactic("masque.h2", Family.MASQUE, Protocol.MASQUE, floor, http2 = true, budgetMs = LIGHT_MS, overhead = 1.10)
        // The TCP carrier with its ClientHello split - the closest thing the engine
        // has to what SNI-spoofing tools do to get MASQUE past SNI-based DPI.
        val h2Frag = Tactic(
            "masque.h2.frag",
            Family.MASQUE,
            Protocol.MASQUE,
            if (floor.ordinal >= Noize.FIREWALL.ordinal) floor else Noize.FIREWALL,
            http2 = true,
            fragment = true,
            budgetMs = HARDENED_MS,
            overhead = 1.15,
        )
        // Core 2.4.0 gool = WireGuard inside MASQUE: it uses the MASQUE identity
        // and the MASQUE gateway cache, so it belongs to THIS lane.
        val gool = Tactic(
            "gool",
            Family.MASQUE,
            Protocol.GOOL,
            if (dpi == DpiClass.OPEN) Noize.LIGHT else Noize.AGGRESSIVE,
            http2 = h2Lead,
            budgetMs = TWO_HOP_MS,
            overhead = 1.25,
        )
        val mim = Tactic(
            "mim",
            Family.MASQUE,
            Protocol.MIM,
            if (dpi == DpiClass.OPEN) Noize.LIGHT else Noize.FIREWALL,
            http2 = h2Lead,
            budgetMs = TWO_HOP_MS,
            overhead = 1.35,
        )
        val singleHop = when {
            // SNI-based DPI or worse: the fragmented TCP carrier right after the bare one.
            dpi == DpiClass.SNI_FILTERING || dpi == DpiClass.HOSTILE -> listOf(h2, h2Frag, h3)
            h2Lead -> listOf(h2, h3, h2Frag)
            else -> listOf(h3, h2, h2Frag)
        }
        val twoHop = if (goolClassic) listOf(mim) else listOf(gool, mim)
        return singleHop + twoHop
    }

    /** Every tactic this mode can offer, for validating a recalled memory. */
    fun allTactics(
        dpi: DpiClass,
        onMobileData: Boolean,
        quicReachable: Boolean? = null,
        goolClassic: Boolean = false,
        preferTcp: Boolean = false,
    ): List<Tactic> =
        tacticsFor(Family.WARP, dpi, onMobileData, quicReachable, goolClassic, preferTcp) +
            tacticsFor(Family.MASQUE, dpi, onMobileData, quicReachable, goolClassic, preferTcp)

    // ------------------------------------------------------------------- plan

    /**
     * The two lanes, both starting at once.
     *
     * The family with evidence on this network is listed first (and its proven
     * tactic leads it); without evidence the family the fingerprint favours is
     * listed first. Listing order only affects which engine is spawned first -
     * a few milliseconds - not the fairness of the race.
     */
    fun plan(
        dpi: DpiClass,
        onMobileData: Boolean,
        memory: SmartPlusMemory.Entry?,
        nowMs: Long,
        quicReachable: Boolean? = null,
        goolClassic: Boolean = false,
        /** 1.7.0: evidence that MASQUE over TCP should lead (an Iranian SIM). */
        preferTcp: Boolean = false,
    ): List<Lane> {
        val offered = allTactics(dpi, onMobileData, quicReachable, goolClassic, preferTcp)
        // 1.7.0 (same version): a remembered tactic may be one the Smart brain
        // hardened on the fly ("masque.h2+dpi"); DpiBrain.resolve rebuilds it.
        val remembered = memory?.takeIf { it.isProvenAt(nowMs) }
            ?.let { entry -> DpiBrain.resolve(entry.tacticId, offered) }
        val failedIds = memory?.failedIdsAt(nowMs).orEmpty()
        val warp = orderByEvidence(
            tacticsFor(Family.WARP, dpi, onMobileData, quicReachable, goolClassic, preferTcp),
            remembered,
            failedIds,
        )
        val masque = orderByEvidence(
            tacticsFor(Family.MASQUE, dpi, onMobileData, quicReachable, goolClassic, preferTcp),
            remembered,
            failedIds,
        )
        val masqueFirst = when {
            remembered != null && remembered.id !in failedIds -> remembered.family == Family.MASQUE
            else -> h2First(dpi, onMobileData, quicReachable, preferTcp)
        }
        val lanes = if (masqueFirst) listOf(Lane(masque), Lane(warp)) else listOf(Lane(warp), Lane(masque))
        return lanes.filter { it.tactics.isNotEmpty() }
    }

    /**
     * One serial ladder from the same tactics, for profiles that cannot race
     * (chains, Tor-fronted stages, a pinned endpoint). The two families are
     * interleaved so neither can starve the other: light tactics of both come
     * before heavy tactics of either.
     */
    fun ladder(
        dpi: DpiClass,
        onMobileData: Boolean,
        memory: SmartPlusMemory.Entry?,
        nowMs: Long,
        quicReachable: Boolean? = null,
        goolClassic: Boolean = false,
        preferTcp: Boolean = false,
    ): List<Tactic> {
        val lanes = plan(dpi, onMobileData, memory, nowMs, quicReachable, goolClassic, preferTcp).map { it.tactics }
        val out = mutableListOf<Tactic>()
        val longest = lanes.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) {
            for (lane in lanes) lane.getOrNull(i)?.let { out += it }
        }
        return out.distinctBy { it.id to it.family }
    }

    /**
     * Puts the remembered tactic at the front of its own lane and pushes anything
     * that failed here recently to the back, preserving the rest of the order.
     */
    private fun orderByEvidence(
        tactics: List<Tactic>,
        remembered: Tactic?,
        failedIds: Set<String>,
    ): List<Tactic> {
        val (failed, fresh) = tactics.partition { it.id in failedIds }
        val inLane = fresh.filter { remembered != null && it.id == remembered.id && it.family == remembered.family }
        // A hardened remembered tactic is not in the catalogue: it leads its own
        // family's lane all the same (unless it failed here since).
        val head = inLane.ifEmpty {
            listOfNotNull(
                remembered?.takeIf { r ->
                    r.id !in failedIds && tactics.isNotEmpty() && tactics.all { it.family == r.family }
                },
            )
        }
        val rest = fresh - inLane.toSet()
        // "It did not work an hour ago" is a reason to try it last, not never.
        return head + rest + failed
    }

    // ---------------------------------------------------------------- ranking

    /**
     * Cost of a proven route; LOWER is better.
     *
     * `4 × latency + ½ × (time to fetch 1 MB) + ½ × (time to send 256 KB)`, all in
     * milliseconds, times the tactic's [Tactic.overhead]. Latency carries the most
     * weight because it is what browsing, messaging and calls feel; throughput
     * breaks ties between routes of similar latency. A transfer that could not be
     * measured is charged as a slow one (1 MB in 4 s, 256 KB in 2 s), never as a
     * free one.
     */
    fun score(quality: TunnelQuality, overhead: Double): Double {
        val rtt = quality.rttMs.coerceAtLeast(1L).toDouble()
        val downMs = if (quality.downKbps > 0) 8_000_000.0 / quality.downKbps else 4_000.0
        val upMs = if (quality.upKbps > 0) 2_000_000.0 / quality.upKbps else 2_000.0
        return (rtt * 4.0 + downMs * 0.5 + upMs * 0.5) * overhead.coerceAtLeast(1.0)
    }

    /**
     * Orders proven routes best first. Measured routes by [score]; a route whose
     * measurement failed entirely goes after every measured one, in the order it
     * proved itself.
     */
    fun rank(proven: List<Pair<Tactic, TunnelQuality?>>): List<Tactic> {
        val (measured, unmeasured) = proven.partition { it.second != null }
        return measured.sortedBy { score(it.second!!, it.first.overhead) }.map { it.first } +
            unmeasured.map { it.first }
    }

    // ------------------------------------------------------------- eligibility

    /** Whether gool runs in its classic (WireGuard-in-WireGuard) form for [profile]. */
    fun goolClassic(profile: ConnectionProfile): Boolean = profile.goolMode == GoolMode.CLASSIC

    /**
     * Whether a profile can be raced at all. Excluded, each for a concrete reason:
     * a pinned endpoint or range (nothing to search), Zero Trust with the e-mail
     * code (one stdin, one code), and chained or Tor-fronted backends (the chain
     * owns the ports). Those run [ladder] serially instead.
     */
    fun eligible(profile: ConnectionProfile): Boolean =
        profile.endpointMode == EndpointMode.AUTO &&
            !(profile.hasTeam && profile.teamAuth == TeamAuth.EMAIL) &&
            profile.backend == TransportBackend.AETHER
}
