package studio.cluvex.aether.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy

/** Exit metadata is informational, independent of the optional connection gate and UI lifetime. */
object TunnelExitMonitor {
    data class Endpoint(val host: String, val port: Int)
    internal fun endpoint(address: String): Endpoint? {
        val port = address.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = address.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
        if (host !in setOf("127.0.0.1", "localhost", "::1")) return null
        return Endpoint(host, port)
    }

    suspend fun watch(states: StateFlow<ConnectionState>, currentIp: () -> IpEndpoint?,
        loading: (Boolean) -> Unit, offer: (IpEndpoint) -> Unit,
        fetch: suspend (Endpoint) -> IpEndpoint?, clear: () -> Unit = {}, attempts: Int = 12, retryMs: Long = 1_000,
        refine: (suspend (Endpoint, IpEndpoint) -> IpEndpoint?)? = null) {
        states.collectLatest { state ->
            if (state !is ConnectionState.Connected) {
                // A cancelled blocking lookup can delay this observer. Never
                // wipe a newer idle/direct lookup while handling an old busy event.
                if (state.isBusy && states.value == state) clear()
                return@collectLatest
            }
            val known = currentIp()?.takeIf { it.viaTunnel }
            if (known != null && (known.countryCode != null || refine == null)) return@collectLatest
            val endpoint = endpoint(state.socksAddr) ?: return@collectLatest
            if (known != null && refine != null) {
                // Diagnostics may have published an IP-only result before Connected.
                // It still needs metadata; never replace it with a different exit IP.
                repeat(3) { attempt ->
                    val updated = try { refine(endpoint, known) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    currentCoroutineContext().ensureActive()
                    if (states.value != state) return@collectLatest
                    if (updated?.ip == known.ip && updated.countryCode != null) {
                        offer(updated.copy(viaTunnel = true)); return@collectLatest
                    }
                    if (attempt < 2) delay(retryMs)
                }
                return@collectLatest
            }
            loading(true)
            try {
                repeat(attempts) { attempt ->
                    val info = try { fetch(endpoint) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                    currentCoroutineContext().ensureActive() // Discard a late result from a stopped/rotated session.
                    if (info != null) {
                        if (states.value == state) offer(info.copy(viaTunnel = true))
                        return@collectLatest
                    }
                    if (attempt + 1 < attempts) delay(retryMs)
                }
            } finally {
                // Loading is shared with the idle/operator lookup owned by the UI.
                // A cancelled tunnel lookup must not clear its newer spinner.
                if (states.value == state) loading(false)
            }
        }
    }
}
