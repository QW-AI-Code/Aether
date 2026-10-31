package studio.cluvex.aether.data

import android.content.Context
import studio.cluvex.aether.core.TeamSignInRecord

/**
 * Keeps the one pre-connect Zero Trust sign-in ([TeamSignInRecord]) sealed in
 * [SecretStore].
 *
 * One record, not one per team: the Settings screen holds one team and one
 * address, and a record for anything else is ignored anyway
 * ([TeamSignInRecord.matches]). A new sign-in replaces it; "Sign out" and
 * "Reset settings" ([SecretStore.clear]) remove it.
 *
 * Separate from [ProfileStore] on purpose. The profile is what the user TYPED and
 * is saved on every keystroke; this is what Cloudflare ISSUED, and it is written
 * only when a sign-in succeeds. Keeping it out of the profile also keeps it out
 * of the Intent the UI sends to the VPN service: the service reads it from here
 * itself, like the other two Zero Trust secrets.
 */
class TeamSignInStore(context: Context) {

    private val secrets = SecretStore(context.applicationContext)

    /** The stored sign-in, or null when there is none or it cannot be read. */
    fun load(): TeamSignInRecord? = TeamSignInRecord.decode(secrets.read(SecretStore.ACCESS_SIGNIN))

    fun save(record: TeamSignInRecord) = secrets.write(SecretStore.ACCESS_SIGNIN, record.encode())

    fun clear() = secrets.write(SecretStore.ACCESS_SIGNIN, "")
}
