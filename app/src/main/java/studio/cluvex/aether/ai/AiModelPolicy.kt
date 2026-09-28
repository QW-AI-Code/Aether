package studio.cluvex.aether.ai

/**
 * The model allow-list, and the single place model ordering is decided.
 *
 * ## Why an allow-list instead of a filter in the UI
 *
 * `models.list` on a Gemini key returns everything the key is *entitled* to see,
 * which in practice is well over thirty entries: image models, video models,
 * speech and TTS models, embedding models, and a long tail of `-pro` and
 * `-thinking` variants that answer `generateContent` with a 429 on the free tier
 * the moment they are asked anything real. A picker built from that list is a
 * list of ways for the feature to fail, and the user has to discover which is
 * which by burning quota.
 *
 * So the set is fixed here, in the data layer, and it is applied on EVERY path
 * that can put a model id in front of the user or into a request:
 *
 *  1. a fresh `models.list` response ([filter]),
 *  2. the cached list replayed from [studio.cluvex.aether.data.GeminiStore] at
 *     startup ([filterIds]),
 *  3. the default-model pick,
 *  4. the id actually sent to `generateContent` ([isAllowed]).
 *
 * Filtering only at (1) is the bug this file exists to prevent: refreshing the
 * model list, or simply restarting with a cached list from an older build, would
 * put the filtered models straight back into the picker. "Even when the user
 * refreshes the list" is a requirement about all four paths, not about the
 * refresh button.
 *
 * ## Ordering
 *
 * [ALLOWED] is written newest-first, and that order IS the display order - a rank
 * lookup, not a string sort. Sorting model ids as text puts `gemini-3.1` after
 * `gemini-3.5` and `gemini-flash-lite-latest` before every numbered release,
 * because `1` < `5` and `f` > `3` lexically. Version-aware parsing of a vendor's
 * naming scheme is a guess that rots; an explicit list is a decision that does
 * not.
 */
object AiModelPolicy {

    /**
     * The only models the app will offer, newest first.
     *
     * Every one of these is a Flash-class model: fast, cheap, and answerable on a
     * free key, which is the key almost every user of this app has.
     */
    val ALLOWED: List<String> = listOf(
        "gemini-3.8-flash",
        // 1.3.1: 3.7-flash shipped 2026-08-13 and was missing from this list, so a
        // key that offers it was ranked below older ids. Not fatal - the list only
        // ORDERS what ListModels returns, it does not gate it - but the ordering is
        // the whole point of the file.
        "gemini-3.7-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite-preview",
        "gemini-flash-lite-latest",
    )

    private val RANK: Map<String, Int> =
        ALLOWED.withIndex().associate { (index, id) -> id to index }

    /** Strips the `models/` prefix the REST API uses and trims stray whitespace. */
    fun normalise(rawId: String): String = rawId.trim().removePrefix("models/").trim()

    fun isAllowed(rawId: String): Boolean = normalise(rawId) in RANK

    /**
     * Display position, 1-based, for the row number shown in the picker.
     *
     * Derived from [ALLOWED] rather than from the position in whatever list is
     * being rendered, so model #3 is model #3 whether or not the user's key can
     * see #2. A number that shifts when an unrelated model becomes unavailable is
     * worse than no number.
     */
    fun displayNumber(rawId: String): Int = (RANK[normalise(rawId)] ?: -1) + 1

    private fun rank(rawId: String): Int = RANK[normalise(rawId)] ?: Int.MAX_VALUE

    /** Filters and orders bare ids - the cached-list path. */
    fun filterIds(ids: List<String>): List<String> =
        ids.asSequence()
            .map(::normalise)
            .filter(::isAllowed)
            .distinct()
            .sortedBy(::rank)
            .toList()

    /** Filters and orders discovered models - the fresh-discovery path. */
    fun filter(models: List<GeminiModel>): List<GeminiModel> =
        models.asSequence()
            .map { if (it.id == normalise(it.id)) it else it.copy(id = normalise(it.id)) }
            .filter { isAllowed(it.id) }
            .distinctBy { it.id }
            .sortedBy { rank(it.id) }
            .toList()

    /**
     * 1.4.0-r5: the DEFAULT is no longer "the newest model".
     *
     * The newest Flash models are the fastest, but on a free key they have the
     * smallest daily request allowance, and the field complaint was the chat
     * going dead with a 429 halfway through the day. Gemini 3.1 Flash-Lite has
     * the highest free daily ceiling of the supported set, so it is what a freshly
     * entered key starts on. The newer models stay one tap away in the picker,
     * each labelled with what it trades (see [tier]).
     *
     * Ordered fallback: if a key cannot see 3.1 Flash-Lite, its preview build and
     * the rolling `flash-lite-latest` alias are the next-best high-allowance
     * choices, and only after those does the pick fall back to display order.
     */
    val DEFAULT_PREFERENCE: List<String> = listOf(
        "gemini-3.1-flash-lite",
        "gemini-3.1-flash-lite-preview",
        "gemini-flash-lite-latest",
    )

    /** The model a new key starts on when the key can see it. */
    const val DEFAULT_MODEL: String = "gemini-3.1-flash-lite"

    /** How each model trades speed against the free-tier daily allowance. */
    enum class Tier {
        /** The default: highest daily ceiling, best for continuous use. */
        RECOMMENDED_DEFAULT,
        /** Very fast, but the daily limit is reached noticeably sooner. */
        FAST_LOW_QUOTA,
        /** Rolling alias / preview build of a high-allowance model. */
        HIGH_QUOTA_ALT,
    }

    fun tier(rawId: String): Tier? = when (normalise(rawId)) {
        DEFAULT_MODEL -> Tier.RECOMMENDED_DEFAULT
        "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash-lite" -> Tier.FAST_LOW_QUOTA
        "gemini-3.1-flash-lite-preview", "gemini-flash-lite-latest" -> Tier.HIGH_QUOTA_ALT
        else -> null
    }

    fun isDefault(rawId: String): Boolean = normalise(rawId) == DEFAULT_MODEL

    /** Default pick over bare ids - the cached-list path ([AiSettings.effectiveModel]). */
    fun pickDefaultId(ids: List<String>): String? {
        val usable = filterIds(ids)
        return DEFAULT_PREFERENCE.firstOrNull { it in usable } ?: usable.firstOrNull()
    }

    /**
     * The model to use when the user has not chosen, or has chosen one that is no
     * longer offered: [DEFAULT_PREFERENCE] first, then the first allowed model the
     * key can see.
     */
    fun pickDefault(models: List<GeminiModel>): String? =
        pickDefaultId(models.map { it.id })
}
