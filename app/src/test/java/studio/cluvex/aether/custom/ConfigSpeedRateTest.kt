package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test

class ConfigSpeedRateTest {
    @Test fun ratesAreActualBytesPerSecondNotBitsOrFabricatedFallbacks() {
        assertEquals(4_000_000L, SpeedRate.bytesPerSecond(8_000_000, 2_000_000_000))
        assertEquals(-1L, SpeedRate.bytesPerSecond(0, 1))
        assertEquals(-1L, SpeedRate.bytesPerSecond(1, 0))
    }
}
