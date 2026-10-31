package studio.cluvex.aether.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.model.ConnectionState

class TunnelExitMonitorTest {
    @Test fun `custom and chain exit addresses use published port not engine default`() {
        assertEquals(18831, TunnelExitMonitor.endpoint("127.0.0.1:18831")!!.port)
        assertEquals(18901, TunnelExitMonitor.endpoint("127.0.0.1:18901")!!.port)
        assertEquals("::1", TunnelExitMonitor.endpoint("[::1]:18831")!!.host)
    }
    @Test fun `malformed remote and invalid port endpoints are refused`() {
        listOf("example.invalid:443", "192.0.2.1:443", "127.0.0.1:0", "127.0.0.1:65536", "bad").forEach {
            assertNull(TunnelExitMonitor.endpoint(it))
        }
    }
    @Test fun `connected custom session queries immediately without verification`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        val offered = CompletableDeferred<IpEndpoint>()
        val job = launch {
            TunnelExitMonitor.watch(states, { null }, {}, { offered.complete(it) }, fetch = {
                assertEquals(18831, it.port); IpEndpoint("192.0.2.5", "DE", false)
            })
        }
        val result = withTimeout(2_000) { offered.await() }
        assertTrue(result.viaTunnel)
        assertEquals("192.0.2.5", result.ip)
        job.cancelAndJoin()
    }
    @Test fun `verified diagnostic ip is not overwritten by second lookup`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            TunnelExitMonitor.watch(states, { IpEndpoint("192.0.2.9", "FR", true) }, {}, {}, fetch = { calls++; null })
        }
        yield(); assertEquals(0, calls)
        job.cancelAndJoin()
    }
    @Test fun `cold start retries and clears loading after success`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        val offered = CompletableDeferred<Unit>()
        var calls = 0; var loading = false
        val job = launch {
            TunnelExitMonitor.watch(states, { null }, { loading = it }, { offered.complete(Unit) }, fetch = {
                if (++calls < 3) null else IpEndpoint("192.0.2.1", null, true)
            }, retryMs = 0)
        }
        withTimeout(2_000) { offered.await() }; yield()
        assertEquals(3, calls); assertFalse(loading)
        job.cancelAndJoin()
    }
    @Test fun `busy phase clears stale exit metadata for next session`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Selecting)
        var ip: IpEndpoint? = IpEndpoint("192.0.2.9", "FR", true)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            TunnelExitMonitor.watch(states, { ip }, {}, {}, fetch = { null }, clear = { ip = null })
        }
        yield(); assertNull(ip); job.cancelAndJoin()
    }
    @Test fun `cancelled slow lookup cannot publish into next connection`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val next = CompletableDeferred<IpEndpoint>(); val results = mutableListOf<String>()
        val job = launch {
            TunnelExitMonitor.watch(states, { null }, {}, { results += it.ip; next.complete(it) }, fetch = { endpoint ->
                if (endpoint.port == 18831) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    IpEndpoint("192.0.2.1", null, true)
                } else IpEndpoint("192.0.2.2", null, true)
            })
        }
        withTimeout(2_000) { started.await() }
        states.value = ConnectionState.Connected("127.0.0.1:18832")
        yield(); release.complete(Unit)
        assertEquals("192.0.2.2", withTimeout(2_000) { next.await() }.ip)
        assertEquals(listOf("192.0.2.2"), results)
        job.cancelAndJoin()
    }
    @Test fun `provider exception is retried without ending observer`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        val offered = CompletableDeferred<Unit>(); var calls = 0
        val job = launch {
            TunnelExitMonitor.watch(states, { null }, {}, { offered.complete(Unit) }, fetch = {
                if (++calls == 1) throw java.io.IOException("provider unavailable")
                IpEndpoint("192.0.2.3", null, true)
            }, retryMs = 0)
        }
        withTimeout(2_000) { offered.await() }; assertEquals(2, calls); job.cancelAndJoin()
    }
    @Test fun `unanswered metadata clears spinner without changing connected state`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        val done = CompletableDeferred<Unit>(); var calls = 0
        val job = launch {
            TunnelExitMonitor.watch(states, { null }, { if (!it) done.complete(Unit) }, {}, fetch = {
                calls++; null
            }, attempts = 2, retryMs = 0)
        }
        withTimeout(2_000) { done.await() }
        assertEquals(2, calls); assertTrue(states.value is ConnectionState.Connected)
        job.cancelAndJoin()
    }

    @Test fun `same port new connection epoch cancels old metadata lookup`() = runBlocking {
        val first = ConnectionState.Connected("127.0.0.1:18831")
        val second = ConnectionState.Connected("127.0.0.1:18831")
        assertNotEquals(first.epoch, second.epoch)
        val states = MutableStateFlow<ConnectionState>(first)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val offered = CompletableDeferred<IpEndpoint>(); var calls = 0
        val job = launch {
            TunnelExitMonitor.watch(states, { null }, {}, { offered.complete(it) }, fetch = {
                if (++calls == 1) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    IpEndpoint("192.0.2.1", null, true)
                } else IpEndpoint("192.0.2.2", null, true)
            })
        }
        withTimeout(2_000) { started.await() }; states.value = second; yield(); release.complete(Unit)
        assertEquals("192.0.2.2", withTimeout(2_000) { offered.await() }.ip)
        job.cancelAndJoin()
    }

    @Test fun `late cancelled tunnel lookup cannot clear newer direct lookup metadata or spinner`() = runBlocking {
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Connected("127.0.0.1:18831"))
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var loading = false; var ip: IpEndpoint? = null
        val job = launch {
            TunnelExitMonitor.watch(states, { ip }, { loading = it }, { ip = it }, fetch = {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                IpEndpoint("192.0.2.1", null, true)
            }, clear = { ip = null })
        }
        withTimeout(2_000) { started.await() }
        states.value = ConnectionState.Disconnecting; yield()
        states.value = ConnectionState.Idle
        ip = IpEndpoint("192.0.2.9", null, false); loading = true
        release.complete(Unit); delay(30)
        assertEquals("192.0.2.9", ip!!.ip); assertTrue(loading)
        job.cancelAndJoin()
    }

}
