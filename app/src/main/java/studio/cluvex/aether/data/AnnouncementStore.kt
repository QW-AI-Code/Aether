package studio.cluvex.aether.data

import android.content.Context
import studio.cluvex.aether.BuildConfig

/**
 * 1.5.0: decides whether the "What's new" announcement opens on its own.
 *
 * The rule is deliberately simple and version-scoped:
 *
 *  * a FRESH INSTALL has no stored version at all, and an UPGRADE stores an older
 *    one - in both cases the dialog opens automatically;
 *  * it keeps opening on every launch of that same version until the user closes
 *    it with "دیگر این پیام را نمایش نده" ticked. That tick is remembered PER
 *    VERSION ([suppressedVersion]), so the next upgrade shows its news again.
 *
 * Plain SharedPreferences rather than DataStore, for the same reason as
 * [LanguagePrefs]: the decision must be known synchronously on the first frame,
 * otherwise the dialog would pop in a moment after the home screen settled.
 * Kept in its own file so a settings reset never re-arms it.
 */
class AnnouncementStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The version the app last launched as; null on a fresh install. */
    val lastLaunchedVersion: String?
        get() = prefs.getString(KEY_LAST_LAUNCHED, null)

    /** The version for which the user ticked "don't show again"; null if never. */
    val suppressedVersion: String?
        get() = prefs.getString(KEY_SUPPRESSED, null)

    /** True when this launch is a first run after install or after an upgrade. */
    fun isFirstLaunchOfThisVersion(): Boolean = lastLaunchedVersion != currentVersion

    /** True only before the first-ever launch has been recorded. */
    fun isFreshInstall(): Boolean = lastLaunchedVersion == null

    /** Whether the announcement should open automatically right now. */
    fun shouldShowOnLaunch(): Boolean =
        shouldShow(currentVersion = currentVersion, suppressedVersion = suppressedVersion)

    /** One-time post-install support prompt state. */
    val supportPromptShown: Boolean
        get() = prefs.getBoolean(KEY_SUPPORT_SHOWN, false)

    /** Records this launch; call once the decision for this launch has been made. */
    fun markLaunched() {
        prefs.edit().putString(KEY_LAST_LAUNCHED, currentVersion).apply()
    }

    /**
     * Called when the dialog is closed (either "متوجه شدم", the close icon or back).
     * Only a ticked checkbox silences the dialog - and only for this version.
     */
    fun onDismissed(dontShowAgain: Boolean) {
        val edit = prefs.edit().putString(KEY_LAST_LAUNCHED, currentVersion)
        if (dontShowAgain) edit.putString(KEY_SUPPRESSED, currentVersion)
        edit.apply()
    }

    /** True once the user ticked "دیگر نمایش نده" on the core-creator note. */
    val supportSuppressed: Boolean
        get() = prefs.getBoolean(KEY_SUPPORT_SUPPRESSED, false)

    fun suppressSupportPrompt() {
        prefs.edit().putBoolean(KEY_SUPPORT_SUPPRESSED, true).apply()
    }

    fun markSupportPromptShown() {
        prefs.edit().putBoolean(KEY_SUPPORT_SHOWN, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "aether_announcements"
        private const val KEY_LAST_LAUNCHED = "last_launched_version"
        private const val KEY_SUPPRESSED = "suppressed_version"
        private const val KEY_SUPPORT_SUPPRESSED = "support_prompt_suppressed"
        private const val KEY_SUPPORT_SHOWN = "support_prompt_shown"

        /**
         * The identity of "this version". versionName AND versionCode, so a
         * rebuilt release with a bumped code counts as an upgrade too.
         */
        val currentVersion: String
            get() = versionKey(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        fun versionKey(name: String, code: Int): String = "$name ($code)"

        /** Pure decision, unit-tested: open unless THIS version was silenced. */
        fun shouldShow(currentVersion: String, suppressedVersion: String?): Boolean =
            suppressedVersion != currentVersion
    }
}
