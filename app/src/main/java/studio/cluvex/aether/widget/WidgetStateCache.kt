package studio.cluvex.aether.widget

import android.content.Context
import studio.cluvex.aether.model.ConnectionState

/**
 * The connection state as the home-screen widget last painted it, on disk.
 *
 * ## Why a widget cannot read [studio.cluvex.aether.core.AetherController] (1.3.1)
 *
 * `AetherController` is an in-process `object`: its `StateFlow` is correct for
 * exactly as long as the process that owns the tunnel is alive. An
 * `AppWidgetProvider` is a `BroadcastReceiver`, and a launcher's
 * `APPWIDGET_UPDATE` - after a reboot, a launcher restart, a low-memory kill,
 * or an "add widget" - is routinely delivered into a FRESHLY FORKED process
 * where the object has only just been constructed. Reading it there returns the
 * field initialiser, [ConnectionState.Idle], no matter what the tunnel is doing.
 *
 * That is one half of the bug users reported as *"when I disconnect with the
 * widget it shows Disconnecting… always"* (issues #20, #23, #15). The other half
 * was in the service: `stopEverything()` painted `Disconnecting` and then
 * flipped the state to `Idle` through a path that never repainted the widget, so
 * the last thing ever written to it was the transient text.
 *
 * ## What is stored, and the one rule that makes it safe
 *
 * A single string: the name of the state class. Nothing identifying - not the
 * endpoint, not the exit IP, not the profile. It goes into its own
 * SharedPreferences file rather than into the DataStore every setting uses,
 * because a broadcast receiver has to answer synchronously and cannot suspend.
 *
 * The rule: **a transient state does not survive a process death.** `Connecting`,
 * `Verifying`, `Launching`, `Reconnecting` and `Disconnecting` all describe work
 * that some thread was doing. If that thread's process is gone, the work is gone
 * with it, and the honest answer is `Idle` - so [restore] maps every transient
 * value to `Idle` and only `Connected` survives a cold read. This is what stops a
 * stale `Disconnecting…` from being shown forever; there is no timer and nothing
 * to expire.
 */
internal object WidgetStateCache {

    private const val PREFS = "aether_widget_state"
    private const val KEY_STATE = "state"

    private const val CONNECTED = "connected"
    private const val ERROR = "error"
    private const val IDLE = "idle"
    private const val CONNECTING = "connecting"
    private const val DISCONNECTING = "disconnecting"
    private const val RECONNECTING = "reconnecting"

    /** Records [state] so a cold process can paint the widget correctly. */
    fun remember(context: Context, state: ConnectionState) {
        val token = when (state) {
            is ConnectionState.Connected -> CONNECTED
            is ConnectionState.Error -> ERROR
            is ConnectionState.Launching,
            is ConnectionState.Connecting,
            is ConnectionState.Verifying,
            -> CONNECTING
            is ConnectionState.Reconnecting -> RECONNECTING
            is ConnectionState.Disconnecting -> DISCONNECTING
            is ConnectionState.Idle -> IDLE
        }
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STATE, token)
                .apply()
        }
    }

    /**
     * The state to paint, given what this process currently believes.
     *
     * [live] wins whenever it says anything other than `Idle`: a live tunnel in
     * this process is the best evidence there is. `Idle` is ambiguous - it is
     * both "disconnected" and "this object was constructed a millisecond ago" -
     * so that is the only case where the stored value is consulted, and only
     * `Connected` and `Error` are honoured from it (see the class comment).
     */
    fun restore(context: Context, live: ConnectionState): ConnectionState {
        if (live !is ConnectionState.Idle) return live
        val token = runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STATE, null)
        }.getOrNull()
        return when (token) {
            // The address is not stored - the widget shows a state, not an
            // endpoint - so an empty one is enough to select the right text.
            CONNECTED -> ConnectionState.Connected("")
            ERROR -> ConnectionState.Error("")
            else -> ConnectionState.Idle
        }
    }
}
