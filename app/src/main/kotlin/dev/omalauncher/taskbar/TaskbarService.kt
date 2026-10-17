package dev.omalauncher.taskbar

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
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
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.omalauncher.ui.splitdrag.ZoneKind
import dev.omalauncher.ui.splitdrag.ZoneBox
import dev.omalauncher.ui.splitdrag.splitSteps
import dev.omalauncher.ui.tile
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

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
    private var barView: ComposeView? = null
    private val session = SplitDragSession()
    private var screen by mutableStateOf<ScreenState>(ScreenState.Unknown)
    private var tray by mutableStateOf(false)
    private var poked by mutableIntStateOf(0)
    private var seen by mutableStateOf(emptyList<String>())
    /** The default home app, unless it's this launcher: ours counts as home only while its home screen is started. */
    private var defaultHome: String? = null
    /** Apps with a home screen, see [launchers]. */
    private var launchers = emptySet<String>()
    private val prefs by lazy { getSharedPreferences("taskbar", MODE_PRIVATE) }

    override fun onServiceConnected() {
        // The taskbar's drag and split need Android 15 (see SplitDrag.canDrag); below it the service shows nothing.
        if (Build.VERSION.SDK_INT < 35) return
        TaskbarStatus.serviceOn = true
        wm = getSystemService(WindowManager::class.java)
        // The launcher's runtime, for the themes and app icons; just that, not Linux or the widgets.
        runtime.startForOverlay()
        seen = prefs.getString(SEEN, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        defaultHome = defaultHome()
        launchers = launchers()
        owner.start()
        // The drop zones under everything, never touchable; the handle and tray over them.
        add(fullScreen()) { DropZoneOverlay(session) }
        barView = add(bottomBar()) { Bar() }
        refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT < 35 || views.isEmpty()) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // The default home can change in settings; resolving it is cheap.
            defaultHome = defaultHome()
            launchers = launchers()
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

    /** Rotated or folded: the bar sits above the navigation bar again, whose height may have changed. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 35 || views.isEmpty()) return
        barView?.let { runCatching { wm.updateViewLayout(it, bottomBar()) } }
    }

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
        barView = null
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
            // Our own windows other than home (Linux, files) can't be relaunched as a split partner: that would open home.
            screenState(apps, size.width().toFloat(), size.height().toFloat(), homePackages(), opaque = launchers)
        }.onSuccess { screen = it }
    }

    /**
     * Apps with a home screen (this launcher, another Omalauncher, One UI Home…): their windows, say a Linux window of the
     * release Omalauncher, can't be a split partner, as relaunching the app would open its home screen instead.
     */
    private fun launchers(): Set<String> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet() + packageName

    /** The home apps: the default one unless it's this launcher, and this launcher only while its home screen is started. */
    private fun homePackages(): Set<String> = setOfNotNull(defaultHome, packageName.takeIf { HomeVisibility.shown })

    private fun defaultHome(): String? =
        packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo
            ?.packageName
            ?.takeIf { it != packageName }

    private fun personalApp(pkg: String): AndroidApp? = runtime.androidApps.apps.firstOrNull { it.component.packageName == pkg && SplitDrag.canDrag(it) }

    private fun add(
        params: WindowManager.LayoutParams,
        content: @Composable () -> Unit,
    ): ComposeView {
        val view =
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent(content)
            }
        wm.addView(view, params)
        views += view
        return view
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
        val hidden = !runtime.taskbar.enabled || screen == ScreenState.Home
        // Hidden, the tray goes back down: it comes back as the handle.
        LaunchedEffect(hidden) { if (hidden) tray = false }
        if (hidden) return
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
        // The screen width from the configuration: the bar window itself only wraps its content.
        val maxWidth = LocalConfiguration.current.screenWidthDp.dp - 32.dp
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
                    onClick = { open(OpenPlan.AsTapped(app), app) },
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
            layout = { w, h ->
                taskbarZones(state, dragged, w, h, canPopUp, oneUi, labelOf = { pkg -> personalApp(pkg)?.label ?: pkg }, canPartner = { personalApp(it) != null })
            },
        ) { zone ->
            if (state is ScreenState.Split) {
                // Replacing a half: the trampoline works out from where it lands how to open the app in that half.
                if (zone.kind in SPLIT_KINDS) replace(app, zone.preview)
                return@begin
            }
            planTaskbarOpen(zone, state, app, ::personalApp)?.let { open(it, app) }
        }
    }

    /** Opens [app] in the split half at [half], replacing what's there; see [TaskbarTrampoline.replace]. */
    private fun replace(
        app: AndroidApp,
        half: ZoneBox,
    ) {
        runtime.usage.record(app.key)
        TaskbarTrampoline.replace(this, app, half)
        tray = false
    }

    /**
     * Opens [plan]; [app] is the one tapped or dragged, which counts as opened whatever else the plan brings forward.
     * Full screen and pop-up start straight from the service; only a split needs the trampoline, as LAUNCH_ADJACENT needs
     * a source activity.
     */
    private fun open(
        plan: OpenPlan<AndroidApp>,
        app: AndroidApp,
    ) {
        runtime.usage.record(app.key)
        when (plan) {
            is OpenPlan.Split -> TaskbarTrampoline.start(this, splitSteps(plan))
            is OpenPlan.AsTapped -> startDirect(plan.app, null)
            is OpenPlan.Windowed -> startDirect(plan.app, plan.bounds)
        }
        tray = false
    }

    /** Starts [app] from the service, full screen, or as a pop-up at [bounds]. */
    private fun startDirect(
        app: AndroidApp,
        bounds: ZoneBox?,
    ) {
        runCatching {
            val intent =
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val options =
                bounds?.let {
                    ActivityOptions.makeBasic().setLaunchBounds(Rect(it.left.roundToInt(), it.top.roundToInt(), it.right.roundToInt(), it.bottom.roundToInt())).toBundle()
                }
            startActivity(intent, options)
        }.onFailure { Log.w("TaskbarService", "Could not open ${app.component}", it) }
    }

    private companion object {
        const val SEEN = "seen"
        const val HIDE_AFTER_MS = 4000L
        val SPLIT_KINDS = setOf(ZoneKind.SplitLeft, ZoneKind.SplitRight, ZoneKind.SplitTop, ZoneKind.SplitBottom)
    }
}
