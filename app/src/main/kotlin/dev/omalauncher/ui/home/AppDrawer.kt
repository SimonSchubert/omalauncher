package dev.omalauncher.ui.home

import androidx.compose.runtime.key
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import dev.omalauncher.widgets.render.WidgetStrip
import dev.omalauncher.widgets.runtime.WidgetInstance
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Obj
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.ColorFilter
import dev.omalauncher.apps.AndroidApp
import dev.omalauncher.apps.AndroidShortcut
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.runtime.NotificationDots
import dev.omalauncher.ui.EdgeGap
import dev.omalauncher.ui.HomeGap
import dev.omalauncher.ui.Icons
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.TextSize
import dev.omalauncher.ui.leavesUpDown

/** One app the drawer lists: an owl app, a Linux or Android app, or a shortcut. */
internal sealed interface AppHit {
    /** Stable id, also the [dev.omalauncher.apps.AppUsage] key. */
    val key: String
    val label: String

    /** What screen readers say, to tell a work app or a clone from the personal one of the same name. */
    val spoken: String get() = label

    /**
     * Another name search matches: a work app's own name with "work" added, so "work" or "whatsapp work"
     * finds it; a clone's with "clone".
     */
    val alias: String? get() = null

    /** In a paused work profile: shown dimmed, and opening it turns work apps back on. */
    fun paused(runtime: Runtime) = false

    /** It has a notification Android would put a dot on it for. */
    val hasDot: Boolean get() = false

    data class Linux(
        val app: LinuxApp,
    ) : AppHit {
        override val key get() = "l:" + app.id
        override val label get() = app.name
    }

    data class Android(
        val app: AndroidApp,
    ) : AppHit {
        override val key get() = "a:" + app.key
        override val label get() = app.label
        override val spoken get() = app.spoken
        override val alias get() = if (app.profile != null) "${app.label} ${if (app.clone) "clone" else "work"}" else null

        override val hasDot get() = NotificationDots.has(app.component.packageName, app.profile)

        override fun paused(runtime: Runtime) = runtime.androidApps.paused(app.profile)
    }

    /** An owl file that's an app: it opens full screen over home. */
    data class Owl(
        val spec: WidgetSpec,
    ) : AppHit {
        override val key get() = "o:" + spec.file.name
        override val label get() = spec.name
    }

    /** A shortcut an app put on the home screen, such as a website installed from a browser. */
    data class Shortcut(
        val shortcut: AndroidShortcut,
    ) : AppHit {
        override val key get() = "s:" + shortcut.key
        override val label get() = shortcut.label
        override val spoken get() = shortcut.spoken
        override val alias get() = if (shortcut.profile != null) "${shortcut.label} ${if (shortcut.clone) "clone" else "work"}" else null

        override val hasDot get() = NotificationDots.has(shortcut.ref.packageName, shortcut.profile, shortcut.ref.id)

        override fun paused(runtime: Runtime) = runtime.androidApps.paused(shortcut.profile)
    }
}

internal fun allApps(runtime: Runtime): List<AppHit> =
    runtime.widgets.apps().map { AppHit.Owl(it) } + runtime.linuxApps.apps.map { AppHit.Linux(it) } + runtime.androidApps.apps.map { AppHit.Android(it) } +
        runtime.androidApps.shortcuts.map { AppHit.Shortcut(it) }

/** What the app cells do: open, pin to home, the Android app's info and uninstall, removing a shortcut, and an owl app's edit, share and remove. */
internal class AppHandlers(
    val launch: (AppHit) -> Unit,
    val pin: (AppHit) -> Unit,
    val info: (AndroidApp) -> Unit,
    val uninstall: (AndroidApp) -> Unit,
    val remove: (AndroidShortcut) -> Unit,
    /** An Android app's shortcuts, for its long-press menu. */
    val shortcuts: (AndroidApp) -> ShortcutMenu,
    /** Opens the default agent on an owl app's file. */
    val edit: (WidgetSpec) -> Unit,
    /** Shares an owl app as a link or QR code, as a widget is shared. */
    val share: (WidgetSpec) -> Unit,
    /** Removes an owl app's file, and its icon from home. */
    val removeOwl: (WidgetSpec) -> Unit,
)

/** What `android.drawer` and `android.recent` call its kind. */
internal val AppHit.kind: String
    get() =
        when (this) {
            is AppHit.Android -> "android"
            is AppHit.Linux -> "linux"
            is AppHit.Owl -> "owl"
            is AppHit.Shortcut -> "shortcut"
        }

/** `work` or `clone` for an app or shortcut in a profile beside the launcher's own, else null. */
internal val AppHit.profileKind: String?
    get() =
        when (this) {
            is AppHit.Android -> app.profile?.let { if (app.clone) "clone" else "work" }
            is AppHit.Shortcut -> shortcut.profile?.let { if (shortcut.clone) "clone" else "work" }
            else -> null
        }

/**
 * The long-press menu's entries, as the words `appAction(word, key)` takes: pin to home, for owl apps editing,
 * sharing and removing, for Android apps their info and uninstall, and for shortcuts removing them.
 */
internal fun AppHit.actionWords(): List<String> =
    when (this) {
        is AppHit.Linux -> listOf("pin")
        is AppHit.Owl -> listOf("pin", "edit", "share", "remove")
        is AppHit.Android -> listOfNotNull("pin", "info", if (app.system) null else "uninstall")
        is AppHit.Shortcut -> listOf("pin", "remove")
    }

/** Runs the menu entry [word], from the menu or a widget's `appAction(...)`; one the app's menu doesn't offer does nothing. */
internal fun AppHit.act(
    word: String,
    handlers: AppHandlers,
) {
    if (word !in actionWords()) return
    when (word) {
        "pin" -> handlers.pin(this)
        "info" -> (this as? AppHit.Android)?.let { handlers.info(it.app) }
        "uninstall" -> (this as? AppHit.Android)?.let { handlers.uninstall(it.app) }
        "remove" ->
            when (this) {
                is AppHit.Shortcut -> handlers.remove(shortcut)
                is AppHit.Owl -> handlers.removeOwl(spec)
                else -> {}
            }
        "edit" -> (this as? AppHit.Owl)?.let { handlers.edit(it.spec) }
        "share" -> (this as? AppHit.Owl)?.let { handlers.share(it.spec) }
    }
}

/** The app's icon and its silhouette, each null until loaded or if it has none. */
internal fun AppHit.icons(runtime: Runtime): Pair<ImageBitmap?, ImageBitmap?> =
    when (this) {
        is AppHit.Linux -> runtime.linuxApps.icons[app.id] to runtime.linuxApps.masks[app.id]
        is AppHit.Owl -> runtime.owlIcon(spec, mono = false) to runtime.owlIcon(spec, mono = true)
        is AppHit.Android -> runtime.androidApps.icons[app.key] to runtime.androidApps.masks[app.key]
        is AppHit.Shortcut -> runtime.androidApps.icons[shortcut.key] to runtime.androidApps.masks[shortcut.key]
    }

/** Opens the app and counts it for the recent strip and search, wherever it was tapped. */
internal fun AppHit.open(runtime: Runtime) {
    runtime.usage.record(key)
    when (this) {
        is AppHit.Linux -> runtime.launch(app)
        is AppHit.Android -> runtime.androidApps.open(app)
        is AppHit.Shortcut -> runtime.androidApps.open(shortcut)
        is AppHit.Owl -> runtime.openOwlApp(spec.file.name)
    }
}

/**
 * The icon of the app whose [AppHit.key] is [key], or its silhouette with [mono]; null until loaded or if it
 * has none. Looked up by kind rather than through [allApps]: widgets ask for it while drawing.
 */
internal fun appIconOf(
    runtime: Runtime,
    key: String,
    mono: Boolean,
): ImageBitmap? {
    val id = key.substringAfter(':')
    return when (key.substringBefore(':')) {
        "a", "s" -> (if (mono) runtime.androidApps.masks else runtime.androidApps.icons)[id]
        "l" -> (if (mono) runtime.linuxApps.masks else runtime.linuxApps.icons)[id]
        "o" -> runtime.widgets.apps().firstOrNull { it.file.name == id }?.let { runtime.owlIcon(it, mono) }
        else -> null
    }
}

/** The stock strip's height, which home keeps for the strip above the bar until the strip has measured its own. */
val RecentStripHeight = 56.dp

/**
 * The room home keeps for the strip: as tall as it measured above the bar, or as wide as it measured [vertical] beside
 * the grid; [RecentStripHeight] until it has. The file decides, so home's tiles make way for a taller strip.
 */
@Composable
internal fun stripRoom(
    runtime: Runtime,
    vertical: Boolean,
): Dp {
    val px = if (vertical) runtime.widgets.stripWidthPx else runtime.widgets.stripHeightPx
    // 0 is a strip that draws nothing, which gives its room back.
    return if (px >= 0) with(LocalDensity.current) { px.toDp() } else RecentStripHeight
}

/**
 * The most of the screen the strip may take: a third of its height above the bar, a quarter of its width beside the
 * grid. Whatever a file asks for, home's tiles and the bar stay on screen; a strip laid out only for portrait, which
 * would fill the whole side in landscape, scrolls in a quarter of it instead.
 */
@Composable
private fun stripMax(vertical: Boolean): Dp {
    val config = LocalConfiguration.current
    return if (vertical) (config.screenWidthDp / 4).dp else (config.screenHeightDp / 3).dp
}

/**
 * The strip above the bar on home, strip.owl (`strip "…" { }`, else the stock one): the apps opened lately, scrolling
 * sideways, as the stock file draws them. [vertical] stands it up beside the grid in landscape, which `android.strip`
 * tells the file. Can be hidden in settings › style.
 */
@Composable
internal fun RecentStrip(
    runtime: Runtime,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    /**
     * Where keys go from its apps toward the grid: Up, or stood up on [side], across to it. Compose's own search doesn't
     * find home's tiles from the strip, so an arrow that has nowhere further to go in the strip goes there.
     */
    home: FocusRequester? = null,
    side: HomeSide? = null,
) {
    val widgets = runtime.widgets
    LaunchedEffect(widgets, vertical) { widgets.stripVertical = vertical }
    val inst = widgets.stripInstance()
    // Not while the file still lays itself out for the other way: `vertical` in android.strip reaches it some frames
    // after home has turned, and a Column as tall as the screen above the bar, or a Row as wide beside the grid, would
    // take all the strip may have and squeeze home's tiles until it did. Its room stays as it last measured this way.
    val turning = ((inst?.androidValue("strip") as? Obj)?.fields?.get("vertical") as? Bool)?.let { it.v != vertical } == true
    if (inst == null || turning) {
        val room = stripRoom(runtime, vertical)
        Spacer(if (vertical) modifier.width(room) else modifier.height(room))
        return
    }
    val focusManager = LocalFocusManager.current
    val inside = remember { booleanArrayOf(false) }
    val (towardHome, direction) =
        when (side) {
            null -> Key.DirectionUp to FocusDirection.Up
            HomeSide.Left -> Key.DirectionRight to FocusDirection.Right
            HomeSide.Right -> Key.DirectionLeft to FocusDirection.Left
        }
    val keys =
        if (home == null) {
            Modifier
        } else {
            Modifier.onFocusChanged { inside[0] = it.hasFocus }.onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || e.key != towardHome) return@onKeyEvent false
                // Further within the strip first, as in a strip of several rows; else on to home's tiles.
                if (!(focusManager.moveFocus(direction) && inside[0])) home.requestFocus()
                true
            }
        }
    // Back on home, the strip starts again at its beginning, wherever it was scrolled to before: the app opened last
    // is first now.
    key(runtime.usage.settled) {
        WidgetStrip(
            inst,
            vertical,
            modifier
                .then(if (vertical) Modifier.fillMaxHeight() else Modifier.padding(horizontal = EdgeGap).fillMaxWidth())
                .then(if (vertical) Modifier.widthIn(max = stripMax(true)) else Modifier.heightIn(max = stripMax(false)))
                .clipToBounds()
                .onSizeChanged { widgets.stripMeasured(vertical, if (vertical) it.width else it.height) }
                .then(keys),
        )
    }
}

/** The strip where home has it, above the bar at the bottom of a screen, for owl-check's picture of it. */
@Composable
internal fun StripPreview(inst: WidgetInstance) {
    Box(
        Modifier.fillMaxSize().background(Palette.background).safeDrawingPadding().padding(bottom = PromptBarHeight + HomeGap + EdgeGap),
        contentAlignment = Alignment.BottomCenter,
    ) {
        WidgetStrip(inst, vertical = false, Modifier.padding(horizontal = EdgeGap).fillMaxWidth())
    }
}

/** [limit] apps the user knows, for the Style menu's previews: the recent ones, topped up from the drawer's. */
internal fun familiarApps(
    runtime: Runtime,
    limit: Int,
): List<AppHit> = (recentApps(runtime, limit) + allApps(runtime)).distinctBy { it.key }.take(limit)

/** The apps opened lately, last opened first; ones not installed (or not loaded yet) are skipped. */
internal fun recentApps(
    runtime: Runtime,
    limit: Int,
): List<AppHit> {
    // By kind, as appIconOf does, rather than through allApps: the strip asks on each recomposition, for a handful of keys.
    val android = runtime.androidApps
    val owl by lazy { runtime.widgets.apps() }
    return runtime.usage.recent
        .asSequence()
        .mapNotNull { key ->
            val id = key.substringAfter(':')
            when (key.substringBefore(':')) {
                "a" -> android.byKey[id]?.let(AppHit::Android)
                "s" -> android.shortcuts.lastOrNull { it.key == id }?.let(AppHit::Shortcut)
                "l" -> runtime.linuxApps.apps.lastOrNull { it.id == id }?.let(AppHit::Linux)
                "o" -> owl.lastOrNull { it.file.name == id }?.let(AppHit::Owl)
                else -> null
            }
        }.take(limit)
        .toList()
}

/** Pulling down past the top of a list in the drawer drags it closed, like stock drawers; for its list, and a panel's in its place. */
@Composable
internal fun rememberDrawerPull(state: LauncherState): NestedScrollConnection {
    val screenHeight = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    return remember(state, screenHeight) {
        object : NestedScrollConnection {
            override fun onPreScroll(
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // Half-closed and scrolling back up: reopen before scrolling the list.
                if (source != NestedScrollSource.UserInput || available.y >= 0f || state.drawer.value >= 1f) return Offset.Zero
                state.dragBy(-available.y / screenHeight)
                return Offset(0f, available.y)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                state.dragBy(-available.y / screenHeight)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (state.drawer.value >= 1f) return Velocity.Zero
                state.settle(available.y)
                return available
            }
        }
    }
}

@Composable
internal fun SearchField(
    query: String,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
    placeholder: String,
) {
    val style = Mono.copy(fontSize = TextSize.Item)
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier =
            Modifier
                .leavesUpDown()
                .fillMaxWidth()
                .clip(LauncherStyle.shape(24.dp))
                .background(Palette.surface)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) BasicText(placeholder, style = style.copy(color = Palette.muted))
                inner()
            }
        },
    )
}

/** The gear that opens settings, in the drawer and the home menu; a dot while system updates wait. */
@Composable
internal fun SettingsButton(
    runtime: Runtime,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(LauncherStyle.shape(22.dp))
            .background(Palette.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(Icons.Gear, stringResource(R.string.home_settings), Modifier.size(22.dp), colorFilter = ColorFilter.tint(Palette.accent))
        // Updates are waiting: settings › system updates lists them.
        if ((runtime.updates.pending?.count ?: 0) > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(8.dp)
                    .background(Palette.red, CircleShape),
            )
        }
    }
}
