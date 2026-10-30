package studio.cluvex.aether.scanner

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import studio.cluvex.aether.custom.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class CleanIpScanEngineTest {
    private fun config(id: String, ip: String = "104.16.0.10") = CustomConfig(id, "@advertisement", ConfigProtocol.VLESS,
        ConfigFormat.URI, ConfigSource.LINK,
        """{"protocol":"vless","settings":{"vnext":[{"address":"$ip","port":443,"users":[{"id":"fixture-identity"}]}]},"streamSettings":{"network":"ws","security":"tls","tlsSettings":{"serverName":"example.invalid"},"wsSettings":{"path":"/fixture","headers":{"Host":"example.invalid"}}}}""",
        server = "$ip:443")
    private class FakeBackend(initial: List<CustomConfig>) : CleanIpScanEngine.Backend {
        var current = initial
        var latency: Long = 80L
        var failCommit = false
        var measurements = 0
        var commits = 0
        val exclusions = mutableListOf<Set<String>>()
        var onMeasure: (suspend () -> Unit)? = null
        override suspend fun currentConfigs() = current
        override suspend fun discover(config: CustomConfig, excluded: Set<String>, progress: (Int) -> Unit): List<CleanIpValidation.Candidate> {
            exclusions += excluded; progress(0); progress(50); progress(100)
            return listOf("104.16.0.1", "104.16.0.2").filter { it !in excluded }.map { CleanIpValidation.Candidate(it, 443) }
        }
        override suspend fun measure(config: CustomConfig, candidates: List<CleanIpValidation.Candidate>): List<Long> {
            measurements++; onMeasure?.invoke(); return candidates.map { latency }
        }
        override suspend fun commit(expected: CustomConfig, pick: CleanIpValidation.Pick) {
            check(!failCommit); check(current.firstOrNull { it.id == expected.id } == expected)
            val updated = expected.copy(server = "${pick.ip}:${pick.port}", outbound = CleanIpEndpoint.replace(expected.outbound, pick.ip, pick.port))
            current = current.map { if (it.id == expected.id) updated else it }; commits++
        }
    }
    @Test fun singleScanReportsOnlyAfterValidatedCommitAndKeepsProtocolIdentity() = runBlocking {
        val original = config("one"); val b = FakeBackend(listOf(original)); val progress = mutableListOf<Int>()
        val reports = mutableListOf<CleanIpScanEngine.Report>()
        CleanIpScanEngine(b).run(listOf(original), 2000, { progress += it.percent }, { check(b.commits == 1); reports += it })
        assertEquals(CleanIpScanEngine.Outcome.APPLIED, reports.single().outcome)
        assertEquals(original.server, reports.single().previous); assertEquals("104.16.0.1:443", reports.single().next)
        assertEquals(80L, reports.single().pingMs); assertTrue(b.measurements >= 4)
        assertEquals(original.id, b.current.single().id)
        val out = org.json.JSONObject(b.current.single().outbound)
        assertEquals("example.invalid", out.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("serverName"))
        assertEquals(progress.sorted(), progress); assertEquals(100, progress.last())
    }
    @Test fun overLimitKeepsTheOriginalEndpointAndDoesNotCommit() = runBlocking {
        val original = config("one"); val b = FakeBackend(listOf(original)); b.latency = 2001
        val results = mutableListOf<CleanIpScanEngine.Report>()
        CleanIpScanEngine(b).run(listOf(original), 2000, {}, { results += it })
        assertEquals(listOf(original), b.current); assertEquals(0, b.commits)
        assertEquals(CleanIpScanEngine.Outcome.UNCHANGED, results.single().outcome); assertNull(results.single().next)
    }
    @Test fun groupUsesDistinctEndpointsAndProcessesEveryServerOnce() = runBlocking {
        val initial = listOf(config("one"), config("two", "104.16.0.11")); val b = FakeBackend(initial)
        val results = mutableListOf<CleanIpScanEngine.Report>()
        CleanIpScanEngine(b).run(initial + initial.first(), null, {}, { results += it })
        assertEquals(2, b.commits); assertEquals(2, results.size)
        assertEquals(2, b.current.map { it.server }.toSet().size)
        assertTrue("104.16.0.1" in b.exclusions.last())
    }
    @Test fun failedCommitNeverEmitsSuccess() = runBlocking {
        val initial = listOf(config("one")); val b = FakeBackend(initial); b.failCommit = true
        val results = mutableListOf<CleanIpScanEngine.Report>()
        CleanIpScanEngine(b).run(initial, 2000, {}, { results += it })
        assertEquals(CleanIpScanEngine.Outcome.FAILED, results.single().outcome)
        assertNull(results.single().next); assertEquals(initial, b.current)
    }
    @Test fun concurrentEditIsKeptInsteadOfBeingOverwritten() = runBlocking {
        val initial = listOf(config("one")); val b = FakeBackend(initial)
        b.onMeasure = { b.current = listOf(initial.single().copy(name = "Edited during scan")) }
        val results = mutableListOf<CleanIpScanEngine.Report>()
        CleanIpScanEngine(b).run(initial, 2000, {}, { results += it })
        assertEquals(0, b.commits); assertEquals("Edited during scan", b.current.single().name)
        assertEquals(CleanIpScanEngine.Outcome.FAILED, results.single().outcome)
    }
    @Test fun stopPropagatesCancellationWithoutSavingAnyCandidate() = runBlocking {
        val initial = listOf(config("one")); val b = FakeBackend(initial); val entered = CompletableDeferred<Unit>()
        b.onMeasure = { entered.complete(Unit); awaitCancellation() }
        val results = mutableListOf<CleanIpScanEngine.Report>()
        val task = launch { CleanIpScanEngine(b).run(initial, null, {}, { results += it }) }
        entered.await(); task.cancelAndJoin()
        assertEquals(0, b.commits); assertEquals(initial, b.current)
        assertEquals(CleanIpScanEngine.Outcome.CANCELLED, results.single().outcome)
    }
    @Test fun lockedAndDeletedRecordsCannotBeScannedOrResurrected() = runBlocking {
        val locked = config("locked").copy(locked = true); val deleted = config("deleted")
        val b = FakeBackend(listOf(locked)); val results = mutableListOf<CleanIpScanEngine.Report>()
        CleanIpScanEngine(b).run(listOf(locked, deleted), null, {}, { results += it })
        assertEquals(0, b.commits); assertEquals(0, b.measurements)
        assertEquals(2, results.count { it.outcome == CleanIpScanEngine.Outcome.FAILED })
    }
}
