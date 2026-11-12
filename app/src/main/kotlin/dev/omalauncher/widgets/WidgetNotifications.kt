package dev.omalauncher.widgets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import dev.omalauncher.MainActivity
import dev.omalauncher.R
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.runtime.WidgetNotification

/**
 * Widgets' own notifications (`notification:`), one per widget file, tagged with its name: posted again only when what
 * it says changes, and taken away when it's null or the widget is gone. A countdown or a count up is a chronometer
 * Android runs, so the lock screen keeps time while the widget, off screen, doesn't redraw.
 */
class WidgetNotifications(
    private val context: Context,
) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** What each widget's notification says as posted, by file name; a button press reads its value from here. */
    private val shown = HashMap<String, WidgetNotification>()

    /** Shows [file]'s notification as [n] says, or takes it away for null. */
    fun show(
        file: String,
        n: WidgetNotification?,
    ) {
        if (n == null) {
            // Whether or not this run posted it: after a restart one from before can still show.
            shown.remove(file)
            nm.cancel(file, ID)
            return
        }
        if (shown[file] == n) return
        // Not kept as shown: once notifications are allowed again, the next change posts it.
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.widgets_status_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                // Default importance, so the lock screen shows it where it hides silent ones; a timer's ring is the
                // widget's own notify(...), so posting and updating this one makes no sound.
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val clock = n.until ?: n.since
        val b =
            Notification
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // A timer's label and time on the lock screen, as a clock app's; the docs say it shows there.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (clock != null) {
            b
                .setWhen((clock * 1000).toLong())
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(n.until != null)
            // Live updates (Android 16 QPR2 on): a running clock is something under way, shown at the top of the lock
            // screen and as a chip in the status bar.
            if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) b.setRequestPromotedOngoing(true)
        } else {
            b.setShowWhen(false)
        }
        n.buttons.forEachIndexed { i, button ->
            val press = Intent(PRESS).setPackage(context.packageName).putExtra(EXTRA_FILE, file).putExtra(EXTRA_BUTTON, i)
            val pending = PendingIntent.getBroadcast(context, "$file\u0000$i".hashCode(), press, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null as Icon?, button.text, pending).build())
        }
        nm.notify(file, ID, b.build())
        shown[file] = n
    }

    /** The value of [file]'s button [i] as last shown, for its onNotification; null for one that isn't there now. */
    fun pressed(
        file: String,
        i: Int,
    ): Value? = shown[file]?.buttons?.getOrNull(i)?.value

    /**
     * Takes away the notifications of widgets not in [files]: deleted, no longer compiling or without a notification:
     * any more, and at startup those a launcher that stopped left behind.
     */
    fun keepOnly(files: Set<String>) {
        shown.keys.retainAll(files)
        runCatching { nm.activeNotifications }.getOrNull()?.forEach { s ->
            if (s.id == ID && s.tag != null && s.tag !in files) nm.cancel(s.tag, ID)
        }
    }

    companion object {
        const val PRESS = "dev.omalauncher.WIDGET_NOTIFICATION_BUTTON"
        const val EXTRA_FILE = "file"
        const val EXTRA_BUTTON = "button"
        private const val CHANNEL = "widget-status"

        /** Every widget's has this id, told apart by its tag, the file name. */
        private const val ID = 7701
    }
}
