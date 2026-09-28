package studio.cluvex.aether.core

import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * The on-disk format of the diagnostics log: an append-only file of individually
 * sealed records.
 *
 * ## Why not "just encrypt the file"
 *
 * The log is mirrored to disk line by line ON PURPOSE: a native fault inside the
 * in-process tunnel can take the whole process down, and the file is what makes
 * the crashing session inspectable on the next launch. A single whole-file
 * ciphertext cannot be appended to, so keeping that property means either
 * re-encrypting the whole file per batch (quadratic I/O on a chatty engine) or
 * holding it in memory and losing exactly the crash the file exists for.
 *
 * So the file is a sequence of RECORDS, each one sealed on its own:
 *
 * ```
 *   "AELOG1\n"                      7-byte magic, identifies the format
 *   repeat:
 *     length : uint32 big-endian    length of the blob that follows
 *     blob   : iv(12) || ct || tag  AES-256-GCM, KeyVault key, UTF-8 plaintext
 * ```
 *
 * Appending is one `write`, exactly as before. A record is one flush of the log
 * writer's batch, so a burst of a hundred engine lines is still one file append.
 *
 * ## Reading is deliberately forgiving
 *
 * A process that dies mid-write leaves a truncated final record. [readLines] stops
 * at the first record it cannot read and returns everything before it, because the
 * whole point is to recover the log of a session that ended badly. A record that
 * fails its GCM tag is likewise the end of the readable log - it is never returned
 * as raw bytes.
 *
 * ## Failure is CLOSED
 *
 * If [KeyVault] has no key, [appendChunk] returns false and the caller stops
 * mirroring to disk. That loses crash-survival on a device whose keystore is
 * broken; it does not write the user's endpoint history to storage in the clear.
 * The in-memory log, which is what the panel shows, is unaffected.
 */
object EncryptedLogFile {

    /** Marks a file written by this format. Anything else is treated as legacy. */
    private val MAGIC = byteArrayOf(0x41, 0x45, 0x4C, 0x4F, 0x47, 0x31, 0x0A) // "AELOG1\n"

    /** Refuse absurd record lengths rather than allocating on a corrupt file. */
    private const val MAX_RECORD_BYTES = 4 * 1024 * 1024

    /** True when [file] starts with this format's magic. */
    fun looksSealed(file: File): Boolean = runCatching {
        if (!file.isFile || file.length() < MAGIC.size) return false
        file.inputStream().use { input ->
            val head = ByteArray(MAGIC.size)
            input.read(head) == MAGIC.size && head.contentEquals(MAGIC)
        }
    }.getOrDefault(false)

    /**
     * Seals [text] and appends it as one record. Returns false when nothing was
     * written, which always means "no key" or "I/O failed" - never "written in
     * the clear".
     */
    fun appendChunk(file: File, text: String): Boolean {
        val blob = KeyVault.seal(text.toByteArray(Charsets.UTF_8)) ?: return false
        return runCatching {
            val fresh = !file.isFile || file.length() == 0L
            FileOutputStream(file, true).use { out ->
                if (fresh) out.write(MAGIC)
                out.write(lengthPrefix(blob.size))
                out.write(blob)
            }
            if (fresh) restrictToOwner(file)
            true
        }.getOrDefault(false)
    }

    /**
     * Every line in [file], oldest first. Empty when the file is missing, is not
     * this format, or cannot be opened with this device's key.
     */
    fun readLines(file: File): List<String> = runCatching {
        if (!looksSealed(file)) return emptyList()
        val out = ArrayList<String>(256)
        DataInputStream(file.inputStream().buffered()).use { input ->
            input.skipNBytesCompat(MAGIC.size.toLong())
            while (true) {
                val length = readLengthOrNull(input) ?: break
                if (length <= 0 || length > MAX_RECORD_BYTES) break
                val blob = ByteArray(length)
                if (!input.readFullyOrNull(blob)) break
                val plain = KeyVault.open(blob) ?: break
                String(plain, Charsets.UTF_8)
                    .split('\n')
                    .forEach { if (it.isNotEmpty()) out.add(it) }
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * The LAST [maxRecords] records of [file], oldest first — without decrypting
     * the ones in front of them.
     *
     * ## Why this exists (1.3.1 startup fix)
     *
     * [readLines] decrypts EVERY record in the file, and each record costs one
     * `Cipher.init` plus one `doFinal` against a non-exportable Android Keystore
     * key — a binder round trip into keystore2 and an operation in the TEE, not
     * an in-process AES call. The log writer seals one record per drained batch,
     * so a session that trickles lines produces roughly one record PER LINE: at
     * the [DiagnosticsLog] size cap of 512 KB that is thousands of records, and
     * thousands of keystore operations back to back.
     *
     * `DiagnosticsLog.init` then threw all but the newest 800 lines away. The
     * work for every record before those was pure waste, and it was waste paid
     * on the main thread during `Application.onCreate` — which is what made a
     * cold start take seconds and grow slower the longer the app had been used,
     * until clearing app data (deleting this file) was the only way back.
     *
     * The record frame is length-prefixed, so the file can be WALKED without a
     * key: read four bytes, skip that many, repeat. Only the tail is opened.
     *
     * Same forgiving contract as [readLines]: a truncated or unopenable record
     * ends the readable log rather than raising.
     */
    fun readLastLines(
        file: File,
        maxLines: Int,
        endOffset: Long = Long.MAX_VALUE,
    ): List<String> = runCatching {
        if (maxLines <= 0 || !looksSealed(file)) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            // r8: [endOffset] lets the startup restore stop at the byte where the
            // PREVIOUS session ended. Records the writer thread appends after that
            // belong to this session and are already in memory; reading them here
            // too would restore them twice, once as "previous session".
            val total = minOf(raf.length(), endOffset)
            // Pass 1: the offset and length of every intact record. No crypto.
            val offsets = ArrayList<Long>(512)
            val lengths = ArrayList<Int>(512)
            var cursor = MAGIC.size.toLong()
            val head = ByteArray(4)
            while (cursor + head.size <= total) {
                raf.seek(cursor)
                if (!raf.readFullyOrNull(head)) break
                val length = ((head[0].toInt() and 0xFF) shl 24) or
                    ((head[1].toInt() and 0xFF) shl 16) or
                    ((head[2].toInt() and 0xFF) shl 8) or
                    (head[3].toInt() and 0xFF)
                if (length <= 0 || length > MAX_RECORD_BYTES) break
                val blobAt = cursor + head.size
                // A record whose declared length runs past the end of the file is
                // the half-written tail of a process that died. Stop before it.
                if (blobAt + length > total) break
                offsets.add(blobAt)
                lengths.add(length)
                cursor = blobAt + length
            }
            // Pass 2: open records from the NEWEST backwards and stop as soon as
            // [maxLines] lines are in hand.
            //
            // r8: this used to take the last `maxLines` RECORDS. One record is one
            // writer batch and can hold up to 256 lines, so "the last 800" could
            // mean decrypting hundreds of thousands of lines only for the caller
            // to keep 800 of them - the very waste this function exists to avoid.
            // Walking backwards bounds the keystore work by what is kept.
            val chunks = ArrayDeque<List<String>>()
            var lines = 0
            for (index in offsets.indices.reversed()) {
                val blob = ByteArray(lengths[index])
                raf.seek(offsets[index])
                if (!raf.readFullyOrNull(blob)) break
                // An unopenable record ends the readable tail: everything older
                // than it would be out of order with what is already collected.
                val plain = KeyVault.open(blob) ?: break
                val recordLines = String(plain, Charsets.UTF_8)
                    .split('\n')
                    .filter { it.isNotEmpty() }
                chunks.addFirst(recordLines)
                lines += recordLines.size
                if (lines >= maxLines) break
            }
            chunks.flatten().takeLast(maxLines)
        }
    }.getOrDefault(emptyList())

    /**
     * Replaces [file] with a single sealed record holding [lines].
     *
     * Used by the size cap and by "clear". Writes a sibling and renames, so a
     * failure half way through can never leave a file with a valid magic and
     * unreadable contents.
     */
    fun rewrite(file: File, lines: List<String>): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching { tmp.delete() }
        val ok = runCatching {
            FileOutputStream(tmp, false).use { out -> out.write(MAGIC) }
            restrictToOwner(tmp)
            if (lines.isEmpty()) true else appendChunk(tmp, lines.joinToString("\n"))
        }.getOrDefault(false)
        if (!ok) {
            runCatching { tmp.delete() }
            return false
        }
        return runCatching {
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            } else {
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Best-effort erase: overwrite with random bytes of the same length, then
     * delete.
     *
     * Honest about what this is worth: on flash storage with wear levelling an
     * overwrite does not guarantee the old blocks are gone, so this is not an
     * anti-forensics tool. It is here because it makes the plaintext WINDOW as
     * small as we can make it - the file stops existing as a readable path
     * immediately - and because leaving a decrypted identity file behind after a
     * disconnect would defeat the whole point of sealing it.
     */
    fun shred(file: File) {
        runCatching {
            if (file.isFile) {
                val length = file.length()
                if (length in 1..MAX_RECORD_BYTES) {
                    val noise = ByteArray(length.toInt())
                    SecureRandom().nextBytes(noise)
                    FileOutputStream(file, false).use { out ->
                        out.write(noise)
                        out.fd.sync()
                    }
                }
            }
        }
        runCatching { file.delete() }
    }

    /** Owner-only permissions, explicitly, rather than trusting the umask. */
    fun restrictToOwner(file: File) {
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private fun lengthPrefix(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readLengthOrNull(input: DataInputStream): Int? {
        val head = ByteArray(4)
        if (!input.readFullyOrNull(head)) return null
        return ((head[0].toInt() and 0xFF) shl 24) or
            ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or
            (head[3].toInt() and 0xFF)
    }

    /**
     * `readFully` without the exception: a truncated tail is the EXPECTED state
     * of a log whose process was killed, not an error worth a stack trace.
     */
    private fun DataInputStream.readFullyOrNull(target: ByteArray): Boolean {
        var done = 0
        while (done < target.size) {
            val read = read(target, done, target.size - done)
            if (read < 0) return false
            done += read
        }
        return true
    }

    /** [readFullyOrNull] for the random-access walk in [readLastLines]. */
    private fun RandomAccessFile.readFullyOrNull(target: ByteArray): Boolean {
        var done = 0
        while (done < target.size) {
            val read = read(target, done, target.size - done)
            if (read < 0) return false
            done += read
        }
        return true
    }

    /**
     * `InputStream.skipNBytes` is API 34 / Java 12; minSdk here is 26, so skip
     * the header by reading it.
     */
    private fun DataInputStream.skipNBytesCompat(count: Long) {
        var left = count
        val scratch = ByteArray(count.coerceAtMost(64L).toInt().coerceAtLeast(1))
        while (left > 0) {
            val read = read(scratch, 0, left.coerceAtMost(scratch.size.toLong()).toInt())
            if (read <= 0) return
            left -= read
        }
    }
}
