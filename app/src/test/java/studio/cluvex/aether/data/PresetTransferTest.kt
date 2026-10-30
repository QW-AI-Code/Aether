package studio.cluvex.aether.data
import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.core.ProfileCodec
import studio.cluvex.aether.model.*

class PresetTransferTest {
    @Test fun systemProtectionAndLocalizationUseStableIdentityNotTheUserEditableName() {
        val raw = ProfileCodec.encode(ConnectionProfile())
        val baseline = ConnectionPreset(ConnectionPreset.SYSTEM_DEFAULT_ID, "Default", raw)
        val user = ConnectionPreset("user", "Default", raw)
        assertTrue(baseline.isSystemDefault)
        assertFalse(user.isSystemDefault)
        assertEquals("پیش‌فرض", baseline.displayName("پیش‌فرض"))
        assertEquals("Default", user.displayName("پیش‌فرض"))
        assertTrue(ConnectionPreset.fromJson(baseline.toJson())!!.isSystemDefault)
    }

    @Test fun selectedExportPreservesNameAndDoesNotIncludeDefault() {
        val profile = studio.cluvex.aether.core.ProfileCodec.encode(studio.cluvex.aether.model.ConnectionProfile())
        val presets = listOf(studio.cluvex.aether.model.ConnectionPreset("default", "Default", profile),
            studio.cluvex.aether.model.ConnectionPreset("named", "نام ذخیره‌شده", profile))
        val decoded = PresetTransferCodec.decode(PresetTransferCodec.encodeSelected(presets, "named"))
        assertEquals(1, decoded.size)
        assertEquals("نام ذخیره‌شده", decoded.single().name)
    }

    @Test fun `portable round trip keeps supported settings but excludes secrets and custom servers`() {
        val p=ConnectionProfile(gameMode=true,gameDnsServers="1.1.1.1",mtu=1400,
            upstreamProxy="socks5://user:secret@server.invalid:80",accessToken="SECRET_TOKEN",accessClientSecret="SECRET_KEY")
        val preset=ConnectionPreset("id","بازی",ProfileCodec.encode(p),"private-config",true)
        val text=PresetTransferCodec.encode(listOf(preset))
        assertFalse(text.contains("secret"));assertFalse(text.contains("SECRET"));assertTrue(text.contains("private-config"))
        val imported=PresetTransferCodec.decode(text).single()
        assertNotEquals("id",imported.id);assertEquals("private-config",imported.customConfigId);assertTrue(imported.customEnabled)
        val decoded=ProfileCodec.decode(imported.profile)
        assertTrue(decoded.gameMode);assertEquals("1.1.1.1",decoded.gameDnsServers);assertEquals(1400,decoded.mtu)
        assertEquals("",decoded.upstreamProxy)
    }
    @Test fun `invalid schema version values and sensitive injection reject atomically`() {
        val good=PresetTransferCodec.encode(listOf(ConnectionPreset("a","A",ProfileCodec.encode(ConnectionProfile()))))
        val bad=listOf(good.replace("\"schemaVersion\":1","\"schemaVersion\":9"),
            good.replace("1.6.0","1.4.0"),good.replace("mtu=1280","mtu=-9"),
            good.replace("backend=AETHER","upstream=secret\\nbackend=AETHER"))
        bad.forEach { text -> try { PresetTransferCodec.decode(text);fail("Bad settings accepted") } catch(_:Exception){} }
    }
    @Test fun `names preserve Persian ZWNJ and remove control characters`() {
        assertEquals("پیش‌فرض",ConnectionPreset.cleanName("  پیش‌فرض  "))
        assertFalse(ConnectionPreset.cleanName("a\nb").contains('\n'))
    }

    @org.junit.Test fun localSnapshotPreservesPrivateTorButPortableExportOmitsIt() {
        val p = studio.cluvex.aether.model.ConnectionProfile(
            torBridges = studio.cluvex.aether.model.TorBridges.ALWAYS,
            torBridgeLines = "obfs4 192.0.2.1:443 secret-cert\nobfs4 192.0.2.2:443 another-cert",
            torCountry = "ir", torDirectSecs = 12, torCheck = "example.org:443")
        val raw = PresetProfileCodec.encode(p)
        val restored = PresetProfileCodec.decode(raw)
        org.junit.Assert.assertEquals(p.torBridgeLines, restored.torBridgeLines)
        org.junit.Assert.assertEquals(p.torBridges, restored.torBridges)
        org.junit.Assert.assertEquals(p.torCountry, restored.torCountry)
        org.junit.Assert.assertEquals(p.torDirectSecs, restored.torDirectSecs)
        org.junit.Assert.assertEquals(p.torCheck, restored.torCheck)
        val export = PresetTransferCodec.encode(listOf(studio.cluvex.aether.model.ConnectionPreset("x", "test", raw)))
        org.junit.Assert.assertFalse(export.contains("presetTor"))
        org.junit.Assert.assertFalse(export.contains("secret-cert"))
    }
}
