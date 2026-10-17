package dev.omalauncher.taskbar

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
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
import dev.omalauncher.InstantActivity
import dev.omalauncher.LinuxWindowActivity
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.linux.matches
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
import dev.omalauncher.wayland.XdgToplevel
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

    /** This launcher's Linux windows on screen when [screen] was read, by [LinuxWindow.id]. */
    private var linuxOnScreen = emptyMap<String, LinuxWindow>()

    /** The lock screen is up (or the screen is off): the taskbar shows nothing there, so it opens no apps past it. */
    private var locked by mutableStateOf(false)

    /** Screen off, on and unlocked: the lock screen comes and goes without an app window changing. */
    private val lockWatch =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(
                context: android.content.Context,
                intent: Intent,
            ) = updateLocked()
        }
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
        seen = prefs.getString(SEEN, null)?.split('\n')?.filter { it.isNotEmpty() }?.map(::savedKey)?.distinct() ?: emptyList()
        defaultHome = defaultHome()
        launchers = launchers()
        owner.start()
        // The drop zones under everything, never touchable; the handle and tray over them.
        add(fullScreen()) { DropZoneOverlay(session) }
        barView = add(bottomBar()) { Bar() }
        registerReceiver(
            lockWatch,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        updateLocked()
        refresh()
    }

    private fun updateLocked() {
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        locked = keyguard.isKeyguardLocked || display?.state == android.view.Display.STATE_OFF
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (Build.VERSION.SDK_INT < 35 || views.isEmpty()) return
        updateLocked()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // The default home can change in settings; resolving it is cheap.
            defaultHome = defaultHome()
            launchers = launchers()
            val pkg = event.packageName?.toString()
            val key = if (pkg == packageName) linuxAppInFront(event.className?.toString())?.let { linuxKey(it.id) } else pkg?.let(::personalApp)?.let { usageKey(it.key) }
            if (key != null && seen.firstOrNull() != key) {
                seen = moveToFront(seen, key)
                prefs.edit().putString(SEEN, seen.joinToString("\n")).apply()
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
        // Its plans read the screen from here.
        runtime.cancelWindowPlans()
        if (views.isEmpty()) return
        runCatching { unregisterReceiver(lockWatch) }
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
                        AppWindow(pkg, ZoneBox(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), w.title?.toString()?.takeIf { pkg == packageName })
                    }
            val size = wm.currentWindowMetrics.bounds
            // Only ours can be Linux windows; reading the tasks otherwise is wasted.
            val linux = if (apps.any { it.packageName == packageName }) linuxTasks().map { it.window } else emptyList()
            // Our own windows other than home and Linux (Files, settings) can't be relaunched as a split partner: that would open home.
            linux.associateBy { it.id } to
                screenState(apps, size.width().toFloat(), size.height().toFloat(), homePackages(), opaque = launchers, own = packageName, linux = linux)
        }.onSuccess { (linux, state) ->
            linuxOnScreen = linux
            screen = state
        }
    }

    /** A Linux window of ours on screen, in its task [taskId], which [intent] opened. */
    private class LinuxTask(
        val window: LinuxWindow,
        val taskId: Int,
        val intent: Intent,
    )

    /** This launcher's Linux window tasks, those opened with an `omarchy://window/…` intent: the [visible] ones, or all. */
    @RequiresApi(35)
    private fun linuxTasks(visible: Boolean = true): List<LinuxTask> =
        getSystemService(ActivityManager::class.java).appTasks.mapNotNull { task ->
            // A task may go while it's being read.
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@mapNotNull null
            val intent = info.baseIntent
            val handle = intent.data?.takeIf { it.scheme == LINUX_SCHEME }?.lastPathSegment ?: return@mapNotNull null
            if (visible && !info.isVisible) return@mapNotNull null
            LinuxTask(LinuxWindow(handle, info.taskDescription?.label ?: "Linux"), info.taskId, intent)
        }

    /**
     * The Linux app whose window of ours just came to the front ([className] is the system's name for the activity that
     * did; see [frontTask]), so the tray lists it as recent as an Android app seen there; null for our other screens, or a
     * window of no app.
     */
    @RequiresApi(35)
    private fun linuxAppInFront(className: String?): LinuxApp? {
        if (!runtime.bootstrap.isInstalled) return null
        val task = frontTask(linuxTasks(), className, LinuxWindowActivity::class.java.name) { it.intent.component?.className } ?: return null
        val window = runtime.wm.windowByHandle(task.window.handle) ?: return null
        val names = listOf(runtime.displayName(window.appId, window.title), window.title)
        return appOfWindow(runtime.linuxApps.apps, window.appId, names, LinuxApp::matches) { it.name }
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
        val hidden = !runtime.taskbar.enabled || screen == ScreenState.Home || locked
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
        val apps = trayApps()
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
            items(apps, key = { it.key }) { entry ->
                when (entry) {
                    is TrayApp.Android -> {
                        val app = entry.app
                        StripCell(
                            app.label,
                            runtime.androidApps.icons[app.key],
                            runtime.androidApps.masks[app.key],
                            onClick = { open(OpenPlan.AsTapped(app), app) },
                            actions = { emptyList() },
                            spoken = app.spoken,
                            dragOut = StripDrag(start = { at -> drag(app, at) }, move = { session.move(it) }, end = { session.end(it) }),
                        )
                    }
                    is TrayApp.Linux -> {
                        val app = entry.app
                        StripCell(
                            app.name,
                            runtime.linuxApps.icons[app.id],
                            runtime.linuxApps.masks[app.id],
                            onClick = { open(app) },
                            actions = { emptyList() },
                            spoken = "${app.name}, Linux app",
                            dragOut = StripDrag(start = { at -> drag(app, at) }, move = { session.move(it) }, end = { session.end(it) }),
                        )
                    }
                }
            }
        }
    }

    /**
     * What the tray lists (see [taskbarApps]): Android apps that can be split, and Linux apps while Linux is installed
     * and has them.
     */
    private fun trayApps(): List<TrayApp> {
        val settings = runtime.taskbar
        val byKey = HashMap<String, TrayApp>()
        for (app in runtime.androidApps.apps) if (SplitDrag.canDrag(app)) byKey[usageKey(app.key)] = TrayApp.Android(app)
        if (runtime.bootstrap.isInstalled) for (app in runtime.linuxApps.apps) byKey[linuxKey(app.id)] = TrayApp.Linux(app)
        return taskbarApps(settings.mode, settings.pinned, seen, runtime.usage.recent, byKey.keys).mapNotNull { byKey[it] }
    }

    /** Starts dragging [app] from [at] (screen pixels), with zones for what's on screen right now. */
    @RequiresApi(35)
    private fun drag(
        app: AndroidApp,
        at: Offset,
    ) {
        // A Linux app dropped earlier mustn't bring its split back over what the user does now.
        runtime.cancelWindowPlans()
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
                taskbarZones(state, dragged, w, h, canPopUp, oneUi, labelOf = ::labelOf, canPartner = { isLinuxWindow(it) || personalApp(it) != null })
            },
        ) { zone ->
            if (state is ScreenState.Split) {
                // Replacing a half: the trampoline works out from where it lands how to open the app in that half.
                if (zone.kind in SPLIT_KINDS) replace(app, zone.preview)
                return@begin
            }
            if (state is ScreenState.Single && isLinuxWindow(state.packageName) && zone.kind in SPLIT_KINDS) {
                nextToLinux(app, zone.kind, state.packageName)
                return@begin
            }
            planTaskbarOpen(zone, state, app, ::personalApp)?.let { open(it, app) }
        }
    }

    /** What a split half's "Replace …" calls the app (or Linux window) [id] in it. */
    private fun labelOf(id: String): String =
        linuxOnScreen[id]?.label
            ?: personalApp(id)?.label
            ?: runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(id, 0)).toString() }.getOrDefault(id)

    /**
     * Opens [app], dropped in a split zone of [kind], next to the Linux window [id]; see [splitHalves]. Its task is
     * looked up again: it may have gone since the drag began, and then the app opens full screen.
     */
    @RequiresApi(35)
    private fun nextToLinux(
        app: AndroidApp,
        kind: ZoneKind,
        id: String,
    ) {
        val task = linuxTasks().firstOrNull { it.window.id == id } ?: return open(OpenPlan.AsTapped(app), app)
        val steps = splitHalves(kind, SplitHalf.App(app), SplitHalf.Task(task.taskId, reopen(task.intent))) ?: return
        runtime.usage.record(usageKey(app.key))
        TaskbarTrampoline.start(this, steps)
        tray = false
    }

    /** A new activity for the Linux window [intent] opened, in a task of its own. */
    private fun reopen(intent: Intent) = Intent(intent).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    /** Starts dragging the Linux [app] from [at] (screen pixels), with the zones an Android app gets. */
    @RequiresApi(35)
    private fun drag(
        app: LinuxApp,
        at: Offset,
    ) {
        runtime.cancelWindowPlans()
        val state = screen
        // Linux windows open full screen or in a split, never as a pop-up.
        val canPopUp = false
        val oneUi = SplitDrag.isOneUi()
        // Its window, as the screen names it, can't be split with itself; an app with no window can go next to anything.
        val dragged = runtime.windowOf(app)?.let(runtime.wm::handleOf)?.let { LinuxWindow(it, app.name).id } ?: linuxKey(app.id)
        session.begin(
            label = app.name,
            icon = runtime.linuxApps.icons[app.id],
            at = at,
            canSplit = true,
            canPopUp = canPopUp,
            oneUi = oneUi,
            layout = { w, h ->
                taskbarZones(state, dragged, w, h, canPopUp, oneUi, labelOf = ::labelOf, canPartner = { isLinuxWindow(it) || personalApp(it) != null })
            },
        ) { zone ->
            when {
                zone.kind !in SPLIT_KINDS -> open(app)
                state is ScreenState.Split -> replace(app, zone.preview)
                state is ScreenState.Single -> nextTo(app, zone.kind, state.packageName)
                else -> open(app)
            }
        }
    }

    /** Opens the Linux [app] as the launcher does, a window it has coming back; it counts as opened. */
    private fun open(app: LinuxApp) {
        runtime.cancelWindowPlans()
        runtime.usage.record(linuxKey(app.id))
        runtime.launch(app)
        tray = false
    }

    /**
     * Opens the Linux [app], dropped in a split zone of [kind], next to the app or Linux window [partner] on screen; see
     * [splitHalves]. A window the app has goes there, its task brought forward or a new activity for it; an app without
     * one starts, and its first window goes there once it shows up (see [dev.omalauncher.Runtime.launch]). The app just
     * opens if the partner has gone since the drag began, or the app opens no Linux window (Files, the editor).
     */
    @RequiresApi(35)
    private fun nextTo(
        app: LinuxApp,
        kind: ZoneKind,
        partner: String,
    ) {
        val half: SplitHalf<AndroidApp, Intent> =
            (
                if (isLinuxWindow(partner)) {
                    linuxTasks().firstOrNull { it.window.id == partner }?.let { SplitHalf.Task(it.taskId, reopen(it.intent)) }
                } else {
                    personalApp(partner)?.let { SplitHalf.App(it) }
                }
            ) ?: return open(app)
        if (!runtime.opensLinuxWindow(app)) return open(app)
        runtime.usage.record(linuxKey(app.id))
        tray = false
        val window = runtime.windowOf(app)
        if (window == null) {
            // The service may be gone by the time the window shows up.
            val context = applicationContext
            val dropped = screen
            runtime.launch(app) { intent ->
                val steps = splitHalves(kind, SplitHalf.Fresh(intent), half)
                if (steps != null && planStillFits(dropped, screen)) TaskbarTrampoline.start(context, steps) else InstantActivity.startInstantly(context, intent)
            }
            return
        }
        // A window with no task yet is having its activity opened: it comes up on its own, and the app mustn't run again.
        val task = taskOf(window) ?: return
        splitHalves(kind, SplitHalf.Task(task.taskId, reopen(task.intent)), half)?.let { TaskbarTrampoline.start(this, it) }
    }

    /**
     * Opens the Linux [app] in the split half at [half], replacing what's there; see [TaskbarTrampoline.replace]. A window
     * it has gets a new activity there, and its old task goes; an app without one starts, and its first window goes
     * there once it shows up.
     */
    @RequiresApi(35)
    private fun replace(
        app: LinuxApp,
        half: ZoneBox,
    ) {
        if (!runtime.opensLinuxWindow(app)) return open(app)
        runtime.usage.record(linuxKey(app.id))
        tray = false
        val window = runtime.windowOf(app)
        if (window == null) {
            val context = applicationContext
            val dropped = screen
            runtime.launch(app) { intent ->
                if (planStillFits(dropped, screen)) TaskbarTrampoline.replace(context, intent, half, leaving = null) else InstantActivity.startInstantly(context, intent)
            }
            return
        }
        // As in nextTo: its activity is on its way.
        val task = taskOf(window) ?: return
        TaskbarTrampoline.replace(this, reopen(task.intent), half, leaving = task.taskId)
    }

    /** The task of the Linux [window], on screen or not; null if it has none (yet). */
    @RequiresApi(35)
    private fun taskOf(window: XdgToplevel): LinuxTask? {
        val handle = runtime.wm.handleOf(window) ?: return null
        return linuxTasks(visible = false).firstOrNull { it.window.handle == handle }
    }

    /** Opens [app] in the split half at [half], replacing what's there; see [TaskbarTrampoline.replace]. */
    private fun replace(
        app: AndroidApp,
        half: ZoneBox,
    ) {
        runtime.cancelWindowPlans()
        runtime.usage.record(usageKey(app.key))
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
        runtime.cancelWindowPlans()
        runtime.usage.record(usageKey(app.key))
        when (plan) {
            is OpenPlan.Split -> TaskbarTrampoline.start(this, splitSteps(plan).map { it.asStep() })
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
        const val LINUX_SCHEME = "omarchy"
        val SPLIT_KINDS = setOf(ZoneKind.SplitLeft, ZoneKind.SplitRight, ZoneKind.SplitTop, ZoneKind.SplitBottom)
    }
}

/** An app in the tray: an Android app, or a Linux one. [key] is its usage key, as [taskbarApps] lists it. */
private sealed interface TrayApp {
    val key: String

    data class Android(
        val app: AndroidApp,
    ) : TrayApp {
        override val key get() = usageKey(app.key)
    }

    data class Linux(
        val app: LinuxApp,
    ) : TrayApp {
        override val key get() = linuxKey(app.id)
    }
}
