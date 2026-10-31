package studio.cluvex.aether.core

import java.io.File

/**
 * Whether this device is already enrolled in a Zero Trust team, and forgetting
 * that enrolment.
 *
 * ## Why sign-in is usually a one-off
 *
 * A sign-in token is only needed to ENROL. Once the engine has enrolled, it
 * writes the device identity to `aether-team-<team>.toml` next to its other
 * identity files (`api::identity_path` / `lib.rs::warp_config_path` and
 * `masque_config_path` both derive that name from the base `aether.toml` with
 * `derive_sibling_path(base, "team-<team>")`), and on every later connect it loads
 * that file and never signs in again. [IdentityVault] seals it at rest as
 * `<name>.sealed`; either form counts here.
 *
 * So the Settings screen can tell the user the one thing that matters before a
 * connect: "already enrolled, nothing to do" versus "you will need to sign in".
 *
 * ## Forgetting
 *
 * [forget] removes the enrolment for ONE team: the identity, its last-good-
 * gateway cache (`-lastconn`), its second hop (`-secondary`), any `.corrupt`
 * copy the engine kept, sealed or not. The next connect enrols afresh. That is
 * what a user needs after the organization revoked the device, or to join as a
 * different person. Consumer WARP identities (`aether.toml`,
 * `aether-masque.toml`) and other teams are never touched.
 *
 * Only call [forget] while no engine is running: a running engine holds the
 * plaintext file and would write it back.
 */
object TeamMembership {

    private const val TAG = "zerotrust"

    /**
     * True when [dir] holds an enrolment for [team] (normalised name, see
     * [ZeroTrustWeb.normalizeTeam]).
     *
     * Only the identity itself counts, plaintext or sealed. A `.corrupt` copy is
     * not an enrolment (the engine re-provisions next to it), and neither is a
     * `-lastconn` cache left behind on its own.
     */
    fun isEnrolled(dir: File, team: String): Boolean {
        if (team.isEmpty()) return false
        val base = "aether-team-$team.toml"
        return File(dir, base).isFile || File(dir, "$base.sealed").isFile
    }

    /** Every file that belongs to [team]'s enrolment in [dir]. */
    fun filesOf(dir: File, team: String): List<File> {
        val pattern = patternFor(team)
        return dir.listFiles { f: File -> f.isFile && pattern.matches(f.name) }?.toList().orEmpty()
    }

    /** Shreds [team]'s enrolment files. Returns how many were removed. */
    fun forget(dir: File, team: String): Int {
        var removed = 0
        for (file in filesOf(dir, team)) {
            EncryptedLogFile.shred(file)
            if (!file.exists()) removed++
        }
        DiagnosticsLog.i(TAG, "Removed this device's enrolment in team $team ($removed file(s)); the next connect enrols again.")
        return removed
    }

    /**
     * `aether-team-<team>[-secondary][-lastconn].toml[.corrupt][.sealed]`.
     *
     * Every name the engine can derive from the team identity: the identity
     * itself, its second hop (`derive_sibling_path(primary, "secondary")` in the
     * WARP-in-WARP and MASQUE-in-MASQUE modes), the last-good-gateway cache of
     * either (`lastconn_path` appends `-lastconn`, so the second hop's cache is
     * `-secondary-lastconn`), the engine's `.corrupt` copy (`config.rs` appends
     * `.corrupt` to the whole file name) and [IdentityVault]'s `.sealed` blob.
     *
     * The team is regex-quoted and anchored on both sides, so team "acme" never
     * matches the files of a team called "acme-eu": after the name only the two
     * fixed suffixes or `.toml` may follow.
     */
    internal fun patternFor(team: String): Regex =
        Regex("^aether-team-" + Regex.escape(team) + "(?:-secondary)?(?:-lastconn)?\\.toml(?:\\.corrupt)?(?:\\.sealed)?$")
}
