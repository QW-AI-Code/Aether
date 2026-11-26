package studio.cluvex.aether.core

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import studio.cluvex.aether.R

/**
 * 1.7.0 (same version): which apps use the internet, for the traffic popup on
 * the connection card.
 *
 * Android does not let one app read another app's live byte counters
 * (TrafficStats returns nothing for foreign UIDs since Android 7), and the
 * tunnel core does not keep per-app counters. The supported way is the
 * system's own network statistics ([NetworkStatsManager]), which needs the
 * user to grant "Usage access" to Aether once in system settings. Traffic an
 * app sends through the VPN is attributed to that app by Android itself.
 *
 * The numbers are Android's: they are recorded in time buckets and refreshed by
 * the system, so they lag a little and the first bucket may start somewhat
 * before the connection did. "Active now" means the app's counters grew since
 * the previous refresh of the popup.
 */
object AppTraffic {

    data class Usage(
        val uid: Int,
        val label: String,
        val rxBytes: Long,
        val txBytes: Long,
        val activeNow: Boolean,
    ) {
        val total: Long get() = rxBytes + txBytes
    }

    /** True once the user granted "Usage access" to this app. */
    @Suppress("DEPRECATION")
    fun hasAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Opens the system page where "Usage access" is granted. */
    fun openAccessSettings(context: Context) {
        val withPackage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val plain = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(withPackage) }.isFailure) {
            runCatching { context.startActivity(plain) }
        }
    }

    /**
     * Per-app bytes since [sinceMs] on Wi-Fi and mobile data, largest first.
     * [previous] is the last result (uid -> total bytes) to mark apps active now.
     * Blocking: call from a background dispatcher.
     */
    fun query(context: Context, sinceMs: Long, previous: Map<Int, Long> = emptyMap()): List<Usage> {
        if (!hasAccess(context)) return emptyList()
        val nsm = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val now = System.currentTimeMillis()
        val start = sinceMs.coerceIn(0L, now - 1L)
        val rx = HashMap<Int, Long>()
        val tx = HashMap<Int, Long>()
        @Suppress("DEPRECATION")
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_ETHERNET)) {
            val stats = runCatching { nsm.querySummary(type, null, start, now) }.getOrNull() ?: continue
            try {
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    rx[bucket.uid] = (rx[bucket.uid] ?: 0L) + bucket.rxBytes
                    tx[bucket.uid] = (tx[bucket.uid] ?: 0L) + bucket.txBytes
                }
            } catch (_: Exception) {
            } finally {
                runCatching { stats.close() }
            }
        }
        val pm = context.packageManager
        return rx.keys.union(tx.keys).map { uid ->
            val r = rx[uid] ?: 0L
            val t = tx[uid] ?: 0L
            val before = previous[uid]
            Usage(uid, labelFor(context, pm, uid), r, t, activeNow = before != null && r + t > before)
        }.filter { it.total > 0L }.sortedByDescending { it.total }
    }

    private fun labelFor(context: Context, pm: android.content.pm.PackageManager, uid: Int): String {
        if (uid == Process.myUid()) return context.getString(R.string.app_traffic_self)
        when (uid) {
            NetworkStats.Bucket.UID_REMOVED -> return context.getString(R.string.app_traffic_removed)
            NetworkStats.Bucket.UID_TETHERING -> return context.getString(R.string.app_traffic_tethering)
            Process.SYSTEM_UID -> return context.getString(R.string.app_traffic_system)
        }
        val pkg = runCatching { pm.getPackagesForUid(uid)?.firstOrNull() }.getOrNull()
            ?: return "UID $uid"
        return runCatching {
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
}
