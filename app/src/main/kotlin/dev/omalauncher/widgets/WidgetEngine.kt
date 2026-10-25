package dev.omalauncher.widgets

import android.content.Context
import android.os.FileObserver
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.BuildConfig
import dev.omalauncher.LauncherData
import dev.omalauncher.Runtime
import dev.omalauncher.linux.Agent
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.pacmanInstall
import dev.omalauncher.linux.runJob
import dev.omalauncher.ui.Palette
import dev.omalauncher.theme.Style
import dev.omalauncher.theme.StyleStore
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import dev.omalauncher.upgrade.writeAtomically
import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Json
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Source
import dev.omalauncher.widgets.market.Review
import dev.omalauncher.widgets.market.Shared
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A widget file, compiled. [program] is null when it doesn't compile; [error] says why. */
class WidgetSpec(
    val file: File,
    val name: String,
    val page: Int,
    val order: Int,
    val program: Program?,
    val error: String? = null,
    /** Preferred size in grid cells; used when the widget is first placed. */
    val size: Pair<Int, Int>? = null,
    /** Resizing stops here. */
    val minSize: Pair<Int, Int> = 1 to 1,
    /** The file's modification time when it was compiled; unchanged files aren't recompiled. */
    val stamp: Long = 0,
    /** A home tile or an app in the drawer; known from the first word even when it doesn't compile. */
    val kind: OwlKind = OwlKind.Widget,
) {
    /** A face file: it is its home page; see [WidgetLayout.owner]. Known from the first word, so also while it doesn't compile. */
    val isFace get() = kind == OwlKind.Face

    fun onPage(page: Int) = WidgetSpec(file, name, page, order, program, error, size, minSize, stamp, kind)
}

/** The owl app in file [file], as a pinned shortcut names it; null once it's gone or no longer an app. */
fun List<WidgetSpec>.app(file: String) = firstOrNull { it.file.name == file && it.kind == OwlKind.App }

/**
 * Loads widgets (`*.owl`) from [dir], ~/.config/omalauncher/widgets in the guest,
 * reloads files as they change, and runs each widget's sources while its
 * page is on screen. Commands run inside the guest, never with Android
 * permissions.
 */
class WidgetEngine(
    private val context: Context,
    private val proot: Proot,
    private val runtime: Runtime,
) {
    /** Outside the rootfs, so widgets work without Linux; the guest sees it at ~/[GUEST_DIR]. */
    val dir = LauncherData.widgetsDir(context)
    private val errorDir get() = File(dir, ".errors")

    /** What `owl-check` reads: per file, the SHA-256 the launcher last compiled, the result and the preview. */
    private val checkDir get() = File(dir, ".check")

    /** Where `owl-send` drops requests (<id>.req) and reads the answers (<id>.done); see [receive]. */
    private val inboxDir get() = File(dir, ".inbox")

    /** Tiles rendered off screen for agents, who can't see the phone; see [Previews]. */
    private val previewDir get() = File(dir, ".preview")

    /** SHA-256 of each widget file as last compiled. */
    private val hashes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** `persist` values, launcher-side like the grid layout, so widget files stay the agent's. */
    private val stateDir = File(context.filesDir, "widget-state")
    val specs = mutableStateListOf<WidgetSpec>()
    /**
     * Observed: Linux coming up after the first load recomposes a tile that needs it before the reload starts its
     * instance, and when that reload changes no spec nothing else would draw the tile again.
     */
    private val instances = mutableStateMapOf<String, WidgetInstance>()
    val layout = WidgetLayout(context)
    val shortcuts = Shortcuts(context, layout)

    /** layout.toml, which [layout] and [shortcuts] write to and an agent's edits come in through. */
    val home =
        HomeStore(context, this, runtime).also { home ->
            layout.onSave = home::changed
            shortcuts.onSave = home::changed
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val services = WidgetServices(context, proot, runtime, scope)

    /** Opens launcher screens ("style" or "themes", "agents", "drawer"); set by the UI. */
    var launcherAction by services::launcherAction

    /** What the drawer's long-press menu does, for appAction(...); set by the UI. */
    internal var appHandlers by services::appHandlers

    /** The home screen's view, for haptic(); set by the UI. */
    var view by services::view

    /** The store's widgets, and its apps. */
    val market = Market(this, OwlKind.Widget)
    val faceMarket = Market(this, OwlKind.Face)
    val appMarket = Market(this, OwlKind.App)

    /** All three, in the store's order. */
    val markets get() = listOf(market, faceMarket, appMarket)

    /** The store for [kind]; panels have none, so they get the widgets'. */
    fun market(kind: OwlKind) = markets.firstOrNull { it.kind == kind } ?: market

    /** Settings' "update all" for widgets and apps is running. */
    var updating by mutableStateOf(false)
        private set

    /** Why the last "update all" stopped short, or null. */
    var updateError by mutableStateOf<String?>(null)
        private set

    /** Reloads every catalog now, on the engine's scope: leaving settings doesn't cancel it. */
    fun checkUpdates() {
        scope.launch { for (m in markets) m.refresh() }
    }

    /** Installs every store update but edited ones, which only the store replaces, on the engine's scope: leaving settings doesn't stop it. */
    fun updateAll() {
        if (updating) return
        updating = true
        updateError = null
        scope.launch {
            try {
                for (m in markets) {
                    for (e in m.updates.filterNot(m::edited)) {
                        m.install(e, page = 0)?.let { updateError = "${e.name}: $it" }
                    }
                }
            } finally {
                updating = false
            }
        }
    }

    /** Widgets whose packages the user already agreed to (store installs): install them without asking again. */
    private val autoInstall = HashSet<String>()

    /** Declared packages that aren't installed, per widget file. Such widgets wait instead of running. */
    val missing = mutableStateMapOf<String, List<String>>()

    /** Package installs in progress or failed, per widget file. */
    val installs = mutableStateMapOf<String, Install>()

    sealed interface Install {
        data object Running : Install

        data class Failed(
            val log: String,
        ) : Install
    }

    /**
     * Home screens currently started. A count, not a flag: when Android replaces
     * the home activity, the old one's onStop can arrive after the new one's
     * onStart, and a flag would then switch widgets off while they're on screen.
     */
    private var started by mutableIntStateOf(0)

    /** Sources pause while no home screen is showing. */
    val visible get() = started > 0

    fun homeStarted() {
        started++
    }

    fun homeStopped() {
        started = (started - 1).coerceAtLeast(0)
    }

    /**
     * Linux is installed and running. Without it, widgets that need it
     * ([Program.needsLinux]) don't run and show as placeholders in their cells.
     */
    var linux by mutableStateOf(false)
        private set

    /** Linux was installed or removed: starts or stops the widgets that need it; their cells stay theirs either way. */
    fun linuxChanged(on: Boolean) {
        if (linux == on) return
        linux = on
        if (!on) {
            missing.clear()
            installs.clear()
        }
        // Through the reload loop, so it never runs alongside a reload for a file change.
        changes.trySend(false)
    }

    /** [spec] needs Linux, which isn't installed: it shows as a placeholder. */
    fun waitsForLinux(spec: WidgetSpec) = !linux && spec.program?.needsLinux == true

    /** File name of the widget whose screen { } is open, full screen over home; see [screenInstance]. */
    var openScreen by mutableStateOf<String?>(null)

    /** [name] is a running app, which opens in its own task (OwlAppActivity). */
    fun isApp(name: String) = instances[name]?.program?.kind == OwlKind.App

    /** A running app with its spec, for its activity; null once it's removed or stops compiling. */
    fun appInstance(name: String): Pair<WidgetSpec, WidgetInstance>? {
        val spec = specs.app(name) ?: return null
        return instances[name]?.takeIf { it.program.kind == OwlKind.App }?.let { spec to it }
    }

    /** Apps whose activity is started, by file name: a count, like [started]. They stay live while the home screen isn't showing. */
    private val shownApps = mutableStateMapOf<String, Int>()

    fun appStarted(name: String) {
        shownApps[name] = (shownApps[name] ?: 0) + 1
    }

    fun appStopped(name: String) {
        val n = (shownApps[name] ?: 0) - 1
        if (n > 0) shownApps[name] = n else shownApps.remove(name)
    }

    /** Apps that ran close(): their activity closes. */
    val appCloses = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** App files that compile and can run here, for the app drawer. */
    fun apps(): List<WidgetSpec> = specs.filter { it.kind == OwlKind.App && it.program != null && !waitsForLinux(it) }

    /** Panel files, which gesture settings offer for swiping up and down; ones that don't compile too, to say so. Not the stock drawer. */
    fun panels(): List<WidgetSpec> = specs.filter { it.kind == OwlKind.Panel && it.file.name != STOCK_DRAWER }

    /**
     * The panel the drawer shows above the prompt bar: the one picked for swiping up, else drawer.owl, else the stock
     * drawer, so a drawer that's gone or broken never leaves the apps out of reach. Null until one runs.
     */
    fun drawerPanel(): String? = listOf(LauncherStyle.style[Style.SWIPE_UP], DRAWER, STOCK_DRAWER).firstOrNull { panelInstance(it) != null }

    /** The running panel [name] with its spec, for home to show; null if it's gone, doesn't compile or can't run here. */
    fun panelInstance(name: String): Pair<WidgetSpec, WidgetInstance>? {
        val spec = specs.firstOrNull { it.file.name == name && it.kind == OwlKind.Panel } ?: return null
        if (waitsForLinux(spec)) return null
        return instances[name]?.takeIf { it.program.kind == OwlKind.Panel }?.let { spec to it }
    }

    /** Panels on screen, by file name; set by home as they slide in and out. */
    var shownPanels by mutableStateOf(emptySet<String>())

    /** Panels that ran close(): home slides them away. */
    val panelCloses = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** The panels gestures open: kept live while home shows, so one is ready the moment a swipe starts. */
    private fun gesturePanels() = setOfNotNull(drawerPanel(), LauncherStyle.style[Style.SWIPE_DOWN])

    /**
     * The extra keys over the soft keyboard: keybar.owl, else the stock ones, so keys that are gone or broken never leave a
     * terminal without esc or the arrows. Null until one runs.
     */
    fun keybarInstance(): WidgetInstance? =
        // Through specs, which are snapshot state, so the keys on screen follow a reload.
        listOf(KEYBAR, STOCK_KEYBAR).firstNotNullOfOrNull { name -> specs.firstOrNull { it.file.name == name && it.kind == OwlKind.Keybar }?.let(::instance) }

    /** How many places show the extra keys now (home, a Linux window); they're live only then. */
    private var keybarShown by mutableIntStateOf(0)

    fun keybarShown(shown: Boolean) {
        keybarShown += if (shown) 1 else -1
    }

    /** On a home page: a widget's tile, or a face; not an app, a panel or the extra keys. */
    fun onHome(spec: WidgetSpec) = spec.kind == OwlKind.Widget || spec.kind == OwlKind.Face

    /** The running widget whose screen is open, with its spec (for the title fallback). */
    fun screenInstance(): Pair<WidgetSpec, WidgetInstance>? {
        val name = openScreen ?: return null
        val spec = specs.firstOrNull { it.file.name == name } ?: return null
        return instance(spec)?.takeIf { it.program.screen != null }?.let { spec to it }
    }

    /** The page on screen; widgets on it and its neighbours stay live, the rest pause. */
    var activePage by mutableIntStateOf(1)

    /** Pages chosen on the phone override the file's own `page:`, so store installs land where you are without editing the file. */
    fun setPage(
        fileName: String,
        page: Int,
    ) = layout.setPage(fileName, page)

    /**
     * The user put [item] where it is: an app they pinned, or a widget with a page chosen on the phone (a store add, a
     * share, a drag). A shipped widget is on its file's own `page:` until then.
     */
    fun placedOnPhone(item: HomeItem) = item !is WidgetItem || layout.page(item.key) != null

    /**
     * A widget with no room on its page moves to the next page with room at its size, or a new one after the last. A face
     * left of page 1 whose place is taken goes further left instead, to a new one left of the leftmost. Returns the page.
     */
    fun moveToFreePage(item: HomeItem): Int {
        val page =
            if (item.isFace && item.page <= FACE_PAGE) {
                newFacePage()
            } else {
                pages.firstOrNull { it > item.page && layout.claim(item, itemsOn(it)) } ?: ((pages.maxOrNull() ?: 1) + 1)
            }
        moveToPage(item, page)
        return page
    }

    /** The empty page waiting left of the leftmost, where a new face goes. */
    fun newFacePage() = minOf(pages.minOrNull() ?: 1, FACE_PAGE + 1) - 1

    /**
     * Puts home's pages in [order] (page numbers, as [pages] lists them) and numbers them again: the first one with tiles
     * becomes page 1, where home opens, the ones before it 0, -1, ... and the rest 2, 3, ..., so tiles never go left of
     * page 1 and no numbers are left between pages. Every move happens before home draws again, so pages never meet.
     */
    fun arrangePages(order: List<Int>) {
        // Before the first load the pages are only partly known, and an order made from an older list (pages came or went
        // since) names pages that aren't these: moving by either would mix them up.
        if (!loaded || order.sorted() != pages) return
        val first = order.indexOfFirst { p -> itemsOn(p).any { !it.isFace } }.let { if (it < 0) order.size else it }
        val moves = order.mapIndexed { i, p -> itemsOn(p) to i - first + 1 }
        for ((items, to) in moves) for (item in items) if (item.page != to) moveToPage(item, to)
    }

    /** Takes [page] off home: its widgets and faces go to .removed/, its apps and folders come off home (they stay installed). */
    fun removePage(page: Int) {
        if (!loaded) return
        for (item in itemsOn(page)) {
            when (item) {
                is WidgetItem -> removeWidget(item.spec)
                is Shortcut, is Folder -> shortcuts.remove(item.key)
            }
            layout.remove(item.key)
        }
        arrangePages(pages)
    }

    /** Puts [item] on [page]: a widget through its page override, a shortcut in place. */
    fun moveToPage(
        item: HomeItem,
        page: Int,
    ) {
        when (item) {
            is WidgetItem -> placeOnPage(item.key, page)
            is Shortcut, is Folder -> shortcuts.move(item.key, page)
        }
    }

    /** Puts the widget or face in [fileName] on [page], loaded already or not. */
    fun placeOnPage(
        fileName: String,
        page: Int,
    ) {
        setPage(fileName, page)
        val i = specs.indexOfFirst { it.file.name == fileName }
        if (i >= 0) specs[i] = specs[i].onPage(page)
    }

    private fun agentWidgetFile(agent: Agent) = "$AGENT_PREFIX${agent.bin}.owl"

    /** An agent's terminal tile, which [addAgentWidget] made. */
    fun isAgentWidget(spec: WidgetSpec) = spec.file.name.startsWith(AGENT_PREFIX)

    /** File names of the widgets this app ships, defaults and examples alike. */
    private val bundledFiles by lazy {
        listOf("widgets", "widgets/examples").flatMap { context.assets.list(it).orEmpty().filter { n -> n.endsWith(".owl") } }.toSet()
    }

    /** The user (or their agent) wrote it: not shipped with the app, installed from the store, shared with them, or an agent's tile. */
    fun isOwnWidget(spec: WidgetSpec): Boolean {
        val name = spec.file.name
        return name !in bundledFiles && !isAgentWidget(spec) && !name.startsWith(TERMINAL_PREFIX) && !name.startsWith(Shared.FILE_PREFIX) &&
            markets.none { m -> m.installed.values.any { i -> i.files.any { it.file == name } } }
    }

    /** [agent]'s terminal tile is on the home screen. */
    fun hasAgentWidget(agent: Agent) = specs.any { it.file.name == agentWidgetFile(agent) }

    /** Adds a terminal tile running [agent] to [page]; see [agentWidget]. */
    suspend fun addAgentWidget(
        agent: Agent,
        page: Int,
    ) = withContext(Dispatchers.IO) {
        val name = agentWidgetFile(agent)
        setPage(name, page)
        File(dir, name).writeText(agentWidget(agent))
    }

    /**
     * Adds another plain terminal tile to [page], a copy of the default one. Each is a file of its own, so there
     * can be any number, and one removed (the default included) is always a tap away in the store.
     */
    suspend fun addTerminalWidget(page: Int) =
        withContext(Dispatchers.IO) {
            val name = TERMINAL_PREFIX + System.currentTimeMillis().toString(36) + ".owl"
            setPage(name, page)
            File(dir, name).writeBytes(context.assets.open("widgets/$TERMINAL_ASSET").use { it.readBytes() })
        }

    /** What a widget from a share link is saved as is there already, with the same text (null when it isn't there). */
    fun sharedWidgetIsSame(shared: Shared): Boolean? = File(dir, shared.fileName).takeIf { it.exists() }?.let { it.readText() == shared.text }

    /**
     * Adds a widget from a share link, once the user has read what it runs: on [page], its packages installed
     * right after, as a store install. One shared before under the same name is replaced and keeps its page. A face
     * goes left of page 1 instead, to the empty one waiting there.
     */
    suspend fun addSharedWidget(
        shared: Shared,
        page: Int,
    ) = withContext(Dispatchers.IO) {
        val name = shared.fileName
        if (!File(dir, name).exists()) setPage(name, if (OwlKind.of(shared.text) == OwlKind.Face) newFacePage() else page)
        installDependenciesWhenLoaded(name)
        File(dir, name).writeText(shared.text)
    }

    /** A copy of a shared widget for an agent to review ([Shared.forReview]), where nothing loads it; its path from ~/[GUEST_DIR]. */
    suspend fun saveForReview(shared: Shared): String =
        withContext(Dispatchers.IO) {
            File(dir, ".review").apply { mkdirs() }.resolve(shared.fileName).writeText(shared.forReview)
            ".review/${shared.fileName}"
        }

    fun installDependenciesWhenLoaded(fileName: String) {
        autoInstall += fileName
    }

    /**
     * Takes a widget off the home screen, or an app out of the drawer (and its icon off home). Its file moves to
     * .removed/ rather than being deleted: it may be an agent's work, and this is easy to undo by hand.
     */
    fun removeWidget(spec: WidgetSpec) {
        val trash = File(dir, ".removed").apply { mkdirs() }
        spec.file.renameTo(File(trash, spec.file.name))
        instances.remove(spec.file.name)?.stop()
        if (openScreen == spec.file.name) openScreen = null
        shortcuts.removeOwl(spec)
        specs.remove(spec)
        forget(spec.file.name)
    }

    /** A widget file that's gone takes its place, page and saved values along, so a new file of that name starts fresh. */
    private fun forget(fileName: String) {
        layout.remove(fileName)
        File(stateDir, "$fileName.json").delete()
        File(stateDir, "$fileName.secret").delete()
        hashes.remove(fileName)
        File(checkDir, "$fileName.txt").delete()
        File(previewDir, "$fileName.png").delete()
        File(previewDir, "$fileName.screen.png").delete()
    }

    /** Widgets, pinned apps and folders on [page]. Wrappers compare by spec, so unchanged pages skip recomposition. */
    fun itemsOn(page: Int): List<HomeItem> = itemsByPage[page].orEmpty()

    /** Built once per change rather than per call: home asks for every page on each recomposition, and drags ask often. */
    private val itemsByPage by derivedStateOf {
        (specs.filter(::onHome).map(::WidgetItem) + shortcuts.onGrid()).groupBy { it.page }
    }

    private val pageList by derivedStateOf {
        (specs.filter(::onHome).map { it.page } + shortcuts.onGrid().map { it.page }).distinct().sorted().ifEmpty { listOf(1) }
    }

    val pages: List<Int> get() = pageList

    fun instance(spec: WidgetSpec): WidgetInstance? = instances[spec.file.name]?.takeIf { it.program === spec.program }

    /** A tap on the tile itself, rather than on a node with its own onTap. */
    fun tap(spec: WidgetSpec) {
        val inst = instance(spec) ?: return
        spec.program?.onTap?.run(inst.root, emptyList())
    }

    /** Signals a change in [dir], true for a file written there; conflated, so a burst of writes reloads once. */
    private val changes = Channel<Boolean>(Channel.CONFLATED)

    @Suppress("unused") // Held so it isn't collected, which would stop the events.
    private var observer: FileObserver? = null

    @Suppress("unused") // The same, for preview requests.
    private var checkObserver: FileObserver? = null

    @Suppress("unused") // The same, for owl-send.
    private var inboxObserver: FileObserver? = null

    fun start() {
        scope.launch {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                checkDir.mkdirs()
                // Requests from before this start have been given up on: owl-send waits a few seconds at most.
                inboxDir.deleteRecursively()
                inboxDir.mkdirs()
                markets.forEach { it.load() }
            }
            // owl-check asks for a preview the launcher hasn't made yet by writing the file's hash to <file>.want.
            checkObserver =
                object : FileObserver(checkDir, CLOSE_WRITE or MOVED_TO) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if (path != null && path.endsWith(".themes") && BuildConfig.DEBUG) {
                            val file = File(checkDir, path)
                            val screens = runCatching { file.readLines() }.getOrDefault(emptyList()).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                            file.delete()
                            scope.launch { previewThemes(path.removeSuffix(".themes"), screens) }
                            return
                        }
                        if (path == null || !path.endsWith(".want")) return
                        val want = File(checkDir, path)
                        val hash = runCatching { want.readText().trim() }.getOrNull()
                        want.delete()
                        val name = path.removeSuffix(".want")
                        scope.launch { if (hash != null && hashes[name] == hash) requestPreview(name) }
                    }
                }.also { it.startWatching() }
            launch { for (name in previewQueue) preview(name) }
            inboxObserver =
                object : FileObserver(inboxDir, CLOSE_WRITE or MOVED_TO) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if (path != null && path.endsWith(".req")) scope.launch { receive(path.removeSuffix(".req")) }
                    }
                }.also { it.startWatching() }
            observer =
                object : FileObserver(dir, CLOSE_WRITE or MOVED_TO or MOVED_FROM or DELETE or CREATE) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if (path != null && path.endsWith(".owl")) changes.trySend(true)
                    }
                }.also { it.startWatching() }
            launch {
                snapshotFlow { loaded }.first { it }
                snapshotFlow { runtime.style.style[Style.COLUMNS] }.collect { layout.regrid(it, pages.map(::itemsOn)) }
            }
            changes.trySend(false)
            for (written in changes) {
                // Editors write in several steps (truncate, write, rename); let them finish. Only then: home waits on the
                // first load, and on the one that starts the widgets that need Linux.
                if (written) delay(200)
                changes.tryReceive()
                reload(withContext(Dispatchers.IO) { widgetFiles() })
            }
        }
    }

    private fun widgetFiles() = dir.listFiles { f -> (!f.name.startsWith(".") || f.name == STOCK_DRAWER || f.name == STOCK_KEYBAR) && f.extension == "owl" }.orEmpty().sortedBy { it.name }

    private suspend fun reload(files: List<File>) {
        val old = specs.associateBy { it.file.name }
        // Each file on its own thread: at startup every one compiles, and home waits for them all.
        val parsed =
            withContext(Dispatchers.IO) {
                files.map { f -> async { old[f.name]?.takeIf { it.stamp == f.lastModified() } ?: compile(f, old[f.name]) } }.awaitAll()
            }
        val changed = parsed.filter { old[it.file.name] !== it }.map { it.file.name }
        specs.clear()
        specs += parsed.sortedWith(compareBy({ it.page }, { it.order }, { it.file.name }))
        // Unchanged files keep their running instance; changed ones start fresh but keep their values.
        val previous = HashMap(instances)
        instances.clear()
        for (spec in parsed) {
            val program = spec.program ?: continue
            // Stopped below with the other leftovers.
            if (program.needsLinux && !linux) continue
            val prev = previous.remove(spec.file.name)
            if (prev?.program === program) {
                instances[spec.file.name] = prev
                continue
            }
            prev?.stop()
            val name = spec.file.name
            val inst =
                WidgetInstance(
                    program,
                    services,
                    { message -> writeError(spec.file, message, append = true) },
                    File(stateDir, "$name.json"),
                    File(stateDir, "$name.secret"),
                    onScreen = { open ->
                        when {
                            program.kind == OwlKind.App -> if (!open) appCloses.tryEmit(name)
                            program.kind == OwlKind.Panel -> if (!open) panelCloses.tryEmit(name)
                            open -> openScreen = name
                            openScreen == name -> openScreen = null
                        }
                    },
                    linux = { linux },
                    foreground = { (shownApps[name] ?: 0) > 0 || openScreen == name || name in shownPanels },
                )
            prev?.let(inst::adopt)
            instances[spec.file.name] = inst
            inst.start(scope) { isLive(spec) }
        }
        // A screen whose widget went away, stopped compiling or lost its screen { } closes.
        if (openScreen?.let { instances[it]?.program?.screen } == null) openScreen = null
        // Stopped first: stopping saves persisted values, which forget() then deletes.
        previous.values.forEach { it.stop() }
        // A file deleted outside the launcher (an agent, rm) frees its spot, so it doesn't come back on top of
        // whatever moved in. Only while running: an empty listing at startup must not wipe the layout.
        if (old.isNotEmpty() && files.isNotEmpty()) {
            for (name in old.keys - parsed.map { it.file.name }.toSet()) forget(name)
        }
        // Not at startup, when every file compiles: only what changed while the launcher runs, which is an agent at work.
        if (loaded) changed.forEach(::requestPreview)
        if (!loaded) {
            runtime.drawOwlIcons(parsed.filter { it.kind == OwlKind.App })
            loaded = true
            // Before faces, page 1 could be emptied by moving its last tile away, and home opens on page 1: the first page
            // of tiles becomes it again.
            val first = pages.firstOrNull { p -> itemsOn(p).any { !it.isFace } }
            if (first != null && first != 1) arrangePages(pages)
            home.loaded()
        }
        // A guest shell call: after home has its widgets rather than before.
        checkDependencies(parsed)
    }

    /**
     * Answers an `owl-send` request: lines of the widget's file name, the
     * command (set or refresh), and for set the state's name, then its value
     * to the end. The answer, `ok` or `error: ...`, goes to <id>.done.
     */
    private suspend fun receive(id: String) {
        val request = File(inboxDir, "$id.req")
        val text = withContext(Dispatchers.IO) { runCatching { request.readText() }.getOrNull().also { request.delete() } } ?: return
        val parts = text.split("\n", limit = 4)
        val name = parts[0]
        val spec = specs.firstOrNull { it.file.name == name }
        val inst = spec?.let(::instance)
        val reply =
            when {
                spec == null -> "error: the launcher hasn't loaded it"
                spec.program == null -> "error: it doesn't compile; run owl-check $name"
                inst == null -> "error: it isn't running" + if (waitsForLinux(spec)) ", because it needs Linux" else ""
                parts.getOrNull(1) == "refresh" -> "ok".also { inst.refreshNow() }
                parts.getOrNull(1) == "set" -> inst.setState(parts.getOrNull(2).orEmpty(), Json.parseOrText(parts.getOrNull(3).orEmpty()))?.let { "error: $it" } ?: "ok"
                else -> "error: unknown command '${parts.getOrNull(1).orEmpty()}'"
            }
        withContext(Dispatchers.IO) {
            runCatching { writeAtomically(File(inboxDir, "$id.done"), (reply + "\n").toByteArray()) }
        }
    }

    /** The first load is done; see [reload]. Until then home's pages are still filling in. */
    var loaded by mutableStateOf(false)
        private set

    private val previews = Previews(context)
    private val previewQueue = Channel<String>(Channel.UNLIMITED)
    private val queued = HashSet<String>()

    /** Widgets being previewed; their sources run while the home screen is hidden. */
    private val previewing = mutableStateListOf<String>()

    /** Main thread. */
    private fun requestPreview(name: String) {
        if (queued.add(name)) previewQueue.trySend(name)
    }

    /**
     * Renders [name]'s tile to .preview/<file>.png once its sources have
     * loaded, at its size on the home screen, and notes it in its check file.
     */
    private suspend fun preview(name: String) {
        queued.remove(name)
        val spec = specs.firstOrNull { it.file.name == name } ?: return
        val program = spec.program ?: return
        val hash = hashes[name] ?: return
        val skip =
            when {
                program.terminal -> "Terminal tiles aren't previewed"
                waitsForLinux(spec) -> "it needs Linux, which isn't installed"
                !missing[name].isNullOrEmpty() -> "it needs ${missing[name]!!.joinToString(" ")}; install with pacman -S --needed"
                instance(spec) == null -> "it isn't running"
                else -> null
            }
        if (skip != null) return writeCheck(spec.file, hash, "ok", "preview-skipped $hash $skip")
        val inst = instance(spec)!!
        if (program.kind == OwlKind.App || program.kind == OwlKind.Panel || program.kind == OwlKind.Keybar) return previewView(spec, inst, hash)
        val cells = tileCells(spec)
        val (w, h) = if (spec.isFace) layout.pagePx(context.resources.displayMetrics) else layout.tilePx(cells, context.resources.displayMetrics)
        previewing += name
        val loaded = loadedOf(inst)
        var screen: String? = null
        val ok =
            try {
                previews.render(spec, inst, shownCells(cells), w, h, File(previewDir, "$name.png"), loaded).also {
                    // The screen { } as it opens over home, at the phone's size.
                    if (it && program.screen != null) {
                        val dm = context.resources.displayMetrics
                        if (previews.renderScreen(spec, inst, dm.widthPixels, dm.heightPixels, File(previewDir, "$name.screen.png"), loaded)) {
                            screen = "screen $hash ${dm.widthPixels}x${dm.heightPixels}"
                        }
                    }
                }
            } finally {
                previewing.remove(name)
            }
        // The file may have changed while this rendered; its own preview follows.
        if (hashes[name] != hash) return
        val line = if (ok) shownCells(cells).let { (c, r) -> "preview $hash ${c}x$r ${w}x$h" } else "preview-skipped $hash rendering failed"
        writeCheck(spec.file, hash, "ok", listOfNotNull(line, screen).joinToString("\n"))
    }

    /** The tile's cells as it's laid out on home, else as the file sizes it; a face's are the whole grid. */
    private fun tileCells(spec: WidgetSpec) = (if (spec.isFace) layout.grid ?: (layout.columns to 12) else null) ?: layout.cell(spec.file.name)?.let { it.w to it.h } ?: spec.size?.let { (w, h) -> w.coerceIn(1, layout.columns) to h.coerceAtLeast(1) } ?: (3 to 3)

    /**
     * [cells] as home shows them, for `cols` and `rows`: on its side in landscape, like the tile on home. The preview
     * shares the tile's instance, so drawing it with the file's cells would put them in the tile on home too.
     */
    private fun shownCells(cells: Pair<Int, Int>) = if (layout.turned) cells.second to cells.first else cells

    /** Waits until every source of [inst] has a value; what a preview draws after. */
    private fun loadedOf(inst: WidgetInstance): suspend () -> Unit = { snapshotFlow { inst.loaded() }.first { it } }

    /** An app has no tile: its view is drawn at the phone's size, as it opens, and noted as its screen. */
    private suspend fun previewView(
        spec: WidgetSpec,
        inst: WidgetInstance,
        hash: String,
    ) {
        val name = spec.file.name
        val dm = context.resources.displayMetrics
        previewing += name
        val ok =
            try {
                previews.renderScreen(spec, inst, dm.widthPixels, dm.heightPixels, File(previewDir, "$name.screen.png"), loadedOf(inst))
            } finally {
                previewing.remove(name)
            }
        if (hashes[name] != hash) return
        val tile =
            "preview-skipped $hash " +
                when (spec.kind) {
                    OwlKind.Panel -> "a panel has no tile"
                    OwlKind.Keybar -> "the terminal key bar has no tile"
                    else -> "an app has no tile"
                }
        writeCheck(spec.file, hash, "ok", if (ok) "$tile\nscreen $hash ${dm.widthPixels}x${dm.heightPixels}" else "$tile; rendering its screen failed")
    }

    /**
     * Debug builds, for the widget store's per-theme previews (scripts/store-previews.sh): once
     * [name]'s sources have loaded, renders its tile in every bundled Omarchy theme to
     * .preview/themes/<theme>/<file>.png (an app: its view at the phone's size), then writes .preview/themes/<file>.done. Asked for by
     * writing .check/<file>.themes. The whole launcher takes each theme's colors while it runs.
     *
     * An app can be shown on several [screens], one per line of the .themes file: the states to set before it's drawn,
     * as `view = "watch"; openId = ""`, in order; `wait = 60` pauses that many seconds there. Each starts from the screen
     * before it. The first is <file>.png, the rest <file>.<n>.png.
     */
    private suspend fun previewThemes(
        name: String,
        screens: List<String> = emptyList(),
    ) {
        val spec = specs.firstOrNull { it.file.name == name } ?: return
        val inst = instance(spec) ?: return
        val cells = tileCells(spec)
        val (w, h) = if (spec.isFace) layout.pagePx(context.resources.displayMetrics) else layout.tilePx(cells, context.resources.displayMetrics)
        val themes = withContext(Dispatchers.IO) { runtime.themes.bundled }
        val before = Palette.current
        val dir = File(previewDir, "themes")
        previewing += name
        try {
            val loaded = loadedOf(inst)
            withTimeoutOrNull(30_000) { loaded() }
            val dm = context.resources.displayMetrics
            for ((n, screen) in screens.ifEmpty { listOf("") }.withIndex()) {
                for ((state, value) in assignments(screen)) {
                    // wait = <seconds> pauses first, for an API that limits calls a minute.
                    if (state == "wait") {
                        delay((value.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 300.0).times(1000).toLong())
                        continue
                    }
                    inst.setState(state, Json.parseOrText(value))?.let { Log.w("widgets", "preview screen ${n + 1} of $name: $it") }
                }
                // What the new screen fetches starts now; give it time before the first theme is drawn.
                if (screen.isNotEmpty()) {
                    delay(SCREEN_SETTLE_MS)
                    withTimeoutOrNull(30_000) { loaded() }
                }
                // Waited for once a screen: a source that never loads would otherwise hold up every theme.
                val ready: suspend () -> Unit = {}
                for (theme in themes) {
                    Palette.apply(theme.colors)
                    val out = File(dir, "${theme.name}/$name${if (n > 0) ".${n + 1}" else ""}.png")
                    if (spec.kind == OwlKind.App || spec.kind == OwlKind.Panel || spec.kind == OwlKind.Keybar) {
                        previews.renderScreen(spec, inst, dm.widthPixels, dm.heightPixels, out, ready)
                    } else {
                        previews.render(spec, inst, shownCells(cells), w, h, out, ready)
                    }
                }
            }
        } finally {
            Palette.restore(before)
            previewing.remove(name)
        }
        withContext(Dispatchers.IO) { File(dir, "$name.done").writeText("${cells.first}x${cells.second} ${w}x$h\n") }
    }

    /** A preview screen's `name = value; name = value` pairs; a value is JSON (a quoted string, a number, true) or plain text. */
    private fun assignments(screen: String) = SCREEN_STATE.findAll(screen).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()

    private fun isLive(spec: WidgetSpec): Boolean {
        if (!missing[spec.file.name].isNullOrEmpty()) return false
        // A preview needs real values, even while the home screen is hidden behind the agent's terminal.
        if (spec.file.name in previewing) return true
        // An app in its own task: live while that's showing, whatever home does.
        if ((shownApps[spec.file.name] ?: 0) > 0) return true
        // Over a Linux window too, while home is hidden.
        if (spec.kind == OwlKind.Keybar) return keybarShown > 0
        if (!visible) return false
        // Its screen is open: it's on screen wherever the pages are.
        if (spec.file.name == openScreen) return true
        if (spec.kind == OwlKind.Panel) return spec.file.name in shownPanels || spec.file.name in gesturePanels()
        // Apps have no page to be near.
        if (!onHome(spec)) return false
        val pages = pages
        val here = pages.indexOf(activePage)
        val there = pages.indexOf(spec.page)
        return here < 0 || there < 0 || kotlin.math.abs(here - there) <= 1
    }

    private fun compile(
        file: File,
        previous: WidgetSpec?,
    ): WidgetSpec {
        val stamp = file.lastModified()
        val bytes = runCatching { file.readBytes() }.getOrElse { return broken(file, previous, stamp, it.message ?: "can't read", "") }
        val text = String(bytes)
        val hash = Review.sha256(bytes)
        hashes[file.name] = hash
        return try {
            val p = Compiler.compile(text)
            writeError(file, null)
            // Advice, after the clear and before the check result, so owl-check prints it with the rest.
            for (w in p.warnings) writeError(file, "warning: $w", append = true)
            writeCheck(file, hash, "ok")
            WidgetSpec(file, p.name, layout.page(file.name) ?: p.page, p.order, p, null, p.size, p.minSize, stamp, p.kind)
        } catch (e: CompileError) {
            broken(file, previous, stamp, Source(text).format(e), hash, OwlKind.of(text))
        } catch (e: RuntimeException) {
            broken(file, previous, stamp, e.toString(), hash, OwlKind.of(text))
        }
    }

    /** A widget that doesn't compile stays where it was, so fixing it doesn't reshuffle the page. */
    private fun broken(
        file: File,
        previous: WidgetSpec?,
        stamp: Long,
        error: String,
        hash: String,
        kind: OwlKind = previous?.kind ?: OwlKind.Widget,
    ): WidgetSpec {
        writeError(file, error)
        writeCheck(file, hash, "error")
        return WidgetSpec(file, file.nameWithoutExtension, previous?.page ?: layout.page(file.name) ?: if (kind == OwlKind.Face) FACE_PAGE else 1, previous?.order ?: 999, null, error, previous?.size, previous?.minSize ?: (1 to 1), stamp, kind)
    }

    /** Error files are written in order: a clear must never land after the error that follows it. */
    private val errorWriter = Dispatchers.IO.limitedParallelism(1)

    /**
     * Errors also go to .errors/<file>.txt, so an agent editing widgets in the
     * guest can read what went wrong without seeing the phone.
     */
    private fun writeError(
        file: File,
        error: String?,
        append: Boolean = false,
    ) {
        val target = File(errorDir, file.name + ".txt")
        scope.launch(errorWriter) {
            runCatching {
                when {
                    error == null -> target.delete()
                    append -> {
                        errorDir.mkdirs()
                        if (target.length() < 64 shl 10) target.appendText("runtime: $error\n")
                    }
                    else -> {
                        errorDir.mkdirs()
                        target.writeText(error + "\n")
                    }
                }
            }
        }
    }

    /**
     * Writes .check/<file>.txt for `owl-check`: `sha256 <hash>`, `result ok` or
     * `result error`, and once rendered, `preview <hash> <cells> <pixels>` or
     * `preview-skipped <hash> <why>`, then `screen <hash> <pixels>` for a screen { }. After the error file, in the same order,
     * so a check that sees the hash also sees that version's errors.
     */
    private fun writeCheck(
        file: File,
        hash: String,
        result: String,
        preview: String? = null,
    ) {
        val text = "sha256 $hash\nresult $result\n" + (preview?.let { "$it\n" } ?: "")
        scope.launch(errorWriter) {
            runCatching { writeAtomically(File(checkDir, "${file.name}.txt"), text.toByteArray()) }
        }
    }

    /**
     * Checks, in one guest shell call, which declared pacman packages are
     * missing and which programs the commands use that aren't installed. A
     * program that's missing and undeclared is reported, so the author adds it
     * to `requires`.
     */
    private suspend fun checkDependencies(specs: List<WidgetSpec>) {
        if (!linux) return
        val programs = specs.mapNotNull { it.program }
        val packages = programs.flatMap { it.requires["pacman"].orEmpty() }.distinct()
        val bins = programs.flatMap { it.programs }.distinct()
        if (packages.isEmpty() && bins.isEmpty()) {
            missing.clear()
            return
        }
        val out = runCatching { services.shells.run(DependencyCheck.script(packages, bins), 20).out }.getOrElse { return }
        val found = DependencyCheck.parse(out)
        val missingPkgs = found.packages
        val missingBins = found.programs
        for (spec in specs) {
            val p = spec.program ?: continue
            val need = p.requires["pacman"].orEmpty().filter { it in missingPkgs }
            if (need.isEmpty()) missing.remove(spec.file.name) else missing[spec.file.name] = need
            if (need.isNotEmpty() && autoInstall.remove(spec.file.name)) installDependencies(spec)
            // With declared packages still missing, their programs are expected to be absent.
            val undeclared = if (need.isEmpty()) p.programs.filter { it in missingBins } else emptyList()
            for (bin in undeclared) {
                if (warned.add("${spec.file.name}:${spec.stamp}:$bin")) {
                    writeError(spec.file, "warning: runs `$bin`, which isn't installed; add its package to requires { pacman: [...] }", append = true)
                }
            }
        }
    }

    /** Warnings already written, per file version, so reloads don't repeat them. */
    private val warned = HashSet<String>()

    /** Installs a widget's missing packages the way the bootstrap does: a full sync, so no partial upgrades. */
    fun installDependencies(spec: WidgetSpec) {
        val name = spec.file.name
        val packages = missing[name].orEmpty()
        if (packages.isEmpty() || installs[name] == Install.Running) return
        installs[name] = Install.Running
        scope.launch {
            val (ok, tail) = runtime.pacman.lock.withLock { withContext(Dispatchers.IO) { proot.runJob(pacmanInstall(packages)) } }
            if (ok) installs.remove(name) else installs[name] = Install.Failed(tail.toString())
            // Other widgets may have been waiting on the same packages.
            checkDependencies(specs.toList())
        }
    }

    /**
     * The widget docs, examples and default widgets. Defaults are the user's
     * once edited or removed; the docs always match this app's language, as
     * reference for agents. Examples aren't loaded until moved up into [dir].
     */
    fun managed(): List<Managed> {
        fun asset(
            path: String,
            target: File,
            policy: Policy,
        ) = Managed(target, policy) { context.assets.open(path).use { it.readBytes() } }

        val assets = context.assets
        return buildList {
            add(asset("widgets/README.md", File(dir, "README.md"), Policy.Owned))
            for (name in assets.list("widgets/docs").orEmpty()) add(asset("widgets/docs/$name", File(dir, "docs/$name"), Policy.Owned))
            for (name in assets.list("widgets/examples").orEmpty()) add(asset("widgets/examples/$name", File(dir, "examples/$name"), Policy.Seeded))
            for (name in assets.list("widgets").orEmpty().filter { it.endsWith(".owl") }) add(asset("widgets/$name", File(dir, name), Policy.Seeded))
            // Always as this launcher ships them, whatever became of drawer.owl and keybar.owl.
            add(asset("widgets/$DRAWER", File(dir, STOCK_DRAWER), Policy.Owned))
            add(asset("widgets/$KEYBAR", File(dir, STOCK_KEYBAR), Policy.Owned))
        }
    }

    /** Agents look for AGENTS.md / CLAUDE.md in the guest home; these point them at the widget docs. */
    fun guestManaged(): List<Managed> =
        listOf("AGENTS.md", "CLAUDE.md").map { Managed.text(File(proot.home, it), Policy.Seeded) { AGENT_POINTER } } +
            listOf("owl-check", "owl-send").map { bin ->
                Managed(File(proot.rootfs, "usr/local/bin/$bin"), Policy.Owned, executable = true) { context.assets.open("$bin.sh").use { it.readBytes() } }
            }

    companion object {
        /** A store preview screen's `name = value`, up to the next `;` outside quotes. */
        private val SCREEN_STATE = Regex("""(\w+)\s*=\s*("(?:[^"\\]|\\.)*"|[^;]+)""")

        /** How long a store preview screen gets to fetch what it shows before its sources count as loaded. */
        private const val SCREEN_SETTLE_MS = 3_000L

        /** The default terminal tile, which [addTerminalWidget] copies. */
        private const val TERMINAL_ASSET = "45-terminal.owl"

        /** The app drawer, a panel: the user's to change, from the stock one the launcher ships. */
        const val DRAWER = "drawer.owl"

        /**
         * The drawer as this launcher ships it, kept that way: what the drawer shows while drawer.owl is gone or broken,
         * and what an agent can compare an edited drawer.owl with. Hidden, so it isn't offered as a panel of its own.
         */
        const val STOCK_DRAWER = ".stock-drawer.owl"

        /** The extra keys over the soft keyboard while typing in a terminal: the user's to change, like the drawer. */
        const val KEYBAR = "keybar.owl"

        /** The extra keys as this launcher ships them, kept that way: the fallback, and what an edited keybar.owl compares with. */
        const val STOCK_KEYBAR = ".stock-keybar.owl"
        private const val TERMINAL_PREFIX = "terminal-"
        private const val AGENT_PREFIX = "agent-"

        /** The widgets dir, relative to the guest home. */
        const val GUEST_DIR = ".config/omalauncher/widgets"

        private const val AGENT_POINTER =
            "# omalauncher\n\nThis Arch system runs inside an Android launcher, omalauncher; its source is at " +
                "https://github.com/SimonSchubert/omalauncher, should you need to understand how the launcher works. " +
                "Home-screen widgets are `.owl` files in " +
                "~/$GUEST_DIR, written in a small declarative language documented in README.md there (and docs/ next to it); so are apps for the " +
                "app drawer (`app \"name\" { }`) and panels that swiping up or down on home opens (`panel \"name\" { }`, picked with " +
                "gestures.swipe-up and gestures.swipe-down in ~/${StyleStore.GUEST_DIR}/style.toml). The app drawer is the panel " +
                "drawer.owl there: to change the drawer, edit it; .stock-drawer.owl is the drawer as this launcher ships it. " +
                "Likewise keybar.owl (`keybar \"name\" { }`) is the terminal key bar over the soft keyboard while typing in a terminal, " +
                "and .stock-keybar.owl the bar as shipped. " +
                "Saved files show up on the phone within seconds. After saving, run `owl-check <file>`: it prints the errors " +
                "and the path of a PNG of the tile or view, drawn with live data; look at it to check the layout. " +
                "examples/ holds more widgets to learn from or move up to use. Scripts, cron jobs and hooks can set a running " +
                "widget's state or refresh it with `owl-send <file> set <state> <value>` and `owl-send <file> refresh`.\n\n" +
                "The launcher's look (theme, background, tile spacing and borders, corners, the system bars' colors, the " +
                "terminals' font) is ~/${StyleStore.GUEST_DIR}/${StyleStore.FILE}; ${StyleStore.DEFAULTS} next to it lists every " +
                "setting with its default and range. Changes show within a second; ${StyleStore.ERRORS} there says what was ignored and why.\n\n" +
                "Where everything on home is (pages in order, faces, widgets, pinned apps and folders, each tile's cell) is " +
                "~/${HomeStore.GUEST_DIR}/${HomeStore.FILE}; edit it to move, resize or pin things or reorder pages. " +
                "docs/home.md in the widgets folder explains it.\n"
    }
}
