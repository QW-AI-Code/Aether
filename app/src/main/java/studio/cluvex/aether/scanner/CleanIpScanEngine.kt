package studio.cluvex.aether.scanner

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import studio.cluvex.aether.custom.CustomConfig

/** Shared single/group lifecycle; discovery alone is never sufficient to commit an endpoint. */
internal class CleanIpScanEngine(private val backend: Backend) {
    interface Backend {
        suspend fun currentConfigs(): List<CustomConfig>
        suspend fun discover(config: CustomConfig, excluded: Set<String>, progress: (Int) -> Unit): List<CleanIpValidation.Candidate>
        suspend fun measure(config: CustomConfig, candidates: List<CleanIpValidation.Candidate>): List<Long>
        suspend fun commit(expected: CustomConfig, pick: CleanIpValidation.Pick)
    }
    enum class Outcome { APPLIED, UNCHANGED, FAILED, CANCELLED }
    data class Report(val configId: String, val protocol: String, val previous: String,
        val next: String? = null, val pingMs: Long? = null, val outcome: Outcome)
    data class Progress(val targetId: String, val percent: Int, val validating: Boolean,
        val completed: Int, val total: Int)
    suspend fun run(targets: List<CustomConfig>, manualCeiling: Int?,
        onProgress: (Progress) -> Unit, onResult: (Report) -> Unit) {
        val servers = targets.distinctBy { it.id }
        val assigned = mutableSetOf<String>()
        for ((index, server) in servers.withIndex()) {
            currentCoroutineContext().ensureActive()
            fun progress(value: Int, validating: Boolean = false) =
                onProgress(Progress(server.id, value.coerceIn(0, 100), validating, index, servers.size))
            fun report(outcome: Outcome, pick: CleanIpValidation.Pick? = null) =
                Report(server.id, server.protocol.short, if (server.locked) "" else
                    CleanIpEndpoint.describe(server.outbound)?.let { "${it.ip}:${it.port}" } ?: server.server,
                    pick?.let { "${it.ip}:${it.port}" }, pick?.pingMs, outcome)
            progress(0)
            try {
                check(!server.locked && !server.isExpired()) { "Config is not editable" }
                val current = backend.currentConfigs()
                check(current.firstOrNull { it.id == server.id } == server) { "Config changed before scan" }
                val excluded = current.filter { it.id != server.id }
                    .mapNotNull { CleanIpEndpoint.describe(it.outbound)?.ip }.toSet() + assigned
                val candidates = backend.discover(server, excluded) { progress(it.coerceIn(0, 100) * 70 / 100) }
                    .filter { it.ip !in excluded }
                currentCoroutineContext().ensureActive()
                progress(70, true)
                val pick = CleanIpValidation.select(candidates, manualCeiling,
                    measure = { batch -> backend.measure(server, batch) },
                    progress = { done, total -> progress(70 + done * 29 / total.coerceAtLeast(1), true) })
                currentCoroutineContext().ensureActive()
                if (pick == null) {
                    progress(100); onResult(report(Outcome.UNCHANGED))
                } else {
                    // Publish neither success nor a new ping until protected persistence succeeds.
                    backend.commit(server, pick)
                    assigned += pick.ip
                    progress(100); onResult(report(Outcome.APPLIED, pick))
                }
            } catch (e: CancellationException) {
                onResult(report(Outcome.CANCELLED)); throw e
            } catch (_: Exception) { onResult(report(Outcome.FAILED)) }
        }
    }
}
