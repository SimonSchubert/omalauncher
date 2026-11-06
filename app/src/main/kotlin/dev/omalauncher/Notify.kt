package dev.omalauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * A notification that opens the home screen, on [channel] (named [channelName] in Android's settings), under [tag] so
 * each poster counts its own ids. False when it can't be posted: notifications off for the launcher, or the channel.
 * Needs the notification permission.
 */
fun Context.postNotification(
    channel: String,
    channelName: String,
    tag: String,
    id: Int,
    title: String,
    text: String?,
): Boolean {
    val nm = getSystemService(NotificationManager::class.java)
    if (!nm.areNotificationsEnabled()) return false
    nm.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT))
    if (nm.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n =
        Notification
            .Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // All of a longer text as it's pulled open, not its first line.
            .setStyle(text?.let { Notification.BigTextStyle().bigText(it) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    nm.notify(tag, id, n)
    return true
}
