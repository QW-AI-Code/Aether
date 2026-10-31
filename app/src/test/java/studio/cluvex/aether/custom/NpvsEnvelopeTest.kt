package studio.cluvex.aether.custom

import kotlinx.serialization.json.*
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.ECDSASigner
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Synthetic fixtures only: no real server, author key or account credential. */
class NpvsEnvelopeTest {
    private val tables by lazy {
        NpvtCodec.Tables.parse(listOf(File("src/main/assets/npvs/gen2-wb.bin"),
            File("app/src/main/assets/npvs/gen2-wb.bin")).first { it.exists() }.readBytes())
    }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun mac(k: ByteArray, data: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(k, "HmacSHA256")); doFinal(data)
    }
    private fun hk(dek: ByteArray, salt: ByteArray, info: ByteArray) = mac(mac(salt, dek), info + byteArrayOf(1))
    private fun u16(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())
    private fun u32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    private fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val c = ChaCha20Poly1305(); c.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(plain.size)); val n = c.processBytes(plain, 0, plain.size, out, 0)
        return out.copyOf(n + c.doFinal(out, n))
    }
    private fun fixture(password: String? = null, expiry: String? = null, mobile: Boolean = false): ByteArray {
        val curve = SECNamedCurves.getByName("secp256r1")
        val domain = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
        val configId = ByteArray(16) { it.toByte() }; val salt = ByteArray(16) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 3).toByte() }; val dek = ByteArray(32) { (it + 4).toByte() }
        val wrapNonce = ByteArray(12) { (it + 5).toByte() }
        val contentId = ByteArray(32) { (it + 6).toByte() }
        val kdk = if (password == null) {
            val a16 = tables.encryptBlock(IntArray(16) { salt[it].toInt() and 255 }).map { it.toByte() }.toByteArray()
            sha("npvtunnel/appkey/v2 ".toByteArray() + a16 + configId)
        } else PKCS5S2ParametersGenerator(SHA256Digest()).run {
            init(password.toByteArray(), salt, 100); (generateDerivedParameters(256) as KeyParameter).key
        }
        val prefix = byteArrayOf(1) + configId + curve.g.getEncoded(true) +
            byteArrayOf(if (password == null) 2 else 1) + u16(0) +
            (if (password == null) u16(2) else u32(100)) + salt + wrapNonce + seal(kdk, wrapNonce, dek, salt)
        val metadata = buildJsonObject { put("policy", buildJsonObject {
            put("attestationLevel", "NONE"); put("onlyMobileNetwork", mobile)
            expiry?.let { put("expiresAt", it) }; put("displayMessage", "Synthetic test")
        }) }.toString().toByteArray()
        val sealedMetadata = seal(hk(dek, nonce, "NPVS-v5/metadata".toByteArray()), nonce, metadata, prefix)
        val header = prefix + u32(sealedMetadata.size) + sealedMetadata
        val fields = linkedMapOf(
            1 to "\"Synthetic\"", 2 to "\"server.invalid\"", 3 to "5", 4 to "\"npvs1:NDQz\"",
            5 to "\"11111111-2222-3333-4444-555555555555\"",
            6 to "\"tcp\"", 7 to "\"none\"",
            65535 to """{"configs":[{"name":1,"v2rayProfile":{"server":2,"configType":3,"serverPort":4,"password":5,"network":6,"security":7}}]}""",
        )
        var body = byteArrayOf(78, 80, 70, 1) + contentId + u16(fields.size)
        fields.forEach { (seq, text) ->
            val plain = text.toByteArray()
            val blob = seal(hk(dek, contentId, "NPV-fields-v1/field/".toByteArray() + u16(seq)), ByteArray(12), plain,
                "NPV-fields-v1/record/".toByteArray() + contentId + u16(seq) + u32(plain.size))
            body += u16(seq) + u16(0) + u16(blob.size) + blob
        }
        body += ByteArray(32) // signed reserved trailer
        val envelope = byteArrayOf(78, 80, 86, 83, 5) + u32(header.size) + header + nonce + u32(body.size) + body
        val signer = ECDSASigner(); signer.init(true, ECPrivateKeyParameters(BigInteger.ONE, domain))
        val sig = signer.generateSignature(sha(envelope))
        fun fixed(n: BigInteger) = n.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        return envelope + fixed(sig[0]) + fixed(sig[1])
    }
    private fun parse(bytes: ByteArray, password: String? = null, now: Long = System.currentTimeMillis()) =
        ConfigParser.parseBytes(bytes, ConfigSource.FILE, { CustomConfigTestSupport.tables }, { tables }, password, now)

    @Test fun `signed app-key envelope imports privately with author policy`() {
        val cfg = parse(fixture(mobile = true)).single()
        assertEquals(ConfigProtocol.VLESS, cfg.protocol)
        assertTrue(cfg.mobileOnly); assertTrue(cfg.locked)
        assertEquals("", cfg.raw); assertEquals("", cfg.server)
        assertTrue(cfg.outbound.contains("server.invalid"))
        assertTrue(cfg.outbound.contains("443"))
    }
    @Test fun `password envelope prompts rejects wrong password and opens correct password`() {
        val bytes = fixture(password = "correct-password")
        try { parse(bytes); fail() } catch (e: NpvtPasswordException) { assertFalse(e.wrong) }
        try { parse(bytes, "wrong"); fail() } catch (e: NpvtPasswordException) { assertTrue(e.wrong) }
        assertEquals(1, parse(bytes, "correct-password").size)
    }
    @Test fun `expired author policy is enforced`() {
        try { parse(fixture(expiry = "2020-01-01")); fail() }
        catch (e: ConfigImportException) { assertTrue(e.message!!.contains("expired")) }
    }
    @Test fun `tampering signature truncation and oversized length are refused`() {
        val bytes = fixture()
        val badSignature = bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val badLength = bytes.clone().also { it[5] = 127 }
        for (bad in listOf(badSignature, bytes.copyOf(bytes.size - 1), badLength, bytes + byteArrayOf(0))) {
            try { parse(bad); fail("Invalid envelope accepted") } catch (_: ConfigImportException) { }
        }
    }
}
