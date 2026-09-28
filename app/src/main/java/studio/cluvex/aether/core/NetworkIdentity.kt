package studio.cluvex.aether.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.security.MessageDigest

/**
 * "Which network is this?" — a stable key for the network the phone is on,
 * without asking for a single new permission.
 *
 * [SmartPlusMemory] needs to tell one network from another; it does not need to
 * know which one. So this produces a short hash and nothing else: no SSID, no
 * operator name, no gateway address ever reaches storage or the log.
 *
 * ## Why not the SSID
 *
 * Reading the SSID of the connected Wi-Fi network requires `ACCESS_FINE_LOCATION`
 * on Android 10 and later. This app ships five permissions and the 1.3.1 work
 * already declined a sixth for a smaller benefit (the battery-optimisation prompt
 * uses `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` rather than the permission
 * that asks directly). A location permission for a cache key would be a bad
 * trade, and users of a censorship-circumvention app are right to refuse it.
 *
 * What is used instead is what the system already hands any app:
 *
 *  * **Cellular** — the operator of the SIM that is actually carrying data
 *    (`SubscriptionManager.getActiveDataSubscriptionId`, the same route the
 *    dual-SIM fix in [SmartAuto] takes) plus the radio generation. Two SIMs on
 *    different carriers are different networks; the same SIM after a reboot is
 *    the same network.
 *  * **Wi-Fi / Ethernet / anything else** — the shape of the link:
 *    `LinkProperties`' DNS servers, its search domains and the gateway of its
 *    default route. In practice that identifies a home router, an office network
 *    and a phone hotspot apart from one another, and it needs no permission.
 *
 * ## What this is not
 *
 * It is not a fingerprint of the user and it is not unique. Two different cafés
 * behind the same ISP router model can collide, and the cost of a collision is
 * one slower connect — the remembered route simply does not work and the race
 * behind it takes over. That is the right failure mode for a cache key, and the
 * reason nothing security-relevant is ever keyed on it.
 */
object NetworkIdentity {

    private const val TAG = "smart+"

    /** Unknown network. Returned when there is nothing to key on at all. */
    const val UNKNOWN = "none"

    /**
     * A short, stable key for the active network, or [UNKNOWN].
     *
     * Cheap: two system lookups and one hash, no I/O and no blocking call.
     */
    fun key(context: Context): String {
        val raw = runCatching { describe(context) }.getOrNull()
        if (raw.isNullOrBlank()) return UNKNOWN
        return hash(raw)
    }

    /** True on cellular. Decides the HTTP/2-before-HTTP/3 rule in [SmartPlusPlan]. */
    fun onMobileData(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)

    // ------------------------------------------------------------------ detail

    private fun describe(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return cellular(context)
        }
        return local(caps, cm.getLinkProperties(network))
    }

    /**
     * Cellular: the data SIM's operator, plus whether this is a metered cellular
     * link at all.
     *
     * `getSimOperator` is the numeric MCC+MNC and needs no permission. It is
     * hashed with everything else, so even that does not survive to storage.
     *
     * The subscription is resolved the same way [SmartAuto.forActiveDataSim] does
     * it, and for the same reason: `getActiveDataSubscriptionId` is API 30 and
     * follows a temporary data switch, `getDefaultDataSubscriptionId` is API 24 and
     * is the best answer below that. minSdk here is 26.
     */
    private fun cellular(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val dataTm = runCatching {
            val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SubscriptionManager.getActiveDataSubscriptionId()
            } else {
                SubscriptionManager.getDefaultDataSubscriptionId()
            }
            if (id != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                tm?.createForSubscriptionId(id)
            } else {
                null
            }
        }.getOrNull() ?: tm
        val operator = dataTm?.simOperator?.takeIf { it.isNotBlank() } ?: "sim?"
        return "cell|$operator"
    }

    /**
     * A local link: DNS servers, search domains and the default gateway.
     *
     * Sorted, because the system does not promise an order and an unstable key is
     * worse than no key. The gateway is taken from the default route rather than
     * from the interface address: a phone behind the same router keeps the same
     * gateway while its own DHCP lease changes.
     */
    private fun local(caps: NetworkCapabilities, lp: LinkProperties?): String {
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }?.sorted()?.joinToString(",").orEmpty()
        val domains = lp?.domains.orEmpty()
        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.hostAddress
            .orEmpty()
        // Nothing to go on: a link with no DNS, no domains and no default route is
        // not a network this can tell apart from the next one.
        if (dns.isEmpty() && domains.isEmpty() && gateway.isEmpty()) return "$kind|bare"
        return "$kind|$gateway|$dns|$domains"
    }

    /** Truncated SHA-256. Twelve hex characters is plenty to tell 32 networks apart. */
    private fun hash(raw: String): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        digest.take(6).joinToString("") { "%02x".format(it) }
    }.getOrElse { UNKNOWN }
}
