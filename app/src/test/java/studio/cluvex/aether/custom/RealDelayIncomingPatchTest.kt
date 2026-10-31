package studio.cluvex.aether.custom

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Offline contracts for the final attached parser: malformed input returns null. */
class RealDelayIncomingPatchTest {
    @Test fun definesProbeTargetAndColdRequestBudget() {
        assertEquals(listOf("www.gstatic.com"),RealDelay.TARGETS)
        assertEquals("/generate_204",RealDelay.TARGET_PATH)
        assertEquals(12000,RealDelay.REQUEST_TIMEOUT_MS)
    }
    @Test fun legacyOkConstructorStillCompiles() {
        assertEquals(123L,RealDelay.Result.Ok(123).ms)
    }
    @Test fun parsesSupportedStatusAndPreservesUnexpectedCodes() {
        assertEquals(204,RealDelay.parseStatusLine("HTTP/1.1 204 No Content")!!)
        assertEquals(204,RealDelay.parseStatusLine("HTTP/1.0 204")!!)
        assertEquals(403,RealDelay.parseStatusLine("HTTP/1.1 403 Forbidden")!!)
        assertEquals(403,RealDelay.UnexpectedStatus(403).code)
    }
    @Test fun rejectsMalformedStatusLines() {
        for(v in listOf("","HTTP/2 204","HTTP/1.1 OK","HTTP/1.1 20","HTTP/1.1 2044","HTTP/1.1 099","HTTP/1.1 600","HTTP/1.1 204oops","HTTP/1.1  204","HTTP/1.1 204\r\nInjected: value"))
            assertNull("accepted $v",RealDelay.parseStatusLine(v))
    }
    @Test fun rejectsNonNumericHttpVersion() {
        for(v in listOf("HTTP/1.X 204 No Content","HTTP/1.x 204","HTTP/1.2 204","HTTP/1.9 204","HTTP/9.1 204"))
            assertNull("Malformed or unsupported HTTP version accepted: $v",RealDelay.parseStatusLine(v))
    }
    private fun readCrlf(input:InputStream,max:Int):String {
        val method=RealDelay::class.java.getDeclaredMethod("readCrlfLine",InputStream::class.java,Int::class.javaPrimitiveType)
        method.isAccessible=true
        try { return method.invoke(RealDelay,input,max) as String }
        catch(e:InvocationTargetException) { throw e.targetException }
    }
    @Test fun rejectsPartialLineAndOverlongLine() {
        for(v in listOf("HTTP/1.1 204 No Content","\r","HTTP/1.1 204\n","HTTP/1.1\r 204\r\n")) {
            try { readCrlf(ByteArrayInputStream(v.toByteArray()),256);fail("partial or malformed line accepted") }
            catch(expected:IOException) { }
        }
        try { readCrlf(ByteArrayInputStream("abcdef\r\n".toByteArray()),3);fail("overlong line accepted") }
        catch(expected:IOException) { }
    }
    @Test fun readsCompleteCrlfLinesWithoutStaleBytes() {
        val input=ByteArrayInputStream("HTTP/1.1 204\r\nX-Test: yes\r\n\r\n".toByteArray())
        assertEquals("HTTP/1.1 204",readCrlf(input,256))
        assertEquals("X-Test: yes",readCrlf(input,256))
        assertEquals("",readCrlf(input,256))
    }
    private class SocksResponse(private val response:String):AutoCloseable {
        private val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val port:Int get()=server.localPort
        private val worker=thread(isDaemon=true,name="parser-socks-fixture") {
            try { server.accept().use { s ->
                s.soTimeout=2000
                val input=s.getInputStream();val out=s.getOutputStream()
                fun readN(n:Int):ByteArray { val b=ByteArray(n); var p=0;while(p<n){val k=input.read(b,p,n-p);if(k<0)throw IOException("EOF");p+=k};return b }
                val greeting=readN(3);if(!greeting.contentEquals(byteArrayOf(5,1,0)))throw IOException("bad greeting")
                out.write(byteArrayOf(5,0));out.flush()
                val head=readN(4)
                when(head[3].toInt()) {1->readN(6);3->readN(input.read()+2);4->readN(18);else->throw IOException("ATYP")}
                out.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0));out.flush()
                val request=StringBuilder()
                while(!request.endsWith("\r\n\r\n")){val c=input.read();if(c<0)throw IOException("EOF");request.append(c.toChar());if(request.length>8192)throw IOException("request too large")}
                out.write(response.toByteArray(Charsets.US_ASCII));out.flush()
            } } catch(_:IOException) { }
        }
        override fun close(){server.close();worker.join(2500)}
    }
    @Test fun rejectsNonNumericVersionThroughActualSocksRequest() {
        SocksResponse("HTTP/1.X 204 No Content\r\n\r\n").use { s ->
            try { RealDelay.requestThrough(s.port,"example.invalid",1500,useTls=false,authenticated=false);fail("invalid wire status returned a delay") }
            catch(expected:RealDelay.PartialResponse) { }
        }
    }
    @Test fun acceptsValid204ThroughActualSocksRequest() {
        SocksResponse("HTTP/1.1 204 No Content\r\n\r\n").use { s ->
            assertTrue(RealDelay.requestThrough(s.port,"example.invalid",1500,useTls=false,authenticated=false)>=0L)
        }
    }
    @Test fun rejectsUnexpectedStatusThroughActualSocksRequest() {
        SocksResponse("HTTP/1.1 403 Forbidden\r\n\r\n").use { s ->
            try { RealDelay.requestThrough(s.port,"example.invalid",1500,useTls=false,authenticated=false);fail("403 returned a delay") }
            catch(expected:RealDelay.UnexpectedStatus) { assertEquals(403,expected.code) }
        }
    }
    @Test fun rejectsMalformedResponseHeaderThroughSocks() {
        SocksResponse("HTTP/1.1 204 No Content\r\nMalformedHeader\r\n\r\n").use { s ->
            try { RealDelay.requestThrough(s.port,"example.invalid",1500,useTls=false,authenticated=false);fail("invalid header accepted") }
            catch(expected:RealDelay.PartialResponse) { }
        }
    }
    @Test fun rejectsTruncatedWireStatus() {
        SocksResponse("HTTP/1.1 204 No Content").use { s ->
            try { RealDelay.requestThrough(s.port,"example.invalid",1500,useTls=false,authenticated=false);fail("truncated status returned a delay") }
            catch(expected:RealDelay.PartialResponse) { }
        }
    }
    @Test fun rejectsLfOnlyWireStatus() {
        SocksResponse("HTTP/1.1 204 No Content\n\n").use { s ->
            try { RealDelay.requestThrough(s.port,"example.invalid",1500,useTls=false,authenticated=false);fail("LF-only status returned a delay") }
            catch(expected:RealDelay.PartialResponse) { }
        }
    }
}
