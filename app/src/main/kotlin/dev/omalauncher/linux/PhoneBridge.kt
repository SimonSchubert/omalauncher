package dev.omalauncher.linux

import android.content.Context
import android.os.FileObserver
import android.util.Log
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Json
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.widgets.runtime.Aap
import dev.omalauncher.widgets.runtime.AirPods
import dev.omalauncher.widgets.runtime.PhoneCalendar
import dev.omalauncher.widgets.runtime.PhoneLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val TAG = "phone"

/**
 * `omalauncher` for the guest (`phone` until 0.20, which stays as another name for it): the phone's location, calendar
 * and AirPods for agents and scripts, as JSON, from the same sources widgets read. Only what the launcher was already
 * allowed: nothing here asks for a permission; a widget's tap does that. A request is a file in the runtime dir's
 * phone/ (<id>.req, its lines the command and its arguments), the answer <id>.done: `ok` and then the JSON, or
 * `usage: ...`, `denied: ...` or `error: ...`, which the script tells apart by exit code; like owl-send's inbox.
 */
class PhoneBridge(
    private val context: Context,
    private val proot: Proot,
    private val location: PhoneLocation,
    private val calendar: PhoneCalendar,
    private val airpods: () -> AirPods,
    /** Main: the sources are read on the main thread, as widgets read them. */
    private val scope: CoroutineScope,
) {
    private val inbox = File(proot.runtimeDir, "phone")
    private var observer: FileObserver? = null

    fun managed(): List<Managed> {
        val bin = File(proot.rootfs, "usr/local/bin")
        return listOf(
            Managed.asset(context, SCRIPT, File(bin, COMMAND), Policy.Owned, executable = true),
            // Agents told about `phone` in 0.20, and AGENTS.md files edited since, still call it that.
            Managed.text(File(bin, OLD_COMMAND), Policy.Owned, executable = true) {
                "#!/bin/sh\n# omalauncher: phone was the name of omalauncher until 0.20\nexec $COMMAND \"$@\"\n"
            },
        )
    }

    /** Any thread. */
    fun start() {
        if (observer != null) return
        inbox.mkdirs()
        // Answers nobody waited for, from an `omalauncher` killed while it waited: nothing reads them now.
        inbox.listFiles()?.forEach { it.delete() }
        observer =
            object : FileObserver(inbox, CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(
                    event: Int,
                    path: String?,
                ) {
                    if (path != null && path.endsWith(".req") && '/' !in path) scope.launch { receive(path.removeSuffix(".req")) }
                }
            }.also { it.startWatching() }
    }

    private suspend fun receive(id: String) {
        val request = File(inbox, "$id.req")
        val args = withContext(Dispatchers.IO) { runCatching { request.readText() }.getOrNull().also { request.delete() } }?.lines() ?: return
        val reply =
            try {
                when (args[0]) {
                    "location" -> location()
                    "calendar" -> calendar(args.getOrNull(1))
                    "airpods" -> airpods(args.getOrNull(1)?.takeIf { it.isNotEmpty() }, args.getOrNull(2))
                    else -> Answer.Error("unknown command '${args[0]}'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "phone ${args[0]} failed", e)
                Answer.Error("${args[0]} failed: ${e.message}")
            }
        val text =
            when (reply) {
                is Answer.Ok -> "ok\n" + (Json.write(reply.value) ?: "null")
                is Answer.Error -> "error: ${reply.message}"
                is Answer.Usage -> "usage: ${reply.message}"
                is Answer.Denied -> "denied: " + NOT_ALLOWED.format(reply.what)
            }
        withContext(Dispatchers.IO) { runCatching { writeAtomically(File(inbox, "$id.done"), (text + "\n").toByteArray()) } }
    }

    /** The fix android.location has, or the first one within [LOCATION_MS]. */
    private suspend fun location(): Answer {
        location.recheck()
        val v = location.updates().settle(LOCATION_MS) { !it.flag("granted") || it.flag("available") }
        return when {
            v == null || !v.flag("granted") -> Answer.Denied("location")
            !v.flag("available") -> Answer.Error("no location yet; is location on, and the launcher in front?")
            else -> Answer.Ok(v.without("granted", "available"))
        }
    }

    private suspend fun calendar(days: String?): Answer {
        val n = if (days.isNullOrEmpty()) DAYS else days.toIntOrNull()?.takeIf { it in 1..MAX_DAYS } ?: return Answer.Usage("days: 1 to $MAX_DAYS")
        val events = calendar.read(n, MAX_EVENTS) ?: return Answer.Denied("calendar")
        return Answer.Ok(Lst(events))
    }

    /**
     * The AirPods connected to the phone, once their link is up and they've said their batteries; with [word], one of
     * airpods(...)'s controls first.
     */
    private suspend fun airpods(
        word: String?,
        arg: String?,
    ): Answer {
        if (word != null && word !in CONTROLS) return Answer.Usage("airpods: one of ${CONTROLS.joinToString(", ")}")
        val pods = airpods()
        pods.recheck()
        if (!pods.supported) return Answer.Error("this phone can't link to AirPods (Android 16 QPR3 or later)")
        if (!pods.granted) return Answer.Denied("Bluetooth")
        if (!pods.enabled) return Answer.Error("Bluetooth is off")
        val updates = pods.updates()
        val v = updates.settle(AIRPODS_MS) { it.flag("connected") && (it.has("left") || it.has("right") || it.has("headset")) }
        if (v == null || !v.flag("connected")) return Answer.Error("no AirPods connected")
        if (word == null) return Answer.Ok(v.without("supported", "granted"))
        pods.control(word, arg?.takeIf { it.isNotEmpty() }?.let(::argument))
        // A listening mode comes back from the AirPods; the other settings stand as sent at once.
        val after = updates.settle(CONTROL_MS) { it != v } ?: v
        return Answer.Ok(after.without("supported", "granted"))
    }

    private sealed interface Answer {
        class Ok(
            val value: Value,
        ) : Answer

        class Error(
            val message: String,
        ) : Answer

        /** Wrong arguments: exit code 2. */
        class Usage(
            val message: String,
        ) : Answer

        /** Not allowed: exit code 3. */
        class Denied(
            val what: String,
        ) : Answer
    }

    private companion object {
        const val COMMAND = "omalauncher"
        const val OLD_COMMAND = "phone"
        const val SCRIPT = "$COMMAND.sh"
        const val NOT_ALLOWED = "the launcher isn't allowed the %s; a widget that shows it asks, or Android's settings for the app"
        const val LOCATION_MS = 10_000L
        const val AIRPODS_MS = 12_000L
        const val CONTROL_MS = 2_000L
        const val DAYS = 14
        const val MAX_DAYS = 366
        const val MAX_EVENTS = 1000
        val CONTROLS = Aap.MODES + listOf("next", "conversation", "oneBud", "earDetection", "adaptiveLevel")

        /** on/off as well as JSON: `omalauncher airpods conversation off`. */
        fun argument(text: String): Value =
            when (text) {
                "on" -> bool(true)
                "off" -> bool(false)
                else -> Json.parseOrText(text)
            }

        fun Value.flag(key: String) = ((this as? Obj)?.fields?.get(key) as? Bool)?.v == true

        fun Value.has(key: String) = (this as? Obj)?.fields?.get(key).let { it != null && it != Null && !(it is Num && it.v.isNaN()) }

        fun Value.without(vararg keys: String): Value = (this as? Obj)?.let { o -> Obj(o.fields - keys.toSet()) } ?: this

        /** The first value that's [done], or the last one by [ms]; null if none came. */
        suspend fun <T> Flow<T>.settle(
            ms: Long,
            done: (T) -> Boolean,
        ): T? {
            var last: T? = null
            return withTimeoutOrNull(ms) { onEach { last = it }.first(done) } ?: last
        }
    }
}
