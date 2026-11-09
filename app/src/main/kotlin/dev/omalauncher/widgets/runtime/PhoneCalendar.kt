package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.registerNotExported
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone
import dev.omalauncher.hasPermission

/**
 * The events in the phone's calendars, local and synced, for widgets: `source c = android.calendar`, and
 * `calendar("request")` and `calendar("open")`. Read only (READ_CALENDAR), and nothing is asked for until a widget's tap
 * asks, or read until it's granted. Read while a widget showing them is on screen, like every android source.
 */
class PhoneCalendar(
    private val context: Context,
) {
    /** Asks the activity in front for the calendar, then says whether it was granted; set as it resumes, like Dictation's. */
    var ask: ((granted: (Boolean) -> Unit) -> Unit)? = null

    /** State, so a source that read it ungranted starts reading the moment it's granted. */
    private var granted by mutableStateOf(check())

    private fun check() = context.hasPermission(Manifest.permission.READ_CALENDAR)

    /** Granted or taken back in Android's settings while away: each activity that shows widgets checks as it resumes. */
    fun recheck() {
        granted = check()
    }

    /** calendar("request") from a tap: the permission dialog, or the app's settings once Android won't show it again. */
    fun request() {
        recheck()
        if (granted) return
        // A preview or a widget behind another app has no activity to ask from.
        ask?.invoke { recheck() }
    }

    /**
     * calendar("open", e): the phone's calendar app on the event (an event from the source, or its id), at that
     * occurrence of a repeating one; calendar("open"): on today. Needs no permission: the calendar app shows it.
     */
    fun open(event: Value?) {
        val id = ((event as? Obj)?.fields?.get("id") ?: event)?.takeIf { it is Str }?.toString()
        val eventId = id?.substringBefore('-')?.toLongOrNull()
        val begin = id?.substringAfter('-', "")?.toLongOrNull()
        val intent =
            if (eventId != null) {
                Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
                    .apply { if (begin != null) putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin) }
            } else {
                Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, System.currentTimeMillis()) }.build())
            }
        // A phone without a calendar app mustn't crash the launcher.
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** android.calendar: {granted, events}, the events as [events] reads them, none until it's granted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updates(): Flow<Value> =
        snapshotFlow { granted }.flatMapLatest { allowed ->
            if (!allowed) flowOf(value(false)) else changes().mapLatest {
                // A provider query can block; never run it on the UI thread. The grant can be taken back at any time.
                try {
                    withContext(Dispatchers.IO) { value(true, events()) }
                } catch (_: SecurityException) {
                    value(false)
                }
            }.catch { if (it is SecurityException) emit(value(false)) else throw it }
        }.distinctUntilChanged()

    /**
     * `omalauncher calendar` in the guest: the events of the next [days] days as android.calendar has them, at most [max]; null
     * without the permission, which only a widget's tap asks for. Main thread.
     */
    suspend fun read(
        days: Int,
        max: Int,
    ): List<Value>? {
        recheck()
        if (!granted) return null
        return try {
            withContext(Dispatchers.IO) { events(days, max) }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Provider changes and the clock both matter: an event can end without a database change, and an all-day one's
     * times are the phone's midnights, which move with its time zone.
     */
    private fun changes(): Flow<Unit> =
        callbackFlow {
            val resolver = context.contentResolver
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            try {
                listOf(CalendarContract.Events.CONTENT_URI, CalendarContract.Calendars.CONTENT_URI, CalendarContract.Instances.CONTENT_URI)
                    .forEach { resolver.registerContentObserver(it, true, observer) }
            } catch (e: SecurityException) {
                resolver.unregisterContentObserver(observer)
                throw e
            }
            val clock =
                IntentFilter().apply {
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_DATE_CHANGED)
                }
            val receiver = context.registerNotExported(clock) { trySend(Unit) }
            trySend(Unit)
            val timer = launch {
                while (true) {
                    delay(60_000L)
                    trySend(Unit)
                }
            }
            awaitClose {
                timer.cancel()
                resolver.unregisterContentObserver(observer)
                context.unregisterReceiver(receiver)
            }
        }.conflate()

    /**
     * Each occurrence from the start of today for [days] days, a repeating event's each on its own, that hasn't ended:
     * {id, eventId, title, start, end, allDay, calendar, color, location}, by start, at most [max]. Times are Unix
     * seconds, an all-day event's from midnight to midnight here, so fmtTime shows its day in any time zone.
     */
    private fun events(
        days: Int = DAYS,
        max: Int = MAX,
    ): List<Value> {
        val now = System.currentTimeMillis()
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val from = day.timeInMillis
        day.add(Calendar.DAY_OF_YEAR, days) // Local calendar days, including daylight-saving changes.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, day.timeInMillis)
        }.build()
        val projection =
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.DISPLAY_COLOR,
            )
        val rows = ArrayList<Triple<Long, Long, Value>>()
        context.contentResolver.query(
            uri, projection,
            // DELETED is the event's: an instance has none of its own. A cancelled time of a repeating event never
            // comes, but a cancelled meeting on its own does, as does one you declined: neither is still to come.
            "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Events.DELETED} = 0" +
                " AND ifnull(${CalendarContract.Instances.STATUS}, -1) != ${CalendarContract.Instances.STATUS_CANCELED}" +
                " AND ifnull(${CalendarContract.Instances.SELF_ATTENDEE_STATUS}, -1) != ${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED}",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val begin = cursor.getLong(1)
                val allDay = cursor.getInt(5) != 0
                // An all-day event is stored from UTC midnight to UTC midnight, not the phone's: on its own, today's
                // would end at 19:00 in New York and yesterday's at 02:00 in Berlin, and show on the day before there.
                val start = if (allDay) here(begin) else begin
                val end = if (allDay) here(cursor.getLong(2)) else cursor.getLong(2)
                if (end <= now) continue
                rows += Triple(start, end, Obj(
                    mapOf(
                        // The occurrence: what calendar("open", e) opens, and a for's key.
                        "id" to Str("$eventId-$begin"),
                        "eventId" to Num(eventId.toDouble()),
                        "title" to Str(cursor.getString(3) ?: ""),
                        "start" to Num(start / 1000.0),
                        "end" to Num(end / 1000.0),
                        "allDay" to bool(allDay),
                        "calendar" to Str(cursor.getString(6) ?: ""),
                        // The event's own color where it has one, else its calendar's, as the calendar app shows it.
                        "color" to Str("#%06x".format(cursor.getInt(7) and 0xffffff)),
                        "location" to Str(cursor.getString(4) ?: ""),
                    ),
                ))
            }
        }
        return rows.sortedWith(compareBy({ it.first }, { it.second })).take(max).map { it.third }
    }

    /** UTC midnight [utc] as the same date's midnight on the phone. */
    private fun here(utc: Long): Long {
        val d = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
        return Calendar.getInstance().apply {
            clear()
            set(d.get(Calendar.YEAR), d.get(Calendar.MONTH), d.get(Calendar.DAY_OF_MONTH))
        }.timeInMillis
    }

    private fun value(
        granted: Boolean,
        events: List<Value> = emptyList(),
    ): Value = Obj(mapOf("granted" to bool(granted), "events" to Lst(events)))

    private companion object {
        const val DAYS = 14
        const val MAX = 100
    }
}
