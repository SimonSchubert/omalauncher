package dev.omalauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.ui.ExtraKeys
import dev.omalauncher.ui.ImagePicker
import dev.omalauncher.ui.KeyboardSink
import dev.omalauncher.ui.WidgetAsks
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.WHEEL_STEP
import dev.omalauncher.ui.linuxWindowInput
import dev.omalauncher.wayland.AXIS_SOURCE_WHEEL
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.BTN_MIDDLE
import dev.omalauncher.wayland.BTN_RIGHT
import dev.omalauncher.wayland.WindowRenderer
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * Shows a single Linux window as its own Android task, so it gets its own
 * Recents card and behaves like a separate app.
 */
class LinuxWindowActivity : InstantActivity() {
    private var window: XdgToplevel? = null
    private val imagePicker = ImagePicker(this) { runtime }
    private val asks = WidgetAsks(this)

    override fun create(savedInstanceState: Bundle?) {
        val window = runtime.wm.windowByHandle(intent.data?.lastPathSegment)
        if (window == null) {
            // The window is gone, e.g. the process restarted since Recents was built.
            finishAndRemoveTask()
            return
        }
        this.window = window
        setInstantContent {
            AppWindow(runtime, window)
            BackHandler { backInstantly() }
            LaunchedEffect(window) {
                snapshotFlow { window in runtime.wm.appWindows }.first { !it }
                closeInstantly()
            }
            // Terminals retitle constantly; only a new label changes the Recents card.
            val label = runtime.displayName(window.appId, window.title)
            val appIcon = runtime.iconNamed(runtime.agentOf(window.appId, label)?.name ?: label)
            LaunchedEffect(label, appIcon, Palette.accent) { describeTask(label, appIcon?.asAndroidBitmap()) }
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.screenStarted()
    }

    override fun onResume() {
        super.onResume()
        window?.let(runtime.wm::focus)
        runtime.keyboard.imagePicker = imagePicker
        asks.resumed(runtime)
    }

    override fun onStop() {
        super.onStop()
        runtime.screenStopped()
    }

    /** Swiping the card away in Recents closes the Linux window, like closing an app. */
    override fun onDestroy() {
        super.onDestroy()
        val w = window ?: return
        if (isFinishing && w in runtime.wm.appWindows) runtime.wm.close(w)
    }

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A game controller's buttons, while a program here has it open.
        if (runtime.gamepads.key(event) { backInstantly() }) return true
        // Super+Esc leaves the window as Back does, where Esc itself is the window's: it keeps running in Recents.
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) backInstantly()
            return true
        }
        if (runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** A game controller's sticks, triggers and hat, while a program here has it open. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (runtime.gamepads.motion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    /** Keys held as the window goes, like Super, would stay down for it: Android sends their lift elsewhere. */
    override fun onPause() {
        super.onPause()
        runtime.keyboard.releaseHeld()
        runtime.gamepads.releaseAll()
        if (runtime.keyboard.imagePicker === imagePicker) runtime.keyboard.imagePicker = null
        asks.paused(runtime)
    }
}

/**
 * Recents shows the manifest label ("Linux", or an alias's), but it does use the
 * task icon: the app's drawer icon, else its initials in the theme colors.
 */
internal fun taskIcon(
    name: String,
    background: Int,
    accent: Int,
): Bitmap {
    val size = 192
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(background)
    val words = name.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    val initials =
        when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.isNotEmpty() -> words[0].take(2).replaceFirstChar(Char::uppercase)
            else -> "◆"
        }
    val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = size * 0.42f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(initials, size / 2f, y, paint)
    return bmp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppWindow(
    runtime: Runtime,
    window: XdgToplevel,
) {
    var sink by remember { mutableStateOf<KeyboardSink?>(null) }
    var surfaceView by remember { mutableStateOf<WindowSurfaceView?>(null) }
    // Shows wherever the window has yet to draw, as when it grows; the terminal's own background, not a lighter one.
    val background = Palette.background.toArgb()
    val density = LocalDensity.current
    // Where the keyboard ends up, not each frame of its slide, and read here so the extra keys come and go in the same
    // frame: the window then resizes once, and a terminal reflows once.
    val keyboard = (WindowInsets.imeAnimationTarget.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .systemBarsPadding(),
    ) {
        AndroidView(
            factory = { KeyboardSink(it, runtime.keyboard).also { v -> sink = v } },
            modifier = Modifier.size(1.dp),
        )
        // Always the whole screen: a SurfaceView that changes size shows its last frame stretched until the next one,
        // so the keyboard and the extra keys go over it, and only the Linux window gets smaller.
        AndroidView(
            factory = { context -> WindowSurfaceView(context, runtime, window).also { surfaceView = it } },
            update = {
                it.background = background
                it.captureWanted = runtime.wm.pointerLock === window
            },
            modifier = Modifier.fillMaxSize(),
        )
        val keyboardHeight = with(density) { keyboard.toDp() }
        // Where the keyboard is still sliding in, so the window's old frame doesn't show beneath it.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(keyboardHeight).background(Palette.background))
        Column(Modifier.fillMaxSize().padding(bottom = keyboardHeight)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { surfaceView?.visibleHeight = it.height },
            ) {
                // Over the SurfaceView, which Compose's pointer input doesn't reach.
                // A tap on the prompt line brings the keyboard back, as the ⌨ button does.
                Box(Modifier.fillMaxSize().linuxWindowInput(runtime.wm, window) { sink?.takeUnless { it.showing }?.show() })
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(40.dp)
                        .background(Palette.overlay, CircleShape)
                        .clickable { sink?.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("⌨", style = Mono.copy(color = Palette.foreground))
                }
            }
            // Below the window rather than over it, so the window shrinks and nothing hides behind the keys; only over a
            // docked keyboard (a hardware keyboard's toolbar takes no height, and that keyboard has these keys).
            if (keyboard > 0) ExtraKeys(runtime, Modifier.background(Palette.background))
        }
    }
}

/**
 * The window's pixels: a [WindowRenderer] draws the Linux surfaces straight
 * into this view's surface, without Compose or bitmaps in between. The
 * window is as wide as the view and [visibleHeight] tall, at its top.
 */
private class WindowSurfaceView(
    context: Context,
    private val runtime: Runtime,
    private val window: XdgToplevel,
) : SurfaceView(context),
    SurfaceHolder.Callback {
    private val renderer = WindowRenderer(runtime.server, window)

    var background by renderer::background

    /**
     * Whether the window's program locked the pointer, as a game does to look around with the mouse. The mouse is
     * captured then, while the window has focus: the cursor goes and only movement arrives ([onCapturedPointerEvent]).
     * Android lets go when the window loses focus, and this asks again when it's back.
     */
    var captureWanted = false
        set(value) {
            if (value == field) return
            field = value
            syncCapture()
        }

    private fun syncCapture() {
        if (captureWanted && hasWindowFocus()) {
            if (hasPointerCapture()) return
            // Captured events go to the focused view, which is otherwise the keyboard's.
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
            requestPointerCapture()
        } else if (hasPointerCapture()) {
            releasePointerCapture()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        syncCapture()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) return
        // Something else took focus, like the keyboard from the ⌨ button: the mouse is free until the window's back.
        isFocusable = false
        if (hasPointerCapture()) releasePointerCapture()
    }

    /** Focused while the mouse is captured: a physical keyboard's keys skip the keyboard app here too. */
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
        runtime.keyboard.preIme(event) || super.dispatchKeyEventPreIme(event)

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        capturedButtons = 0
        runtime.wm.pointerCaptured(window, hasCapture)
    }

    private var capturedButtons = 0

    /** The captured mouse: movement, its buttons and the wheel, as Android reports them while the cursor is gone. */
    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // Each sample is a movement of its own; a batch holds several.
                var dx = event.x
                var dy = event.y
                for (i in 0 until event.historySize) {
                    dx += event.getHistoricalX(i)
                    dy += event.getHistoricalY(i)
                }
                if (dx != 0f || dy != 0f) runtime.wm.relativeMotion(dx, dy)
            }
            MotionEvent.ACTION_SCROLL -> {
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val v = -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                runtime.wm.scroll(h * WHEEL_STEP, v * WHEEL_STEP, AXIS_SOURCE_WHEEL, v.roundToInt())
            }
        }
        val buttons = event.buttonState
        for ((bit, code) in CAPTURED_BUTTONS) {
            if (buttons and bit != 0 && capturedButtons and bit == 0) runtime.wm.pointerButton(code, true)
            if (buttons and bit == 0 && capturedButtons and bit != 0) runtime.wm.pointerButton(code, false)
        }
        capturedButtons = buttons
        return true
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** How much of the view, from its top, the keyboard and the extra keys leave the window; 0 for all of it. */
    var visibleHeight = 0
        set(value) {
            if (value == field) return
            field = value
            layoutWindow()
        }

    private fun layoutWindow() {
        if (surfaceWidth == 0) return
        val h = if (visibleHeight in 1..surfaceHeight) visibleHeight else surfaceHeight
        renderer.visibleHeight = h
        runtime.wm.applyAppLayout(window, surfaceWidth, h)
    }

    init {
        holder.addCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        window.renderer = renderer
    }

    override fun onDetachedFromWindow() {
        if (window.renderer === renderer) window.renderer = null
        renderer.release()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        renderer.attach(holder.surface, width, height)
        layoutWindow()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.detach()

    private companion object {
        val CAPTURED_BUTTONS =
            listOf(
                MotionEvent.BUTTON_PRIMARY to BTN_LEFT,
                MotionEvent.BUTTON_SECONDARY to BTN_RIGHT,
                MotionEvent.BUTTON_TERTIARY to BTN_MIDDLE,
            )
    }
}
