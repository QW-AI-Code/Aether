package studio.cluvex.aether.scanner

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ScanNoticeLifecycleTest {
    @Test fun timerExpiresAndAnOldTimerCannotEraseANewerNotice() = runBlocking {
        assertEquals(4000L, CleanIpScanManager.NOTICE_DURATION_MS)
        CleanIpScanManager.postMessage("finished", 20)
        CleanIpScanManager.invalidateMessage() // Delayed observer for an already committed result.
        assertEquals("finished", CleanIpScanManager.state.value.message)
        val old = CleanIpScanManager.state.value.messageId
        CleanIpScanManager.postMessage("failed", 100)
        CleanIpScanManager.clearMessage(old)
        assertEquals("failed", CleanIpScanManager.state.value.message)
        withTimeout(2000) { CleanIpScanManager.state.first { it.message.isEmpty() } }
        assertFalse(CleanIpScanManager.state.value.running)
    }
}
