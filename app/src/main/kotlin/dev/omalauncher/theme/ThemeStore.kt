package dev.omalauncher.theme

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.LauncherData
import dev.omalauncher.linux.Proot
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.decodeForScreen
import dev.omalauncher.upgrade.appStamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "themes"

/** A theme's colors bundled with the app, for picking a theme before the full themes are downloaded. */
class BundledTheme(
    val name: String,
    val colors: Map<String, String>,
) {
    val title get() = titleOf(name)
}

/** Installing a theme from its repo; see [ThemeStore.install]. */
sealed interface ThemeInstall {
    data object Idle : ThemeInstall

    data class Running(
        val name: String,
        val megabytes: Int,
    ) : ThemeInstall

    data class Failed(
        val message: String,
    ) : ThemeInstall
}

sealed interface ThemeDownload {
    data object Idle : ThemeDownload

    data class Running(
        val megabytes: Int,
    ) : ThemeDownload

    data class Failed(
        val message: String,
    ) : ThemeDownload
}

/**
 * Omarchy themes, in the launcher's own data (see [LauncherData]); the guest
 * sees them where Omarchy keeps them (~/.local/share/omarchy/themes), and the
 * user's own in ~/.config/omarchy/themes (see [ThemeDirs]).
 * Applying a theme recolors the launcher and swaps the wallpaper and, when
 * Linux is installed, writes the guest's themed configs.
 */
class ThemeStore(
    private val context: Context,
    proot: Proot,
    /** Where the picked theme and background are saved. */
    private val style: StyleStore,
    /** Whether Linux is installed; without it, nothing is written into the guest. */
    private val isLinux: () -> Boolean,
    /** Called when the guest configs depending on the theme changed; see [managed]. */
    private val onConfigChanged: () -> Unit,
) {
    private val release = OmarchyRelease(LauncherData.omarchyDir(context))
    private val dirs = themeDirs(context)
    private val guest = GuestThemeConfig(proot, release.dir)
    private val prefs = prefs(context)

    var themes by mutableStateOf<List<OmarchyTheme>>(emptyList())
        private set
    var current by mutableStateOf<OmarchyTheme?>(null)
        private set
    var background by mutableStateOf<File?>(null)
        private set
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
        private set
    var download by mutableStateOf<ThemeDownload>(ThemeDownload.Idle)
        private set

    /** Every Omarchy theme's colors, from the app's assets (see scripts/fetch-theme-colors.sh). Read off the main thread. */
    val bundled: List<BundledTheme> by lazy {
        context.assets.list("themes").orEmpty().sorted().map { file ->
            val text = context.assets.open("themes/$file").bufferedReader().use { it.readText() }
            BundledTheme(file.removeSuffix(".toml"), ThemeColors.resolve(ThemeColors.parse(text)))
        }
    }

    /**
     * The themes to pick from: [bundled], with the colors of the user's
     * override where there is one, then the user's own themes.
     */
    val choices: List<BundledTheme> by derivedStateOf {
        val own = themes.filter { it.own != null }.associateBy { it.name }
        bundled.map { b -> own[b.name]?.let { BundledTheme(it.name, it.colors) } ?: b } +
            own.values.filter { t -> bundled.none { it.name == t.name } }.map { BundledTheme(it.name, it.colors) }
    }

    /*
     * style.toml is the one record of the theme and background. What the user
     * does here ([pick], [apply], [setBackground]) changes the style, then shows
     * it; showing ([applyPicked], [show], [showBackground]) never writes it, so
     * following an edit of the file can't write over that edit.
     */

    /** The theme picked, applied or not; its full version is applied once downloaded. */
    val picked get() = style.style[Style.THEME] ?: DEFAULT_THEME

    /** Recolors the launcher right away and remembers the choice, with its first background, until the theme itself is installed. */
    fun pick(theme: BundledTheme) {
        Palette.apply(theme.colors)
        style.update { it.with(Style.THEME, theme.name).with(Style.BACKGROUND, null) }
    }

    /**
     * Shows the theme and background the style names, once the theme is on
     * disk: wallpaper, launcher colors and guest configs. Returns whether it was.
     */
    suspend fun applyPicked(): Boolean {
        val theme = themes.firstOrNull { it.name == picked } ?: return false
        val backgrounds = theme.backgrounds()
        val bgName = style.style[Style.BACKGROUND]
        val bg = bgName?.let { n -> backgrounds.firstOrNull { it.name == n } }
        if (bgName != null && bg == null) style.report("background: \"$bgName\" isn't one of ${theme.name}'s backgrounds; showing its first")
        val target = bg ?: backgrounds.firstOrNull()
        when {
            theme.name != current?.name || theme.colors != current?.colors -> show(theme, target)
            target != background -> showBackground(target)
        }
        return true
    }

    /**
     * The picked theme's bundled colors until the themes are loaded (or
     * downloaded, on a fresh install). Reads one small asset, so it's fine
     * before the first frame.
     */
    fun loadPalette() {
        // A user's own theme has no bundled colors.
        runCatching { ThemeColors.resolve(ThemeColors.parse(context.assets.open("themes/$picked.toml").bufferedReader().use { it.readText() })) }
            .recoverCatching { checkNotNull(dirs.load(picked)).colors }
            .onSuccess { Palette.apply(it) }
    }

    /** Whether our live wallpaper is the Android wallpaper; the launcher then shows it instead of drawing its own. */
    var liveWallpaperActive by mutableStateOf(false)
        private set

    suspend fun refreshLiveWallpaper() {
        liveWallpaperActive = WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
        if (!liveWallpaperActive && wallpaper == null) wallpaper = decodeWallpaper(background)
    }

    private suspend fun decodeWallpaper(file: File?) = file?.let { withContext(Dispatchers.IO) { decodeForScreen(it, context)?.asImageBitmap() } }

    /** Opens Android's live wallpaper preview with our wallpaper preselected; apps can't set one silently. */
    fun liveWallpaperIntent(): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, OmarchyWallpaperService::class.java))

    /** Whether the Omarchy files on disk are the release this app pins. */
    fun hasCurrentRelease() = release.isCurrent()

    /** Whether [load] has finished; until then it may still replace the wallpaper. */
    var loaded by mutableStateOf(false)
        private set

    /** Restores the saved theme; guest configs are already on disk from when it was applied. */
    suspend fun load() {
        try {
            restoreSaved()
        } finally {
            loaded = true
        }
    }

    private suspend fun restoreSaved() {
        themes = withContext(Dispatchers.IO) { scan() }
        val theme = themes.firstOrNull { it.name == picked } ?: return
        current = theme
        Palette.apply(theme.colors)
        // Templates and how we render them may have changed with an app update.
        if (isLinux() && prefs.getString(RENDERED_BY, null) != appStamp(context)) {
            withContext(Dispatchers.IO) { guest.render(theme) }
            prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
        }
        val bg = theme.backgrounds().let { list -> list.firstOrNull { it.name == style.style[Style.BACKGROUND] } ?: list.firstOrNull() }
        // On reload a different background may replace one already showing; it stays up until the new one is decoded.
        val stale = wallpaper != null && bg != background
        background = bg
        // Decodes the wallpaper on first load, unless ours is the live one.
        refreshLiveWallpaper()
        if (stale && !liveWallpaperActive) wallpaper = decodeWallpaper(bg)
    }

    /** After a new release was downloaded: its themes, templates and configs. */
    suspend fun reload() {
        prefs.edit().remove(RENDERED_BY).apply()
        load()
        onConfigChanged()
    }

    /** Picks up themes the user added or removed in Linux; the theme menus call it when they open. */
    suspend fun rescan() {
        themes = withContext(Dispatchers.IO) { scan() }
    }

    private fun scan(): List<OmarchyTheme> = dirs.scan { name, e -> Log.w(TAG, "bad theme $name", e) }

    /** One download at a time; a second caller waits for the first one's result. */
    private val downloading = Mutex()

    /** Whether every theme is on disk; first run fetches only the picked one (see [fetchPicked]). */
    var complete by mutableStateOf(release.isCurrent())
        private set

    /** Each theme's first background at [OMARCHY_REF], bundled by scripts/fetch-theme-colors.sh. */
    private val firstBackgrounds: Map<String, String> by lazy {
        runCatching { context.assets.open("theme-backgrounds.txt").bufferedReader().use { it.readLines() } }
            .getOrDefault(emptyList())
            .mapNotNull { line -> line.split(' ', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Single-theme fetches by theme name; each resolves to why it failed, or null. Main thread only. */
    private val prefetches = HashMap<String, Deferred<String?>>()

    /**
     * Starts fetching [name]'s colors and first wallpaper (see [fetchPicked]),
     * as soon as it's selected, so start usually finds them on disk. A failed
     * fetch is tried again on the next call.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prefetch(name: String): Deferred<String?> {
        prefetches[name]?.let { if (!it.isCompleted || it.getCompleted() == null) return it }
        return scope.async(Dispatchers.IO) {
            if (complete || dirs.hasColors(name)) return@async null
            try {
                release.downloadTheme(name, firstBackgrounds[name])
                null
            } catch (e: Exception) {
                Log.e(TAG, "theme $name failed", e)
                e.message ?: e.toString()
            }
        }.also { prefetches[name] = it }
    }

    /**
     * First run: the picked theme's colors and first wallpaper alone, a few
     * MB, so the home screen opens fully themed; [ensureRelease] fetches the
     * rest in the background. Returns why it failed, or null once applied.
     */
    suspend fun fetchPicked(): String? {
        prefetch(picked).await()?.let { return it }
        downloading.withLock {
            themes = withContext(Dispatchers.IO) { scan() }
            applyPicked()
        }
        return null
    }

    /**
     * Downloads the pinned Omarchy release if it's missing or outdated (an
     * app update can move [OMARCHY_REF]), then applies the picked theme, or
     * reloads the current one from the new files. Returns whether the themes are current.
     */
    suspend fun ensureRelease(): Boolean =
        downloading.withLock {
            if (hasCurrentRelease()) return@withLock true
            // A single-theme fetch writing into the directory the release replaces could fail the swap.
            prefetches.values.toList().forEach { it.join() }
            if (!downloadThemes()) return@withLock false
            if (current == null) applyPicked() else reload()
            true
        }

    /** Fetches the pinned Omarchy release; see [OmarchyRelease]. Returns whether it worked. */
    private suspend fun downloadThemes(): Boolean =
        withContext(Dispatchers.IO) {
            download = ThemeDownload.Running(0)
            try {
                release.download { download = ThemeDownload.Running(it) }
                themes = scan()
                complete = true
                download = ThemeDownload.Idle
                true
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                download = ThemeDownload.Failed(e.message ?: e.toString())
                false
            }
        }

    /** The community themes on omarchy.org/themes, to [install] from. */
    val catalog = ThemeCatalog(context.cacheDir)

    var installing by mutableStateOf<ThemeInstall>(ThemeInstall.Idle)
        private set

    /**
     * Like omarchy-theme-install: fetches the theme in the GitHub repo at
     * [url] into the user's themes, replacing one of the same name, and
     * applies it. What it may hand to Linux is limited; see [ThemeDirs.isFromRepo].
     */
    suspend fun install(url: String) {
        if (installing is ThemeInstall.Running) return
        val repo = ThemeRepo.parse(url)
        if (repo == null) {
            installing = ThemeInstall.Failed("not a GitHub repo: ${url.trim()}")
            return
        }
        val name = repo.themeName
        installing = ThemeInstall.Running(name, 0)
        val staging = File(dirs.user.parentFile, ".theme-install")
        val done =
            withContext(Dispatchers.IO) {
                runCatching {
                    LauncherData.deleteTree(staging)
                    unpackTarball(repo.tarball, staging, { installing = ThemeInstall.Running(name, it) }, maxMegabytes = 200) { path ->
                        // What the launcher and Linux use: the top-level files and the backgrounds.
                        val depth = path.count { it == '/' }
                        (depth == 0 || depth == 1 && path.startsWith("backgrounds/")) && path != "unlock.png"
                    }
                    checkNotNull(ThemeDirs.colorsOf(staging)) { "it has no colors.toml or alacritty.toml" }
                    File(staging, ThemeDirs.SOURCE_MARKER).writeText(repo.url + "\n")
                    val target = File(dirs.user, name)
                    // The catalog's screenshot, for themes without a preview of their own; an update keeps the one it had.
                    val screenshot =
                        catalog.entryFor(repo)?.let { catalog.image(it) }
                            ?: File(target, ThemeDirs.CATALOG_PREVIEW).takeIf { it.isFile }
                    // Best effort: a theme without its screenshot still installs.
                    runCatching { screenshot?.copyTo(File(staging, ThemeDirs.CATALOG_PREVIEW), overwrite = true) }
                        .onFailure { Log.w(TAG, "no catalog preview for $name", it) }
                    dirs.user.mkdirs()
                    LauncherData.deleteTree(target)
                    check(staging.renameTo(target)) { "can't move the theme into place" }
                }.onFailure { runCatching { LauncherData.deleteTree(staging) } }
            }
        done.exceptionOrNull()?.let { e ->
            Log.e(TAG, "installing $url failed", e)
            installing = ThemeInstall.Failed(if (e is FileNotFoundException) "${repo.label} isn't on GitHub" else e.message ?: e.toString())
            return
        }
        rescan()
        themes.firstOrNull { it.name == name }?.let { apply(it) }
        installing = ThemeInstall.Idle
    }

    /** Fetches [theme] again from where [install] got it. */
    suspend fun update(theme: OmarchyTheme) {
        ThemeDirs.sourceOf(theme)?.let { install(it.url) }
    }

    fun clearInstallError() {
        if (installing is ThemeInstall.Failed) installing = ThemeInstall.Idle
    }

    /**
     * Like omarchy-theme-remove: deletes the user's directory of [theme].
     * If it was applied, its built-in version takes over, or the default theme.
     */
    suspend fun remove(theme: OmarchyTheme) {
        val own = theme.own ?: return
        withContext(Dispatchers.IO) { LauncherData.deleteTree(own) }
        rescan()
        if (current?.name != theme.name) return
        val next = themes.firstOrNull { it.name == theme.name } ?: themes.firstOrNull { it.name == DEFAULT_THEME } ?: themes.firstOrNull()
        if (next != null) {
            apply(next)
        } else {
            current = null
            bundled.firstOrNull { it.name == DEFAULT_THEME }?.let(::pick)
        }
    }

    /** Picks [theme] with its first background, and shows it. */
    suspend fun apply(theme: OmarchyTheme) {
        style.update { it.with(Style.THEME, theme.name).with(Style.BACKGROUND, null) }
        show(theme, theme.backgrounds().firstOrNull())
    }

    /** The launcher's colors and wallpaper first, then the guest's configs, which take a while. */
    private suspend fun show(
        theme: OmarchyTheme,
        bg: File?,
    ) {
        current = theme
        Palette.apply(theme.colors)
        showBackground(bg)
        if (!isLinux()) return
        withContext(Dispatchers.IO) {
            // Called from screen scopes, where a failed write (storage full) would crash the launcher.
            runCatching {
                guest.render(theme)
                prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
                onConfigChanged()
                guest.recolorTerminals(theme, style.style[Style.TERMINAL_OPACITY])
            }.onFailure { Log.e(TAG, "theme ${theme.name} not written to the guest", it) }
        }
    }

    /** Gives open terminals the current terminal.opacity, as a theme change gives them its colors. */
    suspend fun retintTerminals() {
        val theme = current ?: return
        if (!isLinux()) return
        withContext(Dispatchers.IO) { runCatching { guest.recolorTerminals(theme, style.style[Style.TERMINAL_OPACITY]) } }
    }

    /**
     * Writes the current theme into a freshly installed guest: what [apply]
     * skipped while Linux wasn't there. The caller syncs [managed] afterwards.
     */
    suspend fun renderGuest() {
        val theme = current ?: return
        withContext(Dispatchers.IO) {
            guest.render(theme)
            background?.let(guest::linkBackground)
        }
        prefs.edit().putString(RENDERED_BY, appStamp(context)).apply()
    }

    suspend fun setBackground(file: File?) {
        style.update { it.with(Style.BACKGROUND, file?.name) }
        showBackground(file)
    }

    private suspend fun showBackground(file: File?) {
        background = file
        // With the live wallpaper active, the launcher is transparent and needs no copy of its own.
        wallpaper = if (liveWallpaperActive) null else decodeWallpaper(file)
        if (file != null && isLinux()) withContext(Dispatchers.IO) { guest.linkBackground(file) }
        changeListeners.forEach { it() }
    }

    /**
     * The theme or background in style.toml changed under us (an agent, the
     * user in Linux): shows them, as picking them in the Style menu would,
     * without writing the file. A theme not downloaded yet is fetched, as on
     * first run; with the key gone, the default theme is back.
     */
    suspend fun followStyle() {
        rescan()
        if (applyPicked()) return
        val name = picked
        val bundledTheme = bundled.firstOrNull { it.name == name }
        if (bundledTheme == null || complete) {
            style.report("theme: no theme named \"$name\" in ~/.local/share/omarchy/themes or ~/.config/omarchy/themes")
            return
        }
        Palette.apply(bundledTheme.colors)
        fetchPicked()?.let { style.report("theme: \"$name\" couldn't be downloaded: $it") }
    }

    /**
     * Like omarchy-theme-bg-install, then picking it: copies an image into the
     * current theme's ~/.config/omarchy/backgrounds/<theme> and shows it.
     * Formats Linux can't read (HEIC, GIF) are re-encoded as JPEG.
     */
    suspend fun addBackground(uri: Uri) {
        val theme = current ?: return
        val file = withContext(Dispatchers.IO) {
            val dir = File(dirs.backgrounds, theme.name)
            val type = context.contentResolver.getType(uri)
            val ext = when (type) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val out = File(dir, "image-${System.currentTimeMillis()}.$ext")
            runCatching {
                dir.mkdirs()
                val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
                if (type in listOf("image/png", "image/webp", "image/jpeg")) {
                    input.use { i -> out.outputStream().use { i.copyTo(it) } }
                } else {
                    val bmp = input.use(BitmapFactory::decodeStream) ?: error("can't decode $type")
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                }
                out
            }.onFailure {
                out.delete()
                Log.e(TAG, "background not added", it)
            }.getOrNull()
        } ?: return
        setBackground(file)
    }

    /**
     * Deletes one of the user's extra backgrounds, from the phone and Linux.
     * If it was showing, the theme's first takes its place, as on Omarchy
     * when the picked background is gone.
     */
    suspend fun removeBackground(file: File) {
        val theme = current ?: return
        if (!theme.isExtraBackground(file)) return
        withContext(Dispatchers.IO) { file.delete() }
        if (file != background) return
        style.update { it.with(Style.BACKGROUND, null) }
        showBackground(theme.backgrounds().firstOrNull())
    }

    /**
     * Guest configs for Omarchy's apps, in the current theme's colors. Before
     * [load] ran, the saved theme's: leaving them out would count as removing them.
     */
    fun managed() = guest.managed(current ?: savedTheme())

    private fun savedTheme(): OmarchyTheme? = runCatching { dirs.load(picked) }.getOrNull()

    companion object {
        /** The Omarchy commit themes and configs come from. Changing it updates them on every install. */
        const val OMARCHY_REF = "8b4eae66da2938ba9559f103b18dbf85cdf28a70"

        private const val RENDERED_BY = "rendered_by"
        private const val DEFAULT_THEME = "tokyo-night"

        /** Called on the main thread after the theme or background changes (the live wallpaper listens). */
        val changeListeners = CopyOnWriteArraySet<() -> Unit>()

        private fun prefs(context: Context) = context.getSharedPreferences("themes", Context.MODE_PRIVATE)

        private fun themeDirs(context: Context) =
            ThemeDirs(
                File(LauncherData.omarchyDir(context), "themes"),
                LauncherData.userThemesDir(context),
                LauncherData.userBackgroundsDir(context),
            )

        /**
         * The saved theme colors and background, read straight from disk. The wallpaper
         * service can run without the launcher (e.g. after a reboot), so this needs no store.
         */
        fun savedSelection(context: Context): Pair<Map<String, String>, File?>? {
            val saved = StyleStore.read(context)
            // Unset is the default theme: first run leaves the one it showed unsaved.
            val name = saved[Style.THEME] ?: DEFAULT_THEME
            val theme = runCatching { themeDirs(context).load(name) }.getOrNull() ?: return null
            // Unset, or not one of the theme's: its first, as the launcher shows.
            val bg = theme.backgrounds().let { list -> list.firstOrNull { it.name == saved[Style.BACKGROUND] } ?: list.firstOrNull() }
            return theme.colors to bg
        }
    }
}
