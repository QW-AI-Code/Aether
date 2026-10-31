package studio.cluvex.aether.core

import studio.cluvex.aether.BuildConfig

/**
 * Answers one question, before anything else is worth asking: **is the build in
 * front of me the build I think it is?**
 *
 * ## Why this exists (1.2.8-r5)
 *
 * Rounds r2, r3 and r4 all shipped as `versionName "1.2.8"`, `versionCode 12`,
 * and the engine's own banner printed only the upstream core version
 * (`Aether v1.8.0`) which is byte-identical in all three. There was therefore no
 * way - in the UI, in the exported log, or on the releases page - to tell an APK
 * from two rounds ago apart from today's.
 *
 * The r4 field log is the receipt for what that costs. Five independent strings
 * in it belong to the **r3** engine and Kotlin:
 *
 *  1. `netstack buffers=512KB/128KB` - r4 rewrote that line to
 *     `netstack tcp tx/rx=...KB/...KB (rx = advertised window)`.
 *  2. Not one `[netstack]` telemetry line in six minutes - r4 emits one every
 *     15 s, so roughly two dozen were due.
 *  3. `Latency probe failed (viaTunnel=true): 9.9.9.9:443 Connect timed out` -
 *     r4's [PingMonitor] cannot produce that wording; it reports
 *     `dial failed` / `no answer on an established session` and logs
 *     `Warm latency session up via ...` on success.
 *  4. 260 raw `updated server` notices - r4's Psiphon noise filter collapses
 *     those into one `(+N routine server-list notices suppressed)` line.
 *  5. The cached 396 ms endpoint rejected and a 475 ms one accepted 2 ms later -
 *     the contradiction r4's scan quality gate was written to remove.
 *
 * A whole diagnosis round was spent on a binary that predates the fix under
 * test. No amount of care prevents that; it is a missing build identity. So the
 * identity now exists and it is checked at both ends:
 *
 *  * the APK's own patch level is `BuildConfig.PATCH_LEVEL`, read from the
 *    repo-root `PATCHLEVEL` file at compile time;
 *  * the engine stamps the same value into `libaether.so` (see `build.rs`) and
 *    prints it as its second stdout line;
 *  * `scripts/build-natives.sh` greps the stripped `.so` for it and CI greps it
 *    again inside the packaged APK, so a stale engine cannot be released;
 *  * and [ingest] below compares the two AT RUNTIME and shouts if they differ.
 *
 * The practical upshot for whoever reads the next field log: the answer to "am I
 * even testing the right build?" is on line two, every time, and a mismatch is a
 * `W/build` line that cannot be missed.
 */
object BuildProvenance {

    /** What this APK was built as, e.g. `1.2.8-r5`. */
    val apkPatchLevel: String = BuildConfig.PATCH_LEVEL

    /** What the running engine reported, once it has said so. */
    @Volatile
    var enginePatchLevel: String? = null
        private set

    @Volatile
    private var reported = false

    /** True once the engine has identified itself and it matches this APK. */
    val consistent: Boolean
        get() = enginePatchLevel?.let { it == apkPatchLevel } ?: false

    private val STAMP = Regex("AETHER-BUILD-STAMP:([0-9A-Za-z.\\-]+)")
    private val MISSING_LIB = Regex("library \"([^\"]+)\" not found")

    /** Call on every connect, before the engine starts. */
    fun logApkIdentity() {
        reported = false
        enginePatchLevel = null
        DiagnosticsLog.i(
            "build",
            "APK patch level ${apkPatchLevel} (version ${BuildConfig.VERSION_NAME}, " +
                "code ${BuildConfig.VERSION_CODE}, core ${BuildConfig.CORE_VERSION}). " +
                "Quote this line in any bug report - it is what identifies the build.",
        )
        if (apkPatchLevel == "unstamped") {
            DiagnosticsLog.w(
                "build",
                "This APK has no PATCHLEVEL stamp, so it cannot be identified. " +
                    "Build it from the repository root so PATCHLEVEL is picked up.",
            )
        }
    }

    /**
     * Watches engine stdout for the build stamp and cross-checks it.
     *
     * Cheap: one `contains` on the fast path, so it costs nothing on the
     * thousands of lines that are not the banner.
     */
    fun ingest(line: String) {
        if (reported) return
        // 1.5.0: an engine the dynamic linker refused to start never gets to print
        // its stamp either, and the "STALE engine" verdict below then sent the
        // diagnosis the wrong way. Name the real cause instead.
        if (line.contains("CANNOT LINK EXECUTABLE")) {
            reported = true
            val missing = MISSING_LIB.find(line)?.groupValues?.get(1)
            DiagnosticsLog.e(
                "build",
                "The engine could not be started by Android's dynamic linker" +
                    (missing?.let { " (missing $it)" } ?: "") +
                    ". This is a packaging fault in this APK, not a stale engine and not a " +
                    "network problem: libaether.so needs a library the APK does not carry " +
                    "or the engine cannot see. No connection mode can work until it is fixed.",
            )
            return
        }
        if (!line.contains("AETHER-BUILD-STAMP:")) return
        val found = STAMP.find(line)?.groupValues?.get(1) ?: return
        reported = true
        enginePatchLevel = found
        if (found == apkPatchLevel) {
            DiagnosticsLog.i(
                "build",
                "Engine patch level $found matches the APK. This build is internally consistent.",
            )
        } else {
            DiagnosticsLog.e(
                "build",
                "ENGINE/APK PATCH LEVEL MISMATCH: the APK is $apkPatchLevel but libaether.so " +
                    "is $found. You are testing a STALE ENGINE and any conclusion drawn from " +
                    "this session about the data plane will be wrong - this is exactly what " +
                    "happened in the 1.2.8-r4 round. Rebuild the natives " +
                    "(scripts/build-natives.sh aether) and reinstall before testing further.",
            )
        }
    }

    /**
     * Called when the engine exits without ever identifying itself, which means
     * the engine predates r5 entirely.
     */
    fun noteSilentEngine() {
        if (reported) return
        reported = true
        DiagnosticsLog.w(
            "build",
            "The engine never printed a build stamp, so libaether.so predates 1.2.8-r5 " +
                "while this APK is $apkPatchLevel. The native library in this install is " +
                "STALE; rebuild and reinstall before drawing conclusions from this log.",
        )
    }

    /** One-line summary for the About card and bug reports. */
    fun summary(): String {
        val engine = enginePatchLevel
        return when {
            engine == null -> "$apkPatchLevel (engine not yet reported)"
            engine == apkPatchLevel -> apkPatchLevel
            else -> "$apkPatchLevel / engine $engine — MISMATCH"
        }
    }
}
