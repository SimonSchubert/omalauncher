package dev.omalauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.LinuxMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process (and with it proot and the Wayland server) in the
 * foreground while Linux is installed or installing; never without Linux.
 * The notification shows the install's progress.
 */
class LinuxService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux session", NotificationManager.IMPORTANCE_LOW))
        val runtime = (application as App).runtime
        intent?.action?.removePrefix(MEDIA_ACTION_PREFIX)?.takeIf { intent.action?.startsWith(MEDIA_ACTION_PREFIX) == true }?.let(runtime.linuxMedia::control)
        val state = runtime.bootstrap.state
        startForeground(1, notification(state.value, runtime.linuxMedia.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        pending = (pending - 1).coerceAtLeast(0)
        // Stopped before it got here (see [set]): now it may go, unless a later start still has to come here too.
        if (!wanted) {
            if (pending == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) {
            watch =
                scope.launch {
                    state.map(::text).distinctUntilChanged().collect { nm.notify(1, build(it, runtime.linuxMedia.state.value)) }
                }
            scope.launch { runtime.linuxMedia.state.collect { nm.notify(1, build(text(state.value), it)) } }
        }
        // Not restarted after the process dies: Linux only comes back with the launcher (Runtime.start),
        // and Android 12+ may refuse startForeground() in a restart from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(
        state: LinuxState,
        media: LinuxMedia.Published?,
    ) = build(text(state), media)

    private fun text(state: LinuxState): String =
        when (state) {
            is LinuxState.Installing -> "Installing Linux" + (state.progress?.let { " · ${(it * 100).toInt()}%" } ?: "") + " · ${state.stage.label}"
            is LinuxState.Failed -> "Linux setup stopped · open settings › linux to retry"
            LinuxState.Removing -> "Removing Linux"
            else -> "Linux session running"
        }

    private fun build(
        text: String,
        media: LinuxMedia.Published? = null,
    ): Notification {
        val builder =
            Notification
            .Builder(this, CHANNEL)
            .setContentTitle(media?.title?.takeIf { it.isNotBlank() } ?: text)
            .setContentText(media?.artist?.takeIf { it.isNotBlank() })
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (media == null) return builder.build()
        val compact = ArrayList<Int>()
        fun action(command: String, icon: Int, title: String) {
            if (command !in media.commands) return
            val intent = Intent(this, LinuxService::class.java).setAction(MEDIA_ACTION_PREFIX + command)
            val pending = PendingIntent.getService(this, command.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            compact += compact.size
            builder.addAction(Notification.Action.Builder(icon, title, pending).build())
        }
        action("previous", android.R.drawable.ic_media_previous, "Previous")
        action(if (media.playing) "pause" else "play", if (media.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (media.playing) "Pause" else "Play")
        action("next", android.R.drawable.ic_media_next, "Next")
        builder
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(Notification.MediaStyle().setMediaSession((application as App).runtime.linuxMedia.token).setShowActionsInCompactView(*compact.take(3).toIntArray()))
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "session"
        private const val MEDIA_ACTION_PREFIX = "dev.omalauncher.media."

        // Main thread only, like onStartCommand.
        private var wanted = false

        /** startForegroundService() calls whose onStartCommand hasn't run yet. */
        private var pending = 0

        /**
         * Starts or stops the service. Android kills the app when a service started with startForegroundService()
         * is stopped before it calls startForeground(), as a setup that fails at once did on slow phones; a stop
         * that early is left to onStartCommand.
         */
        fun set(
            context: Context,
            on: Boolean,
        ) {
            wanted = on
            val intent = Intent(context, LinuxService::class.java)
            if (on) {
                context.startForegroundService(intent)
                pending++
            } else if (pending == 0) {
                context.stopService(intent)
            }
        }
    }
}
