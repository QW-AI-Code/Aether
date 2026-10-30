package studio.cluvex.aether.data

import java.util.Base64
import kotlinx.serialization.json.*
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.TorBridges

/** Local snapshot supplements the service codec's omitted Tor fields.
 * Runtime-only flags and SecretStore credentials are deliberately never snapshotted.
 * The supplemental field is excluded from portable export (private bridge lines).
 */
internal object PresetProfileCodec {
    fun encode(p: ConnectionProfile): String {
        val tor = buildJsonObject {
            put("bridges", p.torBridges.name); put("lines", p.torBridgeLines)
            put("country", p.torCountry); put("directSecs", p.torDirectSecs); put("check", p.torCheck)
        }.toString().toByteArray(Charsets.UTF_8)
        return ProfileCodec.encode(p) + "\npresetTor=" + Base64.getEncoder().encodeToString(tor)
    }

    fun decode(raw: String): ConnectionProfile {
        val base = ProfileCodec.decode(raw)
        val extra = raw.lineSequence().firstOrNull { it.startsWith("presetTor=") }?.substringAfter('=') ?: return base
        val tor = Json.parseToJsonElement(Base64.getDecoder().decode(extra).toString(Charsets.UTF_8)).jsonObject
        return base.copy(
            torBridges = TorBridges.valueOf(tor.getValue("bridges").jsonPrimitive.content),
            torBridgeLines = tor.getValue("lines").jsonPrimitive.content,
            torCountry = tor.getValue("country").jsonPrimitive.content,
            torDirectSecs = tor.getValue("directSecs").jsonPrimitive.int,
            torCheck = tor.getValue("check").jsonPrimitive.content,
        )
    }
}
