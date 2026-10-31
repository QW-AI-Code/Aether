package studio.cluvex.aether.custom

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * NapsternetV plain-text export:
 *   line 1: NPVO1
 *   line 2: {"configs":[{"name":..,"address":..,"v2rayProfile":{...}}, ...]}
 * String values may be "npvs1:<base64>" — an ENCODING, not encryption.
 *
 * Output is adapted to NpvtCodec.Decoded so the existing, tested pipeline
 * parseDecodedNpvt -> NpvtLock.check -> npvtOutbound -> fromOutbound is reused unchanged.
 * Connection target comes from v2rayProfile.server/serverPort, never from display "address".
 */
internal object NpvoCodec {
    const val HEADER = "NPVO1"
    private const val SENTINEL = "npvs1:"
    private const val MAX_CONFIGS = 500
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_DEPTH = 48
    private val SENTINEL_VALUE = Regex("^npvs1:([A-Za-z0-9+/=_-]*)$")
    private val json = Json { ignoreUnknownKeys = true }

    fun looksLike(text: String): Boolean {
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!t.startsWith(HEADER)) return false
        val next = t.getOrNull(HEADER.length) ?: return true
        // Rejects e.g. "NPVO10..." or "NPVO1X".
        return next == '\n' || next == '\r' || next == ' ' || next == '\t' || next == '{'
    }

    fun decode(text: String): NpvtCodec.Decoded {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw ConfigImportException("NPVO1 file is too large")
        checkDepth(text)
        val t = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (!looksLike(t)) throw ConfigImportException("not an NPVO1 file")
        val body = t.substring(HEADER.length).trim()
        if (body.isEmpty()) throw ConfigImportException("the NPVO1 file holds no servers")

        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: throw ConfigImportException("NPVO1 body is not a JSON object")
        // Lock semantics of NPVO1 are undocumented: refuse rather than import a locked config unlocked.
        if (hasLock(root)) throw ConfigImportException("locked NPVO1 files are not supported")

        val configs = root["configs"] as? JsonArray
            ?: throw ConfigImportException("NPVO1 file has no configs array")
        if (configs.isEmpty()) throw ConfigImportException("the NPVO1 file holds no servers")
        if (configs.size > MAX_CONFIGS) throw ConfigImportException("NPVO1 file holds too many servers")

        val servers = configs.map { el ->
            val obj = el as? JsonObject ?: throw ConfigImportException("invalid NPVO1 server")
            if (hasLock(obj)) throw ConfigImportException("locked NPVO1 files are not supported")
            val profile = obj["v2rayProfile"] as? JsonObject ?: throw ConfigImportException("NPVO1 V2Ray profile missing")
            val decodedProfile = substitute(profile, 0) as JsonObject
            fun value(key: String) = (decodedProfile[key] as? JsonPrimitive)?.content.orEmpty()
            if (value("configType").toIntOrNull() !in setOf(1, 3, 5, 6)) throw ConfigImportException("unsupported NPVO1 server type")
            if (value("server").isBlank() || value("serverPort").toIntOrNull() !in 1..65535)
                throw ConfigImportException("invalid NPVO1 endpoint")
            if (value("password").isBlank()) throw ConfigImportException("NPVO1 credentials missing")
            if (obj["type"]?.let { (it as? JsonPrimitive)?.content != "V2RAY" } == true)
                throw ConfigImportException("unsupported NPVO1 server type")
            JsonObject(obj + ("v2rayProfile" to decodedProfile) + ("type" to JsonPrimitive("V2RAY")))
        }
        return NpvtCodec.Decoded(1, JsonArray(servers).toString(), "{}")
    }

    private fun hasLock(obj: JsonObject): Boolean =
        listOf("lockConfig", "policy", "lock", "isLocked").any { it in obj }

    private fun checkDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > MAX_DEPTH) throw ConfigImportException("NPVO1 file is nested too deeply") }
                '}', ']' -> depth--
            }
        }
    }

    private fun substitute(value: JsonElement, depth: Int): JsonElement {
        if (depth > MAX_DEPTH) throw ConfigImportException("NPVO1 file is nested too deeply")
        return when (value) {
            is JsonObject -> {
                if (hasLock(value)) throw ConfigImportException("locked NPVO1 files are not supported")
                JsonObject(value.mapValues { (_, v) -> substitute(v, depth + 1) })
            }
            is JsonArray -> JsonArray(value.map { substitute(it, depth + 1) })
            is JsonPrimitive ->
                if (value.isString) decodeSentinel(value.content)?.let { JsonPrimitive(it) } ?: value
                else value
        }
    }

    /** Returns decoded text for a whole-value "npvs1:" sentinel, null for a plain string. */
    internal fun decodeSentinel(s: String): String? {
        if (!s.startsWith(SENTINEL)) return null
        val m = SENTINEL_VALUE.matchEntire(s) ?: throw ConfigImportException("bad npvs1 value encoding")
        val encoded = m.groupValues[1].replace('-', '+').replace('_', '/')
        if (encoded.isEmpty() || !encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}")) ||
            ('=' in encoded && encoded.length % 4 != 0)) throw ConfigImportException("bad npvs1 value encoding")
        val raw = encoded.trimEnd('=')
        val padded = raw + "=".repeat((4 - raw.length % 4) % 4)
        val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
            ?: throw ConfigImportException("bad npvs1 value encoding")
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw ConfigImportException("bad npvs1 value encoding")
        }
    }
}
