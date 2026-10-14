package dev.omalauncher.taskbar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.omalauncher.AndroidApp
import dev.omalauncher.App
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.StripCell
import dev.omalauncher.ui.StripDrag
import dev.omalauncher.ui.splitdrag.DropZoneOverlay
import dev.omalauncher.ui.splitdrag.OpenPlan
import dev.omalauncher.ui.splitdrag.SplitDrag
import dev.omalauncher.ui.splitdrag.SplitDragSession
import dev.omalauncher.ui.splitdrag.ZoneBox
import dev.omalauncher.ui.splitdrag.launchSteps
import dev.omalauncher.ui.tile
import kotlinx.coroutines.delay

/**
 * The floating taskbar: a handle at the bottom of other apps that pulls up the apps (recent, or pinned and recent),
 * to switch to one or drag it to split the screen, a pop-up or a split half. An accessibility service, so it can draw
 * over apps and see which apps are on screen (their windows, not what's in them); Android lets only the user turn it
 * on. Shows only while the taskbar is on in settings, and never over home, which has its own strip.
 */
class TaskbarService : AccessibilityService() {
    private val runtime get() = (application as App).runtime
    private val owner = OverlayOwner()
    private lateinit var wm: WindowManager
    private val views = mutableListOf<ComposeView>()
    private val session = SplitDragSession()
    private var screen by mutableStateOf<ScreenState>(ScreenState.Unknown)
    private var tray by mutableStateOf(false)
    private var poked by mutableIntStateOf(0)
    private var seen by mutableStateOf(emptyList<String>())
    private var home = emptySet<String>()
    private val prefs by lazy { getSharedPreferences("taskbar", MODE_PRIVATE) }

    override fun onServiceConnected() {
        // The taskbar's drag and split need Android 15 (see SplitDrag.canDrag); below it the service shows nothing.
        if (Build.VERSION.SDK_INT < 35) return
        TaskbarStatus.serviceOn = true
        wm = getSystemService(WindowManager::class.java)
        runtime.start()
        seen = prefs.getString(SEEN, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        home = homePackages()
        owner.start()
        // The drop zones under everything, never touchable; the handle and tray over them.
        add(fullScreen()) { DropZoneOverlay(session) }
        add(bottomBar()) { Bar() }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT < 35 || views.isEmpty()) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let(::personalApp)?.let { app ->
                if (seen.firstOrNull() != app.key) {
                    seen = moveToFront(seen, app.key)
                    prefs.edit().putString(SEEN, seen.joinToString("\n")).apply()
                }
            }
        }
        refresh()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        TaskbarStatus.serviceOn = false
        if (views.isEmpty()) return
        for (v in views) runCatching { wm.removeView(v) }
        views.clear()
        owner.stop()
    }

    /** Reads what's on screen from the app windows, topmost first; left as it was if the windows can't be read. */
    @RequiresApi(35)
    private fun refresh() {
        runCatching {
            val apps =
                windows
                    .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { it.layer }
                    .mapNotNull { w ->
                        val pkg = w.root?.packageName?.toString() ?: return@mapNotNull null
                        val r = Rect().also(w::getBoundsInScreen)
                        AppWindow(pkg, ZoneBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()))
                    }
            val size = wm.currentWindowMetrics.bounds
            screenState(apps, size.width().toFloat(), size.height().toFloat(), home)
        }.onSuccess { screen = it }
    }

    /** The home apps: the default one, and this launcher whatever the default is. */
    private fun homePackages(): Set<String> {
        val default = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return setOfNotNull(default, packageName)
    }

    private fun personalApp(pkg: String): AndroidApp? = runtime.androidApps.apps.firstOrNull { it.component.packageName == pkg && SplitDrag.canDrag(it) }

    private fun add(
        params: WindowManager.LayoutParams,
        content: @Composable () -> Unit,
    ) {
        val view =
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent(content)
            }
        wm.addView(view, params)
        views += view
    }

    @RequiresApi(35)
    private fun fullScreen() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }

    @RequiresApi(35)
    private fun bottomBar() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            // Above the navigation bar or gesture handle: the very bottom is the system's.
            val nav = wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
            y = nav + (8 * resources.displayMetrics.density).toInt()
        }

    /** The handle, or the tray pulled up from it; nothing while the taskbar is off or home is in front. */
    @RequiresApi(35)
    @Composable
    private fun Bar() {
        if (!runtime.taskbar.enabled || screen == ScreenState.Home) return
        if (!tray) {
            Handle()
            return
        }
        // Back down after a while untouched; a touch starts the wait over.
        LaunchedEffect(poked) {
            delay(HIDE_AFTER_MS)
            if (session.label == null) tray = false
        }
        Tray()
    }

    @Composable
    private fun Handle() {
        Box(
            Modifier
                .size(width = 96.dp, height = 28.dp)
                .clickable { tray = true }
                .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -8f) tray = true } },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(width = 64.dp, height = 6.dp).clip(LauncherStyle.shape(3.dp)).background(Palette.accent.copy(alpha = 0.8f)))
        }
    }

    @RequiresApi(35)
    @Composable
    private fun Tray() {
        val settings = runtime.taskbar
        val catalog = runtime.androidApps
        val byKey = catalog.apps.filter { SplitDrag.canDrag(it) }.associateBy { it.key }
        val apps = taskbarApps(settings.mode, settings.pinned, seen, runtime.usage.recent, byKey.keys).mapNotNull { byKey[it] }
        val maxWidth = with(LocalDensity.current) { (wm.currentWindowMetrics.bounds.width().toDp() - 32.dp) }
        LazyRow(
            Modifier
                .widthIn(max = maxWidth)
                .height(64.dp)
                // While an app is dragged the tray stays, only unseen: leaving composition would end the drag.
                .alpha(if (session.label != null) 0f else 1f)
                .tile()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            poked++
                        }
                    }
                },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(apps, key = { it.key }) { app ->
                StripCell(
                    app.label,
                    catalog.icons[app.key],
                    catalog.masks[app.key],
                    onClick = { open(OpenPlan.AsTapped(app)) },
                    actions = { emptyList() },
                    spoken = app.spoken,
                    dragOut = StripDrag(start = { at -> drag(app, at) }, move = { session.move(it) }, end = { session.end(it) }),
                )
            }
        }
    }

    /** Starts dragging [app] from [at] (screen pixels), with zones for what's on screen right now. */
    private fun drag(
        app: AndroidApp,
        at: Offset,
    ) {
        val state = screen
        val canPopUp = SplitDrag.canPopUp(this)
        val oneUi = SplitDrag.isOneUi()
        val dragged = app.component.packageName
        session.begin(
            label = app.label,
            icon = runtime.androidApps.icons[app.key],
            at = at,
            canSplit = true,
            canPopUp = canPopUp,
            oneUi = oneUi,
            layout = { w, h -> taskbarZones(state, dragged, w, h, canPopUp, oneUi) { pkg -> personalApp(pkg)?.label ?: pkg } },
        ) { zone -> planTaskbarOpen(zone, state, app, dragged, ::personalApp)?.let(::open) }
    }

    private fun open(plan: OpenPlan<AndroidApp>) {
        if (plan is OpenPlan.AsTapped) runtime.usage.record(plan.app.key)
        TaskbarTrampoline.start(this, launchSteps(plan))
        tray = false
    }

    private companion object {
        const val SEEN = "seen"
        const val HIDE_AFTER_MS = 4000L
    }
}
