package dev.omalauncher.linux

import android.content.Context
import android.os.FileObserver
import android.util.Log
import dev.omalauncher.BuildConfig
import dev.omalauncher.PhoneFolders
import dev.omalauncher.PickActivity
import dev.omalauncher.R
import dev.omalauncher.ui.mimeOf
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.runtime.battery
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.FileProvider
import dev.omalauncher.postNotification
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
 * AirPods and battery for agents and scripts, as JSON, from the same sources widgets read; and notifications and
 * Android's share sheet for them. Only what the launcher was already
 * allowed: nothing here asks for a permission; a widget's tap does that. A request is a file in the runtime dir's
 * phone/ (<id>.req, the command and its arguments, each ended by a NUL, so a text can span lines), the answer <id>.done: `ok` and then the JSON, or
 * `usage: ...`, `denied: ...` or `error: ...`, which the script tells apart by exit code; like owl-send's inbox. <id>.ack, made
 * as soon as a request is seen, tells the script the launcher is listening, so it waits longer for a slow answer.
 */
class PhoneBridge(
    private val context: Context,
    private val proot: Proot,
    private val location: PhoneLocation,
    private val calendar: PhoneCalendar,
    private val airpods: () -> AirPods,
    private val folders: PhoneFolders,
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
                    if (path == null || !path.endsWith(".req") || '/' in path) return
                    val id = path.removeSuffix(".req")
                    // Here, not on main: the script tells a launcher that's busy from one that isn't listening by it.
                    runCatching { File(inbox, "$id.ack").createNewFile() }
                    scope.launch { receive(id) }
                }
            }.also { it.startWatching() }
    }

    private suspend fun receive(id: String) {
        val request = File(inbox, "$id.req")
        val args = withContext(Dispatchers.IO) { runCatching { request.readText() }.getOrNull().also { request.delete() } }?.removeSuffix("\u0000")?.split('\u0000') ?: return
        val reply =
            try {
                when (args[0]) {
                    "location" -> location()
                    "calendar" -> calendar(args.getOrNull(1))
                    "battery" -> battery()
                    "share" -> share(args.drop(1).filter { it.isNotEmpty() })
                    "version" -> Answer.Ok(Obj(mapOf("version" to Str(BuildConfig.VERSION_NAME), "versionCode" to Num(BuildConfig.VERSION_CODE.toDouble()), "commands" to Lst(COMMANDS.map(::Str)))))
                    "notify" -> notify(args.getOrNull(1).orEmpty(), args.getOrNull(2)?.takeIf { it.isNotEmpty() })
                    "airpods" -> airpods(args.getOrNull(1)?.takeIf { it.isNotEmpty() }, args.getOrNull(2))
                    "folders" -> withContext(Dispatchers.IO) { folders() }
                    "ls" -> withContext(Dispatchers.IO) { ls(args.getOrNull(1).orEmpty()) }
                    "get" -> withContext(Dispatchers.IO) { get(args.drop(1)) }
                    "put" -> withContext(Dispatchers.IO) { put(args.drop(1)) }
                    "pick" -> pick(args.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: DOWNLOADS)
                    else -> Answer.Usage("unknown command '${args[0]}'")
                }
            } catch (e: Exception) {
                Log.w(TAG, "phone ${args[0]} failed", e)
                Answer.Error("${args[0]} failed: ${e.message}")
            }
        val text = reply.text
        withContext(Dispatchers.IO) {
            // Its .ack gone, the script gave up waiting: nothing would read the answer.
            if (File(inbox, "$id.ack").exists()) runCatching { writeAtomically(File(inbox, "$id.done"), (text + "\n").toByteArray()) }
        }
    }

    /** The fix android.location has, or the first one within [LOCATION_MS]. */
    private suspend fun location(): Answer {
        location.recheck()
        val v = location.updates().settle(LOCATION_MS) { !it.flag("granted") || it.flag("available") }
        return when {
            v == null || !v.flag("granted") -> Answer.Denied(NOT_ALLOWED.format("location"))
            !v.flag("available") -> Answer.Error("no location yet; is location on, and the launcher in front?")
            else -> Answer.Ok(v.without("granted", "available"))
        }
    }

    private suspend fun calendar(days: String?): Answer {
        val n = if (days.isNullOrEmpty()) DAYS else days.toIntOrNull()?.takeIf { it in 1..MAX_DAYS } ?: return Answer.Usage("days: 1 to $MAX_DAYS")
        val events = calendar.read(n, MAX_EVENTS) ?: return Answer.Denied(NOT_ALLOWED.format("calendar"))
        return Answer.Ok(Lst(events))
    }

    /** A notification on the phone, which opens the home screen; posted, it prints nothing. */
    private fun notify(
        title: String,
        text: String?,
    ): Answer {
        if (title.isBlank()) return Answer.Usage("notify: a title")
        val posted = context.postNotification(NOTIFY_CHANNEL, context.getString(R.string.linux_notify_channel), title, text)
        return if (posted) Answer.Ok(null) else Answer.Denied(NO_NOTIFICATIONS)
    }

    /** android.battery's fields, and whether the phone is saving power. */
    private fun battery(): Answer {
        val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val v = battery(context) as Obj
        return Answer.Ok(Obj(v.fields + ("saver" to bool(saver))))
    }

    /**
     * Android's share sheet for [paths], absolute guest paths, as Files shares: the user picks the app. Only files Linux
     * sees, through the launcher's FileProvider, which can't reach the launcher's own.
     */
    private suspend fun share(paths: List<String>): Answer {
        if (paths.isEmpty()) return Answer.Usage("share: one or more files")
        val uris = ArrayList<Uri>()
        val mimes = HashSet<String>()
        for (path in paths) {
            // Not there for Android: one Linux makes up (/proc), or behind a link out of Linux.
            val file = withContext(Dispatchers.IO) { proot.guestFile(path)?.takeIf { it.isFile } }
            val uri = file?.let { runCatching { FileProvider.getUriForFile(context, context.packageName + ".files", it) }.getOrNull() } ?: return Answer.Error("share: $path can't be handed to Android")
            uris += uri
            mimes += mimeOf(file.name) ?: "*/*"
        }
        // One type for them all, or the family they share (image/*), or anything.
        val mime = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.toSet().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send =
            if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }.setType(mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // The apps in the sheet read the uris through the clip, which the chooser passes on.
                .apply { clipData = ClipData.newUri(context.contentResolver, "", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } } }
        return try {
            context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Answer.Ok(null)
        } catch (e: ActivityNotFoundException) {
            Answer.Error("share: no app to share with")
        }
    }

    /** The phone folders Linux may reach, by the names the other commands take. */
    private fun folders(): Answer = Answer.Ok(Lst(folders.folders().map { Obj(mapOf("name" to Str(it.name))) }))

    /** What's in a phone folder, or the one file [path] names. */
    private fun ls(path: String): Answer {
        if (path.isEmpty()) return folders()
        val doc = folders.find(path) ?: return noSuch(path)
        val docs = if (doc.isDir) folders.list(doc) else listOf(doc)
        return Answer.Ok(
            Lst(
                docs.map {
                    Obj(mapOf("name" to Str(it.name), "dir" to bool(it.isDir), "size" to Num(it.size.toDouble()), "modified" to Num((it.modified / 1000).toDouble())))
                },
            ),
        )
    }

    /**
     * Phone files or folders into Linux, as cp -r does: into [args]' last, a folder there, or as that name for one.
     * Answers the Linux paths written.
     */
    private fun get(args: List<String>): Answer {
        if (args.size < 2) return Answer.Usage("get: <folder>/<path>... <linux path>")
        val all = folders.folders()
        val docs = args.dropLast(1).map { folders.find(it, all) ?: return noSuch(it) }
        val dest = proot.guestFs.normalize(args.last())
        val destFile = proot.guestFile(dest) ?: return Answer.Error("get: $dest is out of Linux")
        val into = destFile.isDirectory
        if (docs.size > 1 && !into) return Answer.Usage("get: $dest isn't a folder")
        val written = ArrayList<Value>()
        for (doc in docs) {
            val (file, path) = if (into) File(destFile, doc.name) to proot.guestFs.child(dest, doc.name) else destFile to dest
            if (file.parentFile?.isDirectory != true) return Answer.Error("get: no folder ${dest.substringBeforeLast('/')}")
            folders.copyIn(doc, file)
            written += Str(path)
        }
        return Answer.Ok(Lst(written))
    }

    /** Linux files or folders into a phone folder, as cp -r does, the last of [args]. Answers the phone paths written. */
    private fun put(args: List<String>): Answer {
        if (args.size < 2) return Answer.Usage("put: <linux path>... <folder>[/path]")
        val sources = args.dropLast(1).map { p -> (proot.guestFile(p)?.takeIf { it.exists() } ?: return Answer.Error("put: $p can't be read from Linux")) }
        val dest = args.last().trim('/')
        val all = folders.folders()
        val there = folders.find(dest, all)
        val written = ArrayList<Value>()
        if (there?.isDir == true) {
            for (src in sources) written += Str(folders.copyOut(src, there).path)
        } else {
            // A new name for the one file or folder, in a folder that's there.
            val parent = dest.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { folders.find(it, all) }
            if (sources.size > 1 || parent == null || !parent.isDir) return noSuch(dest)
            written += Str(folders.copyOut(sources[0], parent, dest.substringAfterLast('/')).path)
        }
        return Answer.Ok(Lst(written))
    }

    /** Android's file picker, for the user to choose files from anywhere on the phone; copied into [dir], as they're named. */
    private suspend fun pick(dir: String): Answer {
        val into = withContext(Dispatchers.IO) { proot.guestFile(dir)?.takeIf { it.isDirectory || (dir == DOWNLOADS && it.mkdirs()) } } ?: return Answer.Usage("pick: $dir isn't a folder")
        val picked = PickActivity.picked()
        try {
            context.startActivity(Intent(context, PickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            return Answer.Error("pick: no file picker")
        }
        val uris = withTimeoutOrNull(PICK_MS) { picked.await() } ?: emptyList()
        if (uris.isEmpty()) return Answer.Error("nothing picked")
        val paths = withContext(Dispatchers.IO) { uris.map { Str(proot.guestFs.child(dir, folders.copyIn(it, into).name)) } }
        return Answer.Ok(Lst(paths))
    }

    private fun noSuch(path: String): Answer {
        val name = path.trim('/').substringBefore('/')
        return if (folders.folders().none { it.name == name }) {
            Answer.Denied("no phone folder '$name'; `omalauncher folders` lists them, and the launcher's settings (permissions) add one")
        } else {
            Answer.Error("no $path")
        }
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
        if (!pods.granted) return Answer.Denied(NOT_ALLOWED.format("Bluetooth"))
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

    /** What the script reads: its first line tells it the exit code. */
    private sealed class Answer(
        val text: String,
    ) {
        /** [value] as JSON, or nothing. */
        class Ok(
            value: Value?,
        ) : Answer("ok" + (value?.let { "\n" + (Json.write(it) ?: "null") } ?: ""))

        /** Nothing to say: exit code 1. */
        class Error(
            message: String,
        ) : Answer("error: $message")

        /** Wrong arguments: exit code 2. */
        class Usage(
            message: String,
        ) : Answer("usage: $message")

        /** Not allowed: exit code 3. */
        class Denied(
            message: String,
        ) : Answer("denied: $message")
    }

    private companion object {
        const val COMMAND = "omalauncher"
        const val OLD_COMMAND = "phone"
        const val SCRIPT = "$COMMAND.sh"
        /** What this launcher's `omalauncher` answers, for `omalauncher version`. */
        val COMMANDS = listOf("location", "calendar", "airpods", "battery", "notify", "share", "folders", "ls", "get", "put", "pick", "version")
        const val NOTIFY_CHANNEL = "linux"
        const val NO_NOTIFICATIONS = "the launcher may not post notifications; turn them on in its settings (permissions), or the \"agents and scripts\" ones in Android's"
        const val NOT_ALLOWED = "the launcher isn't allowed the %s; a widget that shows it asks, or Android's settings for the app"
        const val LOCATION_MS = 10_000L
        const val AIRPODS_MS = 12_000L
        const val CONTROL_MS = 2_000L
        /** Under the script's 2 minutes, so a picker left open says nothing was picked. */
        const val PICK_MS = 110_000L
        const val DOWNLOADS = "/root/Downloads"
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
