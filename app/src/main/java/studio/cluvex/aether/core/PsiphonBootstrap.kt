package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Everything the engine's Psiphon (core 2.1.0, `--psiphon`) needs on Android and
 * cannot find by itself. 1.4.0-r1.
 *
 * ROOT CAUSE this answers (field log, 1.4.0, `Aether -> Psiphon` on IR-MCI):
 *
 * ```
 * [+] wireguard tunnel validated ... exposing socks5          <- stage 1 fine
 * [*] starting psiphon through the tunnel at 127.0.0.1:1819
 * [-] psiphon: failed to fetch common remote server list: ... Get
 *     "https://s3.amazonaws.com//psiphon/web/.../server_list_compressed":
 *     tls: failed to verify certificate: x509: certificate signed by unknown authority
 * ... (nothing else, until the session is given up)
 * ```
 *
 * Two independent gaps, both introduced when the Psiphon AAR was removed:
 *
 *  1. **No trust store.** `libpsiphon.so` is psiphon-tunnel-core built as a static
 *     GOOS=linux Go binary. Go looks for CA roots in `/etc/ssl/...`, which Android
 *     does not have, so its root pool is EMPTY and every certificate is "signed by
 *     unknown authority" - through the tunnel or not. The AAR never hit this: it is
 *     built with gomobile for GOOS=android and handed its roots by the library.
 *     Fixed here by exporting the system CA store to a PEM bundle and pointing Go
 *     at it three ways (`SSL_CERT_FILE`, `SSL_CERT_DIR`, and Psiphon's own
 *     `TrustedCACertificatesFilename`).
 *  2. **No servers.** The engine starts Psiphon with an empty datastore, so the
 *     remote list above was its ONLY source of servers; when that one download
 *     fails there is nothing to dial, ever. The AAR had an embedded list
 *     (`server_entries.txt`) and so never depended on the download. The list is
 *     restored and handed to the console client as `-serverList` by the engine
 *     patch `psiphon-embedded-servers` (psiphon.rs).
 *
 * `Tor -> Psiphon` runs the same Psiphon, so it shared both failures.
 *
 * ## 1.5.0-r2: the third gap - NO RESOLVER
 *
 * Field log, `Psiphon -> Npvt` and `Psiphon -> VLESS` (the two chains that put
 * Psiphon FIRST, so it has to reach its network by itself):
 *
 * ```
 * [-] psiphon: failed to fetch common remote server list: ... UntunneledResolveIP
 *     ... lookup s3.amazonaws.com on [redacted]: read udp [...]: read: connection refused
 * [-] psiphon: RoundTripperFailedError: ... lookup b-cdn.net on [redacted]: ...
 *     read: connection refused
 * [-] psiphon: DSL: doDiscoverServerEntriesRequest failed
 * ```
 *
 * Not a block: `connection refused` is an ICMP port-unreachable, i.e. nothing was
 * listening where Psiphon sent the query. `libpsiphon.so` is a static GOOS=linux
 * binary and its resolver asks the servers Go finds in `/etc/resolv.conf` - a file
 * Android does not have. With an empty list Go falls back to the loopback
 * resolver, and the phone has none, so EVERY untunneled lookup is refused before
 * it leaves the device: the remote server list, the in-proxy broker, the
 * domain-fronted CDNs. Psiphon is left with nothing to dial and the engine exits.
 *
 * The REVERSE chains (`Npvt -> Psiphon`, `VLESS -> Psiphon`) always worked for the
 * same reason: there Psiphon dials through the config's SOCKS5 listener, so the
 * names are resolved at the far end of the tunnel and its own resolver is never
 * used. That is the whole difference between the two directions.
 *
 * Fixed by handing Psiphon the resolvers of the network the phone is actually on
 * (plus public fallbacks) through its config overlay, which is the one Psiphon
 * setting this app owns. See [writeOverlay].
 */
object PsiphonBootstrap {

    /** The engine's Psiphon datastore, beside (not inside) the old AAR's `psiphon/`. */
    const val STATE_DIR = "psiphon-engine"

    private const val CA_BUNDLE = "ca-bundle.pem"
    private const val SERVER_LIST = "server_entries.txt"
    private const val BASE_CONFIG = "aether-overrides.json"
    private const val ASSET_SERVER_LIST = "server_entries.txt"

    /** Android's system CA directories: APEX (Android 14+) first, then the classic one. */
    private val SYSTEM_CA_DIRS = listOf(
        "/apex/com.android.conscrypt/cacerts",
        "/system/etc/security/cacerts",
    )

    fun dir(filesDir: File): File = File(filesDir, STATE_DIR)

    /**
     * Writes the CA bundle, the embedded server list and the config overlay into
     * the engine's Psiphon directory. Called before a Psiphon session's engine is
     * launched; cheap enough (one PEM of ~150 certificates, one asset copy that is
     * skipped when unchanged) to run on every connect, which also means a system CA
     * update is picked up on the next session.
     */
    fun prepare(context: Context) {
        val dir = dir(context.filesDir).apply { mkdirs() }
        runCatching { writeCaBundle(File(dir, CA_BUNDLE)) }
            .onFailure { DiagnosticsLog.w(TAG, "Could not export the system CA store for Psiphon: ${it.message}") }
        runCatching { copyServerList(context, File(dir, SERVER_LIST)) }
            .onFailure { DiagnosticsLog.w(TAG, "Could not stage Psiphon's embedded server list: ${it.message}") }
        val resolvers = resolvers(context)
        runCatching { writeOverlay(dir, resolvers) }
            .onFailure { DiagnosticsLog.w(TAG, "Could not write Psiphon's config overlay: ${it.message}") }
        DiagnosticsLog.i(TAG, "Psiphon resolvers for untunneled lookups: ${resolvers.joinToString(" ")}")
    }

    /**
     * The DNS servers Psiphon may use for its UNTUNNELED lookups, best first.
     *
     * The network's own resolvers come first: on a network that only answers its
     * own resolver (and blocks UDP/53 to anywhere else) they are the only ones that
     * work at all. The public ones are the fallback for the opposite network, and
     * for the case where Android reports no resolver for the active link.
     */
    internal fun resolvers(context: Context): List<String> =
        (deviceResolvers(context) + PUBLIC_RESOLVERS).distinct().take(MAX_RESOLVERS)

    /** IPv4 resolvers of the active, non-VPN network, as Android reports them. */
    private fun deviceResolvers(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching emptyList<String>()
        val network = cm.activeNetwork ?: return@runCatching emptyList<String>()
        // A VPN link's resolvers are OUR tunnel's; Psiphon dials outside it.
        if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            return@runCatching emptyList<String>()
        }
        cm.getLinkProperties(network)?.dnsServers
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?.filter { it.isNotBlank() && !it.startsWith("127.") }
            .orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Environment for the engine process (and so for the Psiphon child it spawns,
     * which inherits it). Only names files that actually exist, so a failed
     * [prepare] degrades to upstream's behaviour instead of breaking the launch.
     */
    fun env(filesDir: File): Map<String, String> = buildMap {
        val dir = dir(filesDir)
        val bundle = File(dir, CA_BUNDLE)
        if (bundle.isFile && bundle.length() > 0) {
            // Go reads SSL_CERT_FILE / SSL_CERT_DIR on linux; without them a static
            // linux binary on Android has an empty root pool.
            put("SSL_CERT_FILE", bundle.absolutePath)
        }
        SYSTEM_CA_DIRS.filter { File(it).isDirectory }
            .takeIf { it.isNotEmpty() }
            ?.let { put("SSL_CERT_DIR", it.joinToString(":")) }
        val overlay = File(dir, BASE_CONFIG)
        if (overlay.isFile && overlay.length() > 0) put("AETHER_PSIPHON_CONFIG", overlay.absolutePath)
        val list = File(dir, SERVER_LIST)
        if (list.isFile && list.length() > 0) put("AETHER_PSIPHON_EMBEDDED_SERVERS", list.absolutePath)
    }

    private fun writeCaBundle(target: File) {
        val pem = StringBuilder()
        var count = 0
        // The system CAs only: a user-installed CA is exactly what a TLS
        // interception proxy installs, and Psiphon must not trust it.
        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            for (alias in store.aliases()) {
                if (!alias.startsWith("system:")) continue
                val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                pem.append("-----BEGIN CERTIFICATE-----\n")
                Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
                    .chunked(64).forEach { pem.append(it).append('\n') }
                pem.append("-----END CERTIFICATE-----\n")
                count++
            }
        }
        if (count == 0) {
            // Fallback: the store files are PEM already (plus a text dump that Go's
            // PEM reader skips).
            for (path in SYSTEM_CA_DIRS) {
                File(path).listFiles()?.forEach { file ->
                    runCatching { file.readText() }.getOrNull()
                        ?.takeIf { it.contains("BEGIN CERTIFICATE") }
                        ?.let { pem.append(it).append('\n'); count++ }
                }
                if (count > 0) break
            }
        }
        check(count > 0) { "no system CA certificate could be read" }
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(pem.toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not replace ${target.name}" }
        }
        DiagnosticsLog.i(TAG, "Psiphon trust store ready: $count system CA certificate(s).")
    }

    private fun copyServerList(context: Context, target: File) {
        val bytes = context.assets.open(ASSET_SERVER_LIST).use { it.readBytes() }
        if (target.isFile && target.length() == bytes.size.toLong()) return
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not replace ${target.name}" }
        }
        DiagnosticsLog.i(TAG, "Psiphon embedded server list staged (${bytes.size / 1024} KB).")
    }

    /**
     * Laid over the engine's built-in Psiphon config (`--psiphon-config`, read from
     * `AETHER_PSIPHON_CONFIG`). The trust store and the resolvers: everything else
     * stays the engine's.
     *
     * `DNSResolverAlternateServers` is psiphon-tunnel-core's own parameter for
     * "resolvers to use when the system ones are unusable", which on Android with a
     * static linux build is always. Without it every untunneled lookup is refused
     * on the loopback address - see the class note.
     */
    private fun writeOverlay(dir: File, resolvers: List<String>) {
        val json = JSONObject()
        val bundle = File(dir, CA_BUNDLE)
        if (bundle.isFile && bundle.length() > 0) {
            json.put("TrustedCACertificatesFilename", bundle.absolutePath)
        }
        if (resolvers.isNotEmpty()) {
            json.put("DNSResolverAlternateServers", JSONArray(resolvers))
        }
        val target = File(dir, BASE_CONFIG)
        if (json.length() == 0) {
            target.delete()
            return
        }
        target.writeText(json.toString())
    }

    /** Public resolvers used when the network's own are unknown or unusable. */
    private val PUBLIC_RESOLVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")

    /** Enough for redundancy without turning one lookup into a long serial walk. */
    private const val MAX_RESOLVERS = 5

    private const val TAG = "psiphon"
}
