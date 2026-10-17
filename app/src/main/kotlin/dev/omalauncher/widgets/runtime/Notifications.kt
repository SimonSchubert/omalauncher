package dev.omalauncher.widgets.runtime

import android.app.ActivityOptions
import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The phone's notifications, for `source n = android.notifications`, and
 * `notification("open" | "dismiss", key)`. Read through [MediaListener], so
 * they need the same notification access as media widgets. Nothing is kept:
 * each update reads what's in the shade now.
 */
class Notifications(
    private val context: Context,
    private val media: Media,
    /** "work" or "clone" for a profile beside the launcher's own, null for its own. */
    private val profile: (UserHandle) -> String?,
) {
    /** App labels by package; the lookup is a binder call. */
    private val labels = HashMap<String, String>()

    /** What a widget lists: not ongoing ones (music, downloads, the launcher's own service) or group headers. */
    private fun shown(): List<StatusBarNotification> {
        val listener = MediaListener.connected ?: return emptyList()
        val all = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        val hidden = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        return all.filter { it.notification.flags and hidden == 0 && (title(it) != null || text(it) != null) }.sortedByDescending { it.postTime }
    }

    private fun title(sbn: StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }

    private fun text(sbn: StatusBarNotification) = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }

    /** The conversation's messages of a MessagingStyle notification, oldest first. */
    private fun messages(sbn: StatusBarNotification): List<Value> {
        val style = sbn.notification.extras.getParcelableArray(Notification.EXTRA_MESSAGING_STYLE)
            ?.filterIsInstance<Notification.MessagingStyle>()?.lastOrNull() ?: return emptyList()
        return style.messages.map { m ->
            Obj(
                mapOf(
                    "sender" to Str(m.senderPerson?.name?.toString().orEmpty()),
                    "text" to Str(m.text?.toString().orEmpty()),
                    "time" to Num(Math.floor(m.timestamp / 1000.0)),
                ),
            )
        }
    }

    private fun label(pkg: String) =
        labels.getOrPut(pkg) {
            runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }

    fun value(): Value {
        val available = media.granted && MediaListener.connected != null
        val shown = if (available) shown() else emptyList()
        val items =
            shown.take(MAX_ITEMS).map { sbn ->
                val kind = profile(sbn.user)
                Obj(
                    mapOf(
                        "key" to Str(sbn.key),
                        "app" to Str(label(sbn.packageName)),
                        "package" to Str(sbn.packageName),
                        // From a work profile or a cloned app; icon is the app's icon from that profile, badge included.
                        "profile" to (kind?.let(::Str) ?: Null),
                        "icon" to Str("app:" + sbn.packageName + (kind?.let { "@$it" } ?: "")),
                        "title" to Str(title(sbn).orEmpty()),
                        "text" to Str(text(sbn).orEmpty()),
                        "time" to Num(Math.floor(sbn.postTime / 1000.0)),
                        "clearable" to bool(sbn.isClearable),
                        "messages" to Lst(messages(sbn)),
                    ),
                )
            }
        return Obj(mapOf("available" to bool(available), "count" to Num(shown.size.toDouble()), "items" to Lst(items)))
    }

    /** Emits whenever a notification is posted or removed, or access is granted or revoked. */
    fun updates(): Flow<Value> = MediaListener.changes.map { value() }.distinctUntilChanged()

    /** "open" runs the notification's tap action, "dismiss" clears it; without access, opens the setting that grants it. */
    fun control(
        command: String,
        key: String,
    ) {
        val listener = MediaListener.connected
        if (!media.granted || listener == null) return media.openAccessSettings()
        val sbn = runCatching { listener.getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull() ?: return
        when (command) {
            "open" -> {
                val intent = sbn.notification.contentIntent ?: return
                // The launcher is in front, so it may start the app; since Android 14 it has to say so.
                val options =
                    if (Build.VERSION.SDK_INT >= 34) {
                        @Suppress("DEPRECATION")
                        ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
                    } else {
                        null
                    }
                runCatching { intent.send(context, 0, null, null, null, null, options) }
                if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) listener.cancelNotification(key)
            }
            "dismiss" -> listener.cancelNotification(key)
        }
    }

    companion object {
        private const val MAX_ITEMS = 50
    }
}
