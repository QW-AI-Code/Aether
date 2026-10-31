package studio.cluvex.aether.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Named connection preset. [profile] is the local PresetProfileCodec snapshot — it never
 * contains Zero Trust secrets (those stay in SecretStore and are merged on apply).
 */
@Immutable
data class ConnectionPreset(
    val id: String,
    val name: String,
    val profile: String,
    val customConfigId: String? = null,
    val customEnabled: Boolean = false,
    val chainMode: String = "SOLO",
    val updatedAt: Long = 0L,
    /** Display name of the custom config, used to re-link an imported profile. */
    val customConfigName: String? = null,
) {
    /** Identity, never the editable/localized display name, grants system protection. */
    val isSystemDefault: Boolean get() = id == SYSTEM_DEFAULT_ID

    fun displayName(defaultLabel: String): String =
        if (isSystemDefault) defaultLabel else name

    fun sameContentAs(o: ConnectionPreset): Boolean =
        profile == o.profile && customConfigId == o.customConfigId &&
            customEnabled == o.customEnabled && chainMode == o.chainMode

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("profile", profile)
        customConfigId?.let { put("customConfigId", it) }
        put("customEnabled", customEnabled)
        put("chainMode", chainMode)
        put("updatedAt", updatedAt)
        customConfigName?.let { put("customConfigName", it) }
    }

    companion object {
        const val SYSTEM_DEFAULT_ID = "aether-system-default"
        const val MAX_NAME_LENGTH = 40

        /** Strips control chars (keeps ZWNJ, needed in Persian), trims, caps length. */
        fun cleanName(raw: String): String =
            raw.replace(Regex("\\p{Cntrl}"), " ").trim().take(MAX_NAME_LENGTH)

        fun fromJson(o: JsonObject): ConnectionPreset? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val id = str("id")?.takeIf { it.isNotBlank() } ?: return null
            val profile = str("profile") ?: return null
            return ConnectionPreset(
                id = id,
                name = cleanName(str("name").orEmpty()).ifEmpty { id.take(8) },
                profile = profile,
                customConfigId = str("customConfigId")?.takeIf { it.isNotBlank() },
                customEnabled = (o["customEnabled"] as? JsonPrimitive)?.booleanOrNull ?: false,
                chainMode = str("chainMode") ?: "SOLO",
                updatedAt = (o["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                customConfigName = str("customConfigName")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
