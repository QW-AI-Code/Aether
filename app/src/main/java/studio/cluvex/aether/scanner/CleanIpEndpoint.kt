package studio.cluvex.aether.scanner

import org.json.JSONObject

/** Endpoint-only edits. TLS identity, WS Host/path, credentials and transport are never replaced. */
internal object CleanIpEndpoint {
    data class Endpoint(val ip: String, val port: Int, val tls: Boolean)
    fun describe(outbound: String): Endpoint? = runCatching {
        val root = JSONObject(outbound)
        val protocol = root.optString("protocol")
        require(protocol in setOf("vless", "vmess", "trojan"))
        val stream = root.getJSONObject("streamSettings")
        require(stream.optString("network") in setOf("ws", "httpupgrade", "xhttp"))
        val security = stream.optString("security", "none")
        require(security in setOf("tls", "none", "")) // Never alter REALITY or non-CDN protocols.
        val tls = security == "tls"
        val network = stream.getString("network")
        val transport = stream.optJSONObject(network + "Settings")
        val host = stream.optJSONObject("tlsSettings")?.optString("serverName").orEmpty().ifBlank {
            transport?.optJSONObject("headers")?.optString("Host").orEmpty().ifBlank {
                transport?.optString("host").orEmpty()
            }
        }
        require(host.isNotBlank() && !host.contains('/') && !host.contains(':'))
        val endpoint = root.getJSONObject("settings").getJSONArray(if (protocol == "trojan") "servers" else "vnext")
        require(endpoint.length() == 1)
        val first = endpoint.getJSONObject(0)
        val port = first.getInt("port")
        require(port in if (tls) HTTPS_PORTS else HTTP_PORTS)
        Endpoint(first.getString("address"), port, tls)
    }.getOrNull()
    val HTTPS_PORTS = setOf(443, 8443, 2053, 2083, 2087, 2096)
    val HTTP_PORTS = setOf(80, 8080, 2052, 2082, 2086, 2095, 8880)
    fun applicablePort(endpoint: Endpoint, scannedPort: Int): Int =
        if (endpoint.tls && scannedPort in HTTPS_PORTS) scannedPort else endpoint.port
    fun replace(outbound: String, ip: String, port: Int): String {
        require(CleanIpScanner.isCloudflareIp(ip))
        val endpoint = requireNotNull(describe(outbound))
        require(port in if (endpoint.tls) HTTPS_PORTS else setOf(endpoint.port))
        val root = JSONObject(outbound)
        root.getJSONObject("settings").getJSONArray(if (root.getString("protocol") == "trojan") "servers" else "vnext")
            .getJSONObject(0).put("address", ip).put("port", port)
        return root.toString()
    }
}
