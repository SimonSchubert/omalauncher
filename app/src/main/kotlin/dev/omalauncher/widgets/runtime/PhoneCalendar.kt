package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Upcoming events on the phone, including synced and local calendars, for `android.calendar`. Read only after a tap asks. */
class PhoneCalendar(
    private val context: Context,
) {
    var ask: ((granted: (Boolean) -> Unit) -> Unit)? = null
    private var granted by mutableStateOf(check())

    private fun check() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun recheck() {
        granted = check()
    }

    fun request() {
        recheck()
        if (!granted) ask?.invoke { recheck() }
    }

    /** {granted, events}; each event has id, eventId, title, start, end, date, allDay, calendar and location.
     * Times are Unix seconds; date is the local calendar date of an all-day event (YYYY-MM-DD).
     */
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

    /** Provider changes and the clock both matter: an event can become current without a database change. */
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
            }
        }.conflate()

    private fun events(): List<Value> {
        val now = System.currentTimeMillis()
        val day = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = day.timeInMillis
        day.add(Calendar.DAY_OF_YEAR, 14) // Local calendar days, including daylight-saving changes.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
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
            )
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val result = ArrayList<Value>()
        context.contentResolver.query(
            uri, projection,
            "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.DELETED} = 0 AND ${CalendarContract.Instances.END} > ?",
            arrayOf(now.toString()),
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && result.size < 100) {
                val eventId = cursor.getLong(0)
                val begin = cursor.getLong(1)
                val end = cursor.getLong(2)
                val allDay = cursor.getInt(5) != 0
                result += Obj(
                    mapOf(
                        "id" to Str("$eventId-$begin"),
                        "eventId" to Num(eventId.toDouble()),
                        "title" to Str(cursor.getString(3) ?: ""),
                        "start" to Num(begin / 1000.0),
                        "end" to Num(end / 1000.0),
                        "date" to (if (allDay) Str(date.format(Date(begin))) else Null),
                        "allDay" to bool(allDay),
                        "calendar" to Str(cursor.getString(6) ?: ""),
                        "location" to Str(cursor.getString(4) ?: ""),
                    ),
                )
            }
        }
        return result
    }

    private fun value(granted: Boolean, events: List<Value> = emptyList()): Value =
        Obj(mapOf("granted" to bool(granted), "events" to Lst(events)))
}
