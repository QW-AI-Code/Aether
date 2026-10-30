package studio.cluvex.aether.custom

import java.io.IOException

/** QWVPN private-instance methodology: discard warm-up and never present its startup as latency.
 * Aether uses the executable Xray transport, not the incompatible libv2ray native-delay ABI. */
internal object QwDelayPolicy {
    fun measure(warmUp: () -> Long, measuredRounds: () -> RealDelay.Result): RealDelay.Result {
        try { warmUp() } catch (_: IOException) { return RealDelay.Result.NoReply("Warm-up did not complete; no measured latency") }
        return measuredRounds()
    }
}
