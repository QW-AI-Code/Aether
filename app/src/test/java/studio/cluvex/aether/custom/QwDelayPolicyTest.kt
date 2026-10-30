package studio.cluvex.aether.custom

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class QwDelayPolicyTest {
    @Test fun warmupCostIsDiscardedRatherThanReportedAsLatency() {
        var calls = 0
        val result = QwDelayPolicy.measure({ calls++; 1234L }) { calls++; RealDelay.Result.Ok(112) }
        assertEquals(2, calls)
        assertEquals(112L, (result as RealDelay.Result.Ok).ms)
    }
    @Test fun failedWarmupCannotProduceAnApproximateLatency() {
        var measured = false
        val result = QwDelayPolicy.measure({ throw IOException("fixture") }) { measured = true; RealDelay.Result.Ok(1500) }
        assertFalse(measured)
        assertTrue(result is RealDelay.Result.NoReply)
    }
}
