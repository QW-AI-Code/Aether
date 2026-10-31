package studio.cluvex.aether.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.core.ProfileCodec

/**
 * Core 2.4.0 TLS / API options: values the settings screen collects must reach
 * the engine environment exactly as the Windows build sends them
 * (`tls24_env` in src-tauri/src/profile.rs), survive the Intent codec, and an
 * untouched profile must send nothing new.
 */
class Tls24OptionsTest {

    private val names = listOf(
        "AETHER_API_SNI", "AETHER_API_HOST", "AETHER_MASQUE_SNI", "AETHER_TLS13_CIPHERS",
        "AETHER_TLS_KEY_SHARES", "AETHER_TLS_SIGALGS", "AETHER_TLS_CERT_COMPRESSION",
        "AETHER_TLS_ALPS", "AETHER_TLS_TRUST_ANCHORS", "AETHER_TLS_SERVER_PADDING",
        "AETHER_ENABLE_GREASE_SIGALGS", "AETHER_ENABLE_ECH_GREASE", "AETHER_DISABLE_OCSP_STAPLING",
        "AETHER_DISABLE_SCT", "AETHER_DISABLE_SESSION_TICKET", "AETHER_DISABLE_EXTENSION_PERMUTATION",
    )

    private val full = ConnectionProfile(
        masqueSni = "www.cloudflare.com",
        apiSni = "example.com",
        apiHost = "api.cloudflareclient.com",
        tls13Ciphers = "TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256",
        tlsKeyShares = "X25519MLKEM768:X25519",
        tlsSigalgs = "ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256",
        tlsCertCompression = "zstd",
        tlsAlps = "new",
        tlsTrustAnchors = "11129.9.1:11129.9.4",
        tlsServerPadding = "6000",
        enableGreaseSigalgs = true,
        enableEchGrease = true,
        disableOcspStapling = true,
        disableSct = true,
        disableSessionTicket = true,
        disableExtensionPermutation = true,
    )

    @Test fun `untouched profile sends none of the 2_4_0 options`() {
        val env = ConnectionProfile().toEnv()
        for (n in names) assertFalse("$n leaked into an untouched profile", env.containsKey(n))
        assertTrue(Tls24Options.env(ConnectionProfile()).isEmpty())
    }

    @Test fun `every option reaches the engine environment with its value`() {
        val env = full.toEnv()
        assertEquals("www.cloudflare.com", env["AETHER_MASQUE_SNI"])
        assertEquals("example.com", env["AETHER_API_SNI"])
        assertEquals("api.cloudflareclient.com", env["AETHER_API_HOST"])
        assertEquals("TLS_AES_128_GCM_SHA256:TLS_CHACHA20_POLY1305_SHA256", env["AETHER_TLS13_CIPHERS"])
        assertEquals("X25519MLKEM768:X25519", env["AETHER_TLS_KEY_SHARES"])
        assertEquals("ecdsa_secp256r1_sha256:rsa_pss_rsae_sha256", env["AETHER_TLS_SIGALGS"])
        assertEquals("zstd", env["AETHER_TLS_CERT_COMPRESSION"])
        assertEquals("new", env["AETHER_TLS_ALPS"])
        assertEquals("11129.9.1:11129.9.4", env["AETHER_TLS_TRUST_ANCHORS"])
        assertEquals("6000", env["AETHER_TLS_SERVER_PADDING"])
        for (n in listOf(
            "AETHER_ENABLE_GREASE_SIGALGS", "AETHER_ENABLE_ECH_GREASE", "AETHER_DISABLE_OCSP_STAPLING",
            "AETHER_DISABLE_SCT", "AETHER_DISABLE_SESSION_TICKET", "AETHER_DISABLE_EXTENSION_PERMUTATION",
        )) assertEquals(n, "1", env[n])
    }

    @Test fun `existing 2_3_0 and 2_4_0 options still go out`() {
        val env = ConnectionProfile(
            tlsVerify = true, disableGrease = true, tlsGroups = "X25519:P-256",
            apiAddress = "203.0.113.5:443",
        ).toEnv()
        assertEquals("1", env["AETHER_TLS_VERIFY"])
        assertEquals("1", env["AETHER_DISABLE_GREASE"])
        assertEquals("X25519:P-256", env["AETHER_TLS_GROUPS"])
        assertEquals("203.0.113.5:443", env["AETHER_API_ADDRESS"])
    }

    @Test fun `invalid values are dropped, never sent`() {
        val bad = ConnectionProfile(
            apiHost = "evil host; rm", masqueSni = "a b", apiSni = "x=y",
            tlsAlps = "bogus", tlsCertCompression = "brotli:gzip",
            tlsTrustAnchors = "11129.9.1:abc", tlsServerPadding = "70000",
            tls13Ciphers = "TLS AES", tlsKeyShares = "X25519;", tlsSigalgs = "a|b",
        )
        assertTrue(Tls24Options.env(bad).isEmpty())
    }

    @Test fun `list values and bounds follow the Windows rules`() {
        assertEquals("zlib:brotli", Tls24Options.certCompression("zlib:brotli"))
        assertNull(Tls24Options.certCompression(""))
        assertEquals("old", Tls24Options.alps(" old "))
        assertNull(Tls24Options.alps(""))
        assertEquals("0", Tls24Options.serverPadding("0"))
        assertEquals("65535", Tls24Options.serverPadding("65535"))
        assertNull(Tls24Options.serverPadding("65536"))
        assertNull(Tls24Options.serverPadding("-1"))
        assertNull(Tls24Options.serverPadding("1.5"))
        assertNull(Tls24Options.trustAnchors("11129..9"))
        assertNull(Tls24Options.trustAnchors("11129.9.1:"))
        assertNull(Tls24Options.masqueSni("a".repeat(254)))
        assertEquals("a".repeat(253), Tls24Options.masqueSni("a".repeat(253)))
    }

    @Test fun `invalid flags non blank input only`() {
        assertFalse(Tls24Options.invalid("", Tls24Options.apiHost("")))
        assertFalse(Tls24Options.invalid("ok.example", Tls24Options.apiHost("ok.example")))
        assertTrue(Tls24Options.invalid("bad host", Tls24Options.apiHost("bad host")))
    }

    @Test fun `intent codec round trips every option`() {
        val back = ProfileCodec.decode(ProfileCodec.encode(full))
        assertEquals(full.masqueSni, back.masqueSni)
        assertEquals(full.apiSni, back.apiSni)
        assertEquals(full.apiHost, back.apiHost)
        assertEquals(full.tls13Ciphers, back.tls13Ciphers)
        assertEquals(full.tlsKeyShares, back.tlsKeyShares)
        assertEquals(full.tlsSigalgs, back.tlsSigalgs)
        assertEquals(full.tlsCertCompression, back.tlsCertCompression)
        assertEquals(full.tlsAlps, back.tlsAlps)
        assertEquals(full.tlsTrustAnchors, back.tlsTrustAnchors)
        assertEquals(full.tlsServerPadding, back.tlsServerPadding)
        assertTrue(back.enableGreaseSigalgs && back.enableEchGrease && back.disableOcspStapling)
        assertTrue(back.disableSct && back.disableSessionTicket && back.disableExtensionPermutation)
        assertEquals(Tls24Options.env(full), Tls24Options.env(back))
    }

    @Test fun `old payload without the new keys decodes to defaults`() {
        val old = ProfileCodec.encode(ConnectionProfile()).lines()
            .filterNot { l -> listOf("masqueSni=", "apiSni=", "apiHost=", "tls13=", "keyShares=", "sigalgs=",
                "certComp=", "alps=", "trustAnch=", "srvPad=", "grSig=", "echGrease=", "noOcsp=", "noSct=",
                "noTicket=", "noPerm=").any { l.startsWith(it) } }
            .joinToString("\n")
        val back = ProfileCodec.decode(old)
        assertTrue(Tls24Options.env(back).isEmpty())
    }
}
