package studio.cluvex.aether.custom

import java.util.Base64

/**
 * Reads NapsternetV / Npv Tunnel config files (`.npvt`, header `NPVT1`).
 *
 * ## The format
 *
 * ```
 *   NPVT1
 *   <b64 part 1>,<b64 part 2>,<b64 part 3>
 * ```
 *
 * Each part is `nonce(16) || ciphertext`, AES in CTR mode, with the key held in
 * a white-box form (lookup tables) rather than as a plain key. Part 1 is the
 * format version (`1`), part 2 the JSON list of servers, part 3 the author's
 * lock settings. The tables ship as `assets/npvt/wb.bin` (see [Tables.parse]).
 *
 * ## Locks
 *
 * A NapsternetV author can lock a config: hide its details, give it an expiry
 * date, a password, or bind it to device ids. This reader reports all of that
 * ([NpvtLock]) and the importer enforces it - see [ConfigParser]. A locked
 * config is never shown in clear text by this app.
 */
object NpvtCodec {

    const val BINARY_HEADER = "NPVS"
    fun isBinary(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes.copyOfRange(0, 4).contentEquals(BINARY_HEADER.toByteArray(Charsets.US_ASCII))

    fun decodeFileText(bytes: ByteArray): String {
        require(bytes.size <= 2 * 1024 * 1024) { "npvt file is too large" }
        if (isBinary(bytes)) {
            require(bytes.size >= 9) { "truncated NPVS header" }
            val version = bytes[4].toInt() and 255
            throw IllegalArgumentException("NPVS binary version $version must be imported through ConfigParser.parseBytes, not the text decoder.")
        }
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    const val HEADER = "NPVT1"
    const val SUB_HEADER = "NPVTSUB1"
    private const val BLOCK = 16

    /** True when [text] looks like an `.npvt` payload. */
    fun looksLike(text: String): Boolean {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return t.startsWith(HEADER) || t.startsWith(SUB_HEADER)
    }

    /** The white-box AES tables. */
    class Tables private constructor(
        private val rounds: Int,
        private val xor: ByteArray,
        private val tybox: IntArray,
        private val mbl: IntArray,
        private val last: ByteArray,
    ) {
        private fun x(r: Int, i: Int, a: Int, b: Int): Int =
            xor[((r * 96 + i) * 16 + (a and 15)) * 16 + (b and 15)].toInt() and 0xFF

        private fun ty(r: Int, i: Int, v: Int): Int = tybox[(r * 16 + i) * 256 + (v and 0xFF)]
        private fun mb(r: Int, i: Int, v: Int): Int = mbl[(r * 16 + i) * 256 + (v and 0xFF)]

        /** Encrypts one 16-byte block (the CTR keystream generator). */
        fun encryptBlock(input: IntArray): IntArray {
            var b = input.copyOf()
            for (r in 0 until rounds) {
                b = shiftRows(b)
                for (c in 0 until 4) {
                    val o = c * 4
                    var v0 = ty(r, o, b[o]); var v1 = ty(r, o + 1, b[o + 1])
                    var v2 = ty(r, o + 2, b[o + 2]); var v3 = ty(r, o + 3, b[o + 3])
                    for (j in 0 until 4) {
                        val k = c * 24 + j * 6
                        val s1 = 28 - j * 8
                        val s2 = 24 - j * 8
                        val b10 = x(r, k, v0 ushr s1, v1 ushr s1)
                        val b11 = x(r, k + 1, v2 ushr s1, v3 ushr s1)
                        val i2 = x(r, k + 2, v0 ushr s2, v1 ushr s2)
                        val i3 = x(r, k + 3, v2 ushr s2, v3 ushr s2)
                        b[o + j] = (x(r, k + 5, i2, i3) or (x(r, k + 4, b10, b11) shl 4)) and 0xFF
                    }
                    v0 = mb(r, o, b[o]); v1 = mb(r, o + 1, b[o + 1])
                    v2 = mb(r, o + 2, b[o + 2]); v3 = mb(r, o + 3, b[o + 3])
                    for (j in 0 until 4) {
                        val k = c * 24 + j * 6
                        val s1 = 28 - j * 8
                        val s2 = 24 - j * 8
                        val e0 = x(r, k, v0 ushr s1, v1 ushr s1)
                        val e1 = x(r, k + 1, v2 ushr s1, v3 ushr s1)
                        val e2 = x(r, k + 2, v0 ushr s2, v1 ushr s2)
                        val e3 = x(r, k + 3, v2 ushr s2, v3 ushr s2)
                        b[o + j] = ((x(r, k + 4, e0, e1) shl 4) or x(r, k + 5, e2, e3)) and 0xFF
                    }
                }
            }
            b = shiftRows(b)
            return IntArray(BLOCK) { last[it * 256 + b[it]].toInt() and 0xFF }
        }

        /** CTR over `nonce(16) || data`. Encryption and decryption are the same operation. */
        fun ctr(nonceAndData: ByteArray): ByteArray {
            require(nonceAndData.size >= BLOCK) { "truncated npvt part" }
            val counter = IntArray(BLOCK) { nonceAndData[it].toInt() and 0xFF }
            val out = ByteArray(nonceAndData.size - BLOCK)
            var stream = IntArray(BLOCK)
            for (i in out.indices) {
                val pos = i and 15
                if (pos == 0) {
                    stream = encryptBlock(counter)
                    var k = 15
                    while (k >= 0) {
                        counter[k] = (counter[k] + 1) and 0xFF
                        if (counter[k] != 0) break
                        k--
                    }
                }
                out[i] = (stream[pos] xor (nonceAndData[BLOCK + i].toInt() and 0xFF)).toByte()
            }
            return out
        }

        companion object {
            private val SHIFT = intArrayOf(0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11)

            private fun shiftRows(b: IntArray): IntArray = IntArray(BLOCK) { b[SHIFT[it]] }

            /**
             * `wb.bin` layout: `"AWB1"`, nr (1 byte), then for the nr-1 rounds the
             * xor tables (96 x 16 x 16 nibbles), the T-y boxes (16 x 256 big-endian
             * u32), the mixing bijections (16 x 256 u32), and finally the last-round
             * boxes (16 x 256 bytes).
             */
            fun parse(blob: ByteArray): Tables {
                require(blob.size > 5 && String(blob, 0, 4, Charsets.US_ASCII) == "AWB1") { "not an npvt table file" }
                val nr = blob[4].toInt() and 0xFF
                val rounds = nr - 1
                require(rounds >= 1) { "bad round count" }
                var p = 5
                val xorLen = rounds * 96 * 256
                val boxLen = rounds * 16 * 256
                require(blob.size == 5 + xorLen + boxLen * 8 + 16 * 256) { "npvt table file has the wrong size" }
                val xor = blob.copyOfRange(p, p + xorLen); p += xorLen
                fun u32s(): IntArray = IntArray(boxLen) {
                    val q = p + it * 4
                    ((blob[q].toInt() and 0xFF) shl 24) or ((blob[q + 1].toInt() and 0xFF) shl 16) or
                        ((blob[q + 2].toInt() and 0xFF) shl 8) or (blob[q + 3].toInt() and 0xFF)
                }
                val ty = u32s(); p += boxLen * 4
                val mb = u32s(); p += boxLen * 4
                val last = blob.copyOfRange(p, p + 16 * 256)
                return Tables(rounds, xor, ty, mb, last)
            }
        }
    }

    /** The three decrypted parts of a file. */
    data class Decoded(val version: Int, val servers: String, val lock: String)

    /**
     * Decrypts an `.npvt` text. Throws [IllegalArgumentException] with a
     * user-readable reason when the text is not a valid file.
     */
    fun decode(text: String, tables: Tables): Decoded {
        var body = text.trim().trimStart('\uFEFF')
        body = when {
            body.startsWith(SUB_HEADER) -> body.substring(SUB_HEADER.length)
            body.startsWith(HEADER) -> body.substring(HEADER.length)
            else -> throw IllegalArgumentException("not an NPVT1 file")
        }.trim()
        val parts = body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(parts.size == 3) { "unexpected npvt layout (${parts.size} parts)" }
        val plain = parts.map { part ->
            val bin = decodeBase64(part) ?: throw IllegalArgumentException("npvt part is not base64")
            String(tables.ctr(bin), Charsets.UTF_8).trim()
        }
        val version = plain[0].toIntOrNull() ?: throw IllegalArgumentException("npvt file could not be decrypted")
        require(version <= 1) { "this npvt file needs a newer NapsternetV format (version $version)" }
        return Decoded(version, plain[1], plain[2])
    }

    private fun decodeBase64(s: String): ByteArray? {
        val clean = s.filterNot { it.isWhitespace() }
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
    }
}