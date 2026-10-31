package studio.cluvex.aether.core

import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode
import studio.cluvex.aether.model.TeamAuth
import studio.cluvex.aether.model.TransportBackend

/**
 * SMART, the parallel implementation — what to try, in what order, and what may run
 * at the same time as what.
 *
 * Reached from `Protocol.AUTO` whenever [eligible] allows it; every other profile
 * gets [SmartAuto]'s ladder. It holds no Android types and does no I/O, so every
 * rule below is covered by `SmartPlusPlanTest` rather than by a field report.
 *
 * ## 1.3.1-r4: one button, not two
 *
 * This shipped as a separate selector entry called "Smart Plus" and that was the
 * wrong shape. It promised a mode and then silently behaved like Smart on every
 * chained backend, which is most of what this app offers; and the path that builds
 * a plan for a HAND-PICKED protocol treated `AUTO_PLUS` as if it were one, so a
 * chained session launched the engine with no protocol flag at all and could not
 * connect. Both problems come from the same mistake: making a capability into a
 * mode when it is a property of the backend. Smart now decides for itself.
 *
 * ## Why this is not simply Smart's ladder
 *
 * Smart ([SmartAuto]) is a LADDER: one attempt at a time, each with its own
 * timeout, until one passes. On the networks this app exists for that is up to
 * `60 + 60 + 60 + 150 = 330 s` before the user is told anything — the shape of
 * the field log in report #47, where four attempts failed over about five and a
 * half minutes.
 *
 * Smart Plus keeps that ladder intact and untouched, and adds two things to it:
 *
 *  1. **MEMORY.** What connected on THIS network is tried first next time on this
 *     network, as a single short attempt.
 *  2. **A RACE.** Independent strategies run side by side, and the first one that
 *     carries real traffic wins.
 *
 * The hand-picked protocols keep working exactly as before, and if the race finds
 * nothing it hands over to the ladder rather than failing: this can be worse than
 * the ladder in battery, never in outcome.
 *
 * ## Why exactly two lanes, and why these two
 *
 * This is the one place a straight copy of the reference implementation would
 * have produced a broken app, so it is written down.
 *
 * WhiteAesther races three CARRIERS — its engine, Psiphon, Tor — and its own
 * comment gives the rule: *"different carriers can be tried at once, while two
 * routes of one carrier cannot: there is one engine and one tor."* Aether has one
 * carrier (the WARP engine) and five protocols, so "race the protocols" looks
 * like the obvious translation. It is not, because of what the engine keeps on
 * disk. From `native/aether/aether/src/lib.rs`:
 *
 * ```
 *  --wg    -> aether.toml          + aether-lastconn.toml
 *  --gool  -> aether.toml          + aether-secondary.toml     (warp-in-warp)
 *  --masque-> aether-masque.toml   + aether-masque-lastconn.toml
 *  --mim   -> aether-masque.toml   + aether-masque-secondary.toml
 * ```
 *
 * Two engines sharing an identity file provision over each other's WARP device
 * registration and overwrite each other's remembered gateway — the very cache
 * whose loss this release already had to fix once. So the unit that can be raced
 * is not the protocol, it is the **identity family**: [Family.WARP] (WireGuard,
 * gool) and [Family.MASQUE] (MASQUE, MASQUE×2). Two families, two lanes, and
 * inside a lane the tactics run one after another exactly as the ladder does.
 *
 * That also bounds the cost honestly: two engine processes at once, not five.
 *
 * ## What the order is built from
 *
 * Evidence before inference, in this order:
 *
 *  1. What connected on this network before ([SmartPlusMemory]).
 *  2. What the network measurably looks like right now — reused from
 *     [SmartAuto.fingerprint], not re-derived here, so the two modes cannot drift
 *     apart in their reading of the same network.
 *  3. General knowledge: HTTP/2 before HTTP/3 on mobile data, because carriers
 *     have dropped QUIC for weeks at a time; the two-hop protocols last, because
 *     they are the slowest thing the engine can be asked to do.
 */
object SmartPlusPlan {

    /**
     * The engine state a route needs exclusively — see the class comment.
     *
     * The name of this enum is persisted inside [SmartPlusMemory] entries, so it
     * is part of a storage format: renaming a constant invalidates memories
     * rather than breaking them (an unknown id is simply not recalled).
     */
    enum class Family { WARP, MASQUE }

    /**
     * One concrete thing to try: a protocol plus the obfuscation it is tried
     * with, and how long it may take.
     *
     * [id] is stable and human-readable, because it goes into the diagnostics log
     * and into the per-network memory. It is NOT derived from the enum names, so
     * that renaming a [Noize] constant does not silently invalidate every stored
     * memory.
     */
    data class Tactic(
        val id: String,
        val family: Family,
        val protocol: Protocol,
        /** Obfuscation floor. The user's own choice wins when it is stronger. */
        val noize: Noize,
        val http2: Boolean = false,
        val fragment: Boolean = false,
        val ech: Boolean = false,
        val budgetMs: Long,
    ) {
        /**
         * Applies this tactic to the user's profile.
         *
         * Three things are deliberately NOT overridden, because they are the
         * user's explicit instructions rather than a default: a stronger
         * obfuscation than the tactic asks for, a pinned endpoint or range, and
         * the anti-DPI switches once they are on. A tactic can only ever add
         * hardening, never remove it.
         */
        fun applyTo(user: ConnectionProfile, bindPort: Int = 0): ConnectionProfile {
            val mergedNoize = if (user.noize.ordinal >= noize.ordinal) user.noize else noize
            return user.copy(
                protocol = protocol,
                noize = mergedNoize,
                masqueHttp2 = user.masqueHttp2 || (http2 && isMasqueFamily),
                fragment = user.fragment || fragment,
                ech = user.ech || ech,
                // TURBO per attempt: a lane's speed comes from moving on quickly,
                // not from one exhaustive scan. The user's own scan mode is used
                // for the confirming connect once a tactic has won.
                scanMode = ScanMode.TURBO,
                bindOverride = bindPort,
            )
        }

        private val isMasqueFamily: Boolean get() = family == Family.MASQUE

        /** For the log: what a reader needs to tell two attempts apart. */
        val label: String
            get() = buildString {
                append(protocol.name)
                append(" · noize=").append(noize.name.lowercase())
                if (http2) append(" · h2")
                if (fragment) append(" · fragment")
                if (ech) append(" · ech")
                append(" · ").append(budgetMs / 1000).append("s")
            }
    }

    /**
     * Tactics tried one after another, starting [startAfterMs] into the race.
     *
     * A lane is serial by necessity, not by choice: everything in it needs the
     * same engine identity files.
     */
    data class Lane(val tactics: List<Tactic>, val startAfterMs: Long)

    /** One phase of a plan. */
    sealed interface Step {
        /**
         * A single attempt on its own, on the ordinary session ports.
         *
         * Used for the remembered route: where there is real evidence, spending
         * two engines to re-prove it is waste. It is a bet, and a lost bet costs
         * [REMEMBERED_MS] — which is why the evidence expires and why a route that
         * failed here recently never gets this slot.
         */
        data class Remembered(val tactic: Tactic) : Step

        /** Lanes side by side; the first tactic to carry traffic wins. */
        data class Race(val lanes: List<Lane>) : Step
    }

    // ---------------------------------------------------------------- budgets

    /** A quick, narrowed attempt inside a lane. */
    const val LANE_MS = 60_000L

    /** The remembered route, on its own, before anything else starts. */
    const val REMEMBERED_MS = 45_000L

    /**
     * How long the second lane waits before it starts.
     *
     * Not zero, and not the reference implementation's 45 s either. The two lanes
     * here cost the same and neither is a fallback for the other, so a long
     * stagger would just be the old ladder with extra steps. What the delay buys
     * is the ordinary good case: when the engine still has a usable gateway
     * cached, the first lane is connected within a few seconds, and the second
     * engine — with its own scan, its own battery and its own traffic — never has
     * to start at all.
     */
    const val SECOND_LANE_AFTER_MS = 7_000L

    /** Longest a whole race may take before the mode gives up and hands over. */
    const val RACE_CEILING_MS = 3 * LANE_MS + SECOND_LANE_AFTER_MS

    // ---------------------------------------------------------------- tactics

    /**
     * The tactics each family offers, hardest-working last.
     *
     * The starting hardness comes from the fingerprint: on an open network the
     * bare protocol is tried first because it is the fastest and cleanest
     * session; on a hostile one starting bare only wastes a lane's first slot.
     */
    fun tacticsFor(family: Family, dpi: DpiClass, onMobileData: Boolean): List<Tactic> =
        when (family) {
            Family.WARP -> warpTactics(dpi)
            Family.MASQUE -> masqueTactics(dpi, onMobileData)
        }

    private fun warpTactics(dpi: DpiClass): List<Tactic> {
        val bare = Tactic(
            id = "wg",
            family = Family.WARP,
            protocol = Protocol.WIREGUARD,
            noize = Noize.OFF,
            budgetMs = LANE_MS,
        )
        val hardened = Tactic(
            id = "wg.noize",
            family = Family.WARP,
            protocol = Protocol.WIREGUARD,
            noize = when (dpi) {
                DpiClass.OPEN -> Noize.LIGHT
                DpiClass.SNI_FILTERING -> Noize.BALANCED
                DpiClass.UDP_THROTTLED -> Noize.GFW
                DpiClass.HOSTILE -> Noize.AGGRESSIVE
            },
            budgetMs = LANE_MS,
        )
        // Two hops, so never first: it pays for two handshakes and two scans.
        val gool = Tactic(
            id = "gool",
            family = Family.WARP,
            protocol = Protocol.GOOL,
            noize = if (dpi == DpiClass.OPEN) Noize.LIGHT else Noize.AGGRESSIVE,
            budgetMs = LANE_MS,
        )
        // On a network that throttles UDP, plain WireGuard is the least likely
        // thing in this family to survive - it is UDP and nothing else. Keep it,
        // but not in the slot that delays everything behind it.
        return when (dpi) {
            DpiClass.OPEN, DpiClass.SNI_FILTERING -> listOf(bare, hardened, gool)
            DpiClass.UDP_THROTTLED, DpiClass.HOSTILE -> listOf(hardened, gool, bare)
        }
    }

    private fun masqueTactics(dpi: DpiClass, onMobileData: Boolean): List<Tactic> {
        fun masque(id: String, noize: Noize, h2: Boolean, frag: Boolean, ech: Boolean) = Tactic(
            id = id,
            family = Family.MASQUE,
            protocol = Protocol.MASQUE,
            noize = noize,
            http2 = h2,
            fragment = frag,
            ech = ech,
            budgetMs = LANE_MS,
        )

        val floor = when (dpi) {
            DpiClass.OPEN -> Noize.OFF
            DpiClass.SNI_FILTERING -> Noize.FIREWALL
            DpiClass.UDP_THROTTLED -> Noize.LIGHT
            DpiClass.HOSTILE -> Noize.GFW
        }
        val harden = dpi != DpiClass.OPEN
        // h3 is the engine's own framing (QUIC); h2 is MASQUE over TCP.
        val h3 = masque("masque.h3", floor, h2 = false, frag = harden, ech = harden)
        val h2 = masque("masque.h2", floor, h2 = true, frag = harden, ech = harden)
        // Nested MASQUE last, always: two MASQUE hops is the slowest path here.
        val mim = Tactic(
            id = "mim",
            family = Family.MASQUE,
            protocol = Protocol.MIM,
            noize = if (dpi == DpiClass.OPEN) Noize.LIGHT else Noize.FIREWALL,
            http2 = false,
            budgetMs = LANE_MS,
        )
        // THE ONE RULE ABOUT FRAMING ORDER, and the only place that decides it.
        // h2 leads on mobile data because carriers have dropped QUIC for weeks at
        // a time, and on a network measured as UDP-throttled for the same reason
        // with evidence behind it. h3 leads elsewhere.
        val h2First = onMobileData || dpi == DpiClass.UDP_THROTTLED
        return if (h2First) listOf(h2, h3, mim) else listOf(h3, h2, mim)
    }

    /** Every tactic this mode can offer, for validating a recalled memory. */
    fun allTactics(dpi: DpiClass, onMobileData: Boolean): List<Tactic> =
        tacticsFor(Family.WARP, dpi, onMobileData) + tacticsFor(Family.MASQUE, dpi, onMobileData)

    // ------------------------------------------------------------------- plan

    /**
     * Builds the plan.
     *
     * @param dpi what the network measurably looks like, from [SmartAuto.fingerprint].
     * @param onMobileData cellular rather than Wi-Fi or a cable.
     * @param memory what this network is remembered for, or null.
     * @param nowMs monotonic-enough clock, injected so expiry is testable.
     */
    fun plan(
        dpi: DpiClass,
        onMobileData: Boolean,
        memory: SmartPlusMemory.Entry?,
        nowMs: Long,
    ): List<Step> {
        val offered = allTactics(dpi, onMobileData)
        // A memory of a tactic this fingerprint does not offer is a memory of
        // nothing: recall it by id, and let it go if the id is gone.
        val remembered = memory?.takeIf { it.isProvenAt(nowMs) }
            ?.let { entry -> offered.firstOrNull { it.id == entry.tacticId } }
        val failedIds = memory?.failedIdsAt(nowMs).orEmpty()

        val warp = orderByEvidence(tacticsFor(Family.WARP, dpi, onMobileData), remembered, failedIds)
        val masque = orderByEvidence(tacticsFor(Family.MASQUE, dpi, onMobileData), remembered, failedIds)

        // The family with the evidence starts at once; the other one follows.
        // With nothing to go on, the family the fingerprint favours goes first:
        // MASQUE rides TCP when the network throttles UDP, which is exactly when
        // WireGuard cannot.
        val masqueLeads = when {
            remembered != null -> remembered.family == Family.MASQUE
            else -> dpi == DpiClass.UDP_THROTTLED || dpi == DpiClass.HOSTILE
        }
        val lanes = if (masqueLeads) {
            listOf(Lane(masque, 0L), Lane(warp, SECOND_LANE_AFTER_MS))
        } else {
            listOf(Lane(warp, 0L), Lane(masque, SECOND_LANE_AFTER_MS))
        }
        val race = Step.Race(lanes.filter { it.tactics.isNotEmpty() })

        // Going first is only offered to a route with live evidence that did NOT
        // fail here recently. Both halves matter: the evidence stops the mode
        // spending two engines on a network it already knows, and the recent
        // failure stops it spending 45 s re-proving a route that has since died —
        // the trap the reference implementation documents as "a phone that had
        // connected at home then opened every session on a filtered mobile
        // network by waiting two and a half minutes".
        return if (remembered != null && remembered.id !in failedIds) {
            listOf(Step.Remembered(remembered.copy(budgetMs = REMEMBERED_MS)), race)
        } else {
            listOf(race)
        }
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
        val head = fresh.filter { remembered != null && it.id == remembered.id }
        val rest = fresh.filterNot { remembered != null && it.id == remembered.id }
        // Failed ones are kept, not dropped: "it did not work an hour ago" is a
        // reason to try it last, not a reason to make it unreachable.
        return head + rest + failed
    }

    /**
     * Whether a profile is eligible for the race at all.
     *
     * Three exclusions, each for a concrete reason rather than caution:
     *
     *  * **A pinned endpoint or range.** The user named the gateway; there is
     *    nothing to search and no reason to run two engines to reach one address.
     *  * **Zero Trust with the e-mail code.** That flow has the engine print a
     *    marker and wait on ITS OWN stdin; [LoginCodePrompt] can hold exactly one
     *    stdin, and asking the user for one code while two engines wait for it is
     *    a design that cannot be made correct.
     *  * **A chained or Tor-fronted backend.** Those bring the engine up as stage
     *    one behind another carrier, where the ports and the lifecycle belong to
     *    the chain. Racing there is a separate piece of work, and the app says so
     *    instead of quietly behaving like Smart.
     *
     * In every excluded case the caller runs the ordinary Smart ladder, so the
     * selection still works — it is just not raced.
     */
    fun eligible(profile: ConnectionProfile): Boolean =
        profile.endpointMode == EndpointMode.AUTO &&
            !(profile.hasTeam && profile.teamAuth == TeamAuth.EMAIL) &&
            profile.backend == TransportBackend.AETHER
}
