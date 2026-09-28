package studio.cluvex.aether.core

import android.util.Log
import studio.cluvex.aether.BuildConfig
import studio.cluvex.aether.model.ConnectionProfile
import java.io.File

/**
 * Runs the native `aether` engine (shipped as libaether.so) as a child
 * process. On Android an executable packaged in jniLibs is extracted to
 * nativeLibraryDir with the exec bit set, which is exactly what we run.
 *
 * IDENTITY AT REST (audit 1.2.9-r3, F-3): the engine keeps its WARP identity and
 * its WireGuard **private key** in `aether*.toml` next to the app's files, and it
 * has no access to the Android Keystore to protect them. So this class owns that
 * window: [IdentityVault.unsealInto] restores the files immediately before the
 * engine is spawned and [IdentityVault.sealAndShred] puts them away as soon as it
 * has been reaped. The plaintext exists only while the engine is running, which is
 * the window in which the key is in its memory anyway.
 */
class AetherProcess(
    private val nativeLibDir: String,
    private val workingDir: File,
) {
    private var process: Process? = null

    fun start(profile: ConnectionProfile) {
        val bin = File(nativeLibDir, "libaether.so")
        if (!bin.exists()) {
            throw IllegalStateException("Engine binary missing: ${bin.absolutePath}")
        }

        // Hand the engine its identity back BEFORE it looks for it. Cheap (a
        // couple of small files) and it must not be skipped on the reconnect path,
        // otherwise the engine re-provisions a new WARP device on every restart.
        IdentityVault.unsealInto(workingDir)

        val command = mutableListOf(bin.absolutePath).apply { addAll(profile.toArgs()) }
        // 1.4.0-r5 zero-leak sharing: tell the share bridge, from the argv that is
        // ACTUALLY handed to the engine, whether this engine can route anything
        // directly. If it can, the bridge refuses LAN clients until a reconnect
        // starts an engine without direct rules. Ground truth, not intent.
        // 1.4.0 smart routing: the built-in lists travel as a routes file.
        val smartRoutes = SmartLists.engineRoutesFile(workingDir, profile)
        ShareBridge.noteEngineRoutes(
            directRulesActive = command.contains("--route-direct") ||
                (smartRoutes != null && SmartLists.smartDirectActive(profile) && !profile.enginePsiphon),
        )
        val builder = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            putAll(profile.toEnv())
            smartRoutes?.let { put("AETHER_ROUTES_FILE", it.absolutePath) }
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", workingDir.absolutePath)
            // ---- Tor (engine core 2.0.0) ---------------------------------
            //
            // Two paths the engine cannot work out for itself on Android, so they
            // are handed over here rather than guessed at in the engine:
            //
            //  * the directory cache. Tor downloads a consensus and remembers its
            //    guards; without a writable, PERSISTENT directory it would
            //    bootstrap from nothing on every connect, which is the slow first
            //    start users read as a hang.
            //  * the pluggable transports. On Android the only files that may be
            //    executed are the ones packaged in jniLibs, so lyrebird ships as
            //    `libpt-lyrebird.so` and is named explicitly. Without this the
            //    engine searches PATH and the usual system directories, none of
            //    which exist here, and a bridged connect would fail with nothing
            //    to point at.
            if (profile.backend.usesTor) {
                val torDir = File(workingDir, "tor").apply { mkdirs() }
                put("AETHER_TOR_DIR", torDir.absolutePath)
                put("AETHER_TOR_PT_DIR", nativeLibDir)
                val lyrebird = File(nativeLibDir, "libpt-lyrebird.so")
                if (lyrebird.exists()) {
                    // `name=path`, the form core 2.0.0's --tor-pt documents. One
                    // binary serves obfs4, meek_lite and webtunnel.
                    put(
                        "AETHER_TOR_PT",
                        listOf("obfs4", "meek_lite", "webtunnel")
                            .joinToString(";") { "$it=${lyrebird.absolutePath}" },
                    )
                } else {
                    DiagnosticsLog.w(
                        "engine",
                        "No libpt-lyrebird.so in this APK: Tor can still connect directly " +
                            "and through the tunnel, but bridges have no transport to run.",
                    )
                }
            }
        }

        // ---- Psiphon (engine core 2.1.0, app 1.4.0) ------------------------
        // The engine runs psiphon-tunnel-core itself; on Android only files
        // packaged in jniLibs may be executed, so the binary ships as
        // libpsiphon.so and is named explicitly, and its datastore gets a
        // persistent directory of its own.
        if (profile.enginePsiphon) {
            builder.environment().apply {
                val psiphonDir = PsiphonBootstrap.dir(workingDir).apply { mkdirs() }
                put("AETHER_PSIPHON_DIR", psiphonDir.absolutePath)
                // 1.4.0-r1 ROOT-CAUSE FIX: a trust store and an embedded server
                // list. Without them the static-linux console client cannot verify
                // any certificate on Android and has no server to dial, which is
                // why `Aether -> Psiphon` and `Tor -> Psiphon` never connected. See
                // [PsiphonBootstrap] for the field log.
                putAll(PsiphonBootstrap.env(workingDir))
                val psiphonBin = File(nativeLibDir, "libpsiphon.so")
                if (psiphonBin.exists()) {
                    put("AETHER_PSIPHON_BIN", psiphonBin.absolutePath)
                } else {
                    DiagnosticsLog.w(
                        "engine",
                        "No libpsiphon.so in this APK: the engine's Psiphon cannot start.",
                    )
                }
            }
            reapPsiphonOrphans()
        }

        val proc = builder.start()
        process = proc
        IdentityVault.markRunning(true)
        // ZERO TRUST (1.3.1, issue #12): hand the engine's stdin to the login-code
        // prompt. Nothing in the app had ever written to it, which is why the
        // e-mail enrolment path could not work - the engine asks for the code on
        // stdout and waits for it on stdin. See [LoginCodePrompt].
        //
        // Not for a racing engine: that flow needs ONE stdin to write the code to,
        // and a race has two engines. Smart Plus therefore never races a profile
        // with the e-mail flow (SmartPlusPlan.eligible), and a racing engine does
        // not claim the prompt either - so a race can never leave the winner's
        // session attached to a dead lane's stdin.
        if (profile.bindOverride == 0) LoginCodePrompt.attach(proc.outputStream)

        // 1.2.8-r5: say which build this is BEFORE the engine speaks, so the
        // log identifies itself even if the engine dies immediately.
        BuildProvenance.logApkIdentity()
        DiagnosticsLog.i("engine", "Spawned ${bin.name} ${redactArgs(profile.toArgs())}")
        // A new engine means a new bootstrap: the previous run's percentage must
        // not make this one look like it is already progressing.
        TorBootstrap.reset()
        PsiphonEngine.reset()
        // A racing lane is a probe, not the session: its lines are tagged so two
        // interleaved engines can be told apart in the diagnostics panel, and it
        // publishes nothing to the UI. The info row, the Tor bootstrap percentage
        // and the build-provenance cross-check all describe THE SESSION, and a
        // losing lane must not be able to write its endpoint into a row the user
        // reads as "what I am connected through".
        val racing = profile.bindOverride > 0
        val tag = if (racing) "engine/lane:${profile.bindOverride}" else "engine"
        // Drain stdout/stderr so a full pipe never blocks the engine, mirroring
        // every line into both logcat and the in-app diagnostics panel.
        Thread({
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        // SECURITY FIX: the engine's stdout (endpoints, exit
                        // IPs, config echo) must not be mirrored to Logcat in
                        // release builds — Logcat is world-readable via adb and
                        // ends up in bug reports. The in-app diagnostics panel
                        // (app-private file) still receives every line below.
                        if (BuildConfig.DEBUG) Log.i("aether-engine", it)
                        DiagnosticsLog.d(tag, it)
                        if (!racing) {
                            // Desktop-parity info row: pick out the endpoint the
                            // engine selected (no-op for every other line).
                            EngineMeta.ingest(it)
                        }
                        // Tor bootstrap percentage. Without this the app has no
                        // way to tell "Tor is still working" from "Tor is being
                        // blocked", and 1.3.0 shipped a stage gate that assumed
                        // the worse of the two after four seconds.
                        if (!racing) TorBootstrap.ingest(it)
                        if (!racing) PsiphonEngine.ingest(it)
                        // Cross-check the engine's build stamp against this
                        // APK's. See [BuildProvenance] for the r4 round this
                        // single line would have saved. Once per session is
                        // enough; a lane would only repeat the same verdict.
                        if (!racing) BuildProvenance.ingest(it)
                        // ZERO TRUST: "[zerotrust] login-code-needed attempt=N
                        // email=..." means the engine is blocked on stdin waiting
                        // for the code Cloudflare just emailed. Raise it to the UI.
                        if (!racing) LoginCodePrompt.ingest(it)
                    }
                }
            } catch (_: Exception) {
            } finally {
                DiagnosticsLog.w(tag, "Engine output stream closed.")
                // The engine is gone, so its stdin is too: drop any prompt still on
                // screen rather than collecting a code nothing can read. Only the
                // engine that claimed the prompt may drop it - a racing lane exiting
                // must not take the prompt away from the session's engine.
                if (!racing) LoginCodePrompt.detach()
                // An engine that never identified itself is an engine older than
                // r5, i.e. a stale native library in this install. One verdict per
                // session: a racing lane would repeat it for the same binary.
                if (!racing) BuildProvenance.noteSilentEngine()
            }
        }, "aether-log").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /**
     * Blocks until the engine exits or [timeoutMs] elapses; returns true if it
     * exited.
     *
     * 1.2.2 CPU FIX: the VpnService supervisor used to poll `isAlive()` every
     * two seconds for the whole life of the tunnel. That is thousands of
     * pointless wake-ups per session on a connection that is perfectly
     * healthy, and it keeps the CPU out of deep idle. `Process.waitFor` parks
     * the supervisor coroutine on the OS instead: it costs nothing while the
     * engine is running and returns the moment the engine actually dies, so
     * crash detection is FASTER than the old poll while using less power.
     *
     * The bounded overload is used so the supervisor still re-checks its own
     * cancellation state periodically.
     */
    suspend fun awaitExit(timeoutMs: Long): Boolean =
        runCatching {
            // DISCONNECT-LATENCY ROOT CAUSE (fixed): `Process.waitFor` is a
            // BLOCKING java call. Coroutine cancellation cannot interrupt a
            // blocking call, so when the user tapped disconnect the service
            // sat inside this wait until the whole 60 s window expired — which
            // is exactly the 30–50 s "Disconnecting…" freeze. `runInterruptible`
            // maps cancellation onto a real thread interrupt, so `waitFor`
            // throws immediately and the teardown continues within
            // milliseconds — while still costing zero polling when idle.
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
                process?.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: true
            }
        }.getOrDefault(false)

    /**
     * Stops the engine and does not return until the OS has really reaped it.
     *
     * 1.2.2 PROTOCOL-SWITCH FIX: this used to be a fire-and-forget
     * `destroy()`. `destroy()` only *asks* the process to exit, so the old
     * engine was often still alive — and still holding the local SOCKS5
     * listener on 127.0.0.1:1819 — while the next connect was already
     * spawning a new engine. The new engine then either failed to bind or the
     * app's port probe saw the DYING engine's socket and declared "port is
     * up" far too early, which is exactly why switching protocols felt like it
     * hung for tens of seconds and then had to retry. We now wait for the
     * process to actually exit and escalate to SIGKILL if it does not.
     */
    fun stop() {
        val proc = process ?: run {
            // Nothing to reap, but a previous session may still have left the
            // identity in the clear (e.g. start() threw after the unseal).
            sealIfLastEngine()
            return
        }
        process = null
        runCatching {
            proc.destroy()
            // Give the engine a very short, fixed grace period, then SIGKILL.
            // Deliberately short: the user is waiting for the button to turn
            // grey, and the next connect independently waits for the local
            // proxy port to be released, so nothing depends on a long wait
            // here.
            if (!proc.waitFor(GRACEFUL_EXIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        }
        // The engine's Psiphon child must not outlive it (a SIGKILLed engine
        // cannot reap it) and keep holding its port and a tunnel.
        reapPsiphonOrphans()
        // The engine is gone, so the identity files are nobody's working set any
        // more: seal them and shred the plaintext. Done AFTER the reap on purpose -
        // sealing a file the engine is still writing would race its own save.
        sealIfLastEngine()
    }

    /**
     * Seals the identity, but only once the LAST engine has gone.
     *
     * A Smart Plus race has two engines alive at the same time
     * ([studio.cluvex.aether.core.SmartPlusPlan]), and sealing SHREDS the
     * plaintext. Sealing when the first of them is reaped would therefore pull the
     * WARP identity and the WireGuard private key out from under the engine that
     * is still connecting - not a slow path but a broken session and a device that
     * has to enrol again. [IdentityVault.markRunning] returns how many are left.
     */
    private fun sealIfLastEngine() {
        val stillRunning = IdentityVault.markRunning(false)
        if (stillRunning > 0) {
            DiagnosticsLog.d(
                "engine",
                "Engine reaped; $stillRunning still running, so the identity stays unsealed.",
            )
            return
        }
        IdentityVault.sealAndShred(workingDir)
    }

    /** Kills psiphon-tunnel-core processes of this app left behind by an earlier engine. */
    private fun reapPsiphonOrphans() {
        runCatching {
            val self = android.os.Process.myPid()
            File("/proc").listFiles()?.forEach { dir ->
                val pid = dir.name.toIntOrNull() ?: return@forEach
                if (pid == self) return@forEach
                val cmd = runCatching { File(dir, "cmdline").readText() }.getOrNull() ?: return@forEach
                if (cmd.contains("libpsiphon.so")) {
                    android.os.Process.killProcess(pid)
                    DiagnosticsLog.w("engine", "Stopped a leftover Psiphon process ($pid).")
                }
            }
        }
    }

    private companion object {
        /** How long a polite SIGTERM gets before we escalate to SIGKILL. */
        const val GRACEFUL_EXIT_MS = 250L
    }

    /**
     * Flags whose VALUE identifies the user, their organization or the exact
     * endpoint they are using. The flag itself is kept (it is what makes the log
     * useful when a session fails); the value is not.
     *
     * SECURITY (audit 1.2.7-r2): the diagnostics log is app-private but it is
     * also the file users are asked to share when they report a problem, and it
     * survives a crash on disk. A Zero Trust team name, a hand-pinned gateway or
     * the resolver set someone chose because of what their network blocks is
     * exactly the metadata that should not travel out of the device inside a bug
     * report. No secret was ever on the command line (those go through the
     * environment, see ConnectionProfile.toEnv) - this closes the metadata half.
     */
    private val REDACTED_FLAGS = setOf(
        "--team", "--peer", "--dns", "--route-block", "--route-direct",
    )

    /** Renders an argument list with the values of [REDACTED_FLAGS] masked. */
    private fun redactArgs(args: List<String>): String {
        val out = StringBuilder()
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            out.append(if (out.isEmpty()) "" else " ").append(arg)
            if (arg in REDACTED_FLAGS && index + 1 < args.size) {
                out.append(" <redacted>")
                index++
            }
            index++
        }
        return out.toString()
    }
}
