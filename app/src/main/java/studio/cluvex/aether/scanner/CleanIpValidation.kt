// Selection/stability policy ported from QWVPN ServerRepository (GPL-3.0).
package studio.cluvex.aether.scanner

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** TLS/trace results order candidates only. A winner MUST carry real proxy traffic three more times. */
internal object CleanIpValidation {
    data class Candidate(val ip: String, val port: Int)
    data class Pick(val ip: String, val port: Int, val pingMs: Long)
    const val CONFIRM_ROUNDS = 3
    const val VALIDATE_BATCH = 5
    const val MAX_CANDIDATES = 10
    const val VALIDATE_BUDGET_MS = 90_000L
    suspend fun select(candidates: List<Candidate>, manualCeiling: Int?,
        measure: suspend (List<Candidate>) -> List<Long>,
        progress: (Int, Int) -> Unit = { _, _ -> }): Pick? = withTimeoutOrNull(VALIDATE_BUDGET_MS) {
        val ceiling = manualCeiling?.coerceIn(50, 5000)?.toLong() ?: Long.MAX_VALUE
        val passing = mutableListOf<Pair<Candidate, Long>>()
        suspend fun confirm(candidate: Candidate): Pick? {
            val readings = mutableListOf<Long>()
            repeat(CONFIRM_ROUNDS) {
                currentCoroutineContext().ensureActive()
                val reading = measure(listOf(candidate)).singleOrNull() ?: return null
                if (reading !in 0..ceiling) return null
                readings += reading
            }
            return Pick(candidate.ip, candidate.port, readings.sorted()[readings.size / 2])
        }
        var done = 0
        val list = candidates.distinct().take(MAX_CANDIDATES)
        for (batch in list.chunked(VALIDATE_BATCH)) {
            currentCoroutineContext().ensureActive()
            val readings = measure(batch)
            for ((i, candidate) in batch.withIndex()) {
                val ms = readings.getOrNull(i) ?: -1
                if (ms in 0..ceiling) {
                    if (manualCeiling != null) confirm(candidate)?.let { return@withTimeoutOrNull it }
                    else passing += candidate to ms
                }
                progress(++done, list.size)
            }
        }
        for ((candidate, _) in passing.sortedBy { it.second }) {
            currentCoroutineContext().ensureActive()
            confirm(candidate)?.let { return@withTimeoutOrNull it }
        }
        null
    }
}
