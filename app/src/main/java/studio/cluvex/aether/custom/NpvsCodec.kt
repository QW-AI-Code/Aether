package studio.cluvex.aether.custom

import kotlinx.serialization.json.*
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.ECDSASigner
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NPVS v5 compact envelope and authenticated NPF fields.
 * Interoperability reference: FrontierTM/Pantegnos, commit
 * 5a599878c4c113d4d91d1da7294d43095bdb8433 (AGPL-3.0).
 * No plaintext, derived key or credential is logged or returned to the UI.
 */
object NpvsCodec {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private fun u16(b: ByteArray, p: Int): Int {
        require(p >= 0 && p + 2 <= b.size) { "truncated NPVS field" }
        return ((b[p].toInt() and 255) shl 8) or (b[p + 1].toInt() and 255)
    }
    private fun u32(b: ByteArray, p: Int): Int {
        require(p >= 0 && p + 4 <= b.size) { "truncated NPVS length" }
        val n = (0..3).fold(0L) { a, i -> (a shl 8) or (b[p + i].toLong() and 255) }
        require(n <= MAX_BYTES) { "NPVS length exceeds limit" }
        return n.toInt()
    }
    private fun cut(b: ByteArray, off: Int, len: Int): ByteArray {
        require(off >= 0 && len >= 0 && off.toLong() + len <= b.size) { "truncated NPVS payload" }
        return b.copyOfRange(off, off + len)
    }
    private fun be16(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())
    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun utf8(b: ByteArray) = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun mac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    private fun hkdf(dek: ByteArray, salt: ByteArray, info: ByteArray) =
        mac(mac(salt, dek), info + byteArrayOf(1))

    private fun open(key: ByteArray, nonce: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12 && blob.size >= 16) { "bad NPVS cipher parameters" }
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(blob.size))
        try {
            val used = cipher.processBytes(blob, 0, blob.size, out, 0)
            val size = used + cipher.doFinal(out, used)
            return out.copyOf(size)
        } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw IllegalArgumentException("NPVS authentication failed (wrong password or damaged file)")
        } finally { out.fill(0) }
    }

    /** Validates the creator's P-256 signature over the entire envelope before unlocking. */
    private fun verifySignature(bytes: ByteArray, creator: ByteArray) {
        try {
            val curve = SECNamedCurves.getByName("secp256r1")
            val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
            val point = curve.curve.decodePoint(creator)
            val verifier = ECDSASigner()
            verifier.init(false, ECPublicKeyParameters(point, domain))
            val sig = bytes.takeLast(64).toByteArray()
            require(verifier.verifySignature(sha(bytes.copyOf(bytes.size - 64)),
                BigInteger(1, sig.copyOfRange(0, 32)), BigInteger(1, sig.copyOfRange(32, 64)))) {
                "NPVS creator signature is invalid"
            }
        } catch (e: IllegalArgumentException) { throw e
        } catch (_: Exception) { throw IllegalArgumentException("NPVS creator signature is invalid") }
    }

    fun decode(bytes: ByteArray, tables: NpvtCodec.Tables, password: String? = null): NpvtCodec.Decoded {
        require(bytes.size in 9..MAX_BYTES && NpvtCodec.isBinary(bytes)) { "truncated NPVS header" }
        require(bytes[4].toInt() == 5) { "unsupported NPVS envelope version ${bytes[4].toInt() and 255}" }
        val headerLength = u32(bytes, 5)
        require(headerLength >= 135) { "truncated NPVS compact header" }
        val h = cut(bytes, 9, headerLength)
        require(h[0].toInt() == 1) { "unsupported NPVS compact header" }
        val method = h[50].toInt() and 255
        val recipients = u16(h, 51)
        require(recipients <= 1024) { "too many NPVS recipients" }
        var off = 53 + recipients * 125
        val salt: ByteArray
        val wrap: ByteArray
        val iterations: Int
        when (method) {
            1 -> {
                iterations = u32(h, off)
                require(iterations in 1..2_000_000) { "NPVS password work factor is unsupported" }
                salt = cut(h, off + 4, 16); wrap = cut(h, off + 20, 60); off += 80
            }
            2 -> {
                require(u16(h, off) == 2) { "bad NPVS app-key block" }
                salt = cut(h, off + 2, 16); wrap = cut(h, off + 18, 60); off += 78
                iterations = 0
            }
            0 -> throw IllegalArgumentException("NPVS recipient-bound config requires its owner's device key")
            else -> throw IllegalArgumentException("unsupported NPVS unlock method $method")
        }
        val metaLength = u32(h, off)
        require(off + 4L + metaLength == h.size.toLong()) { "invalid NPVS metadata length" }
        val meta = cut(h, off + 4, metaLength)
        val prefix = cut(h, 0, off)
        val bodyOffset = 9 + headerLength
        val nonce = cut(bytes, bodyOffset, 12)
        val bodyLength = u32(bytes, bodyOffset + 12)
        val body = cut(bytes, bodyOffset + 16, bodyLength)
        require(bodyOffset + 16L + bodyLength + 64 == bytes.size.toLong()) { "invalid NPVS body length" }
        verifySignature(bytes, cut(h, 17, 33))
        if (method == 1 && password.isNullOrEmpty()) throw NpvtPasswordException(false)
        val kdk = if (method == 2) {
            val a16 = tables.encryptBlock(IntArray(16) { salt[it].toInt() and 255 }).map { it.toByte() }.toByteArray()
            sha("npvtunnel/appkey/v2 ".toByteArray() + a16 + cut(h, 1, 16)).also { a16.fill(0) }
        } else {
            val p = password!!.toByteArray(Charsets.UTF_8)
            try { PKCS5S2ParametersGenerator(SHA256Digest()).run {
                init(p, salt, iterations); (generateDerivedParameters(256) as KeyParameter).key
            } } finally { p.fill(0) }
        }
        val dek = try { open(kdk, wrap.copyOfRange(0, 12), wrap.copyOfRange(12, 60), salt)
        } catch (e: IllegalArgumentException) {
            if (method == 1) throw NpvtPasswordException(true) else throw e
        } finally { kdk.fill(0) }
        try {
            val metadata = Json.parseToJsonElement(utf8(open(hkdf(dek, nonce,
                "NPVS-v5/metadata".toByteArray()), nonce, meta, prefix))).jsonObject
            val policy = metadata["policy"] as? JsonObject ?: throw IllegalArgumentException("NPVS policy missing")
            val attestation = (policy["attestationLevel"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            require(attestation.isEmpty() || attestation == "NONE") { "NPVS device attestation is not supported" }
            require(body.size >= 38 && body.copyOfRange(0, 4).contentEquals(byteArrayOf(78, 80, 70, 1))) {
                "invalid NPVS NPF body"
            }
            val contentId = cut(body, 4, 32)
            val count = u16(body, 36)
            require(count in 1..8192) { "invalid NPVS field count" }
            val fields = mutableMapOf<Int, ByteArray>()
            var p = 38
            repeat(count) {
                val seq = u16(body, p); val flags = u16(body, p + 2); val len = u16(body, p + 4); p += 6
                require(flags == 0 && seq !in fields && len >= 16) { "unsupported or duplicate NPVS field" }
                val cipher = cut(body, p, len); p += len
                val key = hkdf(dek, contentId, "NPV-fields-v1/field/".toByteArray() + be16(seq))
                fields[seq] = try { open(key, ByteArray(12), cipher,
                    "NPV-fields-v1/record/".toByteArray() + contentId + be16(seq) + be32(len - 16))
                } finally { key.fill(0) }
            }
            // The NPF trailer is covered by the envelope's P-256 signature.
            // Its independent meaning is not assumed to be a plain SHA-256.
            require(p + 32 == body.size) { "invalid NPVS NPF trailer length" }
            try {
                val spec = Json.parseToJsonElement(utf8(fields[65535]
                    ?: throw IllegalArgumentException("NPVS config table is missing"))).jsonObject
                fun substitute(value: JsonElement, depth: Int = 0): JsonElement {
                    require(depth <= 48) { "NPVS config nesting is too deep" }
                    return when (value) {
                        is JsonObject -> JsonObject(value.mapValues { substitute(it.value, depth + 1) })
                        is JsonArray -> JsonArray(value.map { substitute(it, depth + 1) })
                        is JsonPrimitive -> if (!value.isString && value.intOrNull != null) {
                            val raw = utf8(fields[value.int] ?: throw IllegalArgumentException("NPVS field is missing"))
                            val text = (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonPrimitive)
                                ?.contentOrNull ?: raw.trim()
                            JsonPrimitive(decodeSentinels(text))
                        } else value
                        else -> value
                    }
                }
                val configs = spec["configs"] as? JsonArray ?: throw IllegalArgumentException("NPVS configs are missing")
                require(configs.size in 1..500) { "invalid NPVS config count" }
                val servers = JsonArray(configs.map { el ->
                    val server = substitute(el).jsonObject
                    require(server["v2rayProfile"] is JsonObject) { "NPVS server type is not V2Ray/Xray" }
                    require("lockConfig" !in server) { "nested NPVS lock policy is unsupported" }
                    JsonObject(server + ("type" to JsonPrimitive("V2RAY")))
                })
                val expiry = (policy["expiresAt"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                require(expiry.isEmpty() || NpvtLock.parseExpiry(expiry) > 0) { "invalid NPVS expiry" }
                // Encrypted exports stay private regardless of UI policy defaults.
                val lock = buildJsonObject {
                    put("isLocked", true); put("expiryDate", expiry)
                    put("onlyMobileNetwork", (policy["onlyMobileNetwork"] as? JsonPrimitive)?.booleanOrNull ?: false)
                    put("message", listOf("displayMessage", "customServerMessage").mapNotNull {
                        (policy[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    }.joinToString("\n"))
                }
                return NpvtCodec.Decoded(1, servers.toString(), lock.toString())
            } finally { fields.values.forEach { it.fill(0) } }
        } finally { dek.fill(0) }
    }

    internal fun decodeSentinels(text: String): String = Regex("npvs1:([A-Za-z0-9+/=_-]+)").replace(text) { m ->
        val raw = m.groupValues[1].replace('-', '+').replace('_', '/')
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        val decoded = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: throw IllegalArgumentException("bad NPVS sentinel encoding")
        utf8(decoded)
    }
}
