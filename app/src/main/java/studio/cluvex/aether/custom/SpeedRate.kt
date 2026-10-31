package studio.cluvex.aether.custom

/** Pure actual-byte throughput calculation; nonmeasurements remain unavailable. */
internal object SpeedRate {
    fun bytesPerSecond(bytes: Long, nanos: Long): Long =
        if (bytes <= 0 || nanos <= 0) -1 else (bytes.toDouble() * 1_000_000_000 / nanos).toLong()
}
