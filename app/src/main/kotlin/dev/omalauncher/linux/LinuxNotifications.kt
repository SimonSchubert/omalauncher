package dev.omalauncher.linux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.omalauncher.MainActivity
import dev.omalauncher.R

/**
 * Linux apps' notifications (notify-send, an app saying a job finished) as
 * Android ones, in a channel of their own the user can mute. They come from
 * the guest's notification service (guest/omalauncher-portal) through the
 * open bridge; a client that replaces one updates it in place.
 */
class LinuxNotifications(
    private val context: Context,
) {
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    fun post(
        id: Int,
        app: String,
        summary: String,
        body: String,
    ) {
        val nm = nm
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Linux apps", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(summary.ifBlank { app })
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .apply { if (app.isNotBlank()) setSubText(app) }
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        nm.notify(BASE_ID + id, n)
    }

    fun close(id: Int) = nm.cancel(BASE_ID + id)

    companion object {
        private const val CHANNEL = "linux-apps"

        /** Clear of the launcher's own notification ids. */
        private const val BASE_ID = 0x4C000000

        /** A field as the guest's service escapes it for the bridge's one line: \n for newline, \\ for \. */
        fun unescape(s: String): String =
            buildString {
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '\\' && i + 1 < s.length) {
                        append(if (s[i + 1] == 'n') '\n' else s[i + 1])
                        i += 2
                    } else {
                        append(c)
                        i++
                    }
                }
            }
    }
}
