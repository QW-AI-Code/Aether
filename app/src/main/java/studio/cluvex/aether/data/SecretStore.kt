package studio.cluvex.aether.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM secret vault for the Zero Trust credentials introduced with engine
 * v1.5.0 (Access service-token client secret, enrolment JWT).
 *
 * WHY THIS EXISTS
 * ---------------
 * Everything else in the connection profile is a preference; losing it is
 * cosmetic. An Access service token is a long-lived credential for someone's
 * *organization*. Keeping it next to the MTU setting in a plain DataStore
 * protobuf would mean it lands in any backup of the app's data dir, is
 * readable with a file manager on a rooted device, and survives in plaintext
 * after the user clears the field.
 *
 * So the value is sealed with an AES-256-GCM key generated inside the Android
 * Keystore and marked non-exportable: the raw key never enters the app's
 * address space, and the ciphertext alone is useless on another device.
 *
 * Layout: Base64( iv(12) || ciphertext||tag ).
 */
class SecretStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("aether_secrets", Context.MODE_PRIVATE)

    /** Reads and decrypts [name]; returns "" when unset or undecryptable. */
    fun read(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return runCatching {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size <= IV_LEN) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_LEN)),
            )
            String(cipher.doFinal(blob.copyOfRange(IV_LEN, blob.size)), Charsets.UTF_8)
        }.getOrElse {
            // Key invalidated or restored onto another device: drop the
            // unusable ciphertext instead of keeping a value we can't read.
            prefs.edit().remove(name).apply()
            ""
        }
    }

    /** Encrypts and stores [value]; a blank value clears the entry. */
    fun write(name: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(name, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
        }
    }

    /** Wipes every sealed secret (used by "Reset settings"). */
    fun clear() = prefs.edit().clear().apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately NOT user-authentication bound: the VPN must be
                // able to reconnect unattended (boot, network change).
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ACCESS_SECRET = "access_client_secret"
        const val ACCESS_TOKEN = "access_token"

        /**
         * The pre-connect Zero Trust sign-in (1.4.0-r9): the enrolment JWT that
         * Cloudflare issued for the e-mail code typed in Settings, bound to the
         * team and the address it was issued for. One sealed value, so the binding
         * is inside the ciphertext. See [TeamSignInStore] and
         * [studio.cluvex.aether.core.TeamSignInRecord].
         */
        const val ACCESS_SIGNIN = "access_signin"

        /**
         * The user's own Gemini API key (1.2.9 AI features).
         *
         * Sealed here for the same reason the Access secret is, plus one that is
         * specific to it: a Gemini key is a BILLABLE credential tied to the
         * user's Google account, so a leak out of a backup or off a rooted
         * device means somebody else spending the user's quota under the user's
         * identity. It therefore never lands in the plain DataStore preferences
         * file next to the MTU, it is scrubbed out of anything the AI layer
         * sends to Google (see [studio.cluvex.aether.ai.AiRedaction]), and it
         * travels in a request HEADER rather than a query string so it cannot
         * end up inside a URL that something else logs.
         */
        const val GEMINI_KEY = "gemini_api_key"

        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "aether_secret_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}
