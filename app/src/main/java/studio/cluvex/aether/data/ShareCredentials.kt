package studio.cluvex.aether.data

import android.content.Context
import studio.cluvex.aether.core.LanGuard
import studio.cluvex.aether.core.ShareBridge
import studio.cluvex.aether.core.ShareLeakGuard

/**
 * VPN-sharing preferences and credential (1.4.0-r5).
 *
 *  - **Mode** (Home Wi-Fi / Mobile-data hotspot) and **authentication on/off**
 *    are plain preferences - neither is a secret.
 *  - **Username + password** live in [SecretStore] (AES-256-GCM under a
 *    non-exportable Android Keystore key), never in a plain preferences file.
 *
 * Authentication is OPTIONAL and off by default (1.4.0-r5): the user turns it on
 * when they want it. A strong password is still generated on first use, so
 * turning it on is one tap, and the user may replace the username/password
 * with their own (validated in [setCustom]).
 *
 * Everything is pushed into [ShareBridge] by [ensure], which is idempotent and
 * called at process start and before any code path that binds the LAN.
 */
object ShareCredentials {

    /** Default username. */
    const val USER = ShareBridge.DEFAULT_USER

    /** Minimum length of a user-chosen password. */
    const val MIN_PASSWORD = 8

    /**
     * Loads mode, auth flag and credential (minting a password once) and hands
     * them to [ShareBridge]. Returns the password.
     */
    fun ensure(context: Context): String {
        // r6: lets the bridge ask Android which interfaces are upstream networks
        // (so tethering downstreams are recognised on every vendor's naming).
        ShareBridge.attach(context)
        val prefs = prefs(context)
        val modeName = prefs.getString(PREF_MODE, null)
        val mode = ShareLeakGuard.Mode.entries.firstOrNull { it.name == modeName }
            ?: ShareLeakGuard.Mode.HOME_WIFI
        ShareBridge.setMode(mode)

        val store = SecretStore(context)
        val user = store.read(KEY_USER).ifBlank { USER }
        var password = store.read(KEY_PASSWORD)
        if (password.isBlank()) {
            password = LanGuard.randomPassword()
            store.write(KEY_PASSWORD, password)
        }
        ShareBridge.setCredentials(user, password)
        ShareBridge.setAuthRequired(prefs.getBoolean(PREF_AUTH, false))
        return password
    }

    fun setMode(context: Context, mode: ShareLeakGuard.Mode) {
        prefs(context).edit().putString(PREF_MODE, mode.name).apply()
        ShareBridge.setMode(mode)
    }

    fun setAuthRequired(context: Context, required: Boolean) {
        prefs(context).edit().putBoolean(PREF_AUTH, required).apply()
        ShareBridge.setAuthRequired(required)
    }

    /** Result of [setCustom]. */
    enum class Validation { OK, BAD_USER, SHORT_PASSWORD }

    /**
     * Stores a user-chosen username/password. The username must be 1-64
     * printable ASCII characters without ':' (it travels in HTTP Basic, where
     * ':' separates the fields); the password at least [MIN_PASSWORD] chars and
     * at most 128 (RFC 1929 carries at most 255 bytes).
     */
    fun setCustom(context: Context, user: String, password: String): Validation {
        val u = user.trim()
        if (u.isEmpty() || u.length > 64 || u.any { it == ':' || it.code !in 0x21..0x7E }) {
            return Validation.BAD_USER
        }
        if (password.length < MIN_PASSWORD || password.length > 128 ||
            password.any { it.code !in 0x20..0x7E }
        ) {
            return Validation.SHORT_PASSWORD
        }
        val store = SecretStore(context)
        store.write(KEY_USER, u)
        store.write(KEY_PASSWORD, password)
        ShareBridge.setCredentials(u, password)
        return Validation.OK
    }

    /**
     * Replaces the password with a fresh random one. Existing sessions keep
     * running; nothing new can attach with the old password.
     */
    fun rotate(context: Context): String {
        val fresh = LanGuard.randomPassword()
        SecretStore(context).write(KEY_PASSWORD, fresh)
        ShareBridge.setCredentials(ShareBridge.proxyUser.value, fresh)
        return fresh
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Same secret key name as r4, so an existing password carries over. */
    private const val KEY_PASSWORD = "share_proxy_password"
    private const val KEY_USER = "share_proxy_user"
    private const val PREFS_FILE = "aether_share"
    private const val PREF_MODE = "mode"
    private const val PREF_AUTH = "auth_required"
}
