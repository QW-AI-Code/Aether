package studio.cluvex.aether.custom

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * 1.7.0 (same version): "the network changed" for automatic config selection.
 *
 * Watches the phone's REAL networks (NOT_VPN, so our own tunnel never counts):
 * Wi-Fi -> mobile data, a different Wi-Fi, or another SIM (each SIM's data
 * connection is its own [Network]). Signal-strength and similar capability
 * updates of the same network do not change the signature and are ignored.
 */
object UnderlyingNetworks {

    /** Quiet time before a change counts: hand-overs come in bursts. */
    const val SETTLE_MS = 3_000L

    /** The current set of real networks as one string; a new value on every change. */
    fun signatures(context: Context): Flow<String> = callbackFlow {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            close()
            return@callbackFlow
        }
        val nets = java.util.concurrent.ConcurrentHashMap<Network, String>()
        fun publish() {
            trySend(nets.values.sorted().joinToString("|"))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                    else -> "other"
                }
                val label = "$kind:$network"
                if (nets.put(network, label) != label) publish()
            }

            override fun onLost(network: Network) {
                if (nets.remove(network) != null) publish()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val registered = runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess
        if (!registered) {
            close()
            return@callbackFlow
        }
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }

    /** One event per real network change after the first stable state (initial state is not a change). */
    @OptIn(FlowPreview::class)
    fun changes(context: Context): Flow<String> =
        signatures(context).debounce(SETTLE_MS).distinctUntilChanged().drop(1)
}
