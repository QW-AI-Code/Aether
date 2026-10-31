package studio.cluvex.aether.custom

import java.net.Socket
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class RealDelayDeadlineTest {
    private class TrackedSocket: Socket() {
        var closes=0
        override fun close() { closes++ }
    }
    @Test fun completedTimerCannotCloseTheNextKeepAliveRequest() {
        val channel=TrackedSocket()
        val first=RealDelay.ProbeSession.AttemptDeadline()
        first.watch(channel); first.finish()
        val next=RealDelay.ProbeSession.AttemptDeadline()
        next.watch(channel)
        first.expire() // scheduled callback started despite cancel(false)
        assertEquals(0,channel.closes)
        next.expire(); assertEquals(1,channel.closes)
    }
    @Test fun expiredAttemptRejectsAndClosesLateSocketRegistration() {
        val timer=RealDelay.ProbeSession.AttemptDeadline()
        timer.expire()
        val socket=TrackedSocket()
        assertThrows(SocketTimeoutException::class.java) { timer.watch(socket) }
        assertEquals(1,socket.closes)
    }
}
