package dev.omalauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.omalauncher.linux.LinuxState
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.linux_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val state = (application as App).runtime.bootstrap.state
        startForeground(1, notification(state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        pending = (pending - 1).coerceAtLeast(0)
        // Stopped before it got here (see [set]): now it may go, unless a later start still has to come here too.
        if (!wanted) {
            if (pending == 0) stopSelf()
            return START_NOT_STICKY
        }
        if (watch == null) {
            watch =
                scope.launch {
                    state.map(::text).distinctUntilChanged().collect { nm.notify(1, build(it)) }
                }
        }
        // Not restarted after the process dies: Linux only comes back with the launcher (Runtime.start),
        // and Android 12+ may refuse startForeground() in a restart from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: LinuxState) = build(text(state))

    private fun text(state: LinuxState): String =
        when (state) {
            is LinuxState.Installing ->
                state.progress?.let { getString(R.string.linux_notification_installing_percent, (it * 100).toInt(), getString(state.stage.label)) }
                    ?: getString(R.string.linux_notification_installing, getString(state.stage.label))
            is LinuxState.Failed -> getString(R.string.linux_notification_failed)
            LinuxState.Removing -> getString(R.string.linux_notification_removing)
            else -> getString(R.string.linux_notification_running)
        }

    private fun build(text: String): Notification =
        Notification
            .Builder(this, CHANNEL)
            .setContentTitle(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    companion object {
        private const val CHANNEL = "session"

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
