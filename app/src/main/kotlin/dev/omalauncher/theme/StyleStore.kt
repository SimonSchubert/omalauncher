package dev.omalauncher.theme

import android.content.Context
import dev.omalauncher.LauncherData
import dev.omalauncher.linux.TerminalText
import dev.omalauncher.ui.Corners
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.upgrade.SyncedFile
import dev.omalauncher.upgrade.Versioned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The launcher's [Style], kept in [StyleFile] in the launcher's data, which
 * Linux sees as ~/.config/omalauncher/style/style.toml. The Style menu changes
 * it through [update]; an agent or the user editing the file changes it too,
 * and [onExternalChange] hears about that. What couldn't be read goes into
 * errors.txt next to it. Main thread.
 */
class StyleStore(
    private val context: Context,
) {
    private val dir = LauncherData.styleDir(context)
    private val profilesFile = File(dir, "wallpapers.json")
    private val profiles = WallpaperProfiles.read(runCatching { profilesFile.takeIf { it.exists() }?.readText() }.getOrNull())
    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var savingProfiles: Job? = null
    private val profileWriting = Mutex()
    private var wallpaperKey: String? = null

    /** The file's keys, the ones this launcher doesn't know among them; see [StyleFile.encode]. */
    private var table = LinkedHashMap<String, Any>()

    private val synced = SyncedFile(File(dir, FILE), File(dir, ERRORS), "the style") { Toml.write(table, StyleFile.HEADER) }

    /** Kept in [LauncherStyle], where home reads it as it draws. */
    var style: Style
        get() = LauncherStyle.style
        private set(value) {
            LauncherStyle.style = value
        }

    /** What errors.txt says, for home to show: without a terminal nobody would see it. */
    val errors get() = synced.errors

    /** Called after the file changed under us, with the style before and after. */
    var onExternalChange: (old: Style, new: Style) -> Unit = { _, _ -> }

    init {
        // Small, and read before the first frame so home opens in the right style.
        val text = synced.open()
        if (text == null) {
            style = importPrefs(context)
            table = StyleFile.encode(table, style)
            synced.create()
        } else {
            read(text)
        }
        wallpaperKey = ThemeStore.wallpaperKey(context, style)
        profiles.remember(wallpaperKey!!, style.wallpaperFraming)
        saveProfiles()
        running = true
    }

    /** Changes the style and saves it; nothing happens if [change] leaves it as it is. */
    fun update(change: (Style) -> Style) {
        val previous = style
        val proposed = change(previous)
        if (proposed == previous) return
        val selectionChanged = proposed[Style.THEME] != previous[Style.THEME] || proposed[Style.BACKGROUND] != previous[Style.BACKGROUND]
        val key = if (selectionChanged || wallpaperKey == null) ThemeStore.wallpaperKey(context, proposed) else wallpaperKey!!
        val framing = profiles.choose(wallpaperKey, key, previous.wallpaperFraming, proposed.wallpaperFraming)
        val next = if (selectionChanged) proposed.withWallpaperFraming(framing) else proposed
        wallpaperKey = key
        // What errors.txt said about the settings changed here no longer holds.
        val changed = next.changed(style)
        synced.drop { problem -> changed.any { problem.startsWith("$it:") } }
        style = next
        table = StyleFile.encode(table, next)
        synced.save()
        if (selectionChanged || previous.wallpaperFraming != next.wallpaperFraming) saveProfiles()
    }

    /** A first background may only become known after the themes finish downloading. */
    fun bindWallpaper(file: File?) {
        val key = file?.let { ThemeStore.wallpaperKey(context, it) } ?: return
        if (key == wallpaperKey) return
        wallpaperKey = key
        profiles.remember(key, style.wallpaperFraming)
        saveProfiles()
    }

    private fun saveProfiles() {
        savingProfiles?.cancel()
        savingProfiles = profileScope.launch {
            delay(400)
            val data = profiles.data()
            withContext(Dispatchers.IO) {
                profileWriting.withLock {
                    runCatching {
                        profilesFile.parentFile?.mkdirs()
                        Versioned.writeFile(profilesFile, WallpaperProfiles.VERSION, data)
                    }.onFailure { android.util.Log.w("wallpapers", "can't save wallpaper framing", it) }
                }
            }
        }
    }

    /**
     * Notes a problem with a value that read fine but doesn't fit the phone,
     * like a theme that isn't installed. Cleared when the file changes next.
     */
    fun report(problem: String) = synced.report(problem)

    /** defaults.toml, which always matches this launcher's settings. */
    fun managed(): List<Managed> = listOf(Managed.text(File(dir, DEFAULTS), Policy.Owned) { StyleFile.defaultsText() })

    fun start() {
        synced.start { text ->
            val old = style
            // Deleted: back to the defaults, as for a file that says nothing.
            read(text)
            if (text.isEmpty() && !synced.exists) synced.save()
            if (style != old) onExternalChange(old, style)
        }
    }

    /** Takes in the file's [text]; a file that doesn't parse changes nothing but errors.txt, and isn't saved over. */
    private fun read(text: String) {
        val parsed =
            try {
                Toml.parse(text)
            } catch (e: Toml.Error) {
                synced.readable = false
                synced.setProblems(listOf("style.toml ${e.message}; nothing in it was applied, and the Style menu won't save until it's fixed"))
                return
            }
        synced.readable = true
        val old = StyleFile.versionOf(parsed)
        StyleFile.migrate(parsed)
        val decoded = StyleFile.decode(parsed, style)
        table = parsed
        // External edits are authoritative, including an explicit reset or a new background's framing.
        wallpaperKey?.let { profiles.remember(it, style.wallpaperFraming) }
        style = decoded.style
        wallpaperKey = ThemeStore.wallpaperKey(context, style)
        profiles.remember(wallpaperKey!!, style.wallpaperFraming)
        saveProfiles()
        synced.setProblems(decoded.problems)
        // An older format is written back in the current one, so the file reads like defaults.toml.
        if (old < StyleFile.VERSION) {
            table = StyleFile.encode(table, style)
            synced.save()
        }
    }

    companion object {
        const val FILE = "style.toml"
        const val DEFAULTS = "defaults.toml"
        const val ERRORS = "errors.txt"

        /** Where Linux sees [FILE] and the rest, relative to the guest home. */
        const val GUEST_DIR = ".config/omalauncher/style"

        /**
         * Launchers before style.toml kept these in SharedPreferences, under the
         * keys here. Read once, when there's no file yet; they're left in place
         * for a downgrade.
         */
        fun importPrefs(context: Context): Style {
            val themes = ThemeStore.prefs(context)
            val launcher = LauncherData.prefs(context)
            var s = Style()
            themes.getString("theme", null)?.let { s = s.with(Style.THEME, it) }
            themes.getString("background", null)?.let { s = s.with(Style.BACKGROUND, it) }
            s = s.with(Style.MONOCHROME_ICONS, themes.getBoolean("mono_icons", false))
            s = s.with(Style.APP_TILES, !themes.getBoolean("bare_app_icons", false))
            s = s.with(Style.RECENT_APPS, themes.getBoolean("recent_apps", true))
            // Framed was the default then; it's written out, as it no longer is.
            // Only a choice made in the Style menu: unset, the bars take today's default.
            if (themes.contains("framed_system_bars")) {
                val framed = themes.getBoolean("framed_system_bars", false)
                s = s.with(Style.STATUS_BAR.framed, framed).with(Style.NAVIGATION_BAR.framed, framed)
            }
            s = s.with(Style.CORNER_SCALE, Corners.of(themes.getString("corners", null)).scale.toDouble())
            launcher.getString("terminal_font", null)?.let { s = s.with(Style.TERMINAL_FONT, it) }
            s = s.with(Style.TERMINAL_TEXT, TerminalText.of(launcher.getString("terminal_text", null)).em.toDouble())
            return s
        }

        /** Whether a store has read the style in this process; [LauncherStyle] holds it then. */
        @Volatile private var running = false

        /**
         * The style, for the wallpaper service. In the launcher's process it's
         * the one in memory, as the file is saved a moment after a change; the
         * service can also run without the launcher, and then reads the file.
         */
        fun read(context: Context): Style {
            if (running) return LauncherStyle.style
            val text = runCatching { File(LauncherData.styleDir(context), FILE).readText() }.getOrNull() ?: return importPrefs(context)
            return runCatching { StyleFile.decode(Toml.parse(text).also(StyleFile::migrate)).style }.getOrDefault(Style())
        }
    }
}
