package dev.omalauncher.widgets.runtime

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import dev.omalauncher.AppLabels
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.widgets.lang.num
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import dev.omalauncher.widgets.lang.textOrNull

/**
 * The phone's notifications, for `source n = android.notifications`, and `notification(...)` to open, dismiss or
 * clear them, press their buttons or reply to them. Read through [MediaListener], so they need the same notification
 * access as media widgets. Nothing is kept: each update reads what's in the shade now.
 */
class Notifications(
    private val context: Context,
    private val media: Media,
    /** "work" or "clone" for a profile beside the launcher's own, null for its own. */
    private val profile: (UserHandle) -> String?,
) {
    /** Where [value] runs: reading the shade is a binder call with every notification's extras; one at a time, for [labels]. */
    private val worker = Dispatchers.Default.limitedParallelism(1)

    private val labels = AppLabels(context)

    /** What a widget lists: not ongoing ones (music, downloads, the launcher's own service) or group headers. */
    private fun shown(): List<StatusBarNotification> {
        val listener = MediaListener.connected ?: return emptyList()
        val all = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        val hidden = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        return all.filter { it.notification.flags and hidden == 0 && (title(it) != null || text(it) != null) }.sortedByDescending { it.postTime }
    }

    private fun title(sbn: StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }

    private fun text(sbn: StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }

    fun value(): Value {
        val available = media.granted && MediaListener.connected != null
        val shown = if (available) shown() else emptyList()
        val items =
            shown.take(MAX_ITEMS).map { sbn ->
                val kind = profile(sbn.user)
                Obj(
                    mapOf(
                        "key" to Str(sbn.key),
                        "app" to Str(labels[sbn.packageName]),
                        "package" to Str(sbn.packageName),
                        // From a work profile or a cloned app; icon is the app's icon from that profile, badge included.
                        "profile" to (kind?.let(::Str) ?: Null),
                        "icon" to Str("app:" + sbn.packageName + (kind?.let { "@$it" } ?: "")),
                        "title" to Str(title(sbn).orEmpty()),
                        "text" to Str(text(sbn).orEmpty()),
                        "time" to Num(Math.floor(sbn.postTime / 1000.0)),
                        "clearable" to bool(sbn.isClearable),
                        "actions" to Lst(actions(sbn).map(::action)),
                    ),
                )
            }
        return Obj(mapOf("available" to bool(available), "count" to Num(shown.size.toDouble()), "items" to Lst(items)))
    }

    /** The buttons under a notification in the shade, in order: notification("action", key, i) presses the i-th. */
    private fun actions(sbn: StatusBarNotification): List<Notification.Action> = sbn.notification.actions.orEmpty().toList()

    /** Whether the button takes typed text, as Reply does: notification("reply", ...) answers through it. */
    private fun replies(a: Notification.Action) = a.remoteInputs.orEmpty().any { it.allowFreeFormInput }

    private fun action(a: Notification.Action) = Obj(mapOf("title" to Str(a.title?.toString().orEmpty()), "reply" to bool(replies(a))))

    /** Emits whenever a notification is posted or removed, or access is granted or revoked. */
    fun updates(): Flow<Value> = MediaListener.changes.map { withContext(worker) { value() } }.distinctUntilChanged()

    /**
     * "open" runs the notification's tap action and "dismiss" clears it; "clear" clears every one that can be, as the
     * shade's Clear all does. "action" presses the [arg]-th of its buttons, and "reply" sends [arg] as typed text through
     * the one that takes it. "snooze" hides it for [arg] seconds, after which Android shows it again; "snoozeAll" every
     * one that can be cleared, for [key] seconds. "unsnooze" brings a snoozed one back now, "unsnoozeAll" every one,
     * the shade's snoozes too. Without access, opens the setting that grants it.
     */
    fun control(
        command: String,
        key: String?,
        arg: Value?,
    ) {
        val listener = MediaListener.connected
        if (!media.granted || listener == null) return media.openAccessSettings()
        if (command == "clear") return listener.cancelAllNotifications()
        // Every one that can be cleared, for "mute 1h": each comes back when its time is up. The seconds come in key's place.
        if (command == "snoozeAll") {
            val ms = snoozeMs(key?.toDoubleOrNull()) ?: return
            for (sbn in shown()) if (sbn.isClearable) runCatching { listener.snoozeNotification(sbn.key, ms) }
            return
        }
        // Android has no unsnooze for apps, but snoozing a snoozed one again replaces its time: a moment, and it's back.
        if (command == "unsnooze" || command == "unsnoozeAll") {
            val snoozed = runCatching { listener.snoozedNotifications }.getOrNull().orEmpty()
            for (sbn in snoozed) if (command == "unsnoozeAll" || sbn.key == key) runCatching { listener.snoozeNotification(sbn.key, UNSNOOZE_MS) }
            return
        }
        val sbn = key?.let { runCatching { listener.getActiveNotifications(arrayOf(it)) }.getOrNull()?.firstOrNull() } ?: return
        when (command) {
            "open" -> if (send(sbn.notification.contentIntent)) autoCancel(listener, sbn)
            "dismiss" -> listener.cancelNotification(sbn.key)
            "snooze" -> snoozeMs(arg?.num())?.let { if (sbn.isClearable) runCatching { listener.snoozeNotification(sbn.key, it) } }
            "action" -> {
                val action = arg?.num()?.toInt()?.let { actions(sbn).getOrNull(it) } ?: return
                // Pressed bare, a reply button would send the app no text: that's "reply".
                if (!replies(action)) send(action.actionIntent)
            }
            "reply" -> {
                val text = arg.textOrNull()?.takeIf { it.isNotBlank() } ?: return
                val action = actions(sbn).firstOrNull(::replies) ?: return
                val inputs = action.remoteInputs.filter { it.allowFreeFormInput }.toTypedArray()
                val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                RemoteInput.addResultsToIntent(inputs, fill, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
                // Typed, as from the keyboard: some apps treat a reply picked from suggestions differently.
                RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
                send(action.actionIntent, fill)
            }
        }
    }

    /** How long an unsnoozed notification stays away: the shortest snooze that still reliably ends. */
    private val UNSNOOZE_MS = 1000L

    /** A snooze of [seconds], from a minute to a week; null for none. */
    private fun snoozeMs(seconds: Double?) = seconds?.takeIf { it > 0 }?.coerceIn(60.0, 7 * 24 * 3600.0)?.let { (it * 1000).toLong() }

    /** Sends one of a notification's intents from the launcher, in front; false if the app had cancelled it. */
    private fun send(
        intent: PendingIntent?,
        fill: Intent? = null,
    ): Boolean {
        intent ?: return false
        // The launcher is in front, so it may start the app; since Android 14 it has to say so.
        val options =
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            } else {
                null
            }
        return runCatching { intent.send(context, 0, fill, null, null, null, options) }.isSuccess
    }

    private fun autoCancel(
        listener: MediaListener,
        sbn: StatusBarNotification,
    ) {
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) listener.cancelNotification(sbn.key)
    }

    companion object {
        private const val MAX_ITEMS = 50
    }
}
