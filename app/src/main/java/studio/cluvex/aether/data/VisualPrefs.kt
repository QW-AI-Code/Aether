package studio.cluvex.aether.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small synchronous UI-only appearance settings. */
object VisualPrefs {
    private const val FILE = "aether_visual_prefs"
    private const val KEY_HOME_ANIMATIONS = "home_animations"

    private val homeAnimations = MutableStateFlow(true)
    @Volatile private var loaded = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        homeAnimations.value = context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOME_ANIMATIONS, true)
        loaded = true
    }

    fun homeAnimationsEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return homeAnimations.value
    }

    fun homeAnimationsFlow(context: Context): StateFlow<Boolean> {
        ensureLoaded(context)
        return homeAnimations.asStateFlow()
    }

    fun setHomeAnimationsEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HOME_ANIMATIONS, enabled).apply()
        loaded = true
        homeAnimations.value = enabled
    }
}
