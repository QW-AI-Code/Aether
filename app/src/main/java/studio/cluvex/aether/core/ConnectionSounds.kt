package studio.cluvex.aether.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import studio.cluvex.aether.R
import studio.cluvex.aether.model.ConnectionState

/** Sound assets and completion/error cleanup ported from QWVPN (GPL-3.0). */
object ConnectionSounds {
    private var app: Context? = null
    private val policy = ConnectionSoundPolicy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    fun attach(context: Context) { app = context.applicationContext }
    fun enabled(context: Context): Boolean = context.getSharedPreferences("connection_feedback", Context.MODE_PRIVATE)
        .getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("connection_feedback", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
    }
    @Synchronized fun onState(state: ConnectionState) {
        val cue = policy.accept(state) ?: return
        val context = app ?: return
        if (!enabled(context)) return
        handler.post {
            if (enabled(context)) play(context,
                if (cue == ConnectionSoundPolicy.Cue.CONNECT) R.raw.sfx_connect else R.raw.sfx_disconnect)
        }
    }
    private fun play(context: Context, resource: Int) {
        if (context.getSystemService(AudioManager::class.java)?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        var player: MediaPlayer? = null
        try {
            player = MediaPlayer.create(context, resource, AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0) ?: return
            player.setOnCompletionListener { it.release() }
            player.setOnErrorListener { p, _, _ -> p.release(); true }
            player.setVolume(.9f, .9f)
            player.start()
        } catch (_: Exception) { runCatching { player?.release() } }
    }
}

