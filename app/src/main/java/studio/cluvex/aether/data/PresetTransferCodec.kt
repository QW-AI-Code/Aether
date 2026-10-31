package studio.cluvex.aether.data

import java.util.UUID
import kotlinx.serialization.json.*
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.custom.ChainMode
import studio.cluvex.aether.model.ConnectionPreset
import studio.cluvex.aether.model.ConnectionProfile

/** Portable, non-secret settings only. Local preset storage is separately encrypted. */
object PresetTransferCodec {
    const val MAX_BYTES = 256 * 1024
    private val privateKeys = setOf("upstream", "teamAuth", "team", "accessId", "accessEmail", "gateway")
    private val allowedKeys = ProfileCodec.encode(ConnectionProfile()).lineSequence()
        .map { it.substringBefore('=') }.filter { it !in privateKeys }.toSet()

    private fun portable(raw: String): String = raw.lineSequence().filter {
        it.substringBefore('=') in allowedKeys
    }.joinToString("\n")

    /** Single-profile export: never silently includes a second Default record. */
    fun encodeSelected(presets: List<ConnectionPreset>, activeId: String?): String =
        encode(listOf(requireNotNull(presets.firstOrNull { it.id == activeId }) { "No active preset" }))

    fun encode(presets: List<ConnectionPreset>): String {
        require(presets.isNotEmpty() && presets.size <= 20) { "Invalid preset count" }
        val text = buildJsonObject {
            put("schemaVersion", 1); put("appVersion", "1.6.0")
            put("presets", JsonArray(presets.map { p -> buildJsonObject {
                put("name", p.name); put("profile", portable(p.profile))
                // Only a reference to the custom config (id/name) and routing switches;
                // server data and credentials never leave the device.
                p.customConfigId?.let { put("customConfigId", it) }
                p.customConfigName?.let { put("customConfigName", it) }
                put("customEnabled", p.customEnabled); put("chainMode", p.chainMode)
            } }))
        }.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Export is too large" }
        return text
    }

    fun decode(text: String): List<ConnectionPreset> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Import is too large" }
        // Depth limit before recursive JSON parsing, ignoring delimiters inside strings.
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when(c) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= 12) { "Import is too deep" } }
            '}', ']' -> depth--
        }
        val obj = Json.parseToJsonElement(text).jsonObject
        require(obj["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported preset schema" }
        require(obj["appVersion"]?.jsonPrimitive?.content == "1.6.0") { "Unsupported app version" }
        val list = obj["presets"]?.jsonArray ?: error("Presets missing")
        require(list.size in 1..20) { "Invalid preset count" }
        return list.map { item ->
            val o = item.jsonObject
            val name = ConnectionPreset.cleanName(o["name"]?.jsonPrimitive?.content.orEmpty())
            require(name.isNotEmpty()) { "Preset name missing" }
            val raw = o["profile"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("Profile missing")
            require(raw.length <= 32_768 && '\r' !in raw) { "Invalid profile size or encoding" }
            val lines = raw.lines().filter { it.isNotEmpty() }
            val keys = lines.map { it.substringBefore('=') }
            require(keys.distinct().size == keys.size && keys.all { it in allowedKeys }) { "Unknown, duplicate or sensitive field" }
            require(lines.all { '=' in it }) { "Malformed profile field" }
            val profile = ProfileCodec.decode(raw)
            val normalized = ProfileCodec.encode(profile).lines().associate { it.substringBefore('=') to it.substringAfter('=') }
            // Detect invalid enum, boolean or numeric values instead of silently defaulting.
            require(lines.all { normalized[it.substringBefore('=')] == it.substringAfter('=') }) { "Invalid profile value" }
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }?.take(200)
            val mode = str("chainMode")?.let { m -> ChainMode.values().firstOrNull { it.name == m } } ?: ChainMode.SOLO
            ConnectionPreset(
                UUID.randomUUID().toString(), name, portable(ProfileCodec.encode(profile)),
                customConfigId = str("customConfigId"),
                customEnabled = (o["customEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                chainMode = mode.name,
                customConfigName = str("customConfigName"),
            )
        }
    }
}
