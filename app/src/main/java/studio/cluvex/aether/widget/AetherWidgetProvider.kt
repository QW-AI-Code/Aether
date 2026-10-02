package studio.cluvex.aether.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import studio.cluvex.aether.MainActivity
import studio.cluvex.aether.R
import studio.cluvex.aether.core.AetherController
import studio.cluvex.aether.data.ProfileStore
import studio.cluvex.aether.model.ConnectionState
import studio.cluvex.aether.model.isBusy
import studio.cluvex.aether.model.isConnected

/**
 * Home-screen widget, ported from the merged AetherWidgetProvider and adapted
 * to Aether Mobile's [AetherController] / [ProfileStore] architecture.
 *
 * Shows the live connection state and offers one-tap connect/disconnect.
 * Tapping the body opens the app. Connect reuses the last saved profile; if
 * VPN consent is still missing the app is opened instead so the system
 * consent dialog can be shown (the tile uses the same flow).
 *
 * BATTERY: the provider metadata sets updatePeriodMillis=0, so the system
 * never wakes the app on a timer — repaints happen only on real connection
 * state changes via [updateAllWidgets], called from the VPN service's
 * existing state-change hook.
 */
class AetherWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "studio.cluvex.aether.WIDGET_TOGGLE"

        /**
         * Repaints every placed widget; called on each connection-state change.
         *
         * 1.3.1: also RECORDS the state, so the next repaint that lands in a
         * cold process has something truthful to paint. See [WidgetStateCache].
         */
        fun updateAllWidgets(context: Context) {
            val state = AetherController.state.value
            WidgetStateCache.remember(context, state)
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, AetherWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            ids.forEach { id -> paint(context, manager, id, state) }
        }

        private fun paint(
            rawContext: Context,
            manager: AppWidgetManager,
            id: Int,
            state: ConnectionState,
        ) {
            // A widget provider is a BroadcastReceiver, so it has no
            // attachBaseContext to override: the language is applied to the
            // context handed in instead. Without this the launcher widget was
            // the one surface left showing the phone's language while the rest
            // of the app followed the in-app choice.
            val context = studio.cluvex.aether.data.LanguagePrefs.wrap(rawContext)
            val views = RemoteViews(context.packageName, R.layout.aether_widget)

            val (text, color) = when (state) {
                is ConnectionState.Connected ->
                    context.getString(R.string.state_connected) to 0xFF34C759.toInt()
                is ConnectionState.Launching, is ConnectionState.Connecting, is ConnectionState.Verifying ->
                    context.getString(R.string.state_connecting) to 0xFFFF9500.toInt()
                is ConnectionState.Reconnecting ->
                    context.getString(R.string.state_reconnecting) to 0xFFFF9500.toInt()
                is ConnectionState.Disconnecting ->
                    context.getString(R.string.state_disconnecting) to 0xFFFF9500.toInt()
                is ConnectionState.Error ->
                    context.getString(R.string.state_error) to 0xFFFF5C5C.toInt()
                else ->
                    context.getString(R.string.state_idle) to 0xFF8E8E93.toInt()
            }
            views.setTextViewText(R.id.widget_status, text)
            views.setTextColor(R.id.widget_status, color)
            // ISSUE #15: "show whether it is connected or not by colour". The
            // status text alone is a 10sp string on a 1x1 widget; tinting the
            // power icon with the same colour makes the state readable without
            // reading anything. setColorFilter(int) is on ImageView, so it is
            // reachable over RemoteViews' reflection call.
            views.setInt(R.id.widget_toggle, "setColorFilter", color)
            // ACCESSIBILITY: the button used to say "Toggle connection" in every
            // state. It now names what a tap will do, so it reads "Disconnect"
            // once the tunnel is up (a tap while busy also tears it down).
            views.setContentDescription(
                R.id.widget_toggle,
                context.getString(
                    if (state.isConnected || state.isBusy) R.string.a11y_disconnect
                    else R.string.a11y_connect,
                ),
            )

            // Power button toggles the tunnel.
            val toggle = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, AetherWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_toggle, toggle)

            // Tapping the body opens the app.
            val open = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)

            manager.updateAppWidget(id, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // NOT AetherController.state.value directly: this callback is routinely
        // delivered into a process that was forked to handle it, where that flow
        // still holds its initialiser. See [WidgetStateCache].
        val state = WidgetStateCache.restore(context, AetherController.state.value)
        appWidgetIds.forEach { paint(context, appWidgetManager, it, state) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return

        val state = WidgetStateCache.restore(context, AetherController.state.value)
        if (state.isConnected || state.isBusy) {
            AetherController.disconnect(context)
            return
        }
        // VPN consent missing -> open the app so the system dialog can show.
        if (VpnService.prepare(context) != null) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_CONNECT_ON_LAUNCH, true),
            )
            return
        }
        // Connect with the last saved profile. goAsync: the DataStore read
        // must not block the broadcast's main thread.
        val pending = goAsync()
        Thread {
            try {
                val profile = runBlocking {
                    ProfileStore(context.applicationContext).profile.first()
                }
                AetherController.connect(context.applicationContext, profile)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
