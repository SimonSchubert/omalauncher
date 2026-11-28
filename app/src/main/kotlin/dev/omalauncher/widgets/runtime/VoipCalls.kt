package dev.omalauncher.widgets.runtime

import android.app.Notification
import android.app.Person
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.edit
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A call made or missed in a calling app (WhatsApp, Messenger, …), as [VoipTracker] saw it in the app's notifications. */
data class VoipCall(
    val pkg: String,
    /** in, out or missed. */
    val direction: String,
    val name: String,
    /** The other side's number where the app's notification says it (a `tel:` person), else "". */
    val number: String,
    val video: Boolean,
    /** When its first notification came, in ms. */
    val start: Long,
    /** Seconds from when it was answered (or, going out, placed) to its last notification going, 0 for a missed one. */
    val duration: Long,
) {
    val id get() = "$pkg-$start"
}

/**
 * Tells calls apart in a calling app's call notifications (Android's CallStyle: one that rings, one while the call
 * lasts), from what was posted and removed and when. Pure, so the sequences are tested on the JVM. One call per app at a
 * time, keyed by app rather than by notification: apps ring and talk in different notifications, and the ringing one
 * may go before or after the ongoing one comes, so a call only ends once every one of its notifications has been gone
 * for [grace] ms. Nor while the phone's audio is in a call ([audio]): WhatsApp takes its call notification away while
 * its own call screen is in front, so a call it shows there ends when the audio does.
 *
 * A call first seen ringing came in: answered once an ongoing one comes, missed if it ends without. A call first seen
 * ongoing went out, timed from then, as the notification can't say when the other side picked up.
 */
class VoipTracker(
    private val grace: Long = GRACE_MS,
) {
    private class Live(
        val pkg: String,
        val incoming: Boolean,
        val start: Long,
        var answered: Long?,
        var name: String,
        var number: String,
        var video: Boolean,
        val keys: MutableSet<String> = HashSet(),
        var gone: Long? = null,
    )

    private val live = HashMap<String, Live>()

    /** Whether the phone's audio is in a call now, and when it last left one. */
    private var inCall = false
    private var audioLeft = 0L

    /** Whether a call is under way or waiting out its grace, so the listener knows to come back for [due]. */
    val busy get() = live.isNotEmpty()

    /** A call notification [key] of [pkg] posted, again or for the first time; [ringing] for one that rings, else ongoing. */
    fun posted(
        pkg: String,
        key: String,
        ringing: Boolean,
        name: String,
        number: String,
        video: Boolean,
        now: Long,
    ) {
        val call =
            live.getOrPut(pkg) {
                // Answered calls are timed from when the ongoing notification came; outgoing ones from the start.
                Live(pkg, incoming = ringing, start = now, answered = if (ringing) null else now, name = name, number = number, video = video)
            }
        call.gone = null
        call.keys += key
        if (!ringing && call.answered == null) call.answered = now
        if (name.isNotBlank()) call.name = name
        if (number.isNotBlank()) call.number = number
        call.video = call.video || video
    }

    /** Notification [key] went: its call ends once none of its notifications is back within [grace]. */
    fun removed(
        key: String,
        now: Long,
    ) {
        val call = live.values.firstOrNull { key in it.keys } ?: return
        call.keys -= key
        if (call.keys.isEmpty()) call.gone = now
    }

    /** The phone's audio mode: [calling] while it's in a call or a VoIP call, as told at [now]. */
    fun audio(
        calling: Boolean,
        now: Long,
    ) {
        if (inCall && !calling) audioLeft = now
        inCall = calling
    }

    /** The keys of every call notification still showing, for checking against Android's after the listener reconnects. */
    fun keys(): Set<String> = live.values.flatMapTo(HashSet()) { it.keys }

    /** The calls that have ended by [now], each once. */
    fun due(now: Long): List<VoipCall> {
        if (inCall) return emptyList()
        // Its notifications went, or the audio left the call after they did, whichever came later.
        fun end(c: Live) = maxOf(c.gone!!, audioLeft)
        val ended = live.values.filter { c -> c.gone != null && now - end(c) >= grace }
        for (c in ended) live.remove(c.pkg)
        return ended.map { c ->
            val end = end(c)
            VoipCall(
                pkg = c.pkg,
                direction = if (!c.incoming) "out" else if (c.answered == null) "missed" else "in",
                name = c.name,
                number = c.number,
                video = c.video,
                start = c.start,
                duration = c.answered?.let { ((end - it) / 1000).coerceAtLeast(0) } ?: 0,
            )
        }
    }

    companion object {
        const val GRACE_MS = 4_000L
    }
}

/**
 * Calls in other calling apps, for android.calls: [MediaListener] hands every notification here, and once the user
 * allowed calls("request") the calls it sees are kept in `files/voip-calls.json`, newest first, at most [MAX]. The
 * phone's own calls are in Android's call log, so the dialer's notifications are skipped. Without notification access
 * nothing comes, and nothing is kept before the user asked.
 */
object VoipCalls {
    /** One at a time and in order, as notifications come. */
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + CoroutineExceptionHandler { _, e -> Log.w("widgets", "voip calls", e) })

    private val tracker = VoipTracker()

    /** Follows the audio mode while a call is live, and keeps each call as it ends. On the worker, one at a time. */
    private var watch: kotlinx.coroutines.Job? = null

    @Volatile
    private var calls: List<VoipCall>? = null

    /** Bumped when a call is kept, for android.calls to read again. */
    val changes = MutableStateFlow(0)

    /** The calls kept, newest first; read from the file the first time. Any thread. */
    fun all(context: Context): List<VoipCall> = calls ?: synchronized(this) { calls ?: load(context).also { calls = it } }

    /** Whether calls are kept: from calls("request") on. */
    fun recording(context: Context) = prefs(context).getBoolean(RECORD, false)

    fun record(context: Context) = prefs(context).edit { putBoolean(RECORD, true) }

    fun posted(
        context: Context,
        sbn: StatusBarNotification,
    ) {
        if (!recording(context)) return
        val call = callOf(context, sbn) ?: return
        val now = System.currentTimeMillis()
        worker.launch {
            tracker.posted(sbn.packageName, sbn.key, call.ringing, call.name, call.number, call.video, now)
            settle(context)
        }
    }

    fun removed(
        context: Context,
        sbn: StatusBarNotification,
    ) {
        val now = System.currentTimeMillis()
        worker.launch {
            if (sbn.key !in tracker.keys()) return@launch
            tracker.removed(sbn.key, now)
            settle(context)
        }
    }

    /**
     * The listener bound again, maybe after the launcher was gone: calls whose notifications went meanwhile end now,
     * and those still showing carry on, from when they were posted if this is the first we see of them.
     */
    fun connected(listener: NotificationListenerService) {
        if (!recording(listener)) return
        val active = runCatching { listener.activeNotifications }.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        worker.launch {
            val showing = active.mapNotNull { sbn -> callOf(listener, sbn)?.let { sbn to it } }
            val keys = showing.mapTo(HashSet()) { it.first.key }
            for (key in tracker.keys() - keys) tracker.removed(key, now)
            for ((sbn, call) in showing) tracker.posted(sbn.packageName, sbn.key, call.ringing, call.name, call.number, call.video, sbn.postTime.takeIf { it in 1..now } ?: now)
            settle(context = listener)
        }
    }

    /**
     * Keeps the calls that ended, and while one is live looks at the audio mode every [POLL_MS] until it ends: Android
     * says nothing when an app's call ends with its notification already gone. On the worker.
     */
    private fun settle(context: Context) {
        tracker.audio(calling(context), System.currentTimeMillis())
        keep(context, tracker.due(System.currentTimeMillis()))
        if (!tracker.busy || watch?.isActive == true) return
        watch =
            worker.launch {
                while (tracker.busy) {
                    delay(POLL_MS)
                    tracker.audio(calling(context), System.currentTimeMillis())
                    keep(context, tracker.due(System.currentTimeMillis()))
                }
            }
    }

    /** Whether the phone's audio is in a call: the phone's own, or an app's VoIP call. Needs no permission. */
    private fun calling(context: Context): Boolean =
        runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull() in IN_CALL_MODES

    private fun keep(
        context: Context,
        ended: List<VoipCall>,
    ) {
        if (ended.isEmpty()) return
        save(context, (ended + all(context)).distinctBy { it.id }.sortedByDescending { it.start }.take(MAX))
    }

    /** calls("delete") on a call kept here: it's gone from the list and the file. On the worker, as calls are kept. */
    fun forget(
        context: Context,
        id: String,
    ): kotlinx.coroutines.Job =
        worker.launch {
            val all = all(context)
            if (all.none { it.id == id }) return@launch
            save(context, all.filterNot { it.id == id })
        }

    private fun save(
        context: Context,
        next: List<VoipCall>,
    ) {
        calls = next
        val array = JSONArray()
        for (c in next) {
            array.put(
                JSONObject()
                    .put("package", c.pkg)
                    .put("direction", c.direction)
                    .put("name", c.name)
                    .put("number", c.number)
                    .put("video", c.video)
                    .put("start", c.start)
                    .put("duration", c.duration),
            )
        }
        runCatching { Versioned.writeFile(file(context), VERSION, JSONObject().put("calls", array)) }.onFailure { Log.w("widgets", "voip calls: ${it.message}") }
        changes.update { it + 1 }
    }

    private fun load(context: Context): List<VoipCall> =
        runCatching { Versioned.readFile(file(context), VERSION) }.getOrNull()?.optJSONArray("calls")?.mapObjects {
            VoipCall(
                pkg = it.getString("package"),
                direction = it.getString("direction").takeIf { d -> d in DIRECTIONS } ?: return@mapObjects null,
                name = it.optString("name"),
                number = it.optString("number"),
                video = it.optBoolean("video"),
                start = it.getLong("start"),
                duration = it.optLong("duration"),
            )
        }.orEmpty()

    private class Seen(
        val ringing: Boolean,
        val name: String,
        val number: String,
        val video: Boolean,
    )

    /**
     * A calling app's call notification: Android's CallStyle says so with its call type, older ones only with the call
     * category, and then a full-screen intent is what rings. The dialer's own (in the call log already), group
     * summaries and missed-call notices aren't calls.
     */
    private fun callOf(
        context: Context,
        sbn: StatusBarNotification,
    ): Seen? {
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        // Another app's extras can fail to unparcel; such a notification just isn't a call.
        return runCatching {
            val extras = n.extras
            val type = extras.getInt(EXTRA_CALL_TYPE, 0)
            if (type == 0 && n.category != Notification.CATEGORY_CALL) return null
            if (sbn.packageName == context.packageName || sbn.packageName in dialers(context)) return null
            val ringing = if (type != 0) type == CALL_TYPE_INCOMING || type == CALL_TYPE_SCREENING else n.fullScreenIntent != null
            val person = personOf(extras)
            val name = person?.name?.toString()?.takeIf { it.isNotBlank() } ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val number = person?.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")?.let(android.net.Uri::decode).orEmpty()
            Seen(ringing, name.trim(), number, extras.getBoolean(EXTRA_CALL_IS_VIDEO, false))
        }.getOrNull()
    }

    private fun personOf(extras: android.os.Bundle): Person? =
        if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(EXTRA_CALL_PERSON, Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable<Person>(EXTRA_CALL_PERSON)
        }

    /** The phone's own call screens, whose calls the call log has. */
    private fun dialers(context: Context): Set<String> =
        DIALERS + listOfNotNull(runCatching { context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun file(context: Context) = File(context.filesDir, "voip-calls.json")

    private const val POLL_MS = 2_000L
    private val IN_CALL_MODES = setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION)
    private const val VERSION = 1
    private const val MAX = 1000
    private const val PREFS = "calls"
    private const val RECORD = "record_voip"
    private val DIRECTIONS = setOf("in", "out", "missed")
    private val DIALERS = setOf("com.android.server.telecom", "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.incallui", "com.samsung.android.dialer")

    // Notification.CallStyle's extras, public under these names from Android 12; read by name to work before it.
    private const val EXTRA_CALL_TYPE = "android.callType"
    private const val EXTRA_CALL_PERSON = "android.callPerson"
    private const val EXTRA_CALL_IS_VIDEO = "android.callIsVideo"
    private const val CALL_TYPE_INCOMING = 1
    private const val CALL_TYPE_SCREENING = 3
}
