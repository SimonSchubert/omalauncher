package dev.omalauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.omalauncher.R
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.ColorFilter
import dev.omalauncher.AndroidApp
import dev.omalauncher.AndroidShortcut
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.render.LocalMenuOpened

/** One app the drawer lists: an owl app, a Linux or Android app, or a shortcut. */
internal sealed interface AppHit {
    /** Stable id, also the [dev.omalauncher.AppUsage] key. */
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

/** The shortcuts an Android app offers above its menu's actions; null for anything else. */
private fun AppHit.shortcutMenu(handlers: AppHandlers): ShortcutMenu? = (this as? AppHit.Android)?.let { handlers.shortcuts(it.app) }

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

private val ACTION_LABELS =
    mapOf(
        "pin" to R.string.drawer_action_pin,
        "info" to R.string.drawer_action_info,
        "uninstall" to R.string.drawer_action_uninstall,
        "edit" to R.string.home_edit,
        "share" to R.string.home_share,
        "remove" to R.string.home_remove,
    )

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

/** The long-press menu of an app cell, labeled as stock launchers label it. */
@Composable
private fun AppHit.actions(handlers: AppHandlers): List<Pair<String, () -> Unit>> =
    actionWords().map { word -> (ACTION_LABELS[word]?.let { stringResource(it) } ?: word) to { act(word, handlers) } }

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

/** Height of the recent apps strip; home keeps this much room for it above the bar, even while it's empty. */
val RecentStripHeight = 56.dp

/**
 * The apps opened lately, on home right above the bar, scrolling sideways; [vertical] stands it up
 * beside the grid in landscape, scrolling down. A home tile like the others: frame, fill and gaps,
 * or bare when home icons are. Can be hidden in themes.
 */
@Composable
internal fun RecentStrip(
    runtime: Runtime,
    handlers: AppHandlers,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    /**
     * Where keys go from its apps toward the grid: Up, or stood up on [side], across to it. On each, as the list's own
     * focus group keeps it from reaching them from outside.
     */
    home: FocusRequester? = null,
    side: HomeSide? = null,
) {
    // Not before every kind of app is listed: the ones missing would push in as their kind arrived.
    val apps = if (runtime.appsListed) recentApps(runtime, 20) else emptyList()
    // Nothing opened yet: no strip, but its room stays, so home doesn't shift when the first app shows up.
    if (apps.isEmpty()) {
        Spacer(if (vertical) modifier.width(RecentStripHeight) else modifier.height(RecentStripHeight))
        return
    }
    val bare = LauncherStyle.bareAppIcons
    val listState = rememberLazyListState()
    // Back on home, the strip starts at the app opened last, wherever it was scrolled to before.
    LaunchedEffect(runtime.usage.settled) { listState.scrollToItem(0) }
    Box(
        modifier
            .then(if (vertical) Modifier.fillMaxHeight().width(RecentStripHeight) else Modifier.padding(horizontal = EdgeGap).fillMaxWidth().height(RecentStripHeight))
            .then(if (bare) Modifier else Modifier.tile()),
        contentAlignment = if (vertical) Alignment.TopCenter else Alignment.CenterStart,
    ) {
        val cells: LazyListScope.() -> Unit = {
            items(apps, key = { it.key }) { hit ->
                val (icon, mask) = hit.icons(runtime)
                Box(if (home != null) Modifier.focusProperties { toHome(side, home) } else Modifier) {
                    StripCell(hit.label, icon, mask, onClick = { handlers.launch(hit) }, actions = { hit.actions(handlers) }, shortcuts = { hit.shortcutMenu(handlers) }, spoken = hit.spoken, paused = hit.paused(runtime))
                }
            }
        }
        if (vertical) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, content = cells)
        } else {
            LazyRow(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = cells)
        }
    }
}

/** An app in the recent strip, drawn like a pinned app's tile: the icon alone, or on a badge when bare. */
@Composable
private fun StripCell(
    label: String,
    icon: ImageBitmap?,
    mask: ImageBitmap?,
    onClick: () -> Unit,
    /** Built when the menu opens: a fresh list of lambdas would keep the cell from skipping recomposition. */
    actions: @Composable () -> List<Pair<String, () -> Unit>>,
    /** Also built when the menu opens. */
    shortcuts: () -> ShortcutMenu? = { null },
    /** As in [AppHit.spoken]. */
    spoken: String = label,
    /** As in [AppHit.paused]: drawn dimmed, like stock launchers. */
    paused: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val mono = LauncherStyle.monochromeIcons
    val bare = LauncherStyle.bareAppIcons
    val size = 40.dp
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (paused) PausedAlpha else 1f)
            .clip(LauncherStyle.shape(12.dp))
            .menuLongPresses { menuOpen = true }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = if (mono) mask else icon
        when {
            // Bare silhouettes and initials sit on a badge, as pinned apps do, so they read on any wallpaper.
            bare && (mono || bmp == null) ->
                IconBadge(size) {
                    if (bmp != null) {
                        Image(bmp, spoken, Modifier.size(size * 0.75f), colorFilter = ColorFilter.tint(Palette.accent))
                    } else {
                        BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Body))
                    }
                }
            bmp != null -> Image(bmp, spoken, Modifier.size(size * if (mono) 0.85f else 1f), colorFilter = if (mono) ColorFilter.tint(Palette.accent) else null)
            else -> BasicText(initials(label), style = Mono.copy(color = Palette.accent, fontSize = TextSize.Large))
        }
        if (menuOpen) AppActions(actions(), shortcuts, onDismiss = { menuOpen = false })
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

/** The long-press popup: sits above the cell (below it when there's no room), closes on outside tap. */
@Composable
private fun AppActions(
    actions: List<Pair<String, () -> Unit>>,
    shortcuts: () -> ShortcutMenu?,
    onDismiss: () -> Unit,
) {
    val opened = LocalMenuOpened.current
    LaunchedEffect(Unit) { opened() }
    val (menu, offered) = offeredShortcuts(Unit, shortcuts)
    if (offered == null) return
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    // From the screen's sides, as far as home's tiles are.
    val edge = with(LocalDensity.current) { EdgeGap.roundToPx() }
    val position =
        remember(gap, edge) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(edge, (windowSize.width - edge - popupContentSize.width).coerceAtLeast(edge))
                    val above = anchorBounds.top - popupContentSize.height - gap
                    val y = if (above >= 0) above else anchorBounds.bottom + gap
                    return IntOffset(x, y)
                }
            }
        }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(Modifier.holdsKeys()) {
            MenuItems(actions, onDismiss, minWidth = 160.dp, itemPadding = 12.dp) {
                if (menu != null) ShortcutRows(offered, menu, onDismiss, TextSize.Item)
            }
        }
    }
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
