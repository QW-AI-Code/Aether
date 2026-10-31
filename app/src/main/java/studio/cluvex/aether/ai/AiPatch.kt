package studio.cluvex.aether.ai

import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.CoreLogLevel
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.IpVersion
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.Protocol
import studio.cluvex.aether.model.ScanMode

/** One setting change the model asked for. */
data class AiChange(
    /** A key from [AiPatch.WRITABLE]. Anything else is dropped. */
    val key: String,
    /** The requested value, as text. Parsed and validated by [AiPatch]. */
    val value: String,
    /** The model's own one-line reason, shown to the user before anything is applied. */
    val why: String = "",
)

/** The outcome of applying a set of changes. */
data class AiPatchResult(
    val profile: ConnectionProfile,
    /** Changes that were understood and written. */
    val applied: List<AiChange>,
    /**
     * Changes that were refused, each with why. Surfaced rather than swallowed: a
     * model that asks for something it is not allowed to change is exactly what a
     * user needs to be told about, not something to hide.
     */
    val rejected: List<Pair<AiChange, String>>,
)

/**
 * The bridge between what a language model says and what this app will do.
 *
 * ## The allow-list is the security boundary of the whole AI feature
 *
 * Items 2 and 4 of the AI brief mean a remote model's output can end up changing
 * this app's configuration. That model's input includes text from a log, and its
 * behaviour can be steered by anything that gets into its context - so it has to
 * be treated as an untrusted source, in a VPN app whose users are relying on it
 * for their safety. The blast radius is therefore fixed here, by construction,
 * and not by prompt wording: an instruction the model cannot express is an
 * instruction it cannot follow.
 *
 * [WRITABLE] contains only knobs that change HOW the tunnel obfuscates and paces
 * itself. Every one of the following is deliberately absent, and each for a
 * concrete reason rather than caution in general:
 *
 *  - `backend` - switching to plain Aether would silently disable the AI itself
 *    (see [AiGate]), so a single bad suggestion would make the feature look broken
 *    and un-fixable from inside the chat that broke it.
 *  - `upstreamProxy` - a writable proxy address is a writable "send all of this
 *    user's traffic through this host of my choosing". That is the single most
 *    dangerous string in the profile.
 *  - `routeDirect` / `routeBlock` - a `direct` rule takes a domain OUT of the
 *    tunnel. "Add bank.example.com to direct" reads like a performance tip and is
 *    a de-anonymisation.
 *  - `manualPeer` / `manualRange` - pins the tunnel to an attacker-chosen edge.
 *  - `proxyMode`, `splitMode`, `splitApps`, `blockedApps` - decide which apps are
 *    protected at all; a user's own deliberate choice, never a suggestion.
 *  - every Zero Trust field - organization credentials.
 *  - `lanShare` - exposes a proxy to the local network.
 *
 * A future contributor adding a key here should be able to answer "what is the
 * worst thing this becomes if the model is wrong or manipulated?" first.
 */
object AiPatch {

    /**
     * Profile keys the AI may propose, mapped to a short English description of
     * the accepted values. The map IS the contract handed to the model, so the
     * text is written to be read by one.
     */
    val WRITABLE: Map<String, String> = linkedMapOf(
        "protocol" to "AUTO (Smart: races two routes and remembers per network on the " +
            "plain Aether backend, one at a time elsewhere) | MASQUE | WIREGUARD | GOOL | " +
            "MIM (MASQUE inside MASQUE)",
        "scanMode" to "TURBO | BALANCED | THOROUGH | STEALTH | IRONCLAD",
        "ipVersion" to "V4 | V6 | BOTH",
        "noize" to "OFF | LIGHT | FIREWALL | BALANCED | GFW | AGGRESSIVE (anti-DPI obfuscation strength)",
        "mtu" to "one of 1280, 1380, 1420, 1500, 8500",
        "keepalive" to "one of 0, 10, 25, 45 (seconds; 0 = engine default)",
        "fragment" to "true | false (fragment the TLS ClientHello)",
        "fragmentSize" to "range like 16-32, or empty for the default",
        "fragmentDelay" to "range in ms like 2-10, or empty for the default",
        "ech" to "true | false (Encrypted Client Hello)",
        "masqueHttp2" to "true | false (carry MASQUE over HTTP/2)",
        "quickReconnect" to "true | false",
        "fastEndpointOnly" to "true | false (only reuse a cached endpoint if it is still fast)",
        "endpointMode" to "AUTO only (manual pinning is not AI-writable)",
        "exitRegion" to "empty for automatic, or an ISO-3166 alpha-2 code",
        "dnsServers" to "comma separated resolvers used inside the tunnel, or empty for the default",
        "routeSniff" to "true | false (read the site name from the handshake)",
        "routeSniffMs" to "0-4000 ms (0 = default)",
        "tlsGroups" to "e.g. X25519:P-256, or empty for the default",
        "validateSecs" to "0-3600 (0 = default)",
        "reconnectSecs" to "0-3600 (0 = default)",
        "noDataCheck" to "true | false",
        "noProfileRetry" to "true | false",
        "smartReconnect" to "true | false",
        "reconnectRetryLimit" to "one of 3, 5, 10, 15, 20",
        "ipv6LeakProtection" to "true | false",
        "killSwitch" to "true | false",
        "autoReprovision" to "true | false",
        "coreLogLevel" to "OFF | ERROR | WARN | INFO | DEBUG",
    )

    /** Applies [changes] to [profile], dropping anything invalid. */
    fun apply(profile: ConnectionProfile, changes: List<AiChange>): AiPatchResult {
        var next = profile
        val applied = mutableListOf<AiChange>()
        val rejected = mutableListOf<Pair<AiChange, String>>()

        val seen = HashSet<String>()
        for (change in changes) {
            val key = change.key.trim()
            if (key !in WRITABLE) {
                rejected += change to REASON_NOT_WRITABLE
                continue
            }
            // r4: one proposal, one value per key. A model that lists `mtu` twice
            // is contradicting itself, and applying both silently keeps whichever
            // came last - the user approved a card that showed both.
            if (!seen.add(key)) {
                rejected += change to REASON_DUPLICATE_KEY
                continue
            }
            val raw = change.value.trim()
            val updated = write(next, key, raw)
            if (updated == null) {
                rejected += change to REASON_INVALID_VALUE
                continue
            }
            // Compared against the ORIGINAL profile, not the running one: a
            // proposal that sets a value the user already has is redundant even if
            // an earlier change in the same batch touched something else.
            if (updated == next || write(profile, key, raw) == profile) {
                rejected += change to REASON_ALREADY_SET
                continue
            }
            next = updated
            applied += change
        }
        return AiPatchResult(next, applied, rejected)
    }

    // ---- r4: rejection reasons, as constants the verifier can switch on ------

    const val REASON_NOT_WRITABLE = "not an AI-writable setting"
    const val REASON_DUPLICATE_KEY = "the same setting appears twice in one proposal"
    const val REASON_INVALID_VALUE = "value is not one of the accepted values"
    const val REASON_ALREADY_SET = "already set to that value"

    /**
     * r4: the feedback handed back to the model when its proposal failed
     * verification, one line per refused change, with the user's REAL current
     * value next to it. Null when there is nothing to correct.
     *
     * This is what turns "the app quietly drops the button" into "the model
     * learns, before the user sees anything, that it was about to recommend the
     * setting the user already has" - the answer is then rewritten around a
     * different cause instead of shipping advice that makes the assistant look
     * like it never read the settings.
     */
    fun verificationFeedback(profile: ConnectionProfile, result: AiPatchResult): String? {
        if (result.rejected.isEmpty()) return null
        return buildString {
            result.rejected.forEach { (change, reason) ->
                val key = change.key.trim()
                append("- ").append(key).append(" = \"").append(change.value.trim()).append("\": ")
                when (reason) {
                    REASON_ALREADY_SET ->
                        append("REDUNDANT - the user ALREADY has ").append(key).append(" = ")
                            .append(read(profile, key))
                            .append(". Do not recommend it; if it matters, say it is already set.")
                    REASON_INVALID_VALUE ->
                        append("INVALID - accepted values for ").append(key).append(" are: ")
                            .append(WRITABLE[key] ?: "?").append('.')
                    REASON_DUPLICATE_KEY ->
                        append("DUPLICATE - pick one value for this setting.")
                    else ->
                        append("NOT CHANGEABLE BY YOU - explain where the user changes it by hand, or drop it.")
                }
                append('\n')
            }
        }.trimEnd()
    }

    /**
     * Writes one key, or returns null when the value is not acceptable.
     *
     * Every branch validates, and the numeric ones snap to the presets the UI
     * itself offers. That is not pedantry: the MTU picker only knows five values,
     * so an AI-written 1337 would leave the row showing a value the user can look
     * at but never get back to once they touch it.
     */
    private fun write(p: ConnectionProfile, key: String, raw: String): ConnectionProfile? =
        when (key) {
            "protocol" -> enumOf<Protocol>(raw)?.let { p.copy(protocol = it) }
            "scanMode" -> enumOf<ScanMode>(raw)?.let { p.copy(scanMode = it) }
            "ipVersion" -> enumOf<IpVersion>(raw)?.let { p.copy(ipVersion = it) }
            "noize" -> enumOf<Noize>(raw)?.let { p.copy(noize = it) }
            "coreLogLevel" -> enumOf<CoreLogLevel>(raw)?.let { p.copy(coreLogLevel = it) }
            "mtu" -> raw.toIntOrNull()
                ?.takeIf { it in ConnectionProfile.MTU_PRESETS }
                ?.let { p.copy(mtu = it) }
            "keepalive" -> raw.toIntOrNull()
                ?.takeIf { it in ConnectionProfile.KEEPALIVE_PRESETS }
                ?.let { p.copy(keepalive = it) }
            "reconnectRetryLimit" -> raw.toIntOrNull()
                ?.takeIf { it in RECONNECT_LIMITS }
                ?.let { p.copy(reconnectRetryLimit = it) }
            "routeSniffMs" -> raw.toIntOrNull()
                ?.takeIf { it in 0..4000 }
                ?.let { p.copy(routeSniffMs = it) }
            "validateSecs" -> raw.toIntOrNull()
                ?.takeIf { it in 0..3600 }
                ?.let { p.copy(validateSecs = it) }
            "reconnectSecs" -> raw.toIntOrNull()
                ?.takeIf { it in 0..3600 }
                ?.let { p.copy(reconnectSecs = it) }
            "fragment" -> boolOf(raw)?.let { p.copy(fragment = it) }
            "ech" -> boolOf(raw)?.let { p.copy(ech = it) }
            "masqueHttp2" -> boolOf(raw)?.let { p.copy(masqueHttp2 = it) }
            "quickReconnect" -> boolOf(raw)?.let { p.copy(quickReconnect = it) }
            "fastEndpointOnly" -> boolOf(raw)?.let { p.copy(fastEndpointOnly = it) }
            "routeSniff" -> boolOf(raw)?.let { p.copy(routeSniff = it) }
            "noDataCheck" -> boolOf(raw)?.let { p.copy(noDataCheck = it) }
            "noProfileRetry" -> boolOf(raw)?.let { p.copy(noProfileRetry = it) }
            "smartReconnect" -> boolOf(raw)?.let { p.copy(smartReconnect = it) }
            "ipv6LeakProtection" -> boolOf(raw)?.let { p.copy(ipv6LeakProtection = it) }
            "killSwitch" -> boolOf(raw)?.let { p.copy(killSwitch = it) }
            "autoReprovision" -> boolOf(raw)?.let { p.copy(autoReprovision = it) }
            "fragmentSize" -> raw.takeIf { it.isEmpty() || RANGE.matches(it) }
                ?.let { p.copy(fragmentSize = it) }
            "fragmentDelay" -> raw.takeIf { it.isEmpty() || RANGE.matches(it) }
                ?.let { p.copy(fragmentDelay = it) }
            "tlsGroups" -> raw.takeIf { it.length <= 128 && TLS_GROUPS.matches(it) }
                ?.let { p.copy(tlsGroups = it) }
            "dnsServers" -> raw.takeIf { it.isEmpty() || DNS_LIST.matches(it) }
                ?.let { p.copy(dnsServers = it) }
            // AUTO only: pinning an endpoint is the one endpoint decision that can
            // hand the session to a chosen host, so the model may reset the mode
            // to scanning but never point it anywhere.
            "endpointMode" -> raw.takeIf { it.equals("AUTO", true) }
                ?.let { p.copy(endpointMode = EndpointMode.AUTO) }
            "exitRegion" -> raw.uppercase()
                .takeIf { it.isEmpty() || REGION.matches(it) }
                ?.let { p.copy(exitRegion = it) }
            else -> null
        }

    /**
     * Keys the model is TOLD about but may not change.
     *
     * Without this the snapshot contained writable keys only, so the model gave
     * advice about a tunnel whose actual shape it could not see - it did not know
     * whether Psiphon or Tor was in the path, and "switch to WARP*2 for speed" is
     * wrong advice in a Tor mode, where no WARP tunnel exists at all.
     */
    private val READ_ONLY: List<String> = listOf(
        "backend",
        "proxyMode",
        "splitMode",
        "upstreamProxy",
        "torBridges",
        "torBridgeLines",
    )

    /**
     * The current value of every AI-visible key, as the prompt's context block.
     *
     * r4: every writable line also says whether the value is still the app's
     * DEFAULT and, when it is not, what the default is. A model that cannot tell
     * "the user chose 1280" from "1280 is what everyone has" cannot reason about
     * what to try next, and "reset X to default" is advice it could not give
     * correctly before because it did not know the default.
     */
    fun snapshot(profile: ConnectionProfile): String = buildString {
        val defaults = DEFAULTS
        WRITABLE.keys.forEach { key ->
            val current = read(profile, key)
            val default = read(defaults, key)
            append(key).append(" = ").append(current)
            if (current == default) append("   [default]") else append("   [changed by user; default: ").append(default).append(']')
            append('\n')
        }
        READ_ONLY.forEach { key ->
            append(key).append(" = ").append(read(profile, key)).append("   [read-only for you]").append('\n')
        }
    }

    /** A pristine profile, for the "[default]" annotations in [snapshot]. */
    private val DEFAULTS: ConnectionProfile by lazy { ConnectionProfile() }

    /** Reads one key as display text. Used by the prompt and by the AI hint icons. */
    fun read(profile: ConnectionProfile, key: String): String = when (key) {
        "protocol" -> profile.protocol.name
        "scanMode" -> profile.scanMode.name
        "ipVersion" -> profile.ipVersion.name
        "noize" -> profile.noize.name
        "coreLogLevel" -> profile.coreLogLevel.name
        "mtu" -> profile.mtu.toString()
        "keepalive" -> profile.keepalive.toString()
        "reconnectRetryLimit" -> profile.reconnectRetryLimit.toString()
        "routeSniffMs" -> profile.routeSniffMs.toString()
        "validateSecs" -> profile.validateSecs.toString()
        "reconnectSecs" -> profile.reconnectSecs.toString()
        "fragment" -> profile.fragment.toString()
        "ech" -> profile.ech.toString()
        "masqueHttp2" -> profile.masqueHttp2.toString()
        "quickReconnect" -> profile.quickReconnect.toString()
        "fastEndpointOnly" -> profile.fastEndpointOnly.toString()
        "routeSniff" -> profile.routeSniff.toString()
        "noDataCheck" -> profile.noDataCheck.toString()
        "noProfileRetry" -> profile.noProfileRetry.toString()
        "smartReconnect" -> profile.smartReconnect.toString()
        "ipv6LeakProtection" -> profile.ipv6LeakProtection.toString()
        "killSwitch" -> profile.killSwitch.toString()
        "autoReprovision" -> profile.autoReprovision.toString()
        "fragmentSize" -> profile.fragmentSize.ifBlank { "(default)" }
        "fragmentDelay" -> profile.fragmentDelay.ifBlank { "(default)" }
        "tlsGroups" -> profile.tlsGroups.ifBlank { "(default)" }
        "dnsServers" -> profile.dnsServers.ifBlank { "(default)" }
        "endpointMode" -> profile.endpointMode.name
        "exitRegion" -> profile.exitRegion.ifBlank { "(automatic)" }
        // Read-only context: the model is told about these so its advice makes
        // sense, and cannot write them (they are absent from WRITABLE).
        "backend" -> profile.backend.pipelineLabel
        // Tor is read-only context, deliberately, and the reason is not the usual
        // "an attacker could redirect the tunnel" one. `torBridges` and
        // `torBridgeLines` are how a user on a censored network gets Tor to work at
        // all; a model that turns bridges off - because the throughput advice it
        // was asked for is technically correct - takes away the only thing keeping
        // that user connected, and it would happen while they are reading about
        // something else. Whether to use bridges stays a human decision.
        "torBridges" -> profile.torBridges.name
        "torBridgeLines" -> if (profile.torBridgeLines.isBlank()) {
            "(none - the engine fetches bridges itself)"
        } else {
            "(${profile.sanitizedBridges().size} own bridge line(s))"
        }
        "proxyMode" -> profile.proxyMode.toString()
        "splitMode" -> profile.splitMode.name
        "upstreamProxy" -> if (profile.upstreamProxy.isBlank()) "(none)" else "(set)"
        else -> "(unknown)"
    }

    private inline fun <reified T : Enum<T>> enumOf(raw: String): T? =
        enumValues<T>().firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    private fun boolOf(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true", "on", "yes", "1", "enable", "enabled" -> true
        "false", "off", "no", "0", "disable", "disabled" -> false
        else -> null
    }

    private val RECONNECT_LIMITS = listOf(3, 5, 10, 15, 20)
    private val RANGE = Regex("^\\d{1,5}-\\d{1,5}$")
    private val TLS_GROUPS = Regex("^[A-Za-z0-9:_\\-]*$")
    private val DNS_LIST = Regex("^[0-9a-fA-F.:,\\s\\[\\]]+$")
    private val REGION = Regex("^[A-Z]{2}$")
}
