package studio.cluvex.aether.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.7.0 (same version): the vault never seals or restores an identity that is not
 * UTF-8 text - the copy that made every engine start die with
 * `stream did not contain valid UTF-8` in the IR-MCI field log.
 */
class IdentityVaultUtf8Test {

    @Test
    fun `engine TOML is accepted`() {
        val toml = "device_id = \"d\"\naccess_token = \"t\"\nwg_private_key = \"AAAA\"\n"
        assertTrue(IdentityVault.isUtf8Text(toml.toByteArray(Charsets.UTF_8)))
        assertTrue(IdentityVault.isUtf8Text("name = \"ایران\"\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `shredded noise is rejected`() {
        assertFalse(IdentityVault.isUtf8Text(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x80.toByte())))
        assertFalse(IdentityVault.isUtf8Text(byteArrayOf(0x41, 0x00, 0x42)))
    }
}
