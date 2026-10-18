package dev.omalauncher.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.omalauncher.Runtime
import dev.omalauncher.linux.TerminalFont
import dev.omalauncher.linux.shellQuote
import dev.omalauncher.wayland.WindowRenderer
import dev.omalauncher.wayland.XdgToplevel

/**
 * Terminals that live inside home-screen tiles (the widgets' `Terminal` node)
 * instead of their own Android task. Each runs its own foot with a unique
 * app_id, and the window manager hands that toplevel to the tile rather than
 * opening an activity for it.
 *
 * A tile is inert until tapped, so swipes still turn pages and open the
 * drawer; tapping it gives it the keyboard and its touches, until a tap
 * outside, Back, or leaving the page. A widget's own text field (an Input)
 * that takes the keyboard is treated the same way: its tile lifts above the
 * keyboard and lets go on the same tap outside, Back or page turn. Main
 * thread only.
 */
class TerminalTiles(
    private val runtime: Runtime,
) {
    private val wm get() = runtime.wm
    private val sessions = mutableStateListOf<EmbeddedTerminal>()
    private var nextId = 1

    /** The home screen's soft-keyboard owner; set while the home screen is up. */
    var keyboard: KeyboardSink? = null

    /** Where the extra-keys row sits, in window coordinates, while it shows; pressing it isn't leaving the tile. */
    var extraKeys: Rect? = null

    /** How far the home screen is lifted so the active tile clears the soft keyboard, in pixels. */
    var lift = 0f
        private set

    /** How much of the active tile the keyboard still covers once its top reaches the status bar: the tile gives that up. */
    var cut by mutableFloatStateOf(0f)
        private set

    /** The soft keyboard is closing on its own (Back) and the active tile lets go once it's gone; home shows its bar meanwhile. */
    var keyboardLeaving by mutableStateOf(false)

    /** The typing tile's whole tile, before the [cut], in window pixels as if the home screen weren't lifted. */
    var tile: Rect? = null

    /** The widget text field that has the keyboard, if any; Compose raises the keyboard for it, not [keyboard]. */
    var field by mutableStateOf<TileField?>(null)
        private set

    /** A tile has the keyboard: its terminal ([active]) or one of its widget's text fields ([field]). */
    val typing get() = active != null || field != null

    init {
        wm.claim = { window -> sessions.firstOrNull { it.appId == window.appId }?.attach(window) != null }
        wm.release = { window -> sessions.firstOrNull { it.window === window }?.detach() }
    }

    /** Whether [appId] is a tile's terminal at a size of the widget's own, which foot.ini's doesn't change. */
    fun hasOwnFont(appId: String) = sessions.any { it.appId == appId && it.fontSize != null }

    /** The tile that has the keyboard, if any. */
    val active: EmbeddedTerminal?
        get() {
            val focused = wm.focused ?: return null
            return sessions.firstOrNull { it.window === focused }
        }

    fun create(
        command: String?,
        fontSize: Double?,
    ): EmbeddedTerminal = EmbeddedTerminal(this, "$APP_ID_PREFIX${nextId++}", command, fontSize).also { sessions += it }

    internal fun forget(term: EmbeddedTerminal) {
        sessions -= term
    }

    fun activate(term: EmbeddedTerminal) {
        val window = term.window ?: return
        tile = null
        wm.focus(window)
        keyboard?.show()
    }

    fun deactivate() {
        if (active != null) wm.focus(null)
        tile = null
        keyboard?.hide()
        field?.release()
        field = null
    }

    /** [f] took focus, and with it the keyboard. */
    fun focused(f: TileField) {
        if (active != null) wm.focus(null)
        keyboard?.hide()
        tile = null
        field = f
    }

    /** [f] lost focus. */
    fun unfocused(f: TileField) {
        if (field === f) field = null
    }

    /** A press at window point [p]: outside the typing tile, it gives the home screen its touches back. */
    fun pressAt(p: Offset) {
        val bounds = active?.bounds ?: tile.takeIf { field != null } ?: return
        if (extraKeys?.contains(p) == true) return
        if (!bounds.translate(0f, -lift).contains(p)) deactivate()
    }

    /**
     * The lift that keeps the typing tile's bottom above a soft keyboard of
     * [imeHeight] in a window [windowHeight] tall, but its top below [top] (the
     * status bar); read while drawing, so it follows the keyboard's animation
     * without recomposing. A tile too tall for both gives up the rest at its
     * bottom ([cut]), kept from the keyboard's [imeTarget] so foot resizes once, not on
     * every frame of the animation, and never below [minHeight].
     */
    fun liftFor(
        imeHeight: Int,
        imeTarget: Int,
        windowHeight: Int,
        margin: Float,
        top: Float,
        minHeight: Float,
    ): Float {
        val bounds = typingBounds(field != null, tile, active?.bounds)
        val (l, c) = bounds?.let { liftAndCut(it, imeHeight, imeTarget, windowHeight, margin, top, minHeight) } ?: (0f to 0f)
        lift = l
        cut = c
        return lift
    }

    internal fun launch(
        term: EmbeddedTerminal,
        onExit: () -> Unit,
    ) {
        val font = term.fontSize?.let { " -o " + shellQuote("main.font=${runtime.terminalFont.family}:size=${TerminalFont.format(it)}") }.orEmpty()
        val command = term.command?.let { " -e bash -c " + shellQuote(it) }.orEmpty()
        runtime.launch("foot --app-id=${term.appId}$font$command", onExit)
    }

    internal fun close(window: XdgToplevel) = wm.close(window)

    internal val server get() = runtime.server
    internal val windowManager get() = wm

    companion object {
        /** Tiles' foot app_ids, numbered on. */
        const val APP_ID_PREFIX = "omarchy.tile-"

        /**
         * What the lift keeps clear: the typing tile's whole tile, so its frame clears the keys too, measured before the
         * cut, which would otherwise feed back into it. A focused field's tile counts once it's measured, never a
         * terminal's bounds in its place; a terminal falls back to its own until its tile is.
         */
        internal fun typingBounds(
            fieldFocused: Boolean,
            tile: Rect?,
            terminal: Rect?,
        ): Rect? = if (fieldFocused) tile else terminal?.let { tile ?: it }

        /** The lift and cut for [bounds], see [liftFor]. */
        internal fun liftAndCut(
            bounds: Rect,
            imeHeight: Int,
            imeTarget: Int,
            windowHeight: Int,
            margin: Float,
            top: Float,
            minHeight: Float,
        ): Pair<Float, Float> {
            fun overlap(ime: Int) = if (ime == 0) 0f else (bounds.bottom + margin - (windowHeight - ime)).coerceAtLeast(0f)
            val room = (bounds.top - top).coerceAtLeast(0f)
            return minOf(overlap(imeHeight), room) to (overlap(imeTarget) - room).coerceIn(0f, (bounds.height - minHeight).coerceAtLeast(0f))
        }
    }
}

/** A text field on a home tile's widget, which has the keyboard while it's [TerminalTiles.field]; [release] takes its focus away. */
class TileField(
    /** Its widget's instance, to tell which tile lifts. */
    val widget: Any,
    val release: () -> Unit,
)

/** One tile's terminal. Its foot starts when the tile first shows and runs until the widget goes away. */
class EmbeddedTerminal internal constructor(
    internal val tiles: TerminalTiles,
    val appId: String,
    val command: String?,
    val fontSize: Double?,
) {
    private val main = Handler(Looper.getMainLooper())

    var window by mutableStateOf<XdgToplevel?>(null)
        private set

    /** foot is starting or running. */
    var running by mutableStateOf(false)
        private set

    private var closed = false

    /** Where the tile is, in window pixels, as if the home screen weren't lifted. */
    internal var bounds = Rect.Zero

    fun start() {
        if (running || closed) return
        running = true
        tiles.launch(this) {
            main.post {
                running = false
                window = null
            }
        }
    }

    /** Ends the terminal (the widget was removed or changed); foot closes its shell. */
    fun close() {
        closed = true
        tiles.forget(this)
        window?.let(tiles::close)
    }

    internal fun attach(w: XdgToplevel) {
        // Closed while foot was still starting: it goes as soon as it shows up.
        if (closed) tiles.close(w) else window = w
    }

    internal fun detach() {
        window = null
    }
}

/** The tile's content: the terminal's pixels, and its input once it's tapped, if it's [interactive]. */
@Composable
fun TerminalView(
    term: EmbeddedTerminal,
    modifier: Modifier,
    interactive: Boolean = true,
) {
    val tiles = term.tiles
    val window = term.window
    val active = tiles.active === term
    LaunchedEffect(term) { term.start() }
    val background = Palette.background.toArgb()
    Box(modifier.onGloballyPositioned { term.bounds = it.boundsInWindow().translate(0f, tiles.lift) }) {
        AndroidView(
            factory = { EmbeddedWindowView(it, tiles) },
            update = {
                it.background = background
                it.window = window
            },
            onRelease = { it.window = null },
            modifier = Modifier.fillMaxSize(),
        )
        when {
            window == null ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(term) { detectTapGestures { term.start() } },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(if (term.running) "starting…" else "exited · tap to restart", style = Mono.copy(color = Palette.muted, fontSize = TextSize.Small))
                }
            active -> {
                // Over the TextureView, which Compose's pointer input doesn't reach.
                Box(Modifier.fillMaxSize().linuxWindowInput(tiles.windowManager, window))
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(32.dp)
                        .background(Palette.overlay, CircleShape)
                        .clickable { tiles.keyboard?.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("⌨", style = Mono.copy(color = Palette.foreground))
                }
            }
            interactive -> Box(Modifier.fillMaxSize().pointerInput(term) { detectTapGestures { tiles.activate(term) } })
        }
    }
}

/**
 * Draws an embedded window with a [WindowRenderer], like LinuxWindowActivity's
 * SurfaceView, but into a TextureView: it moves, clips and lifts with the
 * Compose tile around it (a SurfaceView would stay behind on its own layer).
 */
private class EmbeddedWindowView(
    context: Context,
    private val tiles: TerminalTiles,
) : TextureView(context),
    TextureView.SurfaceTextureListener {
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var renderer: WindowRenderer? = null

    var background = 0
        set(value) {
            field = value
            renderer?.background = value
        }

    var window: XdgToplevel? = null
        set(value) {
            if (value === field) return
            unbind()
            field = value
            bind()
        }

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    private fun bind() {
        val w = window ?: return
        if (renderer != null || !isAttachedToWindow) return
        val r = WindowRenderer(tiles.server, w).also { it.background = background }
        renderer = r
        showing.getOrPut(w) { ArrayList() } += r
        w.renderer = r
        surface?.let { attachSurface(it) }
    }

    private fun unbind() {
        val r = renderer ?: return
        window?.let { w ->
            val others = showing[w]?.apply { remove(r) }
            if (others.isNullOrEmpty()) showing -= w
            // A tile carried while arranging is drawn twice; the copy that stays takes over the frames.
            if (w.renderer === r) w.renderer = others?.lastOrNull()?.also { it.requestFrame() }
        }
        r.release()
        renderer = null
    }

    companion object {
        /** Views drawing each window, oldest first. */
        private val showing = HashMap<XdgToplevel, ArrayList<WindowRenderer>>()
    }

    private fun attachSurface(s: Surface) {
        renderer?.attach(s, surfaceWidth, surfaceHeight)
        window?.let { tiles.windowManager.applyAppLayout(it, surfaceWidth, surfaceHeight) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        bind()
    }

    override fun onDetachedFromWindow() {
        unbind()
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureAvailable(
        st: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        attachSurface(Surface(st).also { surface = it })
    }

    override fun onSurfaceTextureSizeChanged(
        st: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        surfaceWidth = width
        surfaceHeight = height
        surface?.let(::attachSurface)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        renderer?.detach()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
}
