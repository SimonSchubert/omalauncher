package dev.omalauncher.desktop

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.AndroidApp
import dev.omalauncher.Runtime
import dev.omalauncher.ui.EmbeddedTerminal
import dev.omalauncher.widgets.HomeItem
import dev.omalauncher.widgets.GRID_COLUMNS
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.Shortcuts
import dev.omalauncher.widgets.Folder
import dev.omalauncher.widgets.WidgetItem
import dev.omalauncher.widgets.WidgetLayout
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WidgetError
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Existing definitions/data services, independent widget UI state, placement and lifetime. */
internal class DesktopWidgets(
    context: Context,
    private val runtime: Runtime,
    profile: String,
    private val launch: (AndroidApp) -> Unit,
    private val action: (String) -> Unit,
    private val unavailable: (String) -> Unit,
    private val authorized: () -> Boolean,
    private val inheritPhoneLayout: Boolean = false,
) {
    val layout = WidgetLayout(context, profile)
    val shortcuts = Shortcuts(context, layout, profile)
    private val prefs = context.getSharedPreferences("desktop_$profile", Context.MODE_PRIVATE)
    private var hidden by mutableStateOf(prefs.getStringSet("hidden", emptySet()).orEmpty().toSet())
    private var pinned by mutableStateOf(prefs.getStringSet("pinned", emptySet()).orEmpty().toSet())
    private val inheritedPages = mutableStateListOf<Int>().apply {
        addAll(prefs.getStringSet("sourcePages", emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.sorted())
    }
    private val stateDir = File(context.filesDir, "desktop-widget-state/$profile")
    val specs = mutableStateListOf<WidgetSpec>()
    // WidgetLayout's page map is concurrent for the compiler, not a Compose snapshot map.
    private var pageRevision by mutableIntStateOf(0)
    val instances = mutableStateMapOf<String, WidgetInstance>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var visible by mutableStateOf(false)
    var openScreen by mutableStateOf<String?>(null)

    private val services = object : Services by runtime.widgets.desktopServices() {
        override fun terminal(command: String?, fontSize: Double?): EmbeddedTerminal = throw WidgetError("Terminal presentation is unavailable on external desktops")

        override fun act(name: String, args: List<Value>, done: () -> Unit) {
            if (!authorized()) return
            when (name) {
                "terminal", "app", "url", "share", "haptic" -> unavailable("$name presentation is unavailable on external desktops")
                "launcher" -> action(args.firstOrNull()?.toString().orEmpty())
                "androidApp" -> {
                    val packageName = args.firstOrNull()?.toString()
                    // Package-only widget actions retain their existing personal-profile meaning.
                    runtime.androidApps.apps.firstOrNull { it.component.packageName == packageName && it.profile == null }
                        ?.let(launch) ?: unavailable("Android app is unavailable")
                }
                else -> runtime.widgets.desktopServices().act(name, args, done)
            }
        }
    }

    fun start() {
        scope.launch {
            snapshotFlow { Triple(runtime.widgets.specs.toList(), runtime.widgets.linux, runtime.androidApps.apps.toList()) }.collect { (definitions, linux, apps) ->
                var seeded = false
                if (inheritPhoneLayout && !prefs.getBoolean("phoneSeeded", false) && definitions.isNotEmpty()) {
                    layout.seedFrom(runtime.widgets.layout)
                    // A phone home need not have been measured before its service starts.
                    // Keep the compiled page even when no placement override exists yet.
                    definitions.forEach { layout.setPage(it.file.name, it.page) }
                    shortcuts.seedFrom(runtime.widgets.shortcuts)
                    inheritedPages.clear()
                    inheritedPages += runtime.widgets.pages
                    prefs.edit().putBoolean("phoneSeeded", true)
                        .putStringSet("sourcePages", inheritedPages.map(Int::toString).toSet())
                        .remove("pinned").putBoolean("shortcutsMigrated", true).apply()
                    pinned = emptySet()
                    seeded = true
                }
                if (!prefs.getBoolean("shortcutsMigrated", false) && (pinned.isEmpty() || apps.isNotEmpty())) {
                    apps.filter { it.key in pinned }.forEach { shortcuts.addAndroid(it, layout.page("app:" + it.key) ?: 1) }
                    prefs.edit().remove("pinned").putBoolean("shortcutsMigrated", true).apply()
                    pinned = emptySet()
                }
                // Keep terminal page/placement visible as a placeholder; never share its input/runtime UI.
                val available = definitions
                val existing = instances.toMap()
                val keep = available.map { it.file.name }.toSet()
                for ((name, instance) in existing) {
                    val spec = available.firstOrNull { it.file.name == name }
                    if (spec?.program !== instance.program || (spec.program.needsLinux && !linux)) {
                        instance.stop()
                        instances.remove(name)
                    }
                }
                specs.clear()
                specs += available
                if (seeded) {
                    // Allocate unmeasured tiles on their inherited page before HomeGrid sees
                    // the desktop aspect ratio. Its used rows then scale to fit the screen.
                    // Otherwise the first frame can move overflow to a new page permanently.
                    for (page in pages) {
                        val items = itemsOn(page)
                        val used = items.maxOfOrNull { layout.cell(it.key)?.bottom ?: 0 } ?: 0
                        val extra = items.filter { layout.cell(it.key) == null }.sumOf { it.size?.second ?: 3 }
                        layout.place(items, GRID_COLUMNS, (used + extra).coerceAtLeast(1))
                    }
                }
                for (spec in available) {
                    val program = spec.program ?: continue
                    if (program.terminal) continue
                    if (spec.file.name in instances || (program.needsLinux && !linux)) continue
                    val name = spec.file.name
                    val saved = File(stateDir, "$name.json")
                    val instance = WidgetInstance(program, services, { unavailable(it) }, saved,
                        onScreen = { open -> if (open) openScreen = name else if (openScreen == name) openScreen = null },
                        linux = { runtime.widgets.linux })
                    // Preserve this desktop's values across edits/reconnections. Seed a new desktop from the phone once.
                    (existing[name] ?: runtime.widgets.instance(spec)?.takeIf { !saved.exists() })?.let(instance::adopt)
                    instances[name] = instance
                    instance.start(scope) { visible && runtime.widgets.missing[name].isNullOrEmpty() }
                }
                if (openScreen?.let { it !in keep } == true) openScreen = null
            }
        }
    }

    fun page(spec: WidgetSpec) = layout.page(spec.file.name) ?: spec.page

    fun itemsOn(page: Int): List<HomeItem> = specs.filter { it.file.name !in hidden && page(it) == page }.map { WidgetItem(it.onPage(page)) } +
        shortcuts.onGrid().filter { it.page == page }

    val pages: List<Int> get() {
        @Suppress("UNUSED_VARIABLE") val revision = pageRevision
        return (inheritedPages + specs.filter { it.file.name !in hidden }.map(::page) + shortcuts.onGrid().map { it.page }).distinct().sorted().ifEmpty { listOf(1) }
    }

    fun move(item: HomeItem, page: Int) { layout.setPage(item.key, page); if (item !is WidgetItem) shortcuts.move(item.key, page); pageRevision++ }

    fun noRoom(item: HomeItem) {
        val page = pages.firstOrNull { it > item.page && layout.claim(item, itemsOn(it)) } ?: ((pages.maxOrNull() ?: 1) + 1)
        move(item, page)
    }

    fun pin(app: AndroidApp, page: Int) {
        shortcuts.addAndroid(app, page)
        layout.setPage("app:" + app.key, page)
    }

    fun group(app: Shortcut, target: HomeItem) { shortcuts.group(app, target) }
    fun reorder(app: Shortcut, before: String?) { shortcuts.moveBefore(app.key, before) }

    fun show(spec: WidgetSpec) {
        hidden = hidden - spec.file.name
        prefs.edit().putStringSet("hidden", hidden).apply()
    }

    fun remove(item: HomeItem) {
        when (item) {
            is WidgetItem -> { hidden = hidden + item.key; prefs.edit().putStringSet("hidden", hidden).apply() }
            is Shortcut, is Folder -> shortcuts.remove(item.key)
        }
        layout.remove(item.key)
    }

    fun tap(spec: WidgetSpec) {
        if (!authorized()) return
        instances[spec.file.name]?.let { spec.program?.onTap?.run(it.root, emptyList()) }
    }

    fun close() {
        visible = false
        instances.values.forEach { it.stop() }
        instances.clear()
        scope.cancel()
    }
}
