package dev.omalauncher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.ui.ExtraKeys
import dev.omalauncher.ui.ImagePicker
import dev.omalauncher.ui.KeyboardSink
import dev.omalauncher.theme.Style
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.LeftToRight
import dev.omalauncher.ui.WidgetAsks
import dev.omalauncher.ui.Mono
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.linuxWindowInput
import dev.omalauncher.ui.terminal.NativeTerminalView
import dev.omalauncher.ui.terminal.TerminalColors
import dev.omalauncher.ui.terminal.TerminalWindows
import dev.omalauncher.wayland.WindowRenderer
import dev.omalauncher.wayland.XdgToplevel
import kotlinx.coroutines.flow.first

/**
 * Shows a single Linux window as its own Android task, so it gets its own
 * Recents card and behaves like a separate app. Or a built-in terminal's
 * window ([TerminalWindows]), the same way.
 */
class LinuxWindowActivity : InstantActivity() {
    private var window: XdgToplevel? = null
    private val imagePicker = ImagePicker(this) { runtime }
    private val asks = WidgetAsks(this)

    /** The built-in terminal's window this shows instead of a Linux one, and the view drawing it. */
    private var terminal: TerminalWindows.Window? = null
    private var terminalView: NativeTerminalView? = null

    /** Whether the guest's font is loaded into [terminalView]: until then its cells have the fallback font's size. */
    private var facesLoaded = false
    private val closeWhenEnded = { closeInstantly() }

    override fun create(savedInstanceState: Bundle?) {
        if (intent.data?.host == TerminalWindows.HOST) return createTerminal()
        val window = runtime.wm.windowByHandle(intent.data?.lastPathSegment)
        if (window == null) {
            // The window is gone, e.g. the process restarted since Recents was built.
            finishAndRemoveTask()
            return
        }
        this.window = window
        setInstantContent {
            LeftToRight { AppWindow(runtime, window) }
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

    private fun createTerminal() {
        val terminal = runtime.terminalWindows[intent.data?.lastPathSegment]
        if (terminal == null) {
            // It has ended, or the process restarted since Recents was built.
            finishAndRemoveTask()
            return
        }
        this.terminal = terminal
        terminal.onEnd = closeWhenEnded
        val font = runtime.terminalFont
        setInstantContent {
            LeftToRight { TerminalWindow(terminal) }
            BackHandler { backInstantly() }
            // Open terminals follow the theme, the text size and the font family directly, unlike foot.
            val theme = runtime.themes.current
            LaunchedEffect(theme) { terminalView?.colors = TerminalColors.from(theme?.colors.orEmpty()) }
            LaunchedEffect(font.size) {
                terminalView?.let {
                    it.emPx = font.emPx(terminal.ownSize ?: font.size)
                    it.padPx = font.padPx
                }
            }
            LaunchedEffect(font.family) {
                val faces = runtime.terminalWindows.faces(font.family)
                terminalView?.let {
                    it.faces = faces
                    facesLoaded = true
                    startTerminal(it)
                }
            }
            val appIcon = runtime.iconNamed(terminal.agent?.name ?: terminal.name)
            LaunchedEffect(appIcon, Palette.accent) { describeTask(terminal.name, appIcon?.asAndroidBitmap()) }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun TerminalWindow(terminal: TerminalWindows.Window) {
        var sink by remember { mutableStateOf<KeyboardSink?>(null) }
        val density = LocalDensity.current
        val keyboard = (WindowInsets.imeAnimationTarget.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
        Box(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding()) {
            AndroidView(factory = { KeyboardSink(it, runtime.keyboard).also { v -> sink = v } }, modifier = Modifier.size(1.dp))
            // The terminal gets smaller for the keyboard and the extra keys, so its program redraws above them.
            Column(Modifier.fillMaxSize().padding(bottom = with(density) { keyboard.toDp() })) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { context ->
                            NativeTerminalView(context).also { v ->
                                terminalView = v
                                val font = runtime.terminalFont
                                v.emPx = font.emPx(terminal.ownSize ?: font.size)
                                v.padPx = font.padPx
                                v.colors = TerminalColors.from(runtime.themes.current?.colors.orEmpty())
                                v.onTap = { sink?.takeUnless { it.showing }?.show() }
                                v.onGrid = { _, _, _, _ -> startTerminal(v) }
                                // Made again while the program runs: it carries on in this view.
                                terminal.session?.let { v.session = it }
                                if (hasWindowFocus()) takeKeyboard()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (LauncherStyle.style[Style.KEYBOARD_BUTTON]) KeyboardButton(Modifier.align(Alignment.BottomEnd)) { sink?.toggle() }
                }
                if (keyboard > 0) ExtraKeys(runtime, Modifier.background(Palette.background))
            }
        }
    }

    /**
     * Starts the program once the view has a grid in the guest's font. Started earlier, it would be resized as the
     * font comes, under its first frame, which Claude Code doesn't draw again whole.
     */
    private fun startTerminal(v: NativeTerminalView) {
        val terminal = terminal ?: return
        if (terminal.session != null || !facesLoaded || v.columns == 0) return
        v.session =
            runCatching { runtime.terminalWindows.start(terminal, v.columns, v.rows, v.cellWidth, v.cellHeight) }.getOrElse {
                Log.w(TAG, "can't start the terminal", it)
                runtime.terminalWindows.close(terminal)
                closeInstantly()
                return
            }
    }

    /** Keys, the soft keyboard's text and the key bar go to the built-in terminal rather than a Linux window. */
    private fun takeKeyboard() {
        val v = terminalView ?: return
        runtime.keyboard.target = v
        v.keyboardFocus = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val v = terminalView ?: return
        if (hasFocus) {
            takeKeyboard()
        } else {
            if (runtime.keyboard.target === v) runtime.keyboard.target = null
            v.keyboardFocus = false
        }
    }

    override fun onStart() {
        super.onStart()
        runtime.screenStarted()
    }

    override fun onResume() {
        super.onResume()
        window?.let(runtime.wm::focus)
        // Before the window has focus again, for the photo picker's result (ImagePicker).
        if (terminal != null) takeKeyboard()
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
        terminal?.let { t ->
            if (runtime.keyboard.target === terminalView) runtime.keyboard.target = null
            if (t.onEnd === closeWhenEnded) t.onEnd = null
            if (isFinishing) runtime.terminalWindows.close(t)
        }
        val w = window ?: return
        if (isFinishing && w in runtime.wm.appWindows) runtime.wm.close(w)
    }

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Super+Esc leaves the window as Back does, where Esc itself is the window's: it keeps running in Recents.
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) backInstantly()
            return true
        }
        if (terminal != null) {
            // Super+W closes this window, not the Linux window that last had the keyboard.
            if (event.keyCode == KeyEvent.KEYCODE_W && event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) closeInstantly()
                return true
            }
            // Until the terminal has the keyboard, keys aren't meant for the Linux window that last had it.
            if (runtime.keyboard.target === terminalView && runtime.keyboard.handle(event)) return true
            return super.dispatchKeyEvent(event)
        }
        if (runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** Keys held as the window goes, like Super, would stay down for it: Android sends their lift elsewhere. */
    override fun onPause() {
        super.onPause()
        runtime.keyboard.releaseHeld()
        if (runtime.keyboard.imagePicker === imagePicker) runtime.keyboard.imagePicker = null
        asks.paused(runtime)
    }

    private companion object {
        const val TAG = "linux-window"
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
            update = { it.background = background },
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
                if (LauncherStyle.style[Style.KEYBOARD_BUTTON]) KeyboardButton(Modifier.align(Alignment.BottomEnd)) { sink?.toggle() }
            }
            // Below the window rather than over it, so the window shrinks and nothing hides behind the keys; only over a
            // docked keyboard (a hardware keyboard's toolbar takes no height, and that keyboard has these keys).
            if (keyboard > 0) ExtraKeys(runtime, Modifier.background(Palette.background))
        }
    }
}

/**
 * The ⌨ button over a window's corner, which shows or hides the soft keyboard. Never focused: keys are the window's,
 * and one that comes before the window has the keyboard (the Enter that opened it) would otherwise ring it.
 */
@Composable
private fun KeyboardButton(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .padding(12.dp)
            .size(40.dp)
            .background(Palette.overlay, CircleShape)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("⌨", style = Mono.copy(color = Palette.foreground))
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
}
