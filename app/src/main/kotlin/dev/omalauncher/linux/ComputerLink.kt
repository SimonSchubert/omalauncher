package dev.omalauncher.linux

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.BuildConfig
import dev.omalauncher.theme.ThemeDirs
import dev.omalauncher.theme.ThemeRepo
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.WidgetEngine
import dev.omalauncher.widgets.lang.bool
import dev.omalauncher.widgets.lang.Json
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.hex
import dev.omalauncher.widgets.lang.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

private const val TAG = "link"

/**
 * Computers linked to this phone: the Omalauncher plugin on an Omarchy desktop (`omarchy-widgets phone ...`) lists,
 * reads, adds and removes the phone's widgets and switches its theme. Only with Linux: sshd runs in the guest, as the
 * omalink user (as the fake root its privilege separation crashes proot), while at least one computer is linked, and
 * the phone is announced on the local network as `_omalauncher._tcp`.
 *
 * Linking: the computer shows a QR code, an [Invite] (its ssh key, its name and a secret). Scanned, the launcher asks
 * the user, then lets that key in, but only for omalauncher-link (assets/omalauncher-link.sh), which hands each
 * request to [handle] through `omalauncher link`. The computer finds the phone, takes its host key as offered, and
 * asks `hello`; the answer's proof, the host key signed with the secret, tells it that it reached this phone and not
 * someone in between. See docs/plans/computer-link.md.
 */
class ComputerLink(
    private val context: Context,
    private val proot: Proot,
    private val engine: WidgetEngine,
    private val themes: ThemeStore,
    /** Main. */
    private val scope: CoroutineScope,
) {
    /**
     * A linked computer. [secret] is its invite's, until it said hello. With [syncTheme], it may switch the phone's theme
     * and follows the phone's (see `watch`).
     */
    data class Computer(
        val id: String,
        val name: String,
        val key: String,
        val added: Long,
        val secret: String?,
        val syncTheme: Boolean = true,
        /** Off until ticked: the background, within the same theme, both ways; images the other side lacks are sent. */
        val syncBackground: Boolean = false,
        /** The widgets kept the same on both: their files and `persist` values, the newest save winning. */
        val widgets: List<String> = emptyList(),
    )

    private val dir = File(context.filesDir, "link")
    private val file = File(dir, "computers.json")
    private val guestDir = File(proot.rootfs, GUEST_DIR.removePrefix("/"))
    private val widgetsDir get() = engine.dir

    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    var computers by mutableStateOf(load())
        private set

    /** Something a connected device did here (or was refused), for the settings' activity list. */
    data class Activity(
        val at: Long,
        val device: String,
        val what: String,
    )

    private val activityFile = File(dir, "activity.json")

    /** The latest [MAX_ACTIVITY], newest first; kept across restarts. */
    var activity by mutableStateOf(loadActivity())
        private set

    /**
     * Paused by the user: sshd is down and no key is let in, nothing is followed and every request is refused, until
     * they resume. Kept across restarts. Only settings change it: nothing in Linux does.
     */
    var paused by mutableStateOf(prefs.getBoolean(PAUSED, false))
        private set

    /** Told when [paused] changed: DeviceSync stops or starts following. */
    var onPauseChanged: () -> Unit = {}

    /** Main thread. */
    fun pause(on: Boolean) {
        if (paused == on) return
        paused = on
        prefs.edit().putBoolean(PAUSED, on).apply()
        Log.i(TAG, if (on) "paused" else "resumed")
        update()
        onPauseChanged()
    }

    /** Any thread. [what] is English, as a log's lines are. */
    private fun note(
        device: String,
        what: String,
    ) {
        val entry = Activity(System.currentTimeMillis(), device, what)
        Log.i(TAG, "$device: $what")
        scope.launch {
            val list = noted(activity, entry)
            activity = list
            runCatching {
                dir.mkdirs()
                val array = JSONArray()
                for (a in list) array.put(JSONObject().put("at", a.at).put("device", a.device).put("what", a.what))
                Versioned.writeFile(activityFile, VERSION, JSONObject().put("activity", array))
            }.onFailure { Log.w(TAG, "activity not saved", it) }
        }
    }

    private fun loadActivity(): List<Activity> =
        runCatching {
            Versioned.readFile(activityFile, VERSION)?.optJSONArray("activity")?.mapObjects { Activity(it.getLong("at"), it.getString("device"), it.getString("what")) }
        }.getOrNull().orEmpty().take(MAX_ACTIVITY)

    /** Whether sshd is running, and so whether the phone can be reached. */
    var listening by mutableStateOf(false)
        private set

    /** This phone, to the computers that look for it: stays the same as long as the launcher's data does. */
    val phoneId: String by lazy {
        prefs.getString(PHONE_ID, null) ?: randomHex(8).also { prefs.edit().putString(PHONE_ID, it).apply() }
    }

    fun managed(): List<Managed> = listOf(Managed.asset(context, SCRIPT, File(proot.rootfs, "usr/local/bin/$COMMAND"), Policy.Owned, executable = true))

    /** Main thread. */
    fun link(invite: Invite) {
        val computer = Computer(invite.id, invite.name, invite.key, System.currentTimeMillis(), invite.secret)
        save(computers.filter { it.id != computer.id } + computer)
        note(computer.name, "accepted here")
        update()
        // Connecting one is the user wanting them on again.
        pause(false)
    }

    /** Main thread. */
    fun setSyncTheme(
        id: String,
        on: Boolean,
    ) = save(computers.map { if (it.id == id) it.copy(syncTheme = on) else it })

    /** Main thread. */
    fun setSyncBackground(
        id: String,
        on: Boolean,
    ) = save(computers.map { if (it.id == id) it.copy(syncBackground = on) else it })

    /** Main thread. */
    fun setSyncWidget(
        id: String,
        name: String,
        on: Boolean,
    ) = save(computers.map { c -> if (c.id != id) c else c.copy(widgets = if (on) (c.widgets - name) + name else c.widgets - name) })

    /** Main thread. Its key stops working at once; a session already open ends with its next request. */
    fun unlink(id: String) {
        computers.firstOrNull { it.id == id }?.let { note(it.name, "disconnected here") }
        save(computers.filter { it.id != id })
        update()
    }

    @Volatile
    private var linux = false

    private var themeWriter: kotlinx.coroutines.Job? = null

    /** Linux came up or went: sshd runs only while it's up. */
    fun linuxChanged(up: Boolean) {
        linux = up
        update()
        // The picked theme, for omalauncher-link's watch, which reads it every few seconds without asking the launcher;
        // and, for one from someone's repo, where it came from, so a computer without it can offer to install it.
        if (up && themeWriter == null) {
            themeWriter =
                scope.launch {
                    combine(
                        snapshotFlow { Triple(themes.picked, themes.themes.firstOrNull { it.name == themes.picked }, themes.background?.name) },
                        widgetChanges(),
                    ) { t, digest -> t to digest }.collect { (t, digest) ->
                        val (name, theme, background) = t
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val source = theme?.let(ThemeDirs::sourceOf)?.url?.trim()?.takeIf { SOURCE.matches(it) }.orEmpty()
                                guestDir.mkdirs()
                                writeAtomically(File(guestDir, "theme"), themeFile(name, source, background.orEmpty(), digest).toByteArray())
                            }
                        }
                    }
                }
        }
    }

    /**
     * A short digest of the synced widgets' files and persisted values (of every linked computer's), looked at every
     * 2 s while any are synced: watch passes it on, so a computer hears that one changed and asks which.
     */
    private fun widgetChanges(): Flow<String> =
        flow {
            while (true) {
                // And the widgets synced with the devices this phone follows (DeviceSync), whose engine reads this too.
                val following = withContext(Dispatchers.IO) { File(proot.rootfs, "root/.local/state/omalauncher/sync").listFiles { f -> f.name.startsWith("synced-") }.orEmpty().flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) } }
                val names = (computers.flatMap { it.widgets } + following.filter(::validName)).toSortedSet()
                val digest =
                    if (names.isEmpty()) {
                        ""
                    } else {
                        withContext(Dispatchers.IO) {
                            sha256Hex(
                                names.joinToString("\n") { n ->
                                    val f = File(widgetsDir, n)
                                    val s = engine.persistedFile(n)
                                    "$n ${f.lastModified()} ${f.length()} ${s.lastModified()} ${s.length()}"
                                }.toByteArray(),
                            ).take(12)
                        }
                    }
                emit(digest)
                delay(2000)
            }
        }.distinctUntilChanged()

    // ---- omalauncher devices: the same, for agents and scripts in Linux ------------------------------------------

    /**
     * `omalauncher devices …`: every connected device, both ways (those that follow this phone, kept here, and those it
     * follows, [following]), what syncs with each, changing that, disconnecting, and opening a code on screen for the
     * user. Accepting a device stays a tap on the device that scans the code: no command here lets a key in. (That
     * keeps an agent from doing it by accident, no more: sshd's files are in Linux, where its root can write them.)
     */
    internal suspend fun devices(
        args: List<String>,
        following: DeviceSync,
    ): PhoneBridge.Answer {
        fun find(word: String): Pair<Computer?, DeviceSync.Device?>? {
            val w = word.lowercase()
            computers.firstOrNull { it.id == word || it.id.startsWith(w) && w.length >= 4 || it.name.lowercase() == w }?.let { return it to null }
            following.devices.firstOrNull { it.id == word || it.id.startsWith(w) && w.length >= 4 || it.name.lowercase() == w }?.let { return null to it }
            return null
        }
        when (args.getOrNull(0)) {
            null, "" -> {
                for (d in following.devices) following.loadSync(d.id)
                val here =
                    computers.map {
                        Obj(
                            mapOf(
                                "id" to Str(it.id), "name" to Str(it.name), "follows" to Str("this phone"),
                                "theme" to bool(it.syncTheme), "background" to bool(it.syncBackground), "widgets" to Lst(it.widgets.map(::Str)),
                            ),
                        )
                    }
                val there =
                    following.devices.map { d ->
                        val s = d.sync
                        Obj(
                            mapOf(
                                "id" to Str(d.id), "name" to Str(d.name), "follows" to Str("it"),
                                "theme" to (s?.let { bool(it.optBoolean("theme")) } ?: Null),
                                "background" to (s?.let { bool(it.optBoolean("background")) } ?: Null),
                                "widgets" to (s?.let { Lst(DeviceSync.widgetsOf(it).map(::Str)) } ?: Null),
                            ),
                        )
                    }
                return PhoneBridge.Answer.Ok(Lst(here + there))
            }
            "sync" -> {
                val (computer, device) = find(args.getOrNull(1).orEmpty()) ?: return PhoneBridge.Answer.Error("no connected device '${args.getOrNull(1).orEmpty()}'; `omalauncher devices` lists them")
                val widget = args.getOrNull(2) == "widget"
                val name = if (widget) args.getOrNull(3).orEmpty().let { if (it.endsWith(".owl")) it else "$it.owl" } else ""
                val on =
                    when (args.getOrNull(if (widget) 4 else 3)) {
                        "on" -> true
                        "off" -> false
                        else -> return PhoneBridge.Answer.Usage("devices sync <device> theme|background on|off, or widget <name> on|off")
                    }
                if (widget && !validName(name)) return badName(name)
                val what = args.getOrNull(2)
                if (!widget && what != "theme" && what != "background") return PhoneBridge.Answer.Usage("devices sync <device> theme|background on|off, or widget <name> on|off")
                if (computer != null) {
                    when {
                        widget -> setSyncWidget(computer.id, name, on)
                        what == "theme" -> setSyncTheme(computer.id, on)
                        else -> setSyncBackground(computer.id, on)
                    }
                } else if (device != null) {
                    if (widget) following.loadSync(device.id, "widget", name, if (on) "on" else "off") else following.loadSync(device.id, what!!, if (on) "on" else "off")
                }
                return devices(emptyList(), following)
            }
            "disconnect" -> {
                val (computer, device) = find(args.getOrNull(1).orEmpty()) ?: return PhoneBridge.Answer.Error("no connected device '${args.getOrNull(1).orEmpty()}'; `omalauncher devices` lists them")
                computer?.let { unlink(it.id) }
                device?.let { following.disconnect(it.id) }
                return PhoneBridge.Answer.Ok(null)
            }
            "connect" -> {
                if (paused) return pausedAnswer()
                // The code is for the user to scan with the other device, which asks them; this only puts it on screen.
                following.showCode = true
                context.startActivity(
                    android.content.Intent(context, dev.omalauncher.SettingsActivity::class.java)
                        .putExtra("page", "Computers")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return PhoneBridge.Answer.Ok(null)
            }
            else -> return PhoneBridge.Answer.Usage("devices [sync <device> …|disconnect <device>|connect]")
        }
    }

    // ---- requests -------------------------------------------------------------------------------------------------

    /** `omalauncher link <verb> <computer id> [args]`, from omalauncher-link; on the main thread. */
    internal suspend fun handle(
        args: List<String>,
        following: DeviceSync,
    ): PhoneBridge.Answer {
        if (paused) return pausedAnswer()
        val answer = request(args, following)
        // watch asks again every few minutes while sync is off: not worth a line each time.
        if (answer is PhoneBridge.Answer.Denied && args.getOrNull(0) != "watch") {
            computers.firstOrNull { it.id == args.getOrNull(1) }?.let { note(it.name, "refused: ${args.getOrNull(0).orEmpty()}") }
        }
        return answer
    }

    private fun pausedAnswer() = PhoneBridge.Answer.Denied("connected devices are paused on this device; its user resumes them in the settings, connected devices")

    private suspend fun request(
        args: List<String>,
        following: DeviceSync,
    ): PhoneBridge.Answer {
        val verb = args.getOrNull(0).orEmpty()
        val id = args.getOrNull(1)
        // This phone's own omalauncher-sync (DeviceSync), following another device: it changes what it took from there,
        // and says from which (self:<id>), for the activity list.
        val from = followedId(id)
        if (from != null && verb !in SELF_VERBS) return PhoneBridge.Answer.Usage("link: '$verb' isn't for self")
        val computer =
            if (from != null) {
                val name = following.devices.firstOrNull { it.id == from }?.name ?: "a device this one follows"
                Computer(SELF, name, "", 0, null, syncTheme = true, syncBackground = true, widgets = withContext(Dispatchers.IO) { widgetsDir.list().orEmpty().filter(::validName) })
            } else {
                computers.firstOrNull { it.id == id } ?: return PhoneBridge.Answer.Denied("this device isn't connected to it (anymore); connect it again")
            }
        val rest = args.drop(2)
        return when (verb) {
            "hello" -> hello(computer)
            "widgets" -> withContext(Dispatchers.IO) { widgets() }
            "get" -> withContext(Dispatchers.IO) { get(computer.name, rest.getOrNull(0).orEmpty()) }
            "put" -> withContext(Dispatchers.IO) { put(computer.name, rest.getOrNull(0).orEmpty(), rest.getOrNull(1).orEmpty()) }
            "remove" -> withContext(Dispatchers.IO) { remove(computer.name, rest.getOrNull(0).orEmpty()) }
            "themes" -> PhoneBridge.Answer.Ok(Lst(themes.choices.map { Obj(mapOf("name" to Str(it.name), "current" to bool(it.name == themes.picked))) }))
            "theme" -> if (!computer.syncTheme) themeSyncOff() else theme(computer.name, rest.getOrNull(0).orEmpty(), rest.getOrNull(1))
            // omalauncher-link asks before it watches: what this computer may follow.
            "watch" ->
                if (!computer.syncTheme && !computer.syncBackground) {
                    themeSyncOff()
                } else {
                    PhoneBridge.Answer.Ok(Obj(mapOf("theme" to bool(computer.syncTheme), "background" to bool(computer.syncBackground))))
                }
            // The computer's own sync settings, shown in its plugin as on the phone: read, or one set.
            "sync" -> sync(computer, rest)
            "synced" -> withContext(Dispatchers.IO) { synced(computer) }
            "state" ->
                when (rest.getOrNull(0)) {
                    "get" -> withContext(Dispatchers.IO) { stateGet(computer, rest.getOrNull(1).orEmpty()) }
                    "put" -> statePut(computer, rest.getOrNull(1).orEmpty(), rest.getOrNull(2).orEmpty())
                    else -> PhoneBridge.Answer.Usage("state: get <name> | put <name>")
                }
            "background" -> if (!computer.syncBackground) backgroundSyncOff() else background(computer.name, rest.getOrNull(0).orEmpty(), rest.getOrNull(1).orEmpty())
            "bgput" -> withContext(Dispatchers.IO) { bgput(computer, rest.getOrNull(0).orEmpty(), rest.getOrNull(1).orEmpty(), rest.getOrNull(2).orEmpty()) }
            "bgget" -> if (!computer.syncBackground) backgroundSyncOff() else withContext(Dispatchers.IO) { bgget(computer.name, rest.getOrNull(0).orEmpty()) }
            else -> PhoneBridge.Answer.Usage("link: unknown request '$verb'")
        }
    }

    private suspend fun hello(computer: Computer): PhoneBridge.Answer {
        val hostKey = withContext(Dispatchers.IO) { hostKey() } ?: return PhoneBridge.Answer.Error("no host key yet")
        // Only while the code could still be scanned: the device that showed it has given up on it by then.
        val proof = computer.secret?.takeIf { inviteOpen(computer.added, System.currentTimeMillis()) }?.let { proof(it, hostKey) }
        // Once: the computer has checked the phone and keeps its host key from now on. Too late, the secret goes too.
        if (computer.secret != null) save(computers.map { if (it.id == computer.id) it.copy(secret = null) else it })
        if (proof != null) note(computer.name, "connected")
        return PhoneBridge.Answer.Ok(
            Obj(
                mapOf(
                    "phone" to Str(phoneName()),
                    "id" to Str(phoneId),
                    "version" to Str(BuildConfig.VERSION_NAME),
                    "proof" to (proof?.let(::Str) ?: Null),
                ),
            ),
        )
    }

    private fun widgets(): PhoneBridge.Answer {
        val files = widgetsDir.listFiles { f -> f.isFile && validName(f.name) }.orEmpty().sortedBy { it.name }
        return PhoneBridge.Answer.Ok(
            Lst(
                files.map {
                    Obj(
                        mapOf(
                            "name" to Str(it.name),
                            "sha256" to Str(sha256Hex(it.readBytes())),
                            "size" to Num(it.length().toDouble()),
                            "modified" to Num((it.lastModified() / 1000).toDouble()),
                        ),
                    )
                },
            ),
        )
    }

    private fun get(
        who: String,
        name: String,
    ): PhoneBridge.Answer {
        if (!validName(name)) return badName(name)
        val f = File(widgetsDir, name)
        if (!f.isFile) return PhoneBridge.Answer.Error("no widget $name there")
        note(who, "read $name")
        return PhoneBridge.Answer.Ok(Obj(mapOf("name" to Str(name), "text" to Str(f.readText()))))
    }

    /** [path]: the guest file omalauncher-link wrote stdin to; read and deleted here. */
    private fun put(
        who: String,
        name: String,
        path: String,
    ): PhoneBridge.Answer {
        val sent = proot.guestFile(path)?.takeIf { it.isFile && path.startsWith("/tmp/") } ?: return PhoneBridge.Answer.Usage("put: no file $path")
        try {
            if (!validName(name)) return badName(name)
            if (sent.length() > MAX_BYTES) return PhoneBridge.Answer.Usage("put: a widget is at most ${MAX_BYTES / 1024} KB")
            val bytes = sent.readBytes()
            if (!isText(bytes)) return PhoneBridge.Answer.Usage("put: $name isn't UTF-8 text")
            val target = File(widgetsDir, name)
            val created = !target.exists()
            widgetsDir.mkdirs()
            // Whole, so the widget engine never compiles half a file.
            writeAtomically(target, bytes)
            note(who, (if (created) "added " else "changed ") + name)
            return PhoneBridge.Answer.Ok(Obj(mapOf("name" to Str(name), "sha256" to Str(sha256Hex(bytes)), "created" to bool(created))))
        } finally {
            sent.delete()
        }
    }

    private fun remove(
        who: String,
        name: String,
    ): PhoneBridge.Answer {
        if (!validName(name)) return badName(name)
        val f = File(widgetsDir, name)
        if (!f.isFile) return PhoneBridge.Answer.Error("no widget $name there")
        f.delete()
        note(who, "removed $name")
        return PhoneBridge.Answer.Ok(null)
    }

    /** As picking it from home's menu does: recolored at once, its full version applied once it's on the phone. */
    /**
     * Switches to theme [name]. One the phone hasn't got, from the GitHub repo [source] (a theme someone made, which
     * the device that sends it has), is installed first: the launcher's install keeps a theme's files to what's safe
     * (see ThemeDirs.isFromRepo), so it asks nobody.
     */
    private suspend fun theme(
        who: String,
        name: String,
        source: String?,
    ): PhoneBridge.Answer {
        // Already it: the computer that follows the phone sends back what it just took from it.
        if (name == themes.picked) return PhoneBridge.Answer.Ok(null)
        val repo = source?.trim()?.takeIf { SOURCE.matches(it) }?.let(ThemeRepo::parse)?.takeIf { it.themeName == name }
        if (themes.choices.none { it.name == name } && repo != null) {
            note(who, "installed theme $name from ${repo.url}")
            themes.install(repo.url)
            return if (themes.picked == name) PhoneBridge.Answer.Ok(null) else PhoneBridge.Answer.Error("couldn't install theme '$name' from ${repo.url}")
        }
        val theme = themes.choices.firstOrNull { it.name == name } ?: return PhoneBridge.Answer.Error("no theme '$name' there; `themes` lists them")
        themes.pick(theme)
        note(who, "switched the theme to $name")
        scope.launch { if (themes.complete) themes.applyPicked() else themes.fetchPicked() }
        return PhoneBridge.Answer.Ok(null)
    }

    /**
     * The computer's background [name] for [theme], when the phone is on that theme: the theme's own or the user's
     * extra by that name. Missing, the answer says so (exit 1), and the computer sends it with bgput.
     */
    private suspend fun background(
        who: String,
        theme: String,
        name: String,
    ): PhoneBridge.Answer {
        if (!validImage(name)) return PhoneBridge.Answer.Usage("background: '$name' isn't an image's file name")
        val current = themes.current
        // Picked on the other side for another theme, or this one is still switching: not ours to show.
        if (current == null || current.name != theme || themes.picked != theme) return PhoneBridge.Answer.Ok(null)
        if (themes.background?.name == name) return PhoneBridge.Answer.Ok(null)
        val file = withContext(Dispatchers.IO) { current.backgroundNamed(name) } ?: return PhoneBridge.Answer.Error("missing: no background $name for $theme")
        themes.setBackground(file)
        note(who, "set the background to $name")
        return PhoneBridge.Answer.Ok(null)
    }

    /** An image sent for [theme]'s backgrounds: into the user's ~/.config/omarchy/backgrounds/<theme>, then shown. [path] as put's. */
    private suspend fun bgput(
        computer: Computer,
        theme: String,
        name: String,
        path: String,
    ): PhoneBridge.Answer {
        val sent = proot.guestFile(path)?.takeIf { it.isFile && path.startsWith("/tmp/") } ?: return PhoneBridge.Answer.Usage("bgput: no file $path")
        try {
            if (!computer.syncBackground) return backgroundSyncOff()
            if (!validImage(name)) return PhoneBridge.Answer.Usage("bgput: '$name' isn't an image's file name")
            if (!THEME_NAME.matches(theme)) return PhoneBridge.Answer.Usage("bgput: '$theme' isn't a theme's name")
            if (sent.length() > MAX_IMAGE_BYTES) return PhoneBridge.Answer.Usage("bgput: an image is at most ${MAX_IMAGE_BYTES shr 20} MB")
            // Only what Android can show: the bytes, not the name, say what it is.
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(sent.path, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return PhoneBridge.Answer.Usage("bgput: $name isn't an image it can show")
            val dir = File(dev.omalauncher.storage.LauncherData.userBackgroundsDir(context), theme)
            dir.mkdirs()
            writeAtomically(File(dir, name), sent.readBytes())
            note(computer.name, "sent the background $name")
        } finally {
            sent.delete()
        }
        return withContext(Dispatchers.Main) { background(computer.name, theme, name) }
    }

    /** One of the current theme's backgrounds, copied to /tmp for omalauncher-link to send and delete: {path}. */
    private fun bgget(
        who: String,
        name: String,
    ): PhoneBridge.Answer {
        if (!validImage(name)) return PhoneBridge.Answer.Usage("bgget: '$name' isn't an image's file name")
        val file = themes.current?.backgrounds()?.firstOrNull { it.name == name } ?: return PhoneBridge.Answer.Error("no background $name")
        if (file.length() > MAX_IMAGE_BYTES) return PhoneBridge.Answer.Error("$name is over ${MAX_IMAGE_BYTES shr 20} MB")
        val out = File(proot.tmpDir, "omalauncher-bg-${randomHex(6)}")
        file.copyTo(out)
        note(who, "read the background $name")
        return PhoneBridge.Answer.Ok(Obj(mapOf("path" to Str("/tmp/" + out.name))))
    }

    private fun sync(
        computer: Computer,
        args: List<String>,
    ): PhoneBridge.Answer {
        val usage = PhoneBridge.Answer.Usage("sync: theme|background on|off, or widget <name> on|off")
        if (args.isNotEmpty()) {
            val widget = args[0] == "widget"
            val on =
                when (args.getOrNull(if (widget) 2 else 1)) {
                    "on" -> true
                    "off" -> false
                    else -> return usage
                }
            when {
                widget && validName(args.getOrNull(1).orEmpty()) -> setSyncWidget(computer.id, args[1], on)
                args[0] == "theme" -> setSyncTheme(computer.id, on)
                args[0] == "background" -> setSyncBackground(computer.id, on)
                else -> return usage
            }
            note(computer.name, "set sync: ${args.joinToString(" ")}")
        }
        val now = computers.firstOrNull { it.id == computer.id } ?: computer
        return PhoneBridge.Answer.Ok(
            Obj(mapOf("theme" to bool(now.syncTheme), "background" to bool(now.syncBackground), "widgets" to Lst(now.widgets.map(::Str)))),
        )
    }

    /** The widgets synced with [computer]: {name, sha256 ("" without a file), modified, savedAt (0 without values)}. */
    private fun synced(computer: Computer): PhoneBridge.Answer =
        PhoneBridge.Answer.Ok(
            Lst(
                computer.widgets.map { n ->
                    val f = File(widgetsDir, n)
                    Obj(
                        mapOf(
                            "name" to Str(n),
                            "sha256" to Str(if (f.isFile) sha256Hex(f.readBytes()) else ""),
                            "modified" to Num((f.lastModified() / 1000).toDouble()),
                            "savedAt" to Num(savedAt(n)),
                        ),
                    )
                },
            ),
        )

    private fun savedAt(name: String): Double =
        runCatching { ((Json.parse(engine.persistedFile(name).readText()) as Obj).fields["savedAt"] as Num).v }.getOrDefault(0.0)

    /** A synced widget's persisted values: {savedAt, values}, as the phone keeps them. */
    private fun stateGet(
        computer: Computer,
        name: String,
    ): PhoneBridge.Answer {
        if (name !in computer.widgets) return notSynced(name)
        val saved = runCatching { Json.parse(engine.persistedFile(name).readText()) as Obj }.getOrNull()
        return PhoneBridge.Answer.Ok(Obj(mapOf("savedAt" to (saved?.fields?.get("savedAt") ?: Num(0.0)), "values" to (saved?.fields?.get("values") ?: Obj(emptyMap())))))
    }

    /**
     * A synced widget's persisted values from the computer ({savedAt, values}, sent on stdin as put's file is): kept if
     * newer than the phone's and not the same, and taken in by the running widget at once.
     */
    private suspend fun statePut(
        computer: Computer,
        name: String,
        path: String,
    ): PhoneBridge.Answer {
        val sent = proot.guestFile(path)?.takeIf { it.isFile && path.startsWith("/tmp/") } ?: return PhoneBridge.Answer.Usage("state put: no file $path")
        val text =
            try {
                if (name !in computer.widgets) return notSynced(name)
                if (sent.length() > MAX_BYTES) return PhoneBridge.Answer.Usage("state put: at most ${MAX_BYTES / 1024} KB")
                withContext(Dispatchers.IO) { sent.readText() }
            } finally {
                withContext(Dispatchers.IO) { sent.delete() }
            }
        val put = runCatching { Json.parse(text) as Obj }.getOrNull() ?: return PhoneBridge.Answer.Usage("state put: {savedAt, values} as JSON")
        val values = put.fields["values"] as? Obj ?: return PhoneBridge.Answer.Usage("state put: no values")
        // Never later than now: a time ahead of this phone's clock would win over every save made here until then.
        val at = ((put.fields["savedAt"] as? Num)?.v ?: return PhoneBridge.Answer.Usage("state put: no savedAt")).coerceAtMost(System.currentTimeMillis() / 1000.0)
        val file = engine.persistedFile(name)
        val kept = withContext(Dispatchers.IO) { runCatching { Json.parse(file.readText()) as Obj }.getOrNull() }
        val keptAt = (kept?.fields?.get("savedAt") as? Num)?.v ?: 0.0
        // The same values are no change (each side writing back what it took would go on forever); older ones lose.
        if (kept?.fields?.get("values") == values || at <= keptAt) return PhoneBridge.Answer.Ok(Obj(mapOf("taken" to bool(false))))
        val out = "{\"v\":1,\"savedAt\":${at.toLong()},\"values\":" + (Json.write(values) ?: return PhoneBridge.Answer.Usage("state put: values")) + "}"
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            writeAtomically(file, out.toByteArray())
        }
        engine.reloadPersisted(name)
        note(computer.name, "changed the values of $name")
        return PhoneBridge.Answer.Ok(Obj(mapOf("taken" to bool(true))))
    }

    private fun notSynced(name: String) = PhoneBridge.Answer.Denied("$name isn't synced with this device")

    private fun backgroundSyncOff() = PhoneBridge.Answer.Denied("background sync is off for this device; turn it on in the settings there, connected devices")

    private fun themeSyncOff() = PhoneBridge.Answer.Denied("theme sync is off for this device; turn it on in the settings there, connected devices")

    private fun badName(name: String) = PhoneBridge.Answer.Usage("'$name' isn't a widget's file name (name.owl: letters, digits, . _ -)")

    // ---- sshd -----------------------------------------------------------------------------------------------------

    private var process: Process? = null
    private var supervisor: Thread? = null

    @Volatile
    private var wanted = false

    /** Main thread. */
    private fun update() {
        wanted = linux && !paused && computers.isNotEmpty()
        // Paused, no key is let in either, whatever still runs.
        val keys = authorizedKeys(if (paused) emptyList() else computers)
        if (linux) {
            // Read on every login, so a link or unlink counts at once, with no restart.
            runCatching {
                guestDir.mkdirs()
                writeAtomically(File(guestDir, "authorized_keys"), keys.toByteArray())
            }.onFailure { Log.w(TAG, "authorized_keys", it) }
        }
        if (wanted && supervisor == null) {
            supervisor = thread(name = "link-sshd", isDaemon = true) { supervise() }
        } else if (!wanted) {
            // The whole tree: a proot killed alone leaves sshd running untraced, still letting computers in.
            process?.let { p -> thread(name = "link-stop", isDaemon = true) { killTree(p) } }
        }
    }

    /** Keeps sshd running while [wanted], as AudioBridge does PulseAudio. */
    private fun supervise() {
        var backoff = 1000L
        while (true) {
            if (!wanted) {
                Thread.sleep(500)
                continue
            }
            val started = System.currentTimeMillis()
            val code =
                runCatching {
                    guestDir.mkdirs()
                    writeAtomically(File(guestDir, "sshd_config"), SSHD_CONFIG.toByteArray())
                    val p = proot.process(START).start()
                    process = p
                    // Only once it says it listens: a setup that failed (no sshd) announces nothing.
                    p.inputStream.bufferedReader().forEachLine { line ->
                        Log.i(TAG, "[sshd] $line")
                        if (line.startsWith("Server listening") && !listening) announce(true)
                    }
                    p.waitFor()
                }.getOrElse {
                    // Stopped on purpose, its output closes under the read.
                    if (wanted) Log.w(TAG, "sshd failed", it)
                    -1
                }
            process = null
            announce(false)
            if (!wanted) continue
            if (System.currentTimeMillis() - started > MAX_BACKOFF_MS) backoff = 1000L
            Log.w(TAG, "sshd exited $code; restarting in ${backoff / 1000} s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private var registration: NsdManager.RegistrationListener? = null

    /** On the local network as `_omalauncher._tcp`, with the phone's id, so a linked computer finds it on any address. */
    private fun announce(on: Boolean) {
        scope.launch {
            listening = on
            val nsd = context.getSystemService(NsdManager::class.java) ?: return@launch
            registration?.let { runCatching { nsd.unregisterService(it) } }
            registration = null
            if (!on) return@launch
            val info =
                NsdServiceInfo().apply {
                    serviceName = "Omalauncher ${phoneName()}"
                    serviceType = SERVICE_TYPE
                    port = PORT
                    setAttribute("id", phoneId)
                    setAttribute("v", "1")
                }
            val listener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {
                        Log.i(TAG, "announced as ${info.serviceName}")
                    }

                    override fun onRegistrationFailed(
                        info: NsdServiceInfo,
                        error: Int,
                    ) {
                        Log.w(TAG, "not announced: $error")
                    }

                    override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                    override fun onUnregistrationFailed(
                        info: NsdServiceInfo,
                        error: Int,
                    ) = Unit
                }
            registration = listener
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { Log.w(TAG, "not announced", it) }
        }
    }

    /** sshd's public host key, as `type base64`: what the computer's ssh sees and the proof covers. */
    private fun hostKey(): String? =
        File(guestDir, "host_key.pub").takeIf { it.isFile }?.readText()?.trim()?.split(' ')?.take(2)?.joinToString(" ")

    // ---- saved ----------------------------------------------------------------------------------------------------

    private fun load(): List<Computer> {
        val data = runCatching { Versioned.readFile(file, VERSION) }.getOrNull() ?: return emptyList()
        return data.optJSONArray("computers")?.mapObjects {
            Computer(
                it.getString("id"),
                it.getString("name"),
                it.getString("key"),
                it.optLong("added"),
                it.optString("secret").takeIf { s -> s.isNotEmpty() },
                it.optBoolean("syncTheme", true),
                it.optBoolean("syncBackground", false),
                it.optJSONArray("widgets")?.let { a -> (0 until a.length()).map(a::getString).filter(::validName) }.orEmpty(),
            )
        }.orEmpty()
    }

    private fun save(list: List<Computer>) {
        computers = list
        val array = JSONArray()
        for (c in list) {
            array.put(JSONObject().put("id", c.id).put("name", c.name).put("key", c.key).put("added", c.added).put("syncTheme", c.syncTheme).put("syncBackground", c.syncBackground).put("widgets", JSONArray(c.widgets)).apply { c.secret?.let { put("secret", it) } })
        }
        runCatching {
            dir.mkdirs()
            Versioned.writeFile(file, VERSION, JSONObject().put("computers", array))
        }.onFailure { Log.e(TAG, "computers not saved", it) }
    }

    /** A computer's QR code: https://…/w#link1.<base64url of {"n": name, "k": ssh key, "s": secret}>. */
    data class Invite(
        val name: String,
        val key: String,
        val secret: String,
    ) {
        val id get() = keyId(key)

        /** What the computer shows beside its QR code, for the user to compare. */
        val code get() = code(key)

        companion object {
            const val PREFIX = "link1."

            /** The invite in [link] (the whole URL, or what follows its #), or null if it's none or can't be read. */
            fun parse(link: String): Invite? {
                val data = link.substringAfter('#').takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
                return runCatching {
                    val json = JSONObject(String(java.util.Base64.getUrlDecoder().decode(data.trimEnd('=')), Charsets.UTF_8))
                    val key = json.getString("k").trim()
                    val secret = json.getString("s")
                    val name = json.optString("n").filter { !it.isISOControl() }.trim().take(MAX_NAME)
                    if (!KEY.matches(key) || secret.length < MIN_SECRET) null else Invite(name.ifEmpty { "device" }, key, secret)
                }.getOrNull()
            }

            /** Whether [link] is one, readable or not: it isn't a widget either way. */
            fun isInvite(link: String) = link.substringAfter('#').startsWith(PREFIX)
        }
    }

    companion object {
        const val PORT = 8122

        /** The id this phone's own omalauncher-sync uses, and what it may ask: see [handle]. */
        const val SELF = "self"
        private val SELF_VERBS = setOf("themes", "theme", "background", "state")
        const val SERVICE_TYPE = "_omalauncher._tcp"
        const val COMMAND = "omalauncher-link"
        private const val SCRIPT = "$COMMAND.sh"
        private const val GUEST_DIR = "/var/lib/omalauncher-link"
        private const val USER = "omalink"
        private const val VERSION = 1
        private const val PHONE_ID = "phone_id"
        private const val PAUSED = "paused"
        const val MAX_ACTIVITY = 50

        /**
         * [list] with [entry] on top. The same thing from the same device again takes the place of the last time: a
         * game that saves every few seconds would otherwise fill the list with one line.
         */
        fun noted(
            list: List<Activity>,
            entry: Activity,
        ): List<Activity> {
            val last = list.firstOrNull()
            val rest = if (last != null && last.device == entry.device && last.what == entry.what) list.drop(1) else list
            return (listOf(entry) + rest).take(MAX_ACTIVITY)
        }

        /** The followed device a `self` or `self:<id>` request is for ("" when it doesn't say); null for a connected device's id. */
        fun followedId(id: String?): String? =
            when {
                id == SELF -> ""
                id != null && id.startsWith("$SELF:") -> id.removePrefix("$SELF:")
                else -> null
            }
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_NAME = 60
        private const val MIN_SECRET = 16

        /** As omalauncher-sync's INVITE_SECONDS: how long a code's secret is good for. */
        const val INVITE_SECONDS = 120

        /** Whether a code accepted at [added] still gets its proof at [now] (both ms): it was made before it was accepted. */
        fun inviteOpen(
            added: Long,
            now: Long,
        ) = now - added in 0..INVITE_SECONDS * 1000L

        /** As omalauncher-link.sh's max. */
        const val MAX_BYTES = 1_048_576L

        /** Only ed25519 keys: all the plugin makes. */
        private val KEY = Regex("^ssh-ed25519 [A-Za-z0-9+/]+=*$")
        /** What a theme's name may hold in the file watch reads, which goes into JSON as it is. */
        private val THEME_CHAR = Regex("[A-Za-z0-9._-]")

        /** A theme's repo as watch passes it on: GitHub over https, nothing a shell or JSON would read differently. */
        private val SOURCE = Regex("^https://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/?$")

        /** The file watch reads: the theme's name, its repo or an empty line, and its background's file name or one. */
        fun themeFile(
            name: String,
            source: String,
            background: String = "",
            widgets: String = "",
        ) = name.filter { THEME_CHAR.matches(it.toString()) } + "\n" + source.takeIf { SOURCE.matches(it) }.orEmpty() + "\n" +
            background.takeIf(::validImage).orEmpty() + "\n" + widgets.filter { it.isLetterOrDigit() } + "\n"

        private val THEME_NAME = Regex("^[a-z0-9_][a-z0-9._+-]*$")
        private val IMAGE = Regex("^[A-Za-z0-9_][A-Za-z0-9._+-]*\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)

        /** A background's file name, plain enough for a path and for JSON: a still image Android shows. */
        fun validImage(name: String) = name.length <= 120 && IMAGE.matches(name)

        /** As omalauncher-link.sh's bgmax. */
        const val MAX_IMAGE_BYTES = 33_554_432L
        private val NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.owl$")

        /** A widget's file name: no path, no dot file (the stock drawer's), nothing but a .owl. */
        fun validName(name: String) = name.length <= 120 && NAME.matches(name)

        /** A computer's id: its key's, so linking the same computer again replaces it. */
        fun keyId(key: String) = sha256Hex(key.toByteArray()).take(12)

        /** Six digits from the key, as the plugin shows them: `printf %s "$key" | sha256sum`, its first 8 hex digits mod 10⁶. */
        fun code(key: String): String {
            val n = sha256Hex(key.toByteArray()).take(8).toLong(16) % 1_000_000
            return "%03d %03d".format(n / 1000, n % 1000)
        }

        /** HMAC-SHA256 of [hostKey] keyed with [secret], in hex: `openssl dgst -sha256 -hmac <secret>` on the computer. */
        fun proof(
            secret: String,
            hostKey: String,
        ): String = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }.doFinal(hostKey.toByteArray()).hex()

        /** Each computer's key, let in only for omalauncher-link with its id. Keys were checked when linked; checked again here. */
        fun authorizedKeys(computers: List<Computer>): String =
            computers.filter { KEY.matches(it.key) && it.id.all { c -> c.isLetterOrDigit() } }.joinToString("") {
                "restrict,command=\"/usr/local/bin/$COMMAND ${it.id}\" ${it.key}\n"
            }

        fun isText(bytes: ByteArray): Boolean =
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                true
            } catch (e: CharacterCodingException) {
                false
            }

        private fun randomHex(bytes: Int) = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.hex()

        private fun phoneName() = Build.MODEL.orEmpty().ifEmpty { "device" }

        /** Where a linked device may come from: private, carrier-grade NAT (Tailscale), link-local and loopback addresses. Before [SSHD_CONFIG], which is made from it. */
        val LOCAL_SOURCES =
            listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "169.254.0.0/16", "127.0.0.0/8", "fc00::/7", "fe80::/10", "::1/128")

        /**
         * sshd for the link: as omalink, on [PORT], keys only and only those in authorized_keys, no terminal, no
         * forwarding. The checks of file owners (StrictModes) are off: a user other than root sees every file as its own
         * under proot, so they'd say nothing.
         */
        val SSHD_CONFIG =
            """
            # Written by Omalauncher for linked computers (ComputerLink.kt); rewritten each time it starts.
            Port $PORT
            HostKey $GUEST_DIR/host_key
            PidFile none
            AuthorizedKeysFile $GUEST_DIR/authorized_keys
            # Only from the local network, a VPN's (Tailscale) or this phone itself: sshd answers on every address the
            # phone has, mobile data's too, where a linked key, even a stolen one, gets no further than this.
            AllowUsers ${LOCAL_SOURCES.joinToString(" ") { "$USER@$it" }}
            PermitRootLogin no
            # Only the keys a linked device has (ed25519, see KEY), and no other way in.
            AuthenticationMethods publickey
            PubkeyAuthentication yes
            PubkeyAcceptedAlgorithms ssh-ed25519
            HostKeyAlgorithms ssh-ed25519
            PasswordAuthentication no
            KbdInteractiveAuthentication no
            HostbasedAuthentication no
            UsePAM no
            StrictModes no
            # omalauncher-link reads its test overrides from the environment: none comes from a client.
            PermitUserEnvironment no
            PermitTTY no
            PermitUserRC no
            DisableForwarding yes
            AllowTcpForwarding no
            AllowStreamLocalForwarding no
            AllowAgentForwarding no
            X11Forwarding no
            PermitTunnel no
            LoginGraceTime 20
            MaxAuthTries 3
            MaxStartups 4:50:10
            # A computer reads the host key with ssh-keyscan, which leaves without logging in: penalized, a computer waiting
            # for the phone to be linked, or several behind one address (adb), were shut out for minutes. Keys only, anyway.
            PerSourcePenalties no
            ClientAliveInterval 30
            """.trimIndent() + "\n"

        /** Makes the user and the host key the first time, then runs sshd as that user, in the foreground. */
        val START =
            """
            set -e
            d=$GUEST_DIR
            command -v sshd >/dev/null || { echo "sshd isn't installed: pacman -S openssh"; exit 1; }
            id $USER >/dev/null 2>&1 || useradd -r -M -d ${'$'}d -s /bin/bash -p '*' $USER
            mkdir -p ${'$'}d
            [ -s ${'$'}d/host_key ] || ssh-keygen -q -t ed25519 -N '' -C omalauncher -f ${'$'}d/host_key
            exec setpriv --reuid=$USER --regid=$USER --clear-groups "${'$'}(command -v sshd)" -D -e -f ${'$'}d/sshd_config
            """.trimIndent()
    }
}
