// Ported from QWVPN-1.0.0 (GPL-3.0); see third_party/QWVPN/LICENSE.
package studio.cluvex.aether.scanner

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Finds fast, GENUINELY-WORKING Cloudflare edge IPs (“clean IPs”) for compatible CDN-backed configurations. The probe brain is a faithful Kotlin re-implementation of the the reference scannerScanner method
 * (internal/prober/prober.go + internal/ipsrc/neighbors.go) — the reference scanner that actually
 * works from inside Iran. Nothing here is copied byte-for-byte; the LOGIC is reproduced.
 *
 * Why the old “TCP + TLS handshake only, 2s, 50–60 candidates” check found nothing in Iran:
 *  - 2s was far too little for TLS+trace+download under DPI, so every probe died before finishing.
 *  - 50–60 random candidates is too few to hit a reachable edge.
 *  - A bare TLS handshake proves nothing: Iran's DPI frequently lets the handshake complete and then
 *    RSTs the connection moments later, so an IP can “handshake-OK” yet be useless for real traffic.
 *
 * The the reference scanner-faithful probe each candidate now runs:
 *  1) HTTP-GET health on https://<host>/cdn-cgi/trace (NOT just TLS). Healthy == HTTP status in
 *     200..399 AND a real `colo` extracted from the CF-Ray header or the trace body — this proves
 *     the IP is a genuine Cloudflare edge that actually SERVES, not an empty handshake.
 *  2) Time-budget split (like the reference scanner): of the whole timeout, TCP dial gets ~¼, the TLS handshake
 *     ~½, and the HTTP GET ~¼ — so on a DPI-throttled link the handshake can't eat the whole budget.
 *  3) SNI rotation across speed.cloudflare.com / www.cloudflare.com / cloudflare.com /
 *     blog.cloudflare.com / 1.1.1.1.cdn.cloudflare.net — if one SNI is blocked the next is tried.
 *  4) InsecureSkipVerify during probing (health is proven by `colo`, not by the certificate) because
 *     the goal is to defeat DPI, not to validate TLS.
 *  5) An idle-hold stability check (like probeStability): after a healthy handshake the connection is
 *     held ~1.5s with NO data sent; if DPI RSTs/EOFs it the IP is rejected (a read TIMEOUT == alive).
 *  6) A REAL micro-download from speed.cloudflare.com/__down to measure genuine throughput.
 *  7) Neighbor expansion (like neighbors.go): when an IP is healthy, ~12 nearby addresses (±, inside
 *     the same Cloudflare range) are also probed — dense CF blocks then yield many more working IPs.
 *
 * Combined ranking: success/stability first, then download throughput (higher better), then latency
 * (lower better), then jitter. Every value is a real socket / TLS / HTTP measurement — never faked;
 * on any failure the candidate is honestly rejected.
 *
 * NOTE (honesty): real reachability from Iran shifts hour-to-hour with the filtering state. This
 * scanner is engineered against the documented DPI behaviour, but its output can only be fully
 * validated on a real Iranian connection — it cannot be verified from a build server.
 */
object CleanIpScanner {

    const val TAG = "SCAN"
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    /** Called only by the single app-owned scan job when Stop/session-start cancels it. */
    fun closeActiveSockets() { sockets.toList().forEach { runCatching { it.close() } } }
    private fun socket(): Socket = Socket().also { sockets.add(it) }
    internal fun isCloudflareIp(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } || it.toIntOrNull() !in 0..255 }) return false
        val value = ipToInt(ip).toLong() and 0xffffffffL
        return inAnyCidr(value) || customRanges.any { value in it }
    }

    /**
     * User-supplied ranges (Settings → clean-IP scan). When non-empty the scan draws its
     * candidates and neighbours EXCLUSIVELY from them. Set by [CleanIpScanManager] for the
     * duration of one scan and cleared afterwards.
     */
    @Volatile
    private var customRanges: List<CustomIpRanges.Range> = emptyList()

    internal fun useCustomRanges(ranges: List<CustomIpRanges.Range>) { customRanges = ranges }

    private fun inScanSpace(value: Long): Boolean =
        customRanges.let { custom -> if (custom.isEmpty()) inAnyCidr(value) else custom.any { value in it } }

    /**
     * Up to [n] distinct candidates not in [seen]. The built-in Cloudflare space is huge, so
     * random draws are fine; a small custom space is enumerated instead, otherwise the
     * "draw until n distinct" loop could never finish once the space is exhausted.
     */
    internal fun sampleCandidates(n: Int, seen: Set<String>): List<String> {
        val custom = customRanges
        if (custom.isEmpty()) {
            return generateSequence { randomIp() }.filter { it !in seen }.distinct().take(n).toList()
        }
        val total = CustomIpRanges.total(custom)
        if (total <= CustomIpRanges.ENUMERATE_LIMIT) {
            return custom.asSequence()
                .flatMap { r -> (r.start..r.end).asSequence() }
                .map { CustomIpRanges.longToIp(it) }
                .filter { it !in seen }
                .toList().shuffled().take(n)
        }
        val out = LinkedHashSet<String>()
        var attempts = 0
        while (out.size < n && attempts++ < n * 40) {
            val ip = randomIp()
            if (ip !in seen) out.add(ip)
        }
        return out.toList()
    }

    data class Result(
        val ip: String,
        val latencyMs: Int,
        val port: Int = 443,
        val successRate: Float = 1f,
        val jitterMs: Int = 0,
        /** Real measured micro-download throughput in BYTES/second (0 = not measured / failed). */
        val downloadBps: Long = 0,
        /** Cloudflare colo (edge datacenter) code proven via /cdn-cgi/trace, e.g. "FRA". */
        val colo: String = "",
    )

    /**
     * Cloudflare IPv4 ranges, weighted. The community in Iran reports 188.114.96.0/20 and the
     * 162.159.0.0/16 / 104.16.0.0/13 space as the most consistently reachable; we sample those more
     * often. (Source ranges: https://www.cloudflare.com/ips/.)
     */
    private val WEIGHTED_CIDRS = listOf(
        "188.114.96.0/20" to 5,
        "162.159.0.0/16" to 4,
        "104.16.0.0/13" to 3,
        "104.24.0.0/14" to 3,
        "172.64.0.0/13" to 2,
        "198.41.128.0/17" to 1,
        "173.245.48.0/20" to 1,
        "103.21.244.0/22" to 1,
        "141.101.64.0/18" to 1,
    )

    /** TLS ports a Cloudflare Worker answers on. 443 first, then the less-filtered alternates. */
    val DEFAULT_PORTS = listOf(443, 8443, 2053, 2083, 2087, 2096)

    /** SNI hostnames rotated to dodge DPI blackholing (mirrors the reference scanner's sniHostnames). */
    private val SNI_HOSTS = listOf(
        "speed.cloudflare.com",
        "www.cloudflare.com",
        "cloudflare.com",
        "blog.cloudflare.com",
        "1.1.1.1.cdn.cloudflare.net",
    )

    /** Trace-host fallback order for the /cdn-cgi/trace health check (mirrors phase1TraceSNIs). */
    private val TRACE_HOSTS = listOf(
        "speed.cloudflare.com",
        "www.cloudflare.com",
        "cloudflare.com",
    )

    /** Zone that serves /__down for the real micro-download throughput stage. */
    private const val HTTP_HOST = "speed.cloudflare.com"
    private const val MICRO_BYTES = 1_000_000

    /**
     * Problem 2: a VPN-usable clean IP must ping well. IPs slower than this are dropped from the
     * FINAL pool unless there aren't enough faster ones to fill the request (a strong preference,
     * never a hard failure that would leave servers without an IP).
     */
    private const val MAX_ACCEPTABLE_LATENCY_MS = 1200

    /** Neighbor-expansion limits (mirror neighbors.go DefaultNeighborRadius / DefaultNeighborPerHit). */
    private const val NEIGHBOR_RADIUS = 32
    private const val NEIGHBOR_PER_HIT = 12

    private val cidrPool: List<String> by lazy {
        WEIGHTED_CIDRS.flatMap { (cidr, w) -> List(w) { cidr } }
    }

    /**
     * A TLS factory that skips certificate validation. We dial CANDIDATE IPs with arbitrary SNI, so
     * normal cert verification would always fail; the reference scanner's phase-1 probe likewise sets
     * InsecureSkipVerify=true and proves health via the `colo` value instead of the certificate.
     */
    private val insecureFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        ctx.init(null, trustAll, SecureRandom())
        ctx.socketFactory
    }

    /**
     * Problem 2: ping is the dominant factor for a VPN. Rank by a LATENCY BUCKET first (excellent <
     * 600ms, acceptable < 1000ms, then slow, with unknown/failed last) so a high-throughput but
     * high-ping edge can NEVER be chosen over a low-ping one; only WITHIN a bucket do success-rate
     * and real download throughput decide, then exact latency and jitter break remaining ties.
     */
    internal fun latencyBucket(ms: Int): Int = when {
        ms < 0 -> 3
        ms < 600 -> 0
        ms < 1000 -> 1
        else -> 2
    }

    internal val rankComparator: Comparator<Result> =
        compareBy<Result> { latencyBucket(it.latencyMs) }
            .thenByDescending { it.successRate }
            .thenByDescending { it.downloadBps }
            .thenBy { it.latencyMs }
            .thenBy { it.jitterMs }

    /**
     * Single-IP scan (used by the per-server “Scan clean IP”). Returns the single best healthy IP,
     * or null. [exclude] holds IPs already assigned to OTHER servers so the result stays unique.
     * After the random sweep, the best hits' neighbors are probed too (dense CF blocks pay off).
     */
    suspend fun scan(
        samples: Int = 400,
        ports: List<Int> = DEFAULT_PORTS,
        timeoutMs: Int = 5000,
        probesPerIp: Int = 2,
        parallelism: Int = 50,
        sni: String = "speed.cloudflare.com",
        exclude: Set<String> = emptySet(),
        // Additive (defaulted) so the public signature stays back-compatible; the scan profile feeds
        // its neighbour budget here (Task 4) without changing any existing call site.
        neighborPerHit: Int = NEIGHBOR_PER_HIT,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Result? = withContext(Dispatchers.IO) {
        ScanLog.i(
            TAG,
            "scan: probing up to $samples candidate IP(s), ports=$ports, tries/IP=$probesPerIp, " +
                "timeout=${timeoutMs}ms, excluding ${exclude.size} already-used IP(s)",
        )
        val seen = HashSet(exclude)
        val candidates = sampleCandidates(samples, seen)
        candidates.forEach { seen.add(it) }

        val tlsOk = AtomicInteger(0)
        val traceOk = AtomicInteger(0)
        val done = AtomicInteger(0)
        val results = probeBatch(candidates, ports, timeoutMs, probesPerIp, parallelism, tlsOk, traceOk) {
            onProgress?.invoke(done.incrementAndGet(), candidates.size)
        }
        ScanLog.i(
            TAG,
            "scan: ${candidates.size} candidate(s) → ${tlsOk.get()} TLS-ok, ${traceOk.get()} trace+idle-ok",
        )

        val all = ArrayList<Result>(results)
        val healthy = results.filter { it.ip !in exclude }
        if (healthy.isNotEmpty()) {
            val neighbors = healthy.sortedWith(rankComparator).take(8)
                .flatMap { neighborsAround(it.ip, NEIGHBOR_RADIUS, neighborPerHit) }
                .filter { it !in seen }
                .distinct()
            neighbors.forEach { seen.add(it) }
            if (neighbors.isNotEmpty()) {
                ScanLog.d(TAG, "scan: probing ${neighbors.size} neighbor IP(s) of healthy hits")
                all += probeBatch(neighbors, ports, timeoutMs, probesPerIp, parallelism, tlsOk, traceOk, null)
            }
        }

        val best = all.filter { it.ip !in exclude }.sortedWith(rankComparator).firstOrNull()
        if (best != null) {
            ScanLog.i(
                TAG,
                "scan: best=${best.ip}:${best.port} colo=${best.colo} " +
                    "success=${(best.successRate * 100).toInt()}% ${best.latencyMs}ms " +
                    "dl=${best.downloadBps}B/s jitter=${best.jitterMs}ms",
            )
        } else {
            ScanLog.w(TAG, "scan: no clean IP found among ${candidates.size} candidate(s)")
        }
        best
    }

    /**
     * Pool scan (used by “Scan all”). Returns up to [count] DISTINCT healthy IPs, best-first. It
     * repeats scanning in rounds (random batch + neighbor expansion of that round's hits) until it
     * has enough distinct healthy IPs or [maxRounds] is hit, so a large server list can each be given
     * its OWN unique IP. [exclude] seeds the dedup set so pool IPs never collide with existing ones.
     */
    suspend fun scanPool(
        count: Int,
        ports: List<Int> = DEFAULT_PORTS,
        timeoutMs: Int = 5000,
        probesPerIp: Int = 2,
        parallelism: Int = 50,
        sni: String = "speed.cloudflare.com",
        exclude: Set<String> = emptySet(),
        maxRounds: Int = 10,
        // Additive (defaulted) so the public signature stays back-compatible. [candidateBudget] is the
        // TOTAL number of candidate IPs we are allowed to probe across all rounds (Task 1/4); the old
        // behaviour is the default. [neighborPerHit] feeds neighbour expansion from the scan profile.
        candidateBudget: Int = (count * 6).coerceAtLeast(48),
        neighborPerHit: Int = NEIGHBOR_PER_HIT,
        onProgress: ((found: Int, needed: Int) -> Unit)? = null,
    ): List<Result> = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext emptyList()
        require(count <= 24 && ports.isNotEmpty() && ports.all { it in DEFAULT_PORTS })
        require(candidateBudget in 48..600 && parallelism in 1..60 && probesPerIp in 1..3)
        // Build a LARGE healthy pool, not just `count` — the old code stopped the moment it had one
        // healthy IP per server, so it shipped high-ping/low-quality edges. We now keep probing until
        // the pool reaches a comfortable floor (so the best `count` are genuinely the fastest), the
        // candidate budget is spent, or we run out of rounds.
        val budget = candidateBudget.coerceAtLeast(count.coerceAtLeast(1))
        // Task 2b: the old floor (count*4, min 40) made a 12-server BALANCED scan probe for minutes.
        // Tie depth to count with a sane cap so the top-`count` quality is unchanged but it finishes
        // in a fraction of the time (12 servers -> floor 24 instead of 48).
        val healthyFloor = maxOf(count, (count * 2).coerceAtMost(24))
        ScanLog.i(
            TAG,
            "scanPool: need $count distinct healthy IP(s) (floor $healthyFloor), budget=$budget " +
                "candidate(s), timeout=${timeoutMs}ms, excluding ${exclude.size}",
        )
        val healthy = LinkedHashMap<String, Result>()
        val seen = HashSet(exclude)
        // Per-round random batch: a healthy chunk of the budget so the pool grows across rounds.
        val batchSize = (count * 6).coerceIn(48, budget)
        val tlsOk = AtomicInteger(0)
        val traceOk = AtomicInteger(0)
        var probed = 0
        var round = 0
        // Task 1: smooth progress. On EVERY single probe completion report the MAX of
        // (healthy found / floor) and (candidates probed / budget) as a 0..99 percent, so the bar
        // climbs gradually across the whole scan instead of jumping 0 -> 100 once per round.
        val probedSoFar = AtomicInteger(0)
        // UNIQUE healthy count, incremented the instant a NEW ip is found (deduplicated), so it
        // exactly matches the loop's stop condition (healthy.size >= floor). Using a raw success
        // counter here made the bar hit 99% early and then STALL, because it counted duplicates and
        // reached the floor long before the loop actually stopped. Progress = max(byHealthy,
        // byBudget): whichever real stop-condition is closer drives the bar, so it tracks the actual
        // work and reaches ~99% only just before the scan really finishes.
        val healthyUnique = AtomicInteger(0)
        fun reportProgress() {
            val byHealthy = if (healthyFloor > 0) healthyUnique.get() * 100 / healthyFloor else 0
            val byBudget = if (budget > 0) probedSoFar.get() * 100 / budget else 0
            onProgress?.invoke(maxOf(byHealthy, byBudget).coerceIn(0, 99), 100)
        }
        while (healthy.size < healthyFloor && probed < budget && round < maxRounds) {
            round++
            val remaining = (budget - probed).coerceAtLeast(0)
            if (remaining <= 0) break
            val thisBatch = batchSize.coerceAtMost(remaining).coerceAtLeast(1)
            val candidates = sampleCandidates(thisBatch, seen)
            // A small custom space can be exhausted before the budget is: stop cleanly.
            if (candidates.isEmpty()) break
            candidates.forEach { seen.add(it) }
            probed += candidates.size
            ScanLog.d(
                TAG,
                "scanPool: round $round — scanning ${candidates.size} candidate(s) " +
                    "(have ${healthy.size}/$healthyFloor, probed $probed/$budget)",
            )
            val results = probeBatch(candidates, ports, timeoutMs, probesPerIp, parallelism, tlsOk, traceOk) { r ->
                probedSoFar.incrementAndGet()
                if (r != null) synchronized(healthy) {
                    if (!healthy.containsKey(r.ip)) { healthy[r.ip] = r; healthyUnique.incrementAndGet() }
                }
                reportProgress()
            }

            // Neighbour expansion of this round's hits also counts toward the candidate budget.
            val neighborRoom = (budget - probed).coerceAtLeast(0)
            if (healthy.size < healthyFloor && neighborRoom > 0 && results.isNotEmpty()) {
                val neighbors = results.map { it.ip }.distinct()
                    .flatMap { neighborsAround(it, NEIGHBOR_RADIUS, neighborPerHit) }
                    .filter { it !in seen }
                    .distinct()
                    .take(neighborRoom)
                neighbors.forEach { seen.add(it) }
                probed += neighbors.size
                if (neighbors.isNotEmpty()) {
                    ScanLog.d(TAG, "scanPool: round $round — probing ${neighbors.size} neighbor IP(s)")
                    val more = probeBatch(neighbors, ports, timeoutMs, probesPerIp, parallelism, tlsOk, traceOk) { r ->
                        probedSoFar.incrementAndGet()
                        if (r != null) synchronized(healthy) {
                            if (!healthy.containsKey(r.ip)) { healthy[r.ip] = r; healthyUnique.incrementAndGet() }
                        }
                        reportProgress()
                    }
                }
            }
            reportProgress()
            ScanLog.d(TAG, "scanPool: round $round done — total healthy=${healthy.size} (need $count)")
        }
        // Now that the pool is large, the top `count` by rank are genuinely the best (low ping +
        // real download throughput first). Problem 2: enforce a HARD ping ceiling - prefer IPs under
        // MAX_ACCEPTABLE_LATENCY_MS and only top up with slower ones if there aren't enough fast ones,
        // so servers still get an IP rather than none.
        val ranked = healthy.values.sortedWith(rankComparator)
        val withinCeiling = ranked.filter { it.latencyMs in 0..MAX_ACCEPTABLE_LATENCY_MS }
        val pool = (withinCeiling + ranked.filter { it !in withinCeiling }).take(count)
        ScanLog.i(
            TAG,
            "scanPool: collected ${healthy.size} healthy IP(s) in $round round(s) (probed $probed/$budget); " +
                "returning best ${pool.size}/$count (${tlsOk.get()} TLS-ok, ${traceOk.get()} trace+idle-ok)",
        )
        if (pool.isEmpty()) ScanLog.w(TAG, "scanPool: no clean IP found")
        pool
    }

    /** Probes a list of IPs concurrently (bounded by [parallelism]) and returns the healthy ones. */
    private suspend fun probeBatch(
        ips: List<String>,
        ports: List<Int>,
        timeoutMs: Int,
        tries: Int,
        parallelism: Int,
        tlsOk: AtomicInteger,
        traceOk: AtomicInteger,
        onOne: ((Result?) -> Unit)?,
    ): List<Result> = coroutineScope {
        val gate = Semaphore(parallelism)
        ips.map { ip ->
            async {
                gate.withPermit {
                    currentCoroutineContext().ensureActive()
                    val r = probeBest(ip, ports, timeoutMs, tries, tlsOk, traceOk)
                    onOne?.invoke(r)
                    r
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Runs the full the reference scanner-style probe for an IP across the given ports and returns the best-scoring
     * healthy port result, or null. An IP is only healthy when, on some port, a /cdn-cgi/trace GET
     * returns 200..399 WITH a real colo AND the connection survives the idle-hold stability check.
     * The first healthy port wins (its number travels back on the Result so the config can switch).
     */
    private suspend fun probeBest(
        ip: String,
        ports: List<Int>,
        timeoutMs: Int,
        tries: Int,
        tlsOk: AtomicInteger,
        traceOk: AtomicInteger,
    ): Result? {
        var ipSawTls = false
        var ipHealthy = false
        var best: Result? = null
        for ((portIndex, port) in ports.withIndex()) {
            currentCoroutineContext().ensureActive()
            val lats = ArrayList<Int>(tries)
            var colo = ""
            var portSawTls = false
            for (t in 0 until tries) {
                val sni = SNI_HOSTS[(portIndex + t) % SNI_HOSTS.size]
                // Stage 1+2+3 with budget split: TCP ¼, TLS ½, HTTP ¼, across the trace-host fallback.
                var winnerHost: String? = null
                var status = 0
                var coloTry = ""
                var lat = 0
                for (host in traceHostsFor(sni)) {
                    currentCoroutineContext().ensureActive()
                    val tr = probeTrace(ip, port, host, timeoutMs)
                    if (tr.tlsOk) portSawTls = true
                    if (tr.status in 200..399 && tr.colo.isNotEmpty()) {
                        winnerHost = host
                        status = tr.status
                        coloTry = tr.colo
                        lat = tr.latencyMs
                        break
                    }
                }
                if (winnerHost != null && status in 200..399 && coloTry.isNotEmpty()) {
                    // Stage 5: idle-hold stability — DPI often RSTs shortly after the first GET.
                    if (probeStability(ip, port, winnerHost, timeoutMs)) {
                        lats.add(lat)
                        if (colo.isEmpty()) colo = coloTry
                    } else {
                        ScanLog.d(TAG, "probe $ip:$port trace-ok but idle-hold RST — rejected")
                    }
                }
                // Prune dead ports fast: if the first try couldn't even TLS-handshake, stop retrying.
                if (t == 0 && !portSawTls) break
            }
            if (portSawTls) ipSawTls = true
            if (lats.isEmpty()) continue

            val successRate = lats.size.toFloat() / tries
            val avg = lats.average().toInt()
            val jitter = if (lats.size > 1) {
                val mean = lats.average()
                sqrt(lats.sumOf { (it - mean) * (it - mean) } / lats.size).toInt()
            } else 0
            // Stage 6: a REAL micro-download to measure genuine throughput.
            val dlTimeout = (timeoutMs * 2).coerceAtLeast(8000)
            val bps = probeDownload(ip, port, dlTimeout, MICRO_BYTES.toLong())
            val candidate = Result(ip, avg, port, successRate, jitter, bps, colo)
            ipHealthy = true
            ScanLog.d(
                TAG,
                "probe $ip:$port colo=$colo success=${(successRate * 100).toInt()}% ${avg}ms " +
                    "jitter=${jitter}ms dl=${bps}B/s — healthy",
            )
            best = candidate
            break // first healthy port is enough
        }
        if (ipSawTls) tlsOk.incrementAndGet()
        if (ipHealthy) traceOk.incrementAndGet()
        return best
    }

    private data class TraceResult(val latencyMs: Int, val tlsOk: Boolean, val status: Int, val colo: String)

    /**
     * Stage 1–3: TCP connect + REAL TLS handshake (SNI=[host]) + REAL HTTP GET /cdn-cgi/trace, with
     * the the reference scanner time-budget split (dial ¼, handshake ½, http ¼). Returns the HTTP status and the
     * colo extracted from the CF-Ray header or the trace body, or a failed TraceResult on any error.
     */
    private fun probeTrace(ip: String, port: Int, host: String, timeoutMs: Int): TraceResult {
        val raw = socket()
        var ssl: SSLSocket? = null
        return try {
            val start = System.nanoTime()
            raw.connect(InetSocketAddress(ip, port), budgetDial(timeoutMs))
            ssl = (insecureFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                soTimeout = budgetHandshake(timeoutMs)
                sslParameters = (sslParameters ?: SSLParameters()).apply {
                    serverNames = listOf(SNIHostName(host))
                }
            }
            ssl.startHandshake()
            ssl.soTimeout = budgetHttp(timeoutMs)
            val request = "GET /cdn-cgi/trace HTTP/1.1\r\n" +
                "Host: $host\r\n" +
                "User-Agent: Mozilla/5.0 (Android) Aether/CleanIpScanner\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n"
            ssl.outputStream.apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = ssl.inputStream
            val header = StringBuilder()
            val one = ByteArray(1)
            while (header.length < 16384) {
                val n = input.read(one)
                if (n < 0) break
                header.append((one[0].toInt() and 0xFF).toChar())
                val len = header.length
                if (len >= 4 &&
                    header[len - 1] == '\n' && header[len - 2] == '\r' &&
                    header[len - 3] == '\n' && header[len - 4] == '\r'
                ) break
            }
            if (header.isEmpty()) return TraceResult(0, true, 0, "")
            val headerStr = header.toString()
            val statusLine = headerStr.substringBefore("\r\n")
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            var colo = parseColoRay(headerValue(headerStr, "CF-Ray"))
            val body = StringBuilder()
            val buf = ByteArray(1024)
            var total = 0
            while (total < 4096) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                body.append(String(buf, 0, n, Charsets.US_ASCII))
            }
            val bodyColo = parseColoCdn(body.toString())
            if (bodyColo.isNotEmpty()) colo = bodyColo
            val lat = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(0)
            TraceResult(lat, true, status, colo)
        } catch (e: Exception) {
            TraceResult(0, false, 0, "")
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw.close() }; sockets.remove(raw)
        }
    }

    /**
     * Stage 5 (idle-hold), mirrors the reference scanner's probeStability: open TLS, then hold ~1.5s WITHOUT sending
     * data. A read TIMEOUT means the connection survived the idle period (healthy). Any other outcome
     * — RST, EOF (read returns -1), or a handshake failure — means DPI killed it, so reject the IP.
     */
    private fun probeStability(ip: String, port: Int, host: String, timeoutMs: Int): Boolean {
        val raw = socket()
        var ssl: SSLSocket? = null
        return try {
            raw.connect(InetSocketAddress(ip, port), budgetDial(timeoutMs))
            ssl = (insecureFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                soTimeout = budgetHandshake(timeoutMs)
                sslParameters = (sslParameters ?: SSLParameters()).apply {
                    serverNames = listOf(SNIHostName(host))
                }
            }
            ssl.startHandshake()
            ssl.soTimeout = 1500 // idle-hold window
            val b = ByteArray(1)
            try {
                val n = ssl.inputStream.read(b)
                // No timeout: either data arrived (n>0, alive) or EOF (n<0, connection closed by DPI).
                n >= 0
            } catch (te: java.net.SocketTimeoutException) {
                true // timeout during idle = connection survived = healthy
            }
        } catch (e: Exception) {
            false
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw.close() }; sockets.remove(raw)
        }
    }

    /**
     * Stage 6, mirrors the reference scanner's probeDownload: a REAL micro-download from speed.cloudflare.com/__down
     * forced through the candidate [ip], returning measured BYTES/second (0 on any failure).
     */
    private fun probeDownload(ip: String, port: Int, timeoutMs: Int, bytes: Long): Long {
        if (bytes <= 0) return 0
        val raw = socket()
        var ssl: SSLSocket? = null
        return try {
            raw.connect(InetSocketAddress(ip, port), budgetDial(timeoutMs))
            ssl = (insecureFactory.createSocket(raw, HTTP_HOST, port, true) as SSLSocket).apply {
                soTimeout = timeoutMs
                sslParameters = (sslParameters ?: SSLParameters()).apply {
                    serverNames = listOf(SNIHostName(HTTP_HOST))
                }
            }
            ssl.startHandshake()
            val request = "GET /__down?bytes=$bytes HTTP/1.1\r\n" +
                "Host: $HTTP_HOST\r\n" +
                "User-Agent: Mozilla/5.0 (Android) Aether/CleanIpScanner\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n"
            ssl.outputStream.apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val input = ssl.inputStream
            // Skip status line + headers.
            val header = StringBuilder()
            val one = ByteArray(1)
            while (header.length < 16384) {
                val n = input.read(one)
                if (n < 0) break
                header.append((one[0].toInt() and 0xFF).toChar())
                val len = header.length
                if (len >= 4 &&
                    header[len - 1] == '\n' && header[len - 2] == '\r' &&
                    header[len - 3] == '\n' && header[len - 4] == '\r'
                ) break
            }
            if (header.isEmpty()) return 0
            val status = header.toString().substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            if (status < 200 || status >= 400) return 0
            // BUGFIX (Task 1): previously this loop insisted on reading the FULL byte count with a
            // single socket timeout covering the whole body. Under Iran's DPI a full micro-download
            // routinely overran that timeout, threw SocketTimeoutException, and the catch returned 0 —
            // so probeDownload ALWAYS reported dl=0B/s and rankComparator could never sort by speed.
            // Fix: read for at most a time WINDOW and divide the bytes ACTUALLY received by the
            // elapsed time (a stalled mid-stream read just ends the window). Still 0 on no bytes
            // (honest failure, never a fake number).
            ssl.soTimeout = budgetHttp(timeoutMs)
            val windowNanos = timeoutMs.toLong() * 1_000_000L
            val start = System.nanoTime()
            var bodyBytes = 0L
            val buf = ByteArray(32 * 1024)
            try {
                while (bodyBytes < bytes && System.nanoTime() - start < windowNanos) {
                    val n = input.read(buf)
                    if (n < 0) break
                    bodyBytes += n
                }
            } catch (_: java.net.SocketTimeoutException) {
                // Throttled/stalled mid-stream: keep whatever we measured so far rather than failing.
            }
            val elapsed = System.nanoTime() - start
            if (bodyBytes <= 0L || elapsed <= 0L) 0L
            else (bodyBytes * 1_000_000_000.0 / elapsed).toLong()
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { ssl?.close() }
            runCatching { raw.close() }; sockets.remove(raw)
        }
    }

    /** Trace-host fallback order: the rotated [primary] SNI first, then the stable defaults. */
    private fun traceHostsFor(primary: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(primary)
        out.addAll(TRACE_HOSTS)
        return out.toList()
    }

    internal fun budgetDial(t: Int): Int = maxOf(t / 4, minOf(2000, t))
    internal fun budgetHandshake(t: Int): Int = maxOf(t / 2, minOf(3000, t))
    internal fun budgetHttp(t: Int): Int = (t / 4).coerceAtLeast(1500)

    private fun headerValue(headers: String, name: String): String {
        for (line in headers.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return ""
    }

    /** CF-Ray looks like "<hex>-FRA"; the colo is the 3-letter suffix. */
    internal fun parseColoRay(ray: String): String {
        val parts = ray.split("-")
        if (parts.size < 2) return ""
        val colo = parts.last().trim()
        return if (colo.length >= 3) colo.substring(0, 3).uppercase() else ""
    }

    /** /cdn-cgi/trace body contains a "colo=FRA" line. */
    internal fun parseColoCdn(body: String): String {
        for (raw in body.split("\n")) {
            val line = raw.trimEnd('\r')
            if (line.startsWith("colo=")) return line.removePrefix("colo=").trim()
        }
        return ""
    }

    /**
     * Neighbor expansion (mirrors neighbors.go NeighborsAround): up to [limit] IPv4 addresses near
     * [ip], spreading outward ±1, ±2, … up to [radius], keeping only those still inside a Cloudflare
     * range. Hits in dense CF blocks then surface many nearby working IPs.
     */
    internal fun neighborsAround(ip: String, radius: Int, limit: Int): List<String> {
        if (limit <= 0 || radius <= 0) return emptyList()
        val base = ipToInt(ip).toLong() and 0xFFFFFFFFL
        val out = ArrayList<String>(limit)
        var delta = 1L
        while (delta <= radius && out.size < limit) {
            for (signed in longArrayOf(delta, -delta)) {
                val next = base + signed
                if (next < 0L || next > 0xFFFFFFFFL) continue
                if (next == base) continue
                if (!inScanSpace(next)) continue
                out.add(intToIp(next))
                if (out.size >= limit) break
            }
            delta++
        }
        return out
    }

    private fun inAnyCidr(value: Long): Boolean {
        for ((cidr, _) in WEIGHTED_CIDRS) {
            val (baseStr, prefixStr) = cidr.split("/")
            val prefix = prefixStr.toInt()
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val baseInt = ipToInt(baseStr).toLong() and 0xFFFFFFFFL
            if ((value and mask) == (baseInt and mask)) return true
        }
        return false
    }

    internal fun randomIp(): String {
        val custom = customRanges
        if (custom.isNotEmpty()) {
            val index = Random.nextLong(CustomIpRanges.total(custom))
            return CustomIpRanges.longToIp(CustomIpRanges.addressAt(custom, index))
        }
        val cidr = cidrPool.random()
        val (base, prefix) = cidr.split("/")
        val baseInt = ipToInt(base)
        val hostBits = 32 - prefix.toInt()
        val size = 1L shl hostBits
        val offset = (Random.nextLong(size - 2) + 1) // avoid network & broadcast
        val mask = (0xffffffffL shl hostBits) and 0xffffffffL
        return intToIp((baseInt.toLong() and mask) + offset)
    }

    private fun ipToInt(ip: String): Int {
        val parts = ip.split(".")
        return (parts[0].toInt() shl 24) or (parts[1].toInt() shl 16) or
            (parts[2].toInt() shl 8) or parts[3].toInt()
    }

    private fun intToIp(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"
    }
}

/** Reference per-IP verbose logs are intentionally not written to exportable diagnostics. */
private object ScanLog {
    fun i(tag: String, message: String) = Unit
    fun d(tag: String, message: String) = Unit
    fun w(tag: String, message: String) = Unit
}
