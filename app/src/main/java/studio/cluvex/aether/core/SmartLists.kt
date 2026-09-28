package studio.cluvex.aether.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.ConnectionState
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.4.0: the two built-in rule lists behind "Bypass Iranian sites" and "Block
 * ads and trackers", and the weekly refresh that keeps them current.
 *
 * ## Where the lists come from
 *
 * A seed copy of each list ships in `assets/routing/`:
 *
 *  * `iran_cidr.txt`    Iranian IPv4 blocks (herrbischoff/country-ip-blocks);
 *  * `iran_domains.txt` `ir` (every .ir name) plus Iranian services elsewhere;
 *  * `ads_domains.txt`  a short, conservative ad/tracker seed.
 *
 * The first time a connected session uses one of the options, and every seven
 * days after that, a fresh copy is downloaded THROUGH THE TUNNEL (the SOCKS5
 * listener of the running session, never the bare uplink, so the refresh is as
 * private as the browsing it serves) from:
 *
 *  * [IRAN_CIDR_URL] - herrbischoff's `ipv4/ir.cidr`;
 *  * [ADS_URL]       - HaGeZi's "Light" DNS blocklist (~80k names, built not to
 *                      break sign-ins, payments or stores).
 *
 * A download is validated (size, entry count, grammar) before it replaces the
 * previous copy, and it takes effect on the NEXT connect: a list is never
 * swapped under a running session.
 *
 * ## Where the lists go
 *
 *  * Engine sessions: a routes file (`[block]` / `[direct]`) handed over with
 *    `AETHER_ROUTES_FILE` ([engineRoutesFile]); the user's own rules keep
 *    travelling as `--route-block` / `--route-direct` and the engine merges both.
 *  * `Aether -> Psiphon`: the engine never sees the user's destinations there
 *    (#66), so the rules - user + built-in - go to
 *    [studio.cluvex.aether.transport.PsiphonSocksFront] instead ([frontRules]).
 *
 * ## Zero-leak sharing
 *
 * While LAN sharing is on, NO direct rule is applied anywhere - the Iranian
 * bypass included - for exactly the reason given in [ShareLeakGuard] point 1.
 * Ad blocking is unaffected: blocking never sends anything outside the tunnel.
 */
object SmartLists {

    private const val TAG = "SmartLists"

    private const val DIR = "routing"
    const val IRAN_CIDR = "iran_cidr.txt"
    const val IRAN_DOMAINS = "iran_domains.txt"
    const val ADS = "ads_domains.txt"
    private const val ROUTES_FILE = "smart_routes.txt"

    const val IRAN_CIDR_URL = "https://raw.githubusercontent.com/herrbischoff/country-ip-blocks/master/ipv4/ir.cidr"
    const val ADS_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/light.txt"

    private const val REFRESH_EVERY_MS = 7L * 24 * 60 * 60 * 1000
    private const val RETRY_AFTER_FAILURE_MS = 6L * 60 * 60 * 1000
    private const val FIRST_RETRY_MS = 30L * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024

    /** A download with fewer valid entries than this is refused as truncated or wrong. */
    private const val MIN_IRAN_CIDRS = 800
    private const val MIN_AD_DOMAINS = 5_000

    private const val PREFS = "aether_smart_lists"

    @Volatile private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)

    /** What the last started session asked for; the refresh only fetches those. */
    @Volatile private var wantIran = false
    @Volatile private var wantAds = false

    /**
     * Idempotent. Keeps the application context and starts the weekly refresh,
     * which runs once per Connected state while a list is due.
     */
    fun attach(context: Context) {
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            app = context.applicationContext
        }
        scope.launch {
            AetherController.state.collect { state ->
                if (state is ConnectionState.Connected) refreshIfDue(state.socksAddr)
            }
        }
    }

    // ------------------------------------------------------------ building

    /** Whether [profile] asks the engine for any built-in list at all. */
    fun wantsLists(profile: ConnectionProfile): Boolean = profile.blockAds || profile.bypassIran

    /** The Iranian bypass is a DIRECT rule: suspended while sharing (zero leak). */
    fun smartDirectActive(profile: ConnectionProfile): Boolean = profile.bypassIran && !profile.lanShare

    /**
     * Writes the built-in lists [profile] asks for into a routes file for the
     * engine and returns it, or null when there is nothing to hand over.
     *
     * Not for the chained Psiphon stage: that engine only carries Psiphon's own
     * server connections, so the lists go to the front instead ([frontRules]).
     */
    fun engineRoutesFile(workingDir: File, profile: ConnectionProfile): File? {
        val target = File(File(workingDir, DIR), ROUTES_FILE)
        if (profile.enginePsiphon || !wantsLists(profile)) {
            target.delete()
            return null
        }
        noteWanted(profile)
        val (block, direct) = builtIn(profile)
        if (block.isEmpty() && direct.isEmpty()) {
            target.delete()
            return null
        }
        return try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$ROUTES_FILE.tmp")
            tmp.bufferedWriter().use { out ->
                out.write("# Aether 1.4.0 built-in routing lists. Regenerated on every connect.\n")
                out.write("[block]\n")
                block.forEach { out.write(it); out.write("\n") }
                out.write("[direct]\n")
                direct.forEach { out.write(it); out.write("\n") }
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) return null
            }
            DiagnosticsLog.i(
                TAG,
                "Built-in routing lists for the engine: ${block.size} block, ${direct.size} direct" +
                    (if (profile.bypassIran && profile.lanShare) " (Iranian bypass suspended while sharing)" else ""),
            )
            target
        } catch (e: Exception) {
            DiagnosticsLog.w(TAG, "Could not write the routes file: ${e.message}")
            null
        }
    }

    /**
     * The complete rule set for the Psiphon front: the user's own rules plus the
     * built-in lists, with every direct rule dropped while sharing.
     */
    fun frontRules(profile: ConnectionProfile): RouteRules {
        noteWanted(profile)
        val (builtBlock, builtDirect) = builtIn(profile)
        val block = StringBuilder()
        profile.sanitizedRules(profile.routeBlock).forEach { block.append(it).append('\n') }
        builtBlock.forEach { block.append(it).append('\n') }
        val direct = StringBuilder()
        if (!profile.lanShare) {
            profile.sanitizedRules(profile.routeDirect).forEach { direct.append(it).append('\n') }
            builtDirect.forEach { direct.append(it).append('\n') }
        }
        val rules = RouteRules.parse(block.toString(), direct.toString())
        if (!rules.isEmpty) {
            DiagnosticsLog.i(
                TAG,
                "Psiphon front routing: ${rules.blockCount} block, ${rules.directCount} direct" +
                    (if (profile.lanShare && (profile.bypassIran || profile.routeDirect.isNotBlank()))
                        " (direct rules suspended while sharing)" else ""),
            )
        }
        return rules
    }

    private fun noteWanted(profile: ConnectionProfile) {
        wantIran = profile.bypassIran
        wantAds = profile.blockAds
    }

    /** The built-in entries [profile] asks for: (block, direct). */
    private fun builtIn(profile: ConnectionProfile): Pair<List<String>, List<String>> {
        val block = if (profile.blockAds) entries(ADS, Kind.DOMAIN) else emptyList()
        val direct = if (smartDirectActive(profile)) {
            entries(IRAN_DOMAINS, Kind.DOMAIN) + entries(IRAN_CIDR, Kind.CIDR)
        } else {
            emptyList()
        }
        return block to direct
    }

    private enum class Kind { DOMAIN, CIDR }

    /** A list's entries: the downloaded copy when there is a valid one, else the seed. */
    private fun entries(name: String, kind: Kind): List<String> {
        val ctx = app
        val downloaded = ctx?.let { File(File(it.filesDir, DIR), name) }
        if (downloaded != null && downloaded.isFile) {
            val list = runCatching { downloaded.inputStream().use { sanitize(it, kind) } }.getOrNull()
            if (!list.isNullOrEmpty()) return list
        }
        if (ctx == null) return emptyList()
        return runCatching { ctx.assets.open("$DIR/$name").use { sanitize(it, kind) } }
            .onFailure { DiagnosticsLog.w(TAG, "Bundled list $name is missing: ${it.message}") }
            .getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- refresh

    private fun refreshIfDue(socksAddr: String) {
        val ctx = app ?: return
        if (!wantIran && !wantAds) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val jobs = buildList {
            if (wantIran && due(prefs.getLong("ok_$IRAN_CIDR", 0), prefs.getLong("try_$IRAN_CIDR", 0), now)) {
                add(Triple(IRAN_CIDR, IRAN_CIDR_URL, Kind.CIDR))
            }
            if (wantAds && due(prefs.getLong("ok_$ADS", 0), prefs.getLong("try_$ADS", 0), now)) {
                add(Triple(ADS, ADS_URL, Kind.DOMAIN))
            }
        }
        if (jobs.isEmpty() || !refreshing.compareAndSet(false, true)) return
        val proxy = parseSocks(socksAddr) ?: run {
            refreshing.set(false)
            return
        }
        scope.launch {
            try {
                for ((name, url, kind) in jobs) {
                    prefs.edit().putLong("try_$name", System.currentTimeMillis()).apply()
                    val count = runCatching { download(ctx, name, url, kind, proxy) }
                        .onFailure { DiagnosticsLog.w(TAG, "Refreshing $name through the tunnel failed: ${it.message}") }
                        .getOrNull()
                    if (count != null) {
                        prefs.edit().putLong("ok_$name", System.currentTimeMillis()).apply()
                        DiagnosticsLog.i(TAG, "Refreshed $name through the tunnel: $count entries (used from the next connect).")
                    }
                }
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * Due once a week. A list that has NEVER been fetched (the seed is all there
     * is) is retried after [FIRST_RETRY_MS]; a failed weekly refresh waits
     * [RETRY_AFTER_FAILURE_MS] so a server that refuses GitHub is not asked on
     * every reconnect.
     */
    private fun due(lastOk: Long, lastTry: Long, now: Long): Boolean {
        if (now - lastOk < REFRESH_EVERY_MS) return false
        val backoff = if (lastOk == 0L) FIRST_RETRY_MS else RETRY_AFTER_FAILURE_MS
        return now - lastTry >= backoff
    }

    private fun parseSocks(addr: String): Proxy? {
        val colon = addr.lastIndexOf(':')
        if (colon <= 0) return null
        val host = addr.substring(0, colon).removePrefix("[").removeSuffix("]")
        val port = addr.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
    }

    /** Downloads, validates and installs one list; returns its entry count. */
    private fun download(ctx: Context, name: String, url: String, kind: Kind, proxy: Proxy): Int {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Aether")
        }
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val entries = BoundedInput(conn.inputStream, MAX_DOWNLOAD_BYTES).use { sanitize(it, kind) }
            val minimum = if (kind == Kind.CIDR) MIN_IRAN_CIDRS else MIN_AD_DOMAINS
            if (entries.size < minimum) error("only ${entries.size} valid entries (need $minimum)")
            val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
            val tmp = File(dir, "$name.download")
            tmp.bufferedWriter().use { out -> entries.forEach { out.write(it); out.write("\n") } }
            val target = File(dir, name)
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("could not install the new copy")
            }
            return entries.size
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ grammar

    private val DOMAIN = Regex("^[a-z0-9_]([a-z0-9_-]{0,62}\\.)*[a-z0-9_-]{1,63}$")
    private val CIDR4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$")

    /**
     * Keeps only well-formed entries, so a hostile or broken download can never
     * inject a rule of another kind (`port:`, `regexp:`, `private`, a section
     * header) into the routes file. Accepts plain lists, `#` comments and the
     * hosts-file form (`0.0.0.0 name`).
     */
    private fun sanitize(input: InputStream, kind: Kind): List<String> {
        val out = LinkedHashSet<String>()
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                var line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                if (kind == Kind.DOMAIN) {
                    val parts = line.split(Regex("\\s+"))
                    line = (if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) parts[1] else parts[0])
                        .trimStart('*', '.').trimEnd('.').lowercase(Locale.ROOT)
                    if (line == "localhost" || !DOMAIN.matches(line)) continue
                    out += line
                } else {
                    val m = CIDR4.matchEntire(line) ?: continue
                    val octets = (1..4).map { m.groupValues[it].toInt() }
                    val bits = m.groupValues[5].toInt()
                    if (octets.any { it > 255 } || bits !in 8..32) continue
                    out += line
                }
            }
        }
        return out.toList()
    }

    /** Refuses to read past [limit] bytes, so a runaway download cannot fill the disk. */
    private class BoundedInput(private val inner: InputStream, private val limit: Long) : InputStream() {
        private var read = 0L
        override fun read(): Int {
            if (read >= limit) throw java.io.IOException("download larger than $limit bytes")
            val b = inner.read()
            if (b >= 0) read++
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read >= limit) throw java.io.IOException("download larger than $limit bytes")
            val n = inner.read(b, off, minOf(len.toLong(), limit - read).toInt())
            if (n > 0) read += n
            return n
        }
        override fun close() = inner.close()
    }
}
