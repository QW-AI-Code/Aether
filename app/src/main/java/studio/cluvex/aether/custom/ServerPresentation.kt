package studio.cluvex.aether.custom

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.*
import studio.cluvex.aether.core.NetProbe

/** Display-only numbering: ids of one tab, in stored order, become S1..SN (no gaps). */
internal fun tabServerNumbers(idsInTab: List<String>): Map<String, Long> =
    idsInTab.distinct().mapIndexed { index, id -> id to (index + 1).toLong() }.toMap()

/** Never render user-supplied names, advertisements or credentials in a server row. */
internal fun serverTechnicalLabel(
    protocol: String,
    endpoint: String,
    number: Long? = null,
    locked: Boolean = false,
): String = buildString {
    append(protocol)
    number?.takeIf { it > 0L }?.let { append(" S").append(it) }
    // Locked NapsternetV endpoints must remain private, even if a malformed
    // stored config contains a non-empty server field.
    if (!locked) cleanServerEndpoint(endpoint).takeIf { it.isNotEmpty() }?.let { append(" • ").append(it) }
}

/** An endpoint, never a share link, path, credential, SNI or WS Host advertisement. */
internal fun cleanServerEndpoint(value: String): String = runCatching {
    require(value.isNotBlank() && value.none { it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() })
    val uri = URI("https://$value")
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty())
    val host = requireNotNull(uri.host).removeSurrounding("[", "]").trimEnd('.').lowercase(Locale.ROOT)
    require(uri.port in 1..65535)
    require(host != "workers.dev" && !host.endsWith(".workers.dev"))
    require(NetProbe.validIpLiteral(host) ||
        (host.length <= 253 && host.contains('.') && host.split('.').all {
            it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
                it.all { ch -> ch in 'a'..'z' || ch in '0'..'9' || ch == '-' }
        } && !host.all { it.isDigit() || it == '.' }))
    "${if (host.contains(':')) "[$host]" else host}:${uri.port}"
}.getOrDefault("")

/** Prefer the actual outbound dial target over stale imported display metadata.
 * A worker-only target has no honest clean substitute: omit it, never invent an IP
 * or mislabel a TLS/WS camouflage identity as the server. A clean-IP scan will
 * supply a real dial target later. Locked NPVT metadata is never inspected.
 */
internal fun configDisplayEndpoint(config: CustomConfig): String {
    if (config.locked) return ""
    val target = runCatching {
        val settings = (Json.parseToJsonElement(config.outbound) as? JsonObject)?.get("settings") as? JsonObject
        val servers = (settings?.get("vnext") ?: settings?.get("servers")) as? JsonArray
        val first = servers?.firstOrNull() as? JsonObject
        val address = (first?.get("address") as? JsonPrimitive)?.contentOrNull ?: return@runCatching null
        val port = (first["port"] as? JsonPrimitive)?.intOrNull ?: return@runCatching ""
        cleanServerEndpoint("${if (address.contains(':')) "[${address.removeSurrounding("[", "]")}]" else address}:$port")
    }.getOrNull()
    return target ?: cleanServerEndpoint(config.server)
}
