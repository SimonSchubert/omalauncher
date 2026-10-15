package dev.omalauncher.widgets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.Display
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import dev.omalauncher.BuildConfig
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import dev.omalauncher.MainActivity
import dev.omalauncher.R
import dev.omalauncher.Runtime
import dev.omalauncher.linux.GuestShell
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.ShellPool
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Builtins
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WidgetError
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.androidAppKey
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.widgets.runtime.Alarm
import dev.omalauncher.widgets.runtime.Media
import dev.omalauncher.widgets.runtime.Modes
import dev.omalauncher.widgets.runtime.Notifications
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.Streams
import dev.omalauncher.widgets.runtime.Volume
import dev.omalauncher.widgets.runtime.watchFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_HTTP_BYTES = 1 shl 20
private const val NOTIFY_CHANNEL = "widgets"

/**
 * What widgets can reach: guest commands (in shared shells, never with
 * Android permissions), HTTP, a few Android facts and actions.
 */
class WidgetServices(
    private val context: Context,
    private val proot: Proot,
    private val runtime: Runtime,
    private val scope: CoroutineScope,
) : Services {
    /** Sources share a few shells, so one slow command doesn't stall the rest. */
    val shells = ShellPool(proot, 3)

    /** Actions get their own shell: a long run(...) never delays widget updates. */
    private val actionShell = GuestShell(proot)
    private val streams = Streams(proot, scope)

    /** What's playing on the phone. */
    private val media = Media(context)
    private val notifications = Notifications(context, media, ::profileKind)
    private val volume = Volume(context)
    private val alarm = Alarm(context)
    private val modes = Modes(context)

    /** Opens launcher screens ("style" or "themes", "agents", "drawer"); set by the UI. */
    var launcherAction: ((String) -> Unit)? = null

    /** The home screen's view, for haptic(): View haptics follow the user's touch feedback setting and need no permission; set by the UI. */
    var view: View? = null

    private val recent = HashMap<String, Pair<Long, Deferred<ShellResult>>>()

    /** Identical commands from several widgets within a second run once. */
    override suspend fun cmd(
        command: String,
        timeoutSeconds: Int,
    ): ShellResult {
        val now = SystemClock.elapsedRealtime()
        val job =
            synchronized(recent) {
                if (recent.size > 64) recent.entries.removeAll { now - it.value.first > 1000 }
                recent[command]?.takeIf { now - it.first < 1000 }?.second
                    ?: scope.async(Dispatchers.IO) { shells.run(command, timeoutSeconds) }.also { recent[command] = now to it }
            }
        return job.await()
    }

    override suspend fun stream(
        command: String,
        onLine: (String) -> Unit,
    ) = streams.collect(command, onLine)

    override fun android(name: String): Value =
        when (name) {
            "battery" -> battery()
            "device" -> device()
            "system" -> system()
            "media" -> media.value()
            "notifications" -> notifications.value()
            "volume" -> volume.value()
            "alarm" -> alarm.value()
            "modes" -> modes.value()
            "apps" -> apps()
            else -> throw WidgetError("unknown android source $name")
        }

    override fun androidUpdates(name: String): Flow<Value>? =
        when (name) {
            "media" -> media.updates()
            "notifications" -> notifications.updates()
            "volume" -> volume.updates()
            "alarm" -> alarm.updates()
            "modes" -> modes.updates()
            "battery" ->
                callbackFlow {
                    val receiver =
                        object : BroadcastReceiver() {
                            override fun onReceive(
                                c: Context,
                                intent: Intent,
                            ) {
                                trySend(battery())
                            }
                        }
                    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    // The broadcast is sticky: registering delivers the current state at once.
                    if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
                    awaitClose { context.unregisterReceiver(receiver) }
                }.distinctUntilChanged()
            else -> null
        }

    override fun androidImage(src: String) = media.art(src)

    /** "work" or "clone" for an app in a profile beside the launcher's own, as the drawer marks it; null for its own. */
    private fun profileKind(user: UserHandle): String? {
        if (user == Process.myUserHandle()) return null
        val serial = context.getSystemService(UserManager::class.java).getSerialNumberForUser(user)
        return if (runtime.androidApps.cloned(serial)) "clone" else "work"
    }

    /**
     * An app's icon as the drawer shows it (its silhouette with monochrome icons), badge included. By package, the launcher's
     * own profile first; `pkg@work` and `pkg@clone` ask for the copy in a work profile or a clone profile, and nothing else.
     */
    override fun appIcon(pkg: String): ImageBitmap? {
        val apps = runtime.androidApps
        val name = pkg.substringBefore('@')
        val app =
            when (pkg.substringAfter('@', "")) {
                "work" -> apps.apps.firstOrNull { it.component.packageName == name && it.work }
                "clone" -> apps.apps.firstOrNull { it.component.packageName == name && it.clone }
                "" -> apps.apps.firstOrNull { it.component.packageName == name && it.profile == null } ?: apps.apps.firstOrNull { it.component.packageName == name }
                else -> null
            } ?: return null
        val key = androidAppKey(app.component, app.profile)
        return if (LauncherStyle.monochromeIcons) apps.masks[key] else apps.icons[key]
    }

    override fun terminal(
        command: String?,
        fontSize: Double?,
    ) = runtime.terminals.create(command, fontSize)

    override fun fileUpdates(path: String) = watchFile(guestFile(path))

    override fun guestFile(path: String): File {
        val p = if (path.startsWith("~/")) Proot.GUEST_HOME + "/" + path.removePrefix("~/") else path
        return proot.hostFile(p)
    }

    /** The phone itself, which the guest can't identify: it has no DMI tables, and /system isn't mounted. */
    private fun device(): Value =
        Obj(
            mapOf(
                "model" to Str(Build.MODEL),
                "maker" to Str(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }),
                "android" to Str(Build.VERSION.RELEASE),
                "sdk" to Num(Build.VERSION.SDK_INT.toDouble()),
                "soc" to Str(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }.orEmpty() else ""),
            ),
        )

    /**
     * What a fetch shows about the phone, all from Android, so it works without Linux:
     * kernel, uptime, memory, storage, CPU, display, build, locale and the launcher itself.
     */
    private fun system(): Value {
        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val data = StatFs(Environment.getDataDirectory().path)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        // Max clock per core in kHz; some phones don't let apps read it.
        val maxFreq =
            (0 until java.lang.Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }.maxOrNull()
        fun num(v: Long) = Num(v.toDouble())
        return Obj(
            mapOf(
                "kernel" to Str(System.getProperty("os.version").orEmpty()),
                "uptime" to num(SystemClock.elapsedRealtime() / 1000),
                "memory" to Obj(mapOf("total" to num(memory.totalMem), "used" to num(memory.totalMem - memory.availMem), "available" to num(memory.availMem))),
                "storage" to Obj(mapOf("total" to num(data.totalBytes), "used" to num(data.totalBytes - data.availableBytes), "free" to num(data.availableBytes))),
                "cpu" to
                    Obj(
                        mapOf(
                            "soc" to Str(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeUnless { it == Build.UNKNOWN }.orEmpty() else ""),
                            "maker" to Str(if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.takeUnless { it == Build.UNKNOWN }.orEmpty() else ""),
                            "hardware" to Str(Build.HARDWARE),
                            "cores" to num(java.lang.Runtime.getRuntime().availableProcessors().toLong()),
                            "abi" to Str(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()),
                            "maxMhz" to (maxFreq?.let { num(it / 1000) } ?: Null),
                        ),
                    ),
                "display" to
                    Obj(
                        mapOf(
                            "width" to (mode?.let { num(it.physicalWidth.toLong()) } ?: Null),
                            "height" to (mode?.let { num(it.physicalHeight.toLong()) } ?: Null),
                            "hz" to (mode?.let { Num(Math.round(it.refreshRate).toDouble()) } ?: Null),
                            "dpi" to num(context.resources.displayMetrics.densityDpi.toLong()),
                        ),
                    ),
                "build" to Str(Build.DISPLAY),
                "codename" to Str(Build.DEVICE),
                "patch" to Str(Build.VERSION.SECURITY_PATCH),
                "locale" to Str(java.util.Locale.getDefault().toLanguageTag()),
                "timezone" to Str(java.util.TimeZone.getDefault().id),
                "launcher" to Str(BuildConfig.VERSION_NAME),
                "theme" to (runtime.themes.current?.title?.let(::Str) ?: Null),
                "apps" to num(runtime.androidApps.apps.size.toLong()),
            ),
        )
    }

    /**
     * Launchable Android apps for `source a = android.apps`, each `{key, name, package, component, profile, system, icon, score, last}`.
     * `key` is [dev.omalauncher.AndroidApp.key], unique across profiles, for `androidApp(a.key)`; `profile` is `work` or
     * `clone` as on notifications, else null; `icon` is the `app:` src for that copy. `score` and `last` are what the
     * search ranks by ([dev.omalauncher.AppUsage]), so a widget can rank apps the same way.
     */
    private fun apps(): Value =
        Lst(
            runtime.androidApps.apps.take(Builtins.MAX_LIST).map { app ->
                val kind = profileKind(app.user)
                val usage = "a:" + app.key
                Obj(
                    mapOf(
                        "key" to Str(app.key),
                        "name" to Str(app.label),
                        "package" to Str(app.component.packageName),
                        "component" to Str(app.component.flattenToShortString()),
                        "profile" to (kind?.let(::Str) ?: Null),
                        "system" to bool(app.system),
                        "icon" to Str("app:${app.component.packageName}${kind?.let { "@$it" } ?: ""}"),
                        "score" to Num(Math.round(runtime.usage.score(usage) * 10.0) / 10.0),
                        "last" to (runtime.usage.last(usage)?.let { Num((it / 1000).toDouble()) } ?: Null),
                    ),
                )
            },
        )

    /** Android hides the battery from the guest (/sys/class/power_supply), so expose it here. */
    private fun battery(): Value {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        // Some builds report no scale (0); dividing by it would throw inside the battery receiver.
        val scale = (intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).takeIf { it > 0 } ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        fun extra(name: String) = intent?.getIntExtra(name, -1)?.takeIf { it >= 0 }
        val health =
            when (extra(BatteryManager.EXTRA_HEALTH)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failing"
                else -> null
            }
        val plugged =
            when (extra(BatteryManager.EXTRA_PLUGGED)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
                else -> null
            }
        return Obj(
            mapOf(
                "level" to Num(if (level >= 0) (level * 100 / scale).toDouble() else 0.0),
                "charging" to bool(charging),
                "status" to Str(if (charging) "charging" else "on battery"),
                // Reported in tenths of a degree and in millivolts.
                "temp" to (extra(BatteryManager.EXTRA_TEMPERATURE)?.let { Num(it / 10.0) } ?: Null),
                "voltage" to (extra(BatteryManager.EXTRA_VOLTAGE)?.let { Num(it / 1000.0) } ?: Null),
                "health" to (health?.let(::Str) ?: Null),
                "plugged" to (plugged?.let(::Str) ?: Null),
                "technology" to (intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }?.let(::Str) ?: Null),
                "cycles" to ((if (Build.VERSION.SDK_INT >= 34) extra(BatteryManager.EXTRA_CYCLE_COUNT) else null)?.let { Num(it.toDouble()) } ?: Null),
            ),
        )
    }

    /** Runs a widget action: terminal, app, run, url, launcher, media, volume, notification, ... */
    override fun act(
        name: String,
        args: List<Value>,
        done: () -> Unit,
    ) {
        // A value still loading (…) would otherwise open, copy or run the text "null".
        if (args.firstOrNull() == Null) return
        val arg = args.firstOrNull()?.toString().orEmpty()
        if (name == "run") return run(arg) { done() }
        when (name) {
            // Named after the command (btop, dua, ...) unless the widget passes a name.
            "terminal" -> runtime.terminal("$arg; exec bash", args.getOrNull(1)?.toString() ?: arg.substringBefore(' '))
            "app" -> runtime.launch(arg)
            // Any scheme an app handles: tel:, geo:, mailto:, ... One nothing handles mustn't crash the launcher.
            "url" ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(arg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "no app opens $arg", Toast.LENGTH_SHORT).show()
                }
            "launcher" -> launcherAction?.invoke(arg)
            "media" -> media.control(arg)
            "volume" -> volume.control(args[0])
            "notification" -> args.getOrNull(1)?.takeIf { it != Null }?.let { notifications.control(arg, it.toString()) }
            "androidApp" -> openAndroidApp(arg)
            "copy" -> copy(arg)
            "notify" -> notify(arg, args.getOrNull(1)?.takeIf { it != Null }?.toString())
            "torch" -> modes.torch(args[0])
            "share" -> share(arg)
            "haptic" -> view?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        // Anything the action changed should show up without waiting for the next interval.
        scope.launch {
            delay(500)
            done()
        }
    }

    override fun run(
        command: String,
        done: (ShellResult?) -> Unit,
    ) {
        // Never retried: a shell dying mid-command must not run a side effect twice.
        scope.launch { done(runCatching { actionShell.run(command, 30, retry = false) }.getOrNull()) }
    }

    override suspend fun http(
        url: String,
        headers: Map<String, String>,
        timeoutSeconds: Int,
    ): ShellResult =
        withContext(Dispatchers.IO) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext ShellResult("", "not an http(s) URL: $url", -1)
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = timeoutSeconds * 1000
                    connection.readTimeout = timeoutSeconds * 1000
                    connection.setRequestProperty("User-Agent", "omalauncher-widgets")
                    headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                    val code = connection.responseCode
                    val stream = if (code >= 400) connection.errorStream else connection.inputStream
                    // Widgets show a few values; a runaway response mustn't fill memory.
                    val body = stream?.use { String(readAtMost(it, MAX_HTTP_BYTES), Charsets.UTF_8) }.orEmpty()
                    if (code in 200..299) ShellResult(body.trimEnd(), "", code) else ShellResult(body.trimEnd(), connection.responseMessage.orEmpty(), code)
                } finally {
                    connection.disconnect()
                }
            } catch (e: IOException) {
                ShellResult("", e.message ?: e.toString(), -1)
            }
        }

    /**
     * By an `android.apps` key, which opens that copy in its own profile, or by package, the launcher's own profile's copy.
     * Counts like an open from the drawer, for the search's ranking and the recent apps.
     */
    private fun openAndroidApp(arg: String) {
        val apps = runtime.androidApps
        val app = apps.apps.firstOrNull { it.key == arg } ?: apps.apps.firstOrNull { it.component.packageName == arg && it.profile == null }
        if (app != null) {
            runtime.usage.record("a:" + app.key)
            apps.open(app)
            return
        }
        context.packageManager.getLaunchIntentForPackage(arg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
    }

    private fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("widget", text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "copied", Toast.LENGTH_SHORT).show()
    }

    /** share(text): Android's share sheet, to send the text to any app. */
    private fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var notificationId = 1000

    /** notify(title, text): an Android notification that opens the home screen. Needs the notification permission. */
    private fun notify(
        title: String,
        text: String?,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) {
            Log.w("widgets", "notify: notifications are off for the launcher")
            return
        }
        nm.createNotificationChannel(NotificationChannel(NOTIFY_CHANNEL, "Widgets", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n =
            Notification
                .Builder(context, NOTIFY_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        nm.notify(notificationId++, n)
    }
}

private fun readAtMost(
    input: InputStream,
    limit: Int,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
