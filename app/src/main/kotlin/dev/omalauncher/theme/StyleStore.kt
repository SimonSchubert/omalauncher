package dev.omalauncher.theme

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import dev.omalauncher.linux.TerminalText
import dev.omalauncher.ui.Corners
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.runtime.watchFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "style"

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
    private val file = File(dir, FILE)
    private val errorsFile = File(dir, ERRORS)

    /** The file's keys, the ones this launcher doesn't know among them; see [StyleFile.encode]. */
    private var table = LinkedHashMap<String, Any>()

    /** Kept in [LauncherStyle], where home reads it as it draws. */
    var style: Style
        get() = LauncherStyle.style
        private set(value) {
            LauncherStyle.style = value
        }

    /** Why parts of the file were ignored: the last read's, then what [report] adds. */
    private var problems = emptyList<String>()
    private val reported = LinkedHashSet<String>()

    /** What errors.txt says, for home to show: without a terminal nobody would see it. */
    var errors by mutableStateOf(emptyList<String>())
        private set

    /**
     * The file's texts we wrote, or read at the start, oldest first, to tell them from others' when the
     * file changes: a write's change can arrive after the next write.
     */
    private val ours = ArrayDeque<String>()

    /**
     * The file's text as we last knew it, written or read, without its end. A save finding something
     * else there leaves it be: someone wrote it since, and the watch will read it.
     */
    @Volatile private var onDisk: String? = null

    /** Bumped when the watch takes in someone else's text; a save started before it doesn't go out. */
    @Volatile private var external = 0

    /** Whether the file parsed when last read. Until it does, nothing is saved over it: it holds the user's work. */
    private var readable = true

    /** Called after the file changed under us, with the style before and after. */
    var onExternalChange: (old: Style, new: Style) -> Unit = { _, _ -> }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writing = Mutex()
    private var saving: Job? = null

    init {
        // What an earlier run left, so the first read deletes it if nothing's wrong now.
        errors = runCatching { errorsFile.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } }.getOrNull().orEmpty()
        // Small, and read before the first frame so home opens in the right style.
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
        if (text == null) {
            style = importPrefs(context)
            table = StyleFile.encode(table, style)
            // Now, once: the watch started next mustn't take a missing file for one deleted.
            val written = Toml.write(table, StyleFile.HEADER)
            runCatching {
                dir.mkdirs()
                writeAtomically(file, written.toByteArray())
            }.onFailure { Log.w(TAG, "can't save the style", it) }
            synchronized(ours) { ours += written }
            onDisk = written.trimEnd()
            saveErrors()
        } else {
            synchronized(ours) { ours += text }
            onDisk = text.trimEnd()
            read(text)
        }
        running = true
    }

    /** Changes the style and saves it; nothing happens if [change] leaves it as it is. */
    fun update(change: (Style) -> Style) {
        val next = change(style)
        if (next == style) return
        // What errors.txt said about the settings changed here no longer holds.
        val changed = next.changed(style)
        fun stale(problem: String) = changed.any { problem.startsWith("$it:") }
        problems = problems.filterNot(::stale)
        reported.removeAll(::stale)
        saveErrors()
        style = next
        table = StyleFile.encode(table, next)
        save()
    }

    /**
     * Notes a problem with a value that read fine but doesn't fit the phone,
     * like a theme that isn't installed. Cleared when the file changes next.
     */
    fun report(problem: String) {
        if (reported.add(problem)) saveErrors()
    }

    /** defaults.toml, which always matches this launcher's settings. */
    fun managed(): List<Managed> = listOf(Managed.text(File(dir, DEFAULTS), Policy.Owned) { StyleFile.defaultsText() })

    private var watching: Job? = null

    fun start() {
        if (watching != null) return
        watching =
            scope.launch {
                watchFile(file).collect { text ->
                    // Ours: it and anything we wrote before it are through. watchFile trims the end, so compare without it.
                    val mine =
                        synchronized(ours) {
                            val i = ours.indexOfFirst { it.trimEnd() == text }
                            repeat(i + 1) { ours.removeFirst() }
                            i >= 0
                        }
                    if (mine) return@collect
                    // The file is theirs now; a save waiting to go out, or already going, would overwrite it.
                    external++
                    saving?.cancel()
                    onDisk = text
                    val old = style
                    reported.clear()
                    // Deleted: back to the defaults, as for a file that says nothing.
                    read(text)
                    if (text.isEmpty() && !file.isFile) save()
                    if (style != old) onExternalChange(old, style)
                }
            }
    }

    /** Takes in the file's [text]; a file that doesn't parse changes nothing but errors.txt, and isn't saved over. */
    private fun read(text: String) {
        val parsed =
            try {
                Toml.parse(text)
            } catch (e: Toml.Error) {
                readable = false
                problems = listOf("style.toml ${e.message}; nothing in it was applied, and the Style menu won't save until it's fixed")
                saveErrors()
                return
            }
        readable = true
        val old = StyleFile.versionOf(parsed)
        StyleFile.migrate(parsed)
        val decoded = StyleFile.decode(parsed, style)
        table = parsed
        style = decoded.style
        problems = decoded.problems
        saveErrors()
        // An older format is written back in the current one, so the file reads like defaults.toml.
        if (old < StyleFile.VERSION) {
            table = StyleFile.encode(table, style)
            save()
        }
    }

    /** Writes the file once changes stop coming: a stepper tapped five times writes it once. */
    private fun save() {
        saving?.cancel()
        if (!readable) return
        val since = external
        saving =
            scope.launch {
                delay(SAVE_DELAY_MS)
                val text = Toml.write(table, StyleFile.HEADER)
                synchronized(ours) {
                    ours += text
                    while (ours.size > MAX_OURS) ours.removeFirst()
                }
                // Not cancellable once it starts, so it checks instead that nobody wrote the file meanwhile.
                withContext(Dispatchers.IO + NonCancellable) {
                    writing.withLock {
                        val there = runCatching { file.takeIf { it.isFile }?.readText() ?: "" }.getOrNull()?.trimEnd()
                        if (since != external || there != onDisk) {
                            synchronized(ours) { ours.remove(text) }
                            return@withLock
                        }
                        runCatching { writeAtomically(file, text.toByteArray()) }
                            .onSuccess { onDisk = text.trimEnd() }
                            .onFailure { Log.w(TAG, "can't save the style", it) }
                    }
                }
            }
    }

    private fun saveErrors() {
        val all = problems + reported
        if (all == errors) return
        errors = all
        scope.launch(Dispatchers.IO) {
            writing.withLock {
                runCatching { if (all.isEmpty()) errorsFile.delete() else writeAtomically(errorsFile, (all.joinToString("\n") + "\n").toByteArray()) }
                    .onFailure { Log.w(TAG, "can't write the style errors", it) }
            }
        }
    }

    companion object {
        const val FILE = "style.toml"
        const val DEFAULTS = "defaults.toml"
        const val ERRORS = "errors.txt"

        private const val SAVE_DELAY_MS = 400L

        /** Writes whose change we may not have seen yet; more would mean the watch has stopped. */
        private const val MAX_OURS = 8

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
