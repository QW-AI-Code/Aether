package studio.cluvex.aether.core

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

/** Offline regressions for the exact incoming NetProbe patch. No real endpoints. */
class NetProbeIncomingPatchTest {
    @Test fun normalizesAssignedCodesAndRtlFormatting() {
        assertEquals("DE", NetProbe.normalizeCountryCode(" de "))
        assertEquals("IR", NetProbe.normalizeCountryCode("\u200fi\u00a0r\u202c"))
    }
    @Test fun unknownAndUnassignedCodesHaveNoEmoji() {
        for (v in listOf(null,"","  ","ZZ","XX","QQ","T1","XK","USA","1R","ای")) {
            assertNull("raw=$v",NetProbe.normalizeCountryCode(v))
            assertNull("raw=$v",NetProbe.flagEmojiOrNull(v))
        }
    }
    @Test fun countryNormalizationDoesNotDependOnTurkishLocale() {
        val old=Locale.getDefault()
        try { Locale.setDefault(Locale("tr","TR")); assertEquals("IR",NetProbe.normalizeCountryCode("ir")) }
        finally { Locale.setDefault(old) }
    }
    @Test fun knownFlagIsExactlyTwoRegionalIndicatorCodepoints() {
        val flag=NetProbe.flagEmojiOrNull("de")!!
        assertArrayEquals(intArrayOf(0x1F1E9,0x1F1EA),flag.codePoints().toArray())
    }
    @Test fun legacyFlagApiRemainsCompatibleForUnknown() {
        assertEquals(NetProbe.FLAG_UNKNOWN,NetProbe.flagEmoji(null))
    }
    @Test fun acceptsValidIpLiteralsWithoutResolution() {
        for(v in listOf("0.0.0.0","255.255.255.255","192.0.2.1","::","::1","2001:db8::1","2605:e440:1::2:c3","ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff","::ffff:192.0.2.1"))
            assertTrue(v,NetProbe.validIpLiteral(v))
    }
    @Test fun rejectsMalformedAndHostLikeIpValues() {
        for(v in listOf("","example.com","256.1.1.1","1.2.3","1.2.3.4.5",":::","1::2::3","1:2:3:4:5:6:7","1:2:3:4:5:6:7:8:9","2001:db8::g","fe80::1%wlan0","1::2:","::ffff:999.1.1.1"))
            assertFalse(v,NetProbe.validIpLiteral(v))
    }
    @Test fun validatesIpAcrossAllResponseFormats() {
        for(body in listOf("{\"ip\":\"example.com\"}","{\"query\":\"bad\",\"countryCode\":\"DE\"}","ip=999.1.1.1\nloc=DE\n"))
            assertNull(NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n$body"))
    }
    @Test fun parsesTraceAndCountrylessIpify() {
        assertEquals(IpInfo("2001:db8::1","DE"),NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\nip=2001:db8::1\nloc=de\n"))
        assertEquals(IpInfo("192.0.2.1",null),NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\n{\"ip\":\"192.0.2.1\"}"))
        assertNull(NetProbe.parseIpInfo("HTTP/1.1 200 OK\r\n\r\nip=192.0.2.1\nloc=DEextra\n")!!.countryCode)
    }
    @Test fun ipv4PreferenceKeepsIpv4CountryAndIpv6Alternate() {
        val v6=IpInfo("2001:db8::1","DE"); val v4=IpInfo("192.0.2.1","US")
        assertEquals(IpInfo("192.0.2.1","US","2001:db8::1"),NetProbe.mergeDualStack(v6,v4,v6))
    }
    @Test fun ipv6FallbackAndNoAnswerRemainHonest() {
        val v6=IpInfo("2001:db8::1",null)
        assertEquals(v6,NetProbe.mergeDualStack(v6,null,v6))
        assertNull(NetProbe.mergeDualStack(null,null,null))
    }
    @Test fun originalConstructorCallSitesRemainSourceCompatible() {
        assertNull(IpInfo("192.0.2.1","DE").alternateIp)
        assertNull(IpEndpoint("192.0.2.1","DE",true).alternateIp)
    }
}
