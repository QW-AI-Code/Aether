package studio.cluvex.aether

import android.app.Application
import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import studio.cluvex.aether.ai.AiSession
import studio.cluvex.aether.core.DiagnosticsLog
import studio.cluvex.aether.core.IdentityVault
import studio.cluvex.aether.core.SignerIdentity
import studio.cluvex.aether.data.LanguagePrefs
import studio.cluvex.aether.data.ShareCredentials
import studio.cluvex.aether.vpn.AetherVpnService
import java.io.File
import kotlin.concurrent.thread

class AetherApp : Application() {

    /**
     * Applies the in-app language before ANY resource is read.
     *
     * The notification channel name and description are created in [onCreate]
     * from string resources, so the language has to be in place by then -
     * otherwise those two strings would be frozen in the phone's language for
     * the lifetime of the install, because Android only creates a channel once.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let { LanguagePrefs.wrap(it) } ?: base)
    }

    override fun onCreate() {
        super.onCreate()

        // Wire the persistent diagnostics log FIRST, so anything logged during
        // startup (and any crash) is written to disk and survives process death.
        DiagnosticsLog.init(File(filesDir, "diagnostics.log"))
        installCrashHandler()

        // 1.2.9-r3 SECURITY: everything below touches the keystore, the package
        // manager and the filesystem, so it runs OFF the main thread - startup
        // latency is a product feature on a VPN app that is often opened to fix a
        // dead connection. None of it is needed before the first frame.
        thread(name = "aether-secure-init", isDaemon = true) {
            // 1.2.9-r3 SECURITY: is this build the build the project published?
            // Writes a single line into the log, and an unmissable error line if
            // the certificate is not ours. See [SignerIdentity] for why this
            // exists and what it is not.
            //
            // 1.3.1: moved off the main thread. It reads the signing certificate
            // through PackageManager (a binder call) and SHA-256s it, and the
            // result is only ever a log line - nothing on the first frame waits
            // for it, so nothing should have been blocking on it.
            runCatching { SignerIdentity.logIdentity(this) }
            // F-3: a session that ended in a crash leaves the engine's identity
            // (WireGuard private key + WARP device) in the clear. Put it away
            // before anything else can read it. No-op while a tunnel is running.
            runCatching { IdentityVault.sealIfIdle(filesDir) }
            // F-5: load (or mint, once) the credential that LAN proxy clients must
            // present, so the bridge is never asked to expose the network without
            // one. Idempotent; the VPN service calls it too.
            runCatching { ShareCredentials.ensure(this) }
        }

        // 1.2.9 AI: bind the AI store to the process, once.
        //
        // Here rather than in an Activity because the AI settings outlive any
        // screen: the chat survives a back gesture, and the on-connect log analysis
        // has to know whether it is switched on even when the app was started by
        // the Quick Settings tile and no Activity was ever created. Attaching is
        // idempotent and touches no network - it only opens a DataStore.
        AiSession.attach(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /**
     * Captures otherwise-fatal JVM exceptions and flushes them to the on-disk
     * diagnostics log BEFORE the process dies. This is why "after a crash the
     * log was empty": the log lived only in memory. Now the crash cause is
     * persisted and reloaded into the panel on the next launch. (Native faults
     * inside the in-process tunnel can't be caught here, but every line logged
     * up to that instant is already on disk because we flush on every write.)
     *
     * Feature merge: the same stack trace is ALSO written to a small
     * standalone file ([CRASH_FILE]). MainActivity checks for it on the next
     * cold start and opens [CrashReportActivity] so the user can actually SEE
     * and copy the report instead of it hiding inside the diagnostics log.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                DiagnosticsLog.e(
                    "crash",
                    "FATAL on thread '${thread.name}': $throwable\n" +
                        Log.getStackTraceString(throwable),
                )
            }
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    "Thread: ${thread.name}\n\n" + Log.getStackTraceString(throwable),
                )
            }

            // THE ONE EXCEPTION WORTH SURVIVING (1.3.0-r2).
            //
            // android.app.ForegroundServiceDidNotStartInTimeException is not a bug
            // in the code that happens to be running - it is the framework's
            // 10-second penalty for a startForegroundService() request that was
            // never answered with startForeground(). Nothing in the process is
            // corrupt when it arrives, and killing the app while a VPN tunnel is up
            // is strictly worse than the missing notification it complains about:
            // that is the "the app suddenly closed" the 1.3.0 field report
            // describes, and every disconnect used to trigger it.
            //
            // The cause is fixed in AetherVpnService.onStartCommand, which now
            // promotes the service for EVERY action. This is the net underneath:
            // post the notification the framework was waiting for, and only carry
            // on dying if that does not work. Matched by NAME, not by type, because
            // the class is not public API on every release the app supports.
            //
            // Deliberately narrow: no other Throwable is swallowed here. A real bug
            // still ends the process and still shows the crash report on the next
            // start, because a process that keeps running on top of broken state is
            // how a VPN app leaks traffic.
            if (throwable.javaClass.name.contains("ForegroundServiceDidNotStartInTime")) {
                val service = AetherVpnService.live
                val rescued = service != null && runCatching { service.rescueForeground() }
                    .getOrDefault(false)
                if (rescued) {
                    runCatching {
                        DiagnosticsLog.w(
                            "crash",
                            "The system reported a missing startForeground() for the VPN " +
                                "service. The notification has been posted now and the " +
                                "session was NOT torn down - the tunnel keeps running. " +
                                "This is survivable and is not a tunnel fault.",
                        )
                    }
                    runCatching { File(filesDir, CRASH_FILE).delete() }
                    return@setDefaultUncaughtExceptionHandler
                }
            }

            // Every other fatal exception: make sure a dying app does not leave a
            // live engine child process (and with it a half-open tunnel) behind.
            // Bounded, best-effort, and it can never itself throw.
            runCatching { AetherVpnService.live?.killNativesFromCrashHandler() }

            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CHANNEL_ID = "aether_vpn"

        /** Standalone crash report consumed by [CrashReportActivity]. */
        const val CRASH_FILE = "last_crash.txt"
    }
}
