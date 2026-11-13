package dev.omalauncher.widgets.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.content.Context
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What a dot goes on: an app in a profile (as in [dev.omalauncher.AndroidApp.profile]), or with [shortcut], that one of its shortcuts. */
data class DotKey(
    val packageName: String,
    val profile: Long?,
    val shortcut: String? = null,
)

/** Whether a notification can put a dot on its app, by Launcher3's rules. Android's own dot settings are [Ranking.canShowBadge]'s. */
internal fun countsForDot(
    flags: Int,
    channel: String?,
    title: CharSequence?,
    text: CharSequence?,
): Boolean {
    if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
    // An app that predates channels can't turn dots off for its ongoing notifications, so they don't get one.
    if (channel == NotificationChannel.DEFAULT_CHANNEL_ID && flags and Notification.FLAG_ONGOING_EVENT != 0) return false
    return !title.isNullOrEmpty() || !text.isNullOrEmpty()
}

/** The notifications that can put a dot on an app, by their keys. Not thread-safe. */
internal class DotTable {
    private class Entry(
        val owner: DotKey,
        var shows: Boolean,
    )

    private val entries = HashMap<String, Entry>()

    fun post(
        key: String,
        owner: DotKey,
        shows: Boolean,
    ) {
        entries[key] = Entry(owner, shows)
    }

    fun remove(key: String) {
        entries.remove(key)
    }

    fun clear() = entries.clear()

    /** Android changed what may show a dot: a channel's setting, the phone's, or Do Not Disturb. */
    fun rank(shows: (String) -> Boolean) {
        for ((key, entry) in entries) entry.shows = shows(key)
    }

    /** Every app with a dot, and every shortcut: a notification from a conversation dots that chat's shortcut, not the app's other ones. */
    fun dots(): Set<DotKey> =
        buildSet {
            for (entry in entries.values) {
                if (!entry.shows) continue
                add(entry.owner.copy(shortcut = null))
                add(entry.owner)
            }
        }
}

/**
 * Which apps have a notification dot, for their icons on home, in folders and in the drawer. Follows [MediaListener]
 * one notification at a time, from what Android hands it, so it never asks Android for the shade after the first
 * read. Without notification access the listener never binds, nothing runs here and every icon reads an empty set.
 */
object NotificationDots {
    /** One at a time and in order, so a notification that comes during the first read is applied after it. */
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, e -> Log.w("widgets", "notification dots", e) })

    private val table = DotTable()

    /** Only changes when a dot comes or goes, so a second message from an app redraws nothing. */
    private var current by mutableStateOf(emptySet<DotKey>())

    fun has(
        packageName: String,
        profile: Long?,
        shortcut: String? = null,
    ): Boolean {
        val dots = current
        return dots.isNotEmpty() && DotKey(packageName, profile, shortcut) in dots
    }

    fun connected(listener: NotificationListenerService) =
        update {
            val active = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
            val ranking = runCatching { listener.currentRanking }.getOrNull()
            table.clear()
            if (ranking != null) for (sbn in active) put(listener, sbn, ranking)
        }

    fun posted(
        listener: Context,
        sbn: StatusBarNotification,
        ranking: RankingMap,
    ) = update { put(listener, sbn, ranking) }

    fun removed(key: String) = update { table.remove(key) }

    fun ranked(ranking: RankingMap) =
        update {
            val out = Ranking()
            table.rank { ranking.getRanking(it, out) && out.canShowBadge() }
        }

    fun disconnected() = update { table.clear() }

    private fun update(change: () -> Unit) {
        worker.launch {
            change()
            current = table.dots()
        }
    }

    private fun put(
        context: Context,
        sbn: StatusBarNotification,
        ranking: RankingMap,
    ) {
        val out = Ranking()
        val ranked = ranking.getRanking(sbn.key, out)
        val n = sbn.notification
        // Another app's extras can fail to unparcel; such a notification just gets no dot.
        val counts = runCatching { countsForDot(n.flags, out.channel?.id, n.extras.getCharSequence(Notification.EXTRA_TITLE), n.extras.getCharSequence(Notification.EXTRA_TEXT)) }.getOrDefault(false)
        if (counts) {
            table.post(sbn.key, DotKey(sbn.packageName, profileOf(context, sbn.user), n.shortcutId), ranked && out.canShowBadge())
        } else {
            table.remove(sbn.key)
        }
    }

    private fun profileOf(
        context: Context,
        user: UserHandle,
    ): Long? = if (user == Process.myUserHandle()) null else context.getSystemService(UserManager::class.java).getSerialNumberForUser(user)
}
