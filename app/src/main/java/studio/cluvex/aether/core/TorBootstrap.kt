package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tor bootstrap progress, read off the engine's own stdout.
 *
 * WHY THIS EXISTS (1.3.0 field log, the `Tor`, `Tor -> Psiphon` and
 * `Tor -> Aether` "does not connect" report):
 *
 * The engine binds its SOCKS5 listener the moment it starts, *before* Tor has
 * reached the network. The app used to take that open port as proof that stage 1
 * was ready and immediately ran a single SOCKS5 handshake with a 4-second
 * timeout. In the field log the port was up 300 ms after launch, the handshake
 * was fired at +0.4 s, and the attempt was declared dead at +4.4 s -- while the
 * very same log showed Tor at 30 %, happily fetching directory certificates. The
 * 300-second bootstrap budget the app had computed for the attempt was never
 * spent, because nothing was ever waiting on the bootstrap itself.
 *
 * So the app needs to know how far Tor actually got, for three separate reasons:
 *
 *  - to WAIT on the handshake for as long as the bootstrap is still moving,
 *    instead of for one fixed 4-second window;
 *  - to give up EARLY, with a message that names the real cause, when the
 *    percentage stops moving -- that, and not a fast handshake, is what a
 *    network filtering Tor looks like;
 *  - to show the percentage in the notification, so five minutes of "Building
 *    Tor circuits…" stops looking like a hung app.
 *
 * The percentage is parsed from the line the engine already prints:
 * `tor reaching the network: 30%: connecting to the internet; directory is …`
 *
 * A bridge retry restarts Tor's bootstrap from a low percentage, so a percentage
 * going DOWN is progress too: any change at all counts as movement.
 *
 * The clock is [System.nanoTime]-derived (monotonic, and unlike `SystemClock` it
 * exists on a plain JVM, so this whole class is unit-testable).
 */
object TorBootstrap {

    data class Snapshot(
        /** Last percentage the engine reported, or -1 when nothing was seen yet. */
        val percent: Int = -1,
        /** The engine's own words for what it is doing at that percentage. */
        val detail: String = "",
        /** True once the bootstrap reported 100 %. */
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Overridable for tests only. */
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000L }

    @Volatile
    private var advancedAt: Long = 0L

    @Volatile
    private var watchingSince: Long = 0L

    val percent: Int get() = _state.value.percent
    val detail: String get() = _state.value.detail
    val done: Boolean get() = _state.value.done

    /** True once the engine has reported any bootstrap progress at all. */
    val seen: Boolean get() = _state.value.percent >= 0

    /** Called when an engine is (re)started: the previous run's progress is meaningless. */
    fun reset() {
        _state.value = Snapshot()
        advancedAt = 0L
        watchingSince = clock()
    }

    /**
     * Scans one engine stdout line for the bootstrap progress message.
     * Cheap string work on the log-drain thread; every other line is ignored.
     */
    fun ingest(line: String) {
        val m = PROGRESS.find(line) ?: return
        val pct = m.groupValues[1].toIntOrNull()?.takeIf { it in 0..100 } ?: return
        val detail = m.groupValues[2].trim()
        val current = _state.value
        // Any change is movement -- including downwards, which is what a fresh
        // attempt through bridges looks like.
        if (pct != current.percent) advancedAt = clock()
        if (watchingSince == 0L) watchingSince = clock()
        _state.value = Snapshot(
            percent = pct,
            detail = if (detail.isNotEmpty()) detail else current.detail,
            done = pct >= 100,
        )
    }

    /**
     * Milliseconds since the percentage last changed -- or since [reset] when the
     * engine has not printed a single progress line, because an engine that says
     * nothing at all is the worse version of the same stall.
     */
    fun idleMs(): Long {
        val since = if (advancedAt != 0L) advancedAt else watchingSince
        if (since == 0L) return 0L
        return (clock() - since).coerceAtLeast(0L)
    }

    /** True when the bootstrap is unfinished and has not moved for [afterMs]. */
    fun stalled(afterMs: Long): Boolean = !done && idleMs() >= afterMs

    /** One-line human summary for logs and error messages. */
    fun describe(): String = when {
        !seen -> "no bootstrap progress reported"
        done -> "bootstrap complete"
        else -> "stuck at $percent%" + (if (detail.isNotEmpty()) " ($detail)" else "")
    }

    private val PROGRESS = Regex("""tor reaching the network:\s*(\d{1,3})\s*%\s*:?\s*(.*)""")
}
