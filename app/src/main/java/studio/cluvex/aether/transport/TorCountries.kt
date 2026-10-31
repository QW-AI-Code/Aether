package studio.cluvex.aether.transport

import studio.cluvex.aether.core.NetProbe

/**
 * The countries offered when telling bridgedb where Tor is being started from.
 *
 * ## Not the same list as [ExitRegions], and not the same question
 *
 * [ExitRegions] is where the user wants to COME OUT. This is where the user
 * currently IS, which is the opposite end of the connection and a different set of
 * places: the codes here are the countries whose networks routinely block a direct
 * Tor connection, and most of them are exactly the ones Psiphon does not offer as
 * an egress. Sharing one list would have meant offering Germany as a bridge country
 * and not offering Iran.
 *
 * Blank means "let the engine work it out". That is the engine's own default
 * behaviour (`detect_country` in `bridges.rs`), and it is right whenever it works -
 * it just asks Cloudflare's trace endpoint where it is, which is a request that can
 * be blocked, or answered from the wrong place by a network's own resolver, on
 * precisely the networks where a bridge is the only way in. Naming the country
 * turns that guess into a fact.
 *
 * The list is deliberately short. bridgedb accepts any ISO 3166-1 alpha-2 code, but
 * a 249-item sheet would bury the ten that matter, and a user in a country not
 * listed here is a user whose direct connection almost certainly works.
 *
 * Labels are English names with the flag derived from the code via
 * [NetProbe.flagEmoji] - the same shape [ExitRegions] uses, so the two sheets do not
 * look like they came from different apps.
 */
object TorCountries {

    /** "" = detect. Kept first so it stays the default row in the sheet. */
    val values = listOf("", "ir", "ru", "cn", "tm", "by", "eg", "sa", "ae", "tr")

    private val names = mapOf(
        "ir" to "Iran",
        "ru" to "Russia",
        "cn" to "China",
        "tm" to "Turkmenistan",
        "by" to "Belarus",
        "eg" to "Egypt",
        "sa" to "Saudi Arabia",
        "ae" to "United Arab Emirates",
        "tr" to "Türkiye",
    )

    /**
     * True when [code] is one of the offered values, after trimming and lowercasing.
     *
     * Used by the settings row to decide whether a stored value can be shown as the
     * current selection. A code the engine would accept but this list does not offer
     * stays in the profile untouched and the row shows the automatic entry: silently
     * rewriting a working setting because the UI has no row for it would be worse
     * than showing it imprecisely.
     */
    fun isOffered(code: String): Boolean = code.trim().lowercase() in values

    /** Flag + name, or the automatic entry for a blank code. */
    fun label(code: String, automatic: String): String {
        val cc = code.trim().lowercase()
        if (cc.isEmpty()) return automatic
        val name = names[cc] ?: cc.uppercase()
        return "${NetProbe.flagEmoji(cc)}  $name"
    }
}
