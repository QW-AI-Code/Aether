package studio.cluvex.aether.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import studio.cluvex.aether.data.SecretStore

/** What the home screen shows about the signed-in user. */
data class Account(
    val displayName: String,
    val plan: String,
    /** Subscription end, epoch millis; null when the plan does not expire. */
    val expiresAtMillis: Long? = null,
) {
    fun encode(): String = JSONObject()
        .put("n", displayName)
        .put("p", plan)
        .apply { expiresAtMillis?.let { put("e", it) } }
        .toString()

    companion object {
        fun decode(raw: String): Account? {
            if (raw.isBlank()) return null
            return runCatching {
                val j = JSONObject(raw)
                Account(
                    displayName = j.getString("n"),
                    plan = j.getString("p"),
                    expiresAtMillis = if (j.has("e")) j.getLong("e") else null,
                )
            }.getOrNull()
        }
    }
}

enum class AuthFailure { INVALID_INPUT, WRONG_CREDENTIALS, NETWORK }

class AuthException(val failure: AuthFailure) : Exception(failure.name)

/**
 * The one seam between the UI and whatever actually authenticates.
 *
 * Swap [DemoAuthRepository] for a class that talks to YOUR server and nothing
 * else in the app has to change.
 */
interface AuthRepository {
    fun currentAccount(): Account?
    suspend fun signIn(username: String, password: String): Result<Account>
    fun signOut()
}

/**
 * DEMO ONLY - NOT SECURITY. Any username with a password of at least
 * [MIN_PASSWORD] characters is accepted, because there is no server to check
 * against yet. Before shipping, replace the body of [signIn] with a real request
 * (HTTPS, e.g. HttpURLConnection/OkHttp) that returns the account's name, plan
 * and expiry, and map server errors to [AuthFailure].
 *
 * Never put a fixed username/password or an API secret in the APK: it is
 * extractable in minutes.
 *
 * The signed-in account is stored sealed in [SecretStore] (AES-256-GCM under a
 * non-exportable Keystore key), like the app's other credentials. The password
 * is never stored.
 */
class DemoAuthRepository(context: Context) : AuthRepository {

    private val secrets = SecretStore(context.applicationContext)

    override fun currentAccount(): Account? = Account.decode(secrets.read(KEY))

    override suspend fun signIn(username: String, password: String): Result<Account> =
        withContext(Dispatchers.IO) {
            delay(350) // stands in for the network round-trip
            val name = username.trim()
            if (name.isEmpty() || password.length < MIN_PASSWORD) {
                return@withContext Result.failure(AuthException(AuthFailure.INVALID_INPUT))
            }
            // TODO(real backend): authenticate here and build Account from the response.
            val account = Account(displayName = name, plan = "Demo", expiresAtMillis = null)
            secrets.write(KEY, account.encode())
            Result.success(account)
        }

    override fun signOut() = secrets.write(KEY, "")

    private companion object {
        const val KEY = "auth_account"
        const val MIN_PASSWORD = 4
    }
}
