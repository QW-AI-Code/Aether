package studio.cluvex.aether.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import studio.cluvex.aether.ai.AiRedaction

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogLine(
    val timeMs: Long,
    val tag: String,
    val level: LogLevel,
    val message: String,
    /** Restored-from-disk lines are already formatted; print them verbatim. */
    val raw: Boolean = false,
) {
    fun format(): String {
        if (raw) return message
        val ts = TS_FORMAT.get()?.format(Date(timeMs)) ?: timeMs.toString()
        val lvl = when (level) {
            LogLevel.DEBUG -> "D"
            LogLevel.INFO -> "I"
            LogLevel.WARN -> "W"
            LogLevel.ERROR -> "E"
        }
        return "$ts $lvl/$tag: $message"
    }

    private companion object {
        val TS_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

enum class CheckState { PENDING, RUNNING, PASS, FAIL }

data class ComponentCheck(
    val id: String,
    val label: String,
    val state: CheckState = CheckState.PENDING,
    val detail: String = "",
)

/**
 * In-app, professional-grade log + self-test store. Every moving part of the
 * tunnel (engine process, in-process hev tunnel, VpnService lifecycle and the
 * connectivity self-tests) writes here, so the UI can show exactly which stage
 * fails when "connected but no site loads" happens.
 *
 * CRASH-SURVIVAL (root-cause fix): now that hev runs IN-PROCESS, a native fault
 * or an [Error] such as UnsatisfiedLinkError can take the whole app down. A
 * memory-only log is wiped by that death, so the user "sees no log after a
 * crash". We therefore mirror every line to a file on disk as it is written and
 * reload it on next launch, so the crashing session is always inspectable.
 *
 * ENCRYPTED AT REST (audit 1.2.9-r3, F-2). That mirror used to be a plaintext
 * file, and its contents are the endpoints the scan settled on, the exit IP, the
 * WARP enrolment handle and the assigned IPv6 - the "who was this user talking to"
 * set, kept across launches by design. It is now written through
 * [EncryptedLogFile]: one AES-256-GCM record per flushed batch, under a
 * non-exportable Android Keystore key, so appending still costs one write and a
 * rooted or seized device yields ciphertext.
 *
 * Two consequences worth stating plainly:
 *
 *  * A file left by an older version is plaintext. It is loaded once (so the user
 *    does not lose the log of the crash they are about to report), then SHREDDED,
 *    and everything after that is sealed. The `.prev` rotation of a legacy file
 *    goes with it.
 *  * If the device keystore cannot give us a key, the disk mirror is switched OFF
 *    and said so once, rather than falling back to plaintext. The panel still
 *    shows the live in-memory log; only crash survival is lost.
 */
object DiagnosticsLog {
    private const val MAX_LINES = 800

    /**
     * 1.2.2 PERFORMANCE (memory + CPU).
     *
     * The 1.2.1 implementation did `_lines.value = _lines.value + line` on
     * EVERY log line. With an 800-line cap and a chatty engine that is an
     * O(n) array copy per line plus a `takeLast(800)` copy on top — i.e. two
     * fresh ~800-element lists allocated per log write, several times a
     * second during a scan. That is what made the app feel heavy and kept the
     * GC busy while connecting.
     *
     * The buffer is now a bounded [ArrayDeque] mutated in place: appending is
     * O(1) and eviction is O(1). Exactly ONE immutable snapshot is published
     * to the UI per batch, and batching is time-based
     * ([UI_PUBLISH_INTERVAL_MS]), so a burst of a hundred engine lines costs
     * one recomposition instead of a hundred.
     *
     * Disk I/O moved off the caller's thread as well: lines are queued and
     * flushed by a single dedicated writer thread, so no scanning/UI thread
     * ever blocks on flash storage. The file is also size-capped
     * ([MAX_FILE_BYTES]) so a long session can no longer grow it without
     * bound.
     */
    private const val UI_PUBLISH_INTERVAL_MS = 200L
    private const val MAX_FILE_BYTES = 512L * 1024L

    /**
     * How long the writer waits for more lines before sealing a record (1.3.1).
     *
     * The writer used to take one line and then drain whatever happened to be
     * queued behind it. During a scan that is a real batch; while the app sits
     * idle and logs a line every few seconds it is a batch of ONE, so the file
     * filled up with one sealed record per line. Every record costs a keystore
     * operation to write and another to read back, and reading them back is what
     * [init] used to do on the main thread at every cold start.
     *
     * A short linger collapses a trickle into one record without delaying
     * anything a user can perceive: the in-memory log the panel shows is already
     * updated, and this only moves when the bytes reach flash. 250 ms is below
     * the panel's own publish interval, so the disk copy never lags the screen
     * by more than one refresh.
     */
    private const val WRITE_COALESCE_MS = 250L

    /** Lines per sealed record. Caps the linger above on a chatty engine. */
    private const val MAX_BATCH_LINES = 256

    /** Bounded in-memory ring buffer. Guarded by [bufferLock]. */
    private val buffer = ArrayDeque<LogLine>(MAX_LINES)
    private val bufferLock = Any()

    /** Set when the buffer changed but the UI snapshot has not been published. */
    private val dirty = AtomicBoolean(false)

    /** Off-thread disk writer: never blocks a caller on flash I/O. */
    private val pendingWrites = LinkedBlockingQueue<String>()

    @Volatile
    private var writerStarted = false

    @Volatile
    private var publisherStarted = false

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private val _checks = MutableStateFlow<List<ComponentCheck>>(emptyList())
    val checks: StateFlow<List<ComponentCheck>> = _checks.asStateFlow()

    @Volatile
    private var logFile: File? = null

    /** Set when sealing failed: the disk mirror is off for this process. */
    @Volatile
    private var diskMirrorDisabled = false

    /** True while the on-disk log is being kept (encrypted). For the UI/report. */
    val persistedEncrypted: Boolean
        get() = logFile != null && !diskMirrorDisabled

    /**
     * Wires the persistent log file (call once from Application.onCreate).
     *
     * Returns as soon as the file is wired: it never touches the disk itself.
     *
     * ## 1.3.1 STARTUP FIX — this method used to be the slow cold start
     *
     * It did three things inline, on whatever thread called it, which is the
     * main thread in `Application.onCreate`:
     *
     *  1. [EncryptedLogFile.readLines] over the WHOLE file — one Android
     *     Keystore operation per sealed record, and the writer produced roughly
     *     one record per line, so a 512 KB log meant thousands of TEE round
     *     trips in a row;
     *  2. a byte copy of the same file to `<name>.prev`;
     *  3. all of it before the first frame could be laid out.
     *
     * Then it kept only the newest [MAX_LINES] of what it had decrypted. Field
     * reports called this "the app takes about ten seconds to open", "it opens
     * slowly or stays on a black screen" and "it only comes back if I clear app
     * data" — clearing data deletes this file, which is why that worked. It also
     * fed the `ForegroundServiceDidNotStartInTimeException` crashes: a main
     * thread parked in the keystore cannot answer `startForegroundService()`
     * with `startForeground()` inside the framework's ten-second window.
     *
     * Now: the restore runs on a background thread, and it decrypts only the
     * tail it is actually going to keep ([EncryptedLogFile.readLastLines]).
     * Lines logged while it is still running are preserved — the restored block
     * is spliced in FRONT of them, so the panel reads in order either way.
     */
    @Synchronized
    fun init(file: File) {
        logFile = file
        val previousEnd = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L)
        if (previousEnd <= 0L) return
        // r8: pin WHICH session is being restored. [clear] bumps [session]; a
        // restore that finds it moved has been overtaken by a new session and
        // must not splice the old lines into it or touch the rotation.
        val restoring = session
        thread(name = "log-restore", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            runCatching { restorePrevious(file, restoring, previousEnd) }
        }
    }

    /**
     * Bumped by [clear]. Read by the restore thread to tell whether the session it
     * was started for is still the current one.
     */
    @Volatile
    private var session = 0

    /**
     * The body of the old [init], off the main thread and reading the tail only.
     *
     * r8: a connect that starts during a slow restore (`AetherVpnService.connectFlow`
     * calls [clear]) used to be able to interleave with it: `clear` rotates the
     * file to `.prev` and empties it, then the restore copied the now EMPTY file
     * over `.prev` - destroying the very previous-session log both were trying to
     * keep - and spliced the old lines into the fresh session.
     *
     * Now the slow part (the keystore decrypts) runs WITHOUT the monitor, so it
     * can never hold up [clear] or anything else on the main thread, and every
     * side effect (rotation, shredding, splicing) runs under the same monitor as
     * [clear], after checking that no [clear] happened in between. Whichever runs
     * second sees what the first did.
     */
    private fun restorePrevious(file: File, restoring: Int, previousEnd: Long) {
        if (session != restoring) return
        val sealed = EncryptedLogFile.looksSealed(file)
        // Only as many lines as can survive the MAX_LINES cap, and only from the
        // bytes that existed before this launch: decrypting anything else was the
        // whole cost and none of the value.
        val previous: List<String> = if (sealed) {
            EncryptedLogFile.readLastLines(file, MAX_LINES, previousEnd)
        } else {
            // A log written by 1.2.9-r2 or earlier: plaintext. Read it once so
            // the user does not lose the session they are about to report.
            runCatching { file.readLines() }.getOrDefault(emptyList())
        }
        val migrated = !sealed
        val restored = previous.takeLast(MAX_LINES).map {
            LogLine(0L, "prev", LogLevel.DEBUG, it, raw = true)
        }
        synchronized(this) {
            // Overtaken by a new session: [clear] already rotated the previous
            // log to `.prev` and started a fresh one. Nothing left to do, and
            // anything done now would damage one or the other.
            if (session != restoring) return
            if (sealed) {
                // Rotate the SEALED bytes, so the previous session survives a
                // trim or a clear without ever existing in the clear.
                runCatching {
                    file.copyTo(File(file.parentFile, file.name + ".prev"), overwrite = true)
                }
            } else {
                // ...then remove the plaintext, along with its plaintext rotation,
                // and start a sealed file in its place.
                EncryptedLogFile.shred(file)
                EncryptedLogFile.shred(File(file.parentFile, file.name + ".prev"))
            }
            if (restored.isEmpty() && !migrated) return
            val header = LogLine(
                System.currentTimeMillis(),
                "log",
                LogLevel.INFO,
                "—— previous session restored (${restored.size} lines) ——",
            )
            synchronized(bufferLock) {
                // Anything logged during startup while this thread was working is
                // NEWER than what is being restored, so it goes after it. Splicing
                // rather than clearing is what makes the restore safe to run late.
                val live = buffer.toList()
                buffer.clear()
                buffer.addAll(restored)
                buffer.addLast(header)
                buffer.addAll(live)
                while (buffer.size > MAX_LINES) buffer.removeFirst()
            }
        }
        dirty.set(true)
        ensurePublisher()
        if (migrated) {
            i("log", "Previous diagnostics log was plaintext: it has been loaded, then erased. From now on the on-disk log is encrypted with a device key.")
        }
    }

    fun log(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        // O(1) append + O(1) eviction, no list copies.
        synchronized(bufferLock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        dirty.set(true)
        ensurePublisher()
        // Queue for the writer thread instead of touching the disk inline.
        pendingWrites.offer(line.format())
        ensureWriter()
    }

    /** Publishes at most one immutable snapshot per [UI_PUBLISH_INTERVAL_MS]. */
    private fun ensurePublisher() {
        if (publisherStarted) return
        synchronized(bufferLock) {
            if (publisherStarted) return
            publisherStarted = true
        }
        thread(name = "log-publisher", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            while (true) {
                Thread.sleep(UI_PUBLISH_INTERVAL_MS)
                if (!dirty.compareAndSet(true, false)) continue
                _lines.value = synchronized(bufferLock) { buffer.toList() }
            }
        }
    }

    /**
     * Single writer thread. Drains the queue in batches so a burst of lines
     * becomes one file append instead of one `appendText` (open + write +
     * close) syscall trio per line, which is what the previous inline flush
     * did on the connect path.
     */
    private fun ensureWriter() {
        if (writerStarted) return
        synchronized(bufferLock) {
            if (writerStarted) return
            writerStarted = true
        }
        thread(name = "log-writer", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val batch = ArrayList<String>(MAX_BATCH_LINES)
            while (true) {
                batch.clear()
                // Block for the first line, then LINGER briefly for more (1.3.1).
                // The old code drained only what was already queued, which on an
                // idle app is nothing - one record per line, see
                // WRITE_COALESCE_MS.
                batch.add(pendingWrites.take())
                val deadline = System.nanoTime() + WRITE_COALESCE_MS * 1_000_000L
                while (batch.size < MAX_BATCH_LINES) {
                    val leftMs = (deadline - System.nanoTime()) / 1_000_000L
                    if (leftMs <= 0L) break
                    val next = pendingWrites.poll(leftMs, TimeUnit.MILLISECONDS) ?: break
                    batch.add(next)
                    pendingWrites.drainTo(batch, MAX_BATCH_LINES - batch.size)
                }
                val file = logFile ?: continue
                if (diskMirrorDisabled) continue
                // ONE sealed record per batch: the same single write the plaintext
                // implementation did, so the burst behaviour is unchanged.
                if (!EncryptedLogFile.appendChunk(file, batch.joinToString("\n"))) {
                    disableDiskMirror(file)
                    continue
                }
                runCatching {
                    if (file.length() > MAX_FILE_BYTES) trimFile(file)
                }
            }
        }
    }

    /**
     * Keeps the on-disk log bounded: rotates to `<name>.prev` and restarts the
     * live file with the most recent lines, so crash-survivability is kept
     * without letting storage grow forever.
     *
     * Both files stay sealed: the rotation is a byte copy of ciphertext and the
     * rewrite re-seals what it keeps.
     */
    private fun trimFile(file: File) {
        runCatching {
            file.copyTo(File(file.parentFile, file.name + ".prev"), overwrite = true)
            val keep = EncryptedLogFile.readLastLines(file, MAX_LINES / 2)
            EncryptedLogFile.rewrite(file, keep)
        }
    }

    /**
     * Turns the disk mirror off after a sealing failure and says so exactly once.
     *
     * Fail CLOSED: the alternative is writing the user's endpoint history to
     * storage in the clear on a device whose keystore is already misbehaving. The
     * live in-memory log - what the panel shows and what "copy logs" copies - is
     * not affected.
     */
    private fun disableDiskMirror(file: File) {
        if (diskMirrorDisabled) return
        diskMirrorDisabled = true
        // Anything already on disk was written by a working key; a file we can no
        // longer append to is of no use, and leaving a half-written record behind
        // would only confuse the next launch.
        EncryptedLogFile.shred(file)
        w(
            "log",
            "This device's keystore would not seal the diagnostics log, so the on-disk copy is OFF " +
                "(the log is still kept in memory for this session). It will never be written in plaintext.",
        )
    }

    fun d(tag: String, m: String) = log(tag, LogLevel.DEBUG, m)
    fun i(tag: String, m: String) = log(tag, LogLevel.INFO, m)
    fun w(tag: String, m: String) = log(tag, LogLevel.WARN, m)
    fun e(tag: String, m: String) = log(tag, LogLevel.ERROR, m)

    /**
     * Starts a fresh session. The prior on-disk log is rotated to `<name>.prev`
     * (never silently destroyed) so a crash log is always recoverable.
     */
    @Synchronized
    fun clear() {
        session++
        synchronized(bufferLock) { buffer.clear() }
        _lines.value = emptyList()
        runCatching {
            logFile?.let { f ->
                if (f.exists() && f.length() > 0L) {
                    f.copyTo(File(f.parentFile, f.name + ".prev"), overwrite = true)
                }
                // Start a fresh SEALED file rather than truncating to an empty
                // plaintext one, which would leave the next append writing into a
                // file with no magic.
                EncryptedLogFile.rewrite(f, emptyList())
            }
        }
    }

    @Synchronized
    fun setChecks(checks: List<ComponentCheck>) {
        _checks.value = checks
    }

    @Synchronized
    fun updateCheck(id: String, state: CheckState, detail: String? = null) {
        _checks.value = _checks.value.map {
            if (it.id == id) it.copy(state = state, detail = detail ?: it.detail) else it
        }
    }

    fun exportText(): String =
        synchronized(bufferLock) { buffer.toList() }.joinToString("\n") { it.format() }

    /**
     * The log with every address, identifier and credential masked (audit
     * 1.2.9-r3, §6).
     *
     * "Copy logs" hands over the raw log, which is right: it is a deliberate user
     * action for a bug report, and a diagnosis needs the detail. But the redaction
     * written to protect the user from the AI model protects them just as well from
     * a public GitHub issue, and until now the safe option simply did not exist -
     * so the only button copied the exit IP, the WARP handle and the endpoints into
     * whatever thread the user pasted it in.
     *
     * Same rule set as the AI digest ([AiRedaction]), minus its 220-line cap:
     * plumbing and error text stay, identity goes.
     */
    fun exportRedactedText(): String =
        AiRedaction.redactAll(exportText())
}
