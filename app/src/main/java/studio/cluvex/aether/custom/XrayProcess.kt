package studio.cluvex.aether.custom

import android.util.Log
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.core.DiagnosticsLog
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled Xray core (`libxray.so`, Xray-core from XTLS, shipped beside
 * the engine in jniLibs exactly like `libpsiphon.so`) as a child process.
 *
 * The config is written to the app's no-backup directory with owner-only
 * permissions for as long as the process needs it and deleted when it stops:
 * it holds the user's server credentials.
 *
 * Xray is a child of THIS app's process, so its sockets carry the app's UID and
 * are therefore outside the VPN (the TUN excludes this package). A custom
 * config dialling out directly never loops back into its own tunnel.
 */
class XrayProcess(
    private val nativeLibDir: String,
    private val workDir: File,
) {
    @Volatile
    private var process: Process? = null
    private var configFile: File? = null

    /** True when this APK carries the Xray core at all. */
    fun available(): Boolean = File(nativeLibDir, BINARY).exists()

    fun start(configJson: String, tag: String) {
        val hasLeafPin = Regex("\"pinnedPeerCertSha256\"\\s*:").containsMatchIn(configJson)
        val modern = File(nativeLibDir, "libxray-pinned.so")
        if (hasLeafPin && !modern.exists()) throw IllegalStateException(
            "This config requires leaf-certificate pinning, unsupported by bundled Xray v25.12.8. A compatible libxray-pinned.so is required; the pin was NOT bypassed.")
        val bin = if (hasLeafPin) modern else File(nativeLibDir, BINARY)
        if (!bin.exists()) throw IllegalStateException(MISSING)
        stop()
        val dir = File(workDir, "xray").apply { mkdirs() }
        val file = File(dir, "config-$tag.json")
        file.writeText(configJson)
        runCatching {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
        configFile = file
        val builder = ProcessBuilder(bin.absolutePath, "run", "-c", file.absolutePath)
            .directory(dir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("XRAY_LOCATION_ASSET", dir.absolutePath)
            put("XRAY_LOCATION_CONFIG", dir.absolutePath)
            put("HOME", dir.absolutePath)
            put("TMPDIR", dir.absolutePath)
        }
        val proc = builder.start()
        process = proc
        DiagnosticsLog.i(LOG_TAG, "Spawned ${bin.name} ($tag)")
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        if (BuildConfig.DEBUG) Log.i("aether-xray", it)
                        DiagnosticsLog.d(LOG_TAG, redact(it))
                    }
                }
            } catch (_: Exception) {
            } finally {
                DiagnosticsLog.w(LOG_TAG, "Xray output stream closed ($tag).")
            }
        }, "xray-log").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun stop() {
        val proc = process
        process = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(GRACEFUL_EXIT_MS, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
            }
        }
        configFile?.let { runCatching { it.delete() } }
        configFile = null
    }

    companion object {
        const val BINARY = "libxray.so"
        const val LOG_TAG = "xray"
        const val MISSING = "This APK does not contain the Xray core (libxray.so)."
        private const val GRACEFUL_EXIT_MS = 400L

        /** Preferred first port for Xray's local listener; the next free one is taken when busy. */
        private const val PREFERRED_PORT = 18_830

        /** A free loopback port for Xray's inbound, never one of [avoid]. */
        fun pickPort(avoid: Set<Int>): Int {
            for (i in 0 until 60) {
                val port = PREFERRED_PORT + i
                if (port in avoid) continue
                val free = runCatching {
                    ServerSocket().use { s ->
                        s.reuseAddress = false
                        s.bind(InetSocketAddress("127.0.0.1", port))
                    }
                    true
                }.getOrDefault(false)
                if (free) return port
            }
            return ServerSocket(0).use { it.localPort }
        }

        /**
         * Xray prints server addresses, names and user ids in its errors. The log
         * is shareable and a locked NapsternetV config must never reveal its
         * server, so IPv4 is cut to /16 and server names, IPv6 literals and
         * UUIDs are replaced before a line is stored.
         */
        private val IPV4 = Regex("\\b(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b")
        private val UUID = Regex("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")
        private val IPV6 = Regex("\\[?\\b(?:[0-9a-fA-F]{1,4}:){2,7}[0-9a-fA-F]{0,4}\\]?")
        private val HOST = Regex("\\b(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}\\b")
        /** File names and the probe's own public targets are not secrets. */
        private val KEEP_SUFFIX = listOf(".json", ".dat", ".go", ".so")
        private val KEEP_HOSTS = setOf("www.gstatic.com", "cp.cloudflare.com")

        internal fun redact(line: String): String {
            var out = UUID.replace(line, "<uuid>")
            out = HOST.replace(out) { m ->
                val v = m.value
                if (v.lowercase() in KEEP_HOSTS || KEEP_SUFFIX.any { v.lowercase().endsWith(it) }) v else "<host>"
            }
            out = IPV6.replace(out) { m -> 
                val v = m.value
                val colons = v.count { it == ':' }
                // Clock times ("13:05:30") have two colons and no hex letters.
                val looksV6 = colons >= 4 || v.contains("::") || (colons >= 2 && v.any { it.lowercaseChar() in 'a'..'f' })
                if (looksV6) "<ipv6>" else v
             }
            return IPV4.replace(out) { m -> if (m.value.startsWith("127.")) m.value else "${m.groupValues[1]}.${m.groupValues[2]}.x.x" }
        }
    }
}