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
import androidx.compose.runtime.DisposableEffect
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
import dev.omalauncher.wayland.AXIS_SOURCE_FINGER
import dev.omalauncher.wayland.BTN_LEFT
import dev.omalauncher.wayland.SurfaceHit
import dev.omalauncher.wayland.WindowRenderer
import dev.omalauncher.wayland.XdgToplevel
import kotlin.math.abs

/**
 * Terminals that live inside home-screen tiles (the widgets' `Terminal` node)
 * instead of their own Android task. Each runs its own foot with a unique
 * app_id, and the window manager hands that toplevel to the tile rather than
 * opening an activity for it.
 *
 * A sideways swipe on an inert tile still turns the page. Dragging up or down
 * scrolls the terminal on that same press, without a tap first and without the
 * keyboard. A tap gives the tile the keyboard and its touches, until a tap
 * outside, Back, or leaving the page. Main thread only.
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

    /** The ⌨ button, in window coordinates, while the tile is active. A press there is the button's. */
    var keyboardButton: Rect? = null

    /** How far the home screen is lifted so the active tile clears the soft keyboard, in pixels. */
    var lift = 0f
        private set

    /** How much of the active tile the keyboard still covers once its top reaches the status bar: the tile gives that up. */
    var cut by mutableFloatStateOf(0f)
        private set

    /** The soft keyboard is closing on its own (Back) and the active tile lets go once it's gone; home shows its bar meanwhile. */
    var keyboardLeaving by mutableStateOf(false)

    /** The active terminal's whole tile, before the [cut], in window pixels as if the home screen weren't lifted. */
    var tile: Rect? = null

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

    /** The tile under window point [p], or null. */
    fun terminalAt(p: Offset): EmbeddedTerminal? =
        sessions.lastOrNull { it.window != null && it.bounds.translate(0f, -lift).contains(p) }

    fun deactivate() {
        if (active != null) wm.focus(null)
        tile = null
        keyboard?.hide()
    }

    /** A press at window point [p]: outside the active tile, it gives the home screen its touches back. */
    fun pressAt(p: Offset) {
        val term = active ?: return
        if (extraKeys?.contains(p) == true) return
        if (!term.bounds.translate(0f, -lift).contains(p)) deactivate()
    }

    /**
     * A tap at window point [p] that did not move. Focuses the terminal tile
     * under it. The tile's own overlay sits on a TextureView, which Compose's
     * pointer input does not reach, so a tap on the text never arrived there
     * and only the prompt line, caught elsewhere, woke the tile.
     */
    fun activateAt(p: Offset) {
        if (active != null) return
        val term = terminalAt(p) ?: return
        activate(term)
    }

    /**
     * A tap on the tile that already has the keyboard. The overlay never sees it
     * over the TextureView, so the cursor would not move.
     */
    fun clickAt(p: Offset) {
        val term = active ?: return
        val window = term.window ?: return
        if (keyboardButton?.contains(p) == true) return
        val local = term.viewPoint(p)
        val hit = window.renderer?.hitTest(local.x, local.y) ?: return
        if (wm.onPress(window, hit)) wm.click(hit, BTN_LEFT)
    }

    /**
     * One finger dragging on a tile. [down] and [at] are window points. The first
     * call starts the touch at [down]; later calls move it. An inert tile is not
     * focused: the keyboard stays down and a sideways swipe can still turn the page.
     */
    fun drag(
        term: EmbeddedTerminal,
        pointerId: Int,
        down: Offset,
        at: Offset,
        takeFocus: Boolean,
    ) {
        if (finger == null || finger?.term !== term || finger?.pointerId != pointerId) {
            endDrag()
            val window = term.window ?: return
            if (takeFocus && active !== term) {
                tile = null
                wm.focus(window)
            }
            val origin = term.viewPoint(down)
            val hit = window.renderer?.hitTest(origin.x, origin.y) ?: return
            if (takeFocus && !wm.onPress(window, hit)) return
            val touch = wm.wantsTouch(hit.surface)
            if (touch) wm.touchDown(hit, pointerId) else wm.pointerMove(hit)
            finger = TileDrag(term, pointerId, hit, touch, origin, origin, scrolling = !touch)
        }
        val session = finger ?: return
        val local = term.viewPoint(at)
        if (session.touch) {
            val d = (local - session.origin) / wm.scale.toFloat()
            wm.touchMotion(session.pointerId, session.hit.x + d.x, session.hit.y + d.y)
        } else {
            val d = local - session.last
            if (d != Offset.Zero) wm.scroll(-d.x / wm.scale, -d.y / wm.scale, AXIS_SOURCE_FINGER)
        }
        session.last = local
    }

    fun endDrag() {
        val session = finger ?: return
        finger = null
        if (session.touch) wm.touchUp(session.pointerId) else if (session.scrolling) wm.scrollStop()
    }

    private var finger: TileDrag? = null

    private fun EmbeddedTerminal.viewPoint(windowPoint: Offset) =
        Offset(windowPoint.x - bounds.left, windowPoint.y - bounds.top + lift)

    /**
     * The lift that keeps the active tile's bottom above a soft keyboard of
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
        val term = active
        if (term == null) {
            lift = 0f
            cut = 0f
            return lift
        }
        // Its whole tile, so its frame clears the keys too; measured before the cut, which would otherwise feed back into it.
        val bounds = tile ?: term.bounds
        fun overlap(ime: Int) = if (ime == 0) 0f else (bounds.bottom + margin - (windowHeight - ime)).coerceAtLeast(0f)
        val room = (bounds.top - top).coerceAtLeast(0f)
        lift = minOf(overlap(imeHeight), room)
        cut = (overlap(imeTarget) - room).coerceIn(0f, (bounds.height - minHeight).coerceAtLeast(0f))
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
    }
}

private class TileDrag(
    val term: EmbeddedTerminal,
    val pointerId: Int,
    val hit: SurfaceHit,
    val touch: Boolean,
    val origin: Offset,
    var last: Offset,
    val scrolling: Boolean,
)

/**
 * Where a drag past the touch slop goes. An inert tile gives a sideways drag to
 * the pager and keeps an up or down drag as scrolling. An active tile keeps both.
 */
internal enum class TerminalGesture { TurnPage, ScrollTile }

internal fun terminalGesture(
    tileActive: Boolean,
    dx: Float,
    dy: Float,
): TerminalGesture = if (!tileActive && abs(dx) > abs(dy)) TerminalGesture.TurnPage else TerminalGesture.ScrollTile

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
                DisposableEffect(term) { onDispose { tiles.keyboardButton = null } }
                // Over the TextureView, which Compose's pointer input doesn't reach.
                // Presses on the text are forwarded from the home screen instead.
                Box(Modifier.fillMaxSize().linuxWindowInput(tiles.windowManager, window))
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .onGloballyPositioned { tiles.keyboardButton = it.boundsInWindow() }
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
