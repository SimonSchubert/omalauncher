package dev.omalauncher.nav

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import dev.omalauncher.theme.ThemeStore
import dev.omalauncher.ui.decodeForScreen
import java.util.concurrent.Executors
import dev.omalauncher.ui.Palette
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The launcher's gesture navigation, for phones that only give gestures to their own launcher: thin
 * strips along the screen's edges, drawn over every app, that turn swipes into back, home and recents
 * ([performGlobalAction]). A tap on a strip is passed on to what's under it.
 *
 * - sides & bottom: swipe in from either side for back; up from the bottom for home, up and hold for recents
 * - bottom only (One UI's "swipe from bottom"): three zones along the bottom, back | home | recents
 *
 * Swiping up draws the app shrinking into a card under the finger, from a screenshot, and holding opens the
 * launcher's own recents ([RecentsView]). For those it notes which app is in front ([onAccessibilityEvent]),
 * and keeps a small screenshot of each.
 */
class NavigationService : AccessibilityService() {
    private val nav get() = gestureNav
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private val density get() = resources.displayMetrics.density
    private val slop get() = ViewConfiguration.get(this).scaledTouchSlop

    /** Strips on screen now. */
    private val strips = mutableListOf<View>()
    private var arrow: BackArrow? = null
    private var settings = NavSettings()
    private var sidesHeld = false

    /** A tap is on its way to the app under a strip: strips ignore touches until it's delivered. */
    private var passing = false

    /** The app as a card following a swipe up, then recents, while on screen. */
    private var recentsWindow: RecentsWindow? = null

    /** [RecentsView] runs here: home loading on the main thread after a swipe home would stall its animations. */
    private lateinit var overlayThread: HandlerThread
    private lateinit var overlayHandler: Handler
    private val recents get() = nav.recents

    /** Apps that can be in recents, by package: the ones with a launcher icon. */
    private val listable = mutableMapOf<String, Boolean>()

    /** Home screens, as package/class: not apps to list, though their package may be (Settings has a fallback one). */
    private var homes = emptySet<String>()

    /** The theme's background, behind the card as on home; decoded off the main thread. */
    private var backdrop: Bitmap? = null
    private val decoder = Executors.newSingleThreadExecutor()
    private val onThemeChanged: () -> Unit = { handler.post(::loadBackdrop) }

    /**
     * The lock screen has its own swipe up from the bottom: the strips stay off it. They come back once it's
     * unlocked, checked for after the screen turns on: HyperOS doesn't always deliver USER_PRESENT to an app
     * in the background.
     */
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                handler.removeCallbacks(untilUnlocked)
                closeRecents()
                layout()
                if (intent.action != Intent.ACTION_SCREEN_OFF) handler.postDelayed(untilUnlocked, UNLOCK_POLL_MS)
            }
        }

    private val untilUnlocked: Runnable =
        object : Runnable {
            override fun run() {
                if (locked()) handler.postDelayed(this, UNLOCK_POLL_MS) else if (strips.isEmpty()) layout()
            }
        }

    private fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun onServiceConnected() {
        windows = getSystemService(WindowManager::class.java)
        overlayThread = HandlerThread("nav-overlay", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        overlayHandler = Handler(overlayThread.looper)
        homes =
            packageManager
                .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .map { it.activityInfo.packageName + "/" + it.activityInfo.name }
                .toSet()
        recents.onFront = ::shootLater
        nav.running.value = true
        ThemeStore.changeListeners.add(onThemeChanged)
        loadBackdrop()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            combine(nav.settings, nav.sideHolds.map { it.isNotEmpty() }.distinctUntilChanged()) { s, held -> s to held }
                .collect { (s, held) ->
                    settings = s
                    sidesHeld = held
                    if (!s.gestures) closeRecents()
                    layout()
                }
        }
    }

    /** An app came to the front: it goes first in recents. The launcher's own screens report themselves. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // Unlocked without the screen turning on (or a missed broadcast): the strips come back with the next screen.
        if (strips.isEmpty() && settings.gestures && !locked()) layout()
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || "$pkg/${event.className}" in homes) return
        if (!listable.getOrPut(pkg) { packageManager.getLaunchIntentForPackage(pkg) != null }) return
        val key = RecentKey.App(pkg)
        recents.toFront(recents.find(key) ?: RecentEntry(key, appLabel(pkg), appIcon(pkg)))
    }

    private fun appLabel(pkg: String) =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun appIcon(pkg: String): Bitmap? =
        runCatching {
            val drawable = packageManager.getApplicationIcon(pkg)
            val size = (48 * density).roundToInt()
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(Canvas(it))
            }
        }.getOrNull()

    /** A screenshot of [entry] once it has drawn, for its card; replaced by the one taken when swiping away from it. */
    private fun shootLater(entry: RecentEntry) {
        handler.removeCallbacksAndMessages(SHOT_TOKEN)
        handler.postDelayed({
            if (recentsWindow == null && !nav.homeInFront && recents.entries.firstOrNull() === entry) takeShot { entry.shot = it }
        }, SHOT_TOKEN, SHOT_DELAY_MS)
    }

    override fun onInterrupt() {}

    /** Turned: the strips are measured again for the new width and height. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::windows.isInitialized) layout()
    }

    override fun onDestroy() {
        if (::windows.isInitialized) {
            nav.running.value = false
            runCatching { unregisterReceiver(screenReceiver) }
            handler.removeCallbacks(untilUnlocked)
            ThemeStore.changeListeners.remove(onThemeChanged)
            removeStrips()
            closeRecents()
            recents.onFront = null
            overlayThread.quitSafely()
        }
        decoder.shutdownNow()
        scope.cancel()
        super.onDestroy()
    }

    private fun loadBackdrop() {
        if (decoder.isShutdown) return
        decoder.execute {
            val decoded = ThemeStore.savedSelection(this)?.second?.let { decodeForScreen(it, this) }?.let(::software)
            handler.post { backdrop = decoded }
        }
    }

    /** A screenshot for the card, where Android takes one: from 11, and not of screens that forbid it (banking apps). */
    private fun takeShot(onShot: (Bitmap) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) return
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    if (bitmap == null || decoder.isShutdown) return
                    // Copied off the main thread, at half size: the card is smaller than the screen as soon as it moves.
                    decoder.execute {
                        val copy = software(bitmap, scale = 0.5f)
                        bitmap.recycle()
                        handler.post { onShot(copy) }
                    }
                }

                override fun onFailure(errorCode: Int) {}
            },
        )
    }

    /** A GPU bitmap copied into memory any window can draw, scaled by [scale]. */
    private fun software(
        bitmap: Bitmap,
        scale: Float = 1f,
    ): Bitmap {
        // copy() reads a GPU bitmap back; a CPU canvas can't draw one.
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (scale == 1f) return copy
        val scaled = Bitmap.createScaledBitmap(copy, (copy.width * scale).roundToInt(), (copy.height * scale).roundToInt(), true)
        if (scaled !== copy) copy.recycle()
        return scaled
    }

    /**
     * Puts up [RecentsView]: with [shot] (the screen as the swipe started) as the current app's card to drag,
     * or without one, to open straight into browsing.
     */
    private fun showRecents(shot: Bitmap?): RecentsWindow? {
        closeRecents()
        val size = screenSize()
        // Turned since the shot was taken.
        if (shot != null && (shot.width > shot.height) != (size.x > size.y)) return null
        val current = !nav.homeInFront && recents.entries.isNotEmpty()
        if (shot != null) {
            if (!current) return null
            recents.entries[0].shot = shot
        }
        val own = settings.ownRecents
        val entries = if (own) recents.entries.toList() else recents.entries.take(1)
        return RecentsWindow(entries, current, own, size).also { recentsWindow = it }
    }

    private fun closeRecents() {
        recentsWindow?.close()
        recentsWindow = null
    }

    /** [RecentsView] in its own window, owned by the overlay thread; what the main thread asks of it is posted there, in order. */
    private inner class RecentsWindow(
        entries: List<RecentEntry>,
        current: Boolean,
        own: Boolean,
        size: Point,
    ) {
        private var view: RecentsView? = null

        private val actions =
            object : RecentsView.Actions {
                override fun open(entry: RecentEntry) {
                    handler.post { openRecent(entry) }
                }

                override fun home() {
                    handler.post { performGlobalAction(GLOBAL_ACTION_HOME) }
                }

                override fun dismiss(entry: RecentEntry) {
                    handler.post { dismissRecent(entry) }
                }

                override fun interactive() {
                    val v = view ?: return
                    val params = v.layoutParams as? WindowManager.LayoutParams ?: return
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    runCatching { windows.updateViewLayout(v, params) }
                }

                override fun closed() {
                    remove()
                    handler.post { if (recentsWindow === this@RecentsWindow) recentsWindow = null }
                }
            }

        init {
            overlayHandler.post {
                val v = RecentsView(this@NavigationService, entries, current, backdrop, neighbors = own, actions)
                val params = overlay(size.x, size.y, Gravity.TOP or Gravity.LEFT, 0, touchable = false)
                if (runCatching { windows.addView(v, params) }.isSuccess) view = v
            }
        }

        private fun on(action: RecentsView.() -> Unit) {
            overlayHandler.post { view?.action() }
        }

        fun drag(
            up: Float,
            dx: Float,
        ) = on { drag(up, dx) }

        fun browse() = on { browse() }

        fun toHome() = on { toHome() }

        fun toApp() = on { toApp() }

        fun toPhoneRecents() = on { toPhoneRecents() }

        fun close() {
            overlayHandler.post(::remove)
        }

        /** On the overlay thread. */
        private fun remove() {
            view?.let { runCatching { windows.removeView(it) } }
            view = null
        }
    }

    /** Back to an app from recents: launching it resumes its task as it was, like a launcher icon does. */
    private fun openRecent(entry: RecentEntry) {
        when (val key = entry.key) {
            is RecentKey.App -> {
                val intent = packageManager.getLaunchIntentForPackage(key.pkg) ?: return
                runCatching {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                }
            }
            is RecentKey.Task -> ownTask(key.id)?.let { runCatching { it.moveToFront() } }
        }
    }

    /** Swiped away: out of recents, and its process stopped where Android allows (not while it's on screen). */
    private fun dismissRecent(entry: RecentEntry) {
        recents.remove(entry.key)
        when (val key = entry.key) {
            is RecentKey.App -> runCatching { getSystemService(ActivityManager::class.java).killBackgroundProcesses(key.pkg) }
            // A Linux window closes with its task, as when its card is swiped away in the phone's recents.
            is RecentKey.Task -> ownTask(key.id)?.let { runCatching { it.finishAndRemoveTask() } }
        }
    }

    private fun ownTask(id: Int) =
        getSystemService(ActivityManager::class.java).appTasks.firstOrNull { runCatching { it.taskInfo?.taskId }.getOrNull() == id }

    private fun removeStrips() {
        hideArrow()
        strips.forEach { runCatching { windows.removeView(it) } }
        strips.clear()
    }

    private fun layout() {
        removeStrips()
        passing = false
        val s = settings
        if (!s.gestures || locked()) return
        val size = screenSize()
        val bottom = (BOTTOM_HEIGHT_DP * density).roundToInt()
        val zones = s.layout == GestureLayout.BottomZones
        // Invisible: with its buttons hidden the phone draws its own handle there.
        add(BottomStrip(zones), WindowManager.LayoutParams.MATCH_PARENT, bottom, Gravity.BOTTOM)
        if (!zones && !sidesHeld) {
            val width = (s.sensitivity.widthDp * density).roundToInt()
            // Not the top fifth: the status bar and apps' menus are there.
            val top = size.y / 5
            val height = size.y - top - bottom
            add(SideStrip(left = true), width, height, Gravity.TOP or Gravity.LEFT, top)
            add(SideStrip(left = false), width, height, Gravity.TOP or Gravity.RIGHT, top)
        }
    }

    private fun add(
        view: View,
        width: Int,
        height: Int,
        gravity: Int,
        y: Int = 0,
    ) {
        if (runCatching { windows.addView(view, overlay(width, height, gravity, y, touchable = true)) }.isSuccess) strips += view
    }

    @Suppress("DEPRECATION")
    private fun screenSize() =
        Point().also { getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(it) }

    private fun overlay(
        width: Int,
        height: Int,
        gravity: Int,
        y: Int,
        touchable: Boolean,
    ) = WindowManager
        .LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Windows from a service aren't hardware accelerated by default; the screenshot can only be drawn by the GPU.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            title = "Omalauncher navigation"
            // Over the navigation bar and the camera cutout too: the strips belong at the screen's very edges.
            layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }

    private fun showArrow(
        left: Boolean,
        y: Float,
    ) {
        hideArrow()
        val w = (ARROW_WIDTH_DP * density).roundToInt()
        val h = (ARROW_HEIGHT_DP * density).roundToInt()
        val view = BackArrow(left)
        val params = overlay(w, h, Gravity.TOP or (if (left) Gravity.LEFT else Gravity.RIGHT), y.roundToInt() - h / 2, touchable = false)
        if (runCatching { windows.addView(view, params) }.isSuccess) arrow = view
    }

    private fun hideArrow() {
        arrow?.let { runCatching { windows.removeView(it) } }
        arrow = null
    }

    /** Hands a tap on [strip] to whatever is under it: the strip stops taking touches until the tap is in. */
    private fun passTap(
        strip: View,
        x: Float,
        y: Float,
    ) {
        val params = strip.layoutParams as? WindowManager.LayoutParams ?: return
        if (!strip.isAttachedToWindow) return
        passing = true
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        windows.updateViewLayout(strip, params)
        val restore = {
            passing = false
            if (strip.isAttachedToWindow) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                windows.updateViewLayout(strip, params)
            }
        }
        // After the window manager has the strip untouchable, or the tap would land on it again.
        handler.postDelayed({
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }
            val tap = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS)).build()
            val callback =
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = restore()

                    override fun onCancelled(gestureDescription: GestureDescription?) = restore()
                }
            if (runCatching { dispatchGesture(tap, callback, handler) }.getOrDefault(false) != true) restore()
        }, PASS_DELAY_MS)
    }

    private fun View.tick() =
        performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.VIRTUAL_KEY,
        )

    private fun isTap(
        e: MotionEvent,
        startX: Float,
        startY: Float,
    ) = hypot(e.rawX - startX, e.rawY - startY) < slop && e.eventTime - e.downTime < ViewConfiguration.getLongPressTimeout()

    /** Swipe in from the side: back, once the arrow has come out all the way. */
    private inner class SideStrip(
        private val left: Boolean,
    ) : View(this@NavigationService) {
        private var startX = 0f
        private var startY = 0f
        private var armed = false
        private var ignored = false

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ignored = passing
                    if (ignored) return true
                    startX = e.rawX
                    startY = e.rawY
                    armed = false
                    showArrow(left, e.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ignored) return true
                    val inward = if (left) e.rawX - startX else startX - e.rawX
                    val trigger = BACK_TRIGGER_DP * density
                    arrow?.progress = inward / trigger
                    // Mostly sideways: a drag along the edge isn't back.
                    val now = inward >= trigger && inward > abs(e.rawY - startY)
                    if (now && !armed) tick()
                    armed = now
                    arrow?.armed = now
                }
                MotionEvent.ACTION_UP -> {
                    if (ignored) return true
                    hideArrow()
                    when {
                        armed -> performGlobalAction(GLOBAL_ACTION_BACK)
                        isTap(e, startX, startY) -> passTap(this, e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_CANCEL -> hideArrow()
            }
            return true
        }
    }

    /** Swipe up: home, or recents when held; on home, the app drawer. In [zones], what the zone it started in does. */
    private inner class BottomStrip(
        private val zones: Boolean,
    ) : View(this@NavigationService) {
        private var startX = 0f
        private var startY = 0f
        private var action = GLOBAL_ACTION_HOME
        private var armed = false
        private var done = false
        private var ignored = false

        /** On home: the drawer following this swipe, and where the finger was last. */
        private var drawer: HomeDrawer? = null
        private var lastY = 0f
        private var screenHeight = 1f
        private var velocity: VelocityTracker? = null

        /** This swipe's screenshot, once it's in, and the recents view that draws it as a card. */
        private var shot: Bitmap? = null
        private var view: RecentsWindow? = null

        /** Counts swipes, so a screenshot that arrives after its swipe ended is dropped. */
        private var swipe = 0
        private var lifted = true

        /** Up and slowed to a stop: recents, while the finger is still down. */
        private val hold =
            Runnable {
                if (armed && !done) {
                    done = true
                    tick()
                    // Held on home: recents instead of the drawer, which closes under it.
                    drawer?.settle(SETTLE_CLOSED)
                    drawer = null
                    recents()
                }
            }

        /** The launcher's recents, growing out of the card under the finger if there is one; or the phone's. */
        private fun recents() {
            if (settings.ownRecents) {
                (view ?: showRecents(null))?.browse()
            } else {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                view?.toPhoneRecents()
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ignored = passing
                    if (ignored) return true
                    startX = e.rawX
                    startY = e.rawY
                    armed = false
                    done = false
                    action =
                        if (!zones) {
                            GLOBAL_ACTION_HOME
                        } else {
                            when ((e.x / width * 3).toInt().coerceIn(0, 2)) {
                                0 -> GLOBAL_ACTION_BACK
                                1 -> GLOBAL_ACTION_HOME
                                else -> GLOBAL_ACTION_RECENTS
                            }
                        }
                    // Taken as the finger lands, so it's in by the time the swipe needs it. Not of home: there's no app to lift.
                    val id = ++swipe
                    lifted = false
                    shot = null
                    view = null
                    closeRecents()
                    drawer = if (action == GLOBAL_ACTION_HOME && nav.homeInFront) nav.homeDrawer?.takeIf { it.available } else null
                    lastY = e.rawY
                    screenHeight = screenSize().y.toFloat()
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    if (settings.animation && action != GLOBAL_ACTION_BACK && !nav.homeInFront) {
                        takeShot { bitmap ->
                            if (id == swipe && !lifted) {
                                shot = bitmap
                                // The app as it's being left: its recents card.
                                recents.entries.firstOrNull()?.shot = bitmap
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ignored || done) return true
                    val up = startY - e.rawY
                    velocity?.addMovement(e)
                    drawer?.drag((lastY - e.rawY) / screenHeight)
                    lastY = e.rawY
                    if (view == null && up > slop) view = shot?.let(::showRecents)
                    view?.drag(up.coerceAtLeast(0f), e.rawX - startX)
                    val now = up >= HOME_TRIGGER_DP * density && up > abs(e.rawX - startX)
                    if (now && !armed) tick()
                    armed = now
                    velocity?.computeCurrentVelocity(1000)
                    val speed = hypot(velocity?.xVelocity ?: 0f, velocity?.yVelocity ?: 0f)
                    when {
                        !armed || action != GLOBAL_ACTION_HOME -> handler.removeCallbacks(hold)
                        // Moving fast: it counts as held once the moves stop coming (a finger at rest sends none).
                        speed > SLOW_DP_S * density -> {
                            handler.removeCallbacks(hold)
                            handler.postDelayed(hold, STOPPED_MS)
                        }
                        // Slowed down: held, unless it speeds up again first. A resting finger still drifts a little.
                        !handler.hasCallbacks(hold) -> handler.postDelayed(hold, HOLD_MS)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(hold)
                    lifted = true
                    shot = null
                    velocity?.addMovement(e)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    velocity?.recycle()
                    velocity = null
                    if (ignored || done) return true
                    val homeDrawer = drawer
                    drawer = null
                    when {
                        isTap(e, startX, startY) -> {
                            // Moved by less than a touch's slop, but moved: back to where it was.
                            homeDrawer?.settle(SETTLE_CLOSED)
                            passTap(this, e.rawX, e.rawY)
                        }
                        homeDrawer != null -> homeDrawer.settle(vy)
                        // Let go slowly, well up the screen: recents, as on Pixel; a flick is home.
                        armed && action == GLOBAL_ACTION_HOME && view != null && settings.ownRecents &&
                            startY - e.rawY > screenHeight * 0.25f && abs(vy) < SLOW_RELEASE_DP_S * density -> {
                            tick()
                            recents()
                        }
                        armed && action == GLOBAL_ACTION_HOME -> view?.toHome() ?: performGlobalAction(GLOBAL_ACTION_HOME)
                        armed && action == GLOBAL_ACTION_RECENTS -> recents()
                        armed -> performGlobalAction(action)
                        else -> view?.toApp()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(hold)
                    lifted = true
                    shot = null
                    velocity?.recycle()
                    velocity = null
                    drawer?.settle(0f)
                    drawer = null
                    if (!done) view?.toApp()
                }
            }
            return true
        }
    }

    /** The arrow that follows a back swipe in from the side; filled in the accent once letting go goes back. */
    private inner class BackArrow(
        private val left: Boolean,
    ) : View(this@NavigationService) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val chevron = Path()

        var progress = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }

        var armed = false
            set(value) {
                if (field != value) invalidate()
                field = value
            }

        override fun onDraw(canvas: Canvas) {
            if (progress <= 0f) return
            val r = 20 * density
            // Comes out from behind the edge, to a little short of the window's far side.
            val out = progress * (width - 8 * density)
            val cx = if (left) -r + out else width + r - out
            val cy = height / 2f
            paint.style = Paint.Style.FILL
            paint.color = (if (armed) Palette.accent else Palette.background).copy(alpha = 0.9f).toArgb()
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = (if (armed) Palette.background else Palette.accent).toArgb()
            val a = 6 * density
            // Points back, away from the screen's middle.
            val d = if (left) a / 2 else -a / 2
            chevron.reset()
            chevron.moveTo(cx + d, cy - a)
            chevron.lineTo(cx - d, cy)
            chevron.lineTo(cx + d, cy + a)
            canvas.drawPath(chevron, paint)
        }
    }

    private companion object {
        /** About the height of Android's own gesture bar. */
        const val BOTTOM_HEIGHT_DP = 22
        const val BACK_TRIGGER_DP = 44
        const val HOME_TRIGGER_DP = 40
        const val HOLD_MS = 120L

        /** No moves for this long after fast ones: the finger has stopped. */
        const val STOPPED_MS = 140L

        /** Slower than this, a swipe up is coming to rest. */
        const val SLOW_DP_S = 220

        /** Let go slower than this, well up the screen: recents. */
        const val SLOW_RELEASE_DP_S = 350
        const val ARROW_WIDTH_DP = 72
        const val ARROW_HEIGHT_DP = 96
        const val TAP_MS = 40L
        const val PASS_DELAY_MS = 48L

        /** After an app comes to the front, its screenshot for recents: once it has drawn, past its opening animation. */
        const val SHOT_DELAY_MS = 900L
        const val UNLOCK_POLL_MS = 400L

        /** A downward fling, for [HomeDrawer.settle]: closed. */
        const val SETTLE_CLOSED = 5000f
        val SHOT_TOKEN = Any()
    }
}
