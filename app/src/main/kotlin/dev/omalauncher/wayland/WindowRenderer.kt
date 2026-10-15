package dev.omalauncher.wayland

import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A surface as placed in its window, in window pixels. [store] is null while it has no content. */
data class Layer(
    val surface: Surface,
    val store: PixelStore?,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Window pixels per logical pixel. */
    val scale: Int,
    val inPopup: Boolean,
)

/** Hit-test result: a surface and a point in its local logical coordinates. */
data class SurfaceHit(
    val surface: Surface,
    val x: Float,
    val y: Float,
    val inPopup: Boolean = false,
)

/**
 * Draws one Linux window (its surface tree and popups) into the Android
 * surface of its activity's SurfaceView. It runs on its own thread, paced by
 * vsync: each frame snapshots the scene under the server lock (no pixels
 * move under it), redraws only what changed, posts, and then tells the
 * clients the frame is done, so they draw at most once per display frame
 * and not at all while the window is hidden.
 */
class WindowRenderer(
    private val server: WaylandServer,
    private val window: XdgToplevel,
    /** Keeps alpha, for a window drawn over the wallpaper; a full-screen one stays opaque. */
    private val translucent: Boolean = false,
) {
    private val thread = HandlerThread("wl-render").apply { start() }
    private val handler = Handler(thread.looper)
    private var choreographer: Choreographer? = null
    private val frameRequested = AtomicBoolean(false)

    /** Guards [native]: a frame and the view's surface going away never overlap. */
    private val lock = ReentrantLock()
    private var native = 0L
    private var width = 0
    private var height = 0

    /** Filled where no surface covers the window, as 0xAARRGGBB. */
    @Volatile
    var background = 0xFF1A1B26.toInt()
        set(value) {
            if (value == field) return
            field = value
            redrawAll = true
            requestFrame()
        }

    /** How much of the view, from its top, the window shows in (the rest is under the keyboard); 0 for all of it. */
    @Volatile
    var visibleHeight = 0

    private var drawn: List<Layer> = emptyList()

    @Volatile
    private var redrawAll = true
    private val dirty = IntArray(4)
    private val callback = Choreographer.FrameCallback(::frame)

    /** What's on screen, bottom to top; read by hit testing on the main thread. */
    @Volatile
    var scene: List<Layer> = emptyList()
        private set

    /** Draws into [surface] of [w] x [h] pixels from now on (the view's surface was created or resized). */
    fun attach(
        surface: android.view.Surface,
        w: Int,
        h: Int,
    ) {
        lock.withLock {
            if (native != 0L) Native.windowRelease(native)
            native = Native.windowCreate(surface, w, h, translucent)
            width = w
            height = h
            redrawAll = true
        }
        requestFrame()
    }

    /** Stops drawing; returns once no frame uses the view's surface any more, as SurfaceHolder requires. */
    fun detach() =
        lock.withLock {
            if (native != 0L) Native.windowRelease(native)
            native = 0L
        }

    fun release() {
        detach()
        thread.quitSafely()
    }

    /** Draws a frame at the next vsync; cheap to call from any thread, as often as needed. */
    fun requestFrame() {
        if (!frameRequested.compareAndSet(false, true)) return
        handler.post {
            val c = choreographer ?: Choreographer.getInstance().also { choreographer = it }
            c.postFrameCallback(callback)
        }
    }

    private fun frame(nanos: Long) {
        frameRequested.set(false)
        lock.withLock {
            if (native == 0L) return
            val layers = synchronized(server) { buildScene() }
            // A failed lock leaves the damage it took undrawn: draw everything next time.
            redrawAll = collectDirty(layers) && !draw(layers)
            drawn = layers
            scene = layers
        }
        // After posting: the clients' next frames are drawn against what's now on screen.
        synchronized(server) {
            val time = (nanos / 1_000_000).toInt()
            for (l in scene) l.surface.flushFrames(time)
        }
    }

    /** Sets [dirty] to what needs drawing; false if nothing does. Every store's damage is taken either way. */
    private fun collectDirty(layers: List<Layer>): Boolean {
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (layer in layers) {
            val store = layer.store ?: continue
            val d = synchronized(store) { store.takeDamage() } ?: continue
            // Buffer pixels to window pixels; round outwards.
            val sx = layer.width.toFloat() / store.width
            val sy = layer.height.toFloat() / store.height
            l = minOf(l, layer.x + (d[0] * sx).toInt())
            t = minOf(t, layer.y + (d[1] * sy).toInt())
            r = maxOf(r, layer.x + kotlin.math.ceil(d[2] * sx).toInt())
            b = maxOf(b, layer.y + kotlin.math.ceil(d[3] * sy).toInt())
        }
        // Anything moved, appeared or went away: redraw it all rather than track old positions.
        if (redrawAll || layers != drawn) {
            l = 0
            t = 0
            r = width
            b = height
        }
        dirty[0] = l.coerceAtLeast(0)
        dirty[1] = t.coerceAtLeast(0)
        dirty[2] = r.coerceAtMost(width)
        dirty[3] = b.coerceAtMost(height)
        return dirty[0] < dirty[2] && dirty[1] < dirty[3]
    }

    private fun draw(layers: List<Layer>): Boolean {
        if (!Native.windowBegin(native, dirty, background)) return false
        for (layer in layers) {
            val store = layer.store ?: continue
            synchronized(store) {
                if (store.ptr != 0L) Native.windowBlit(native, store.ptr, layer.x, layer.y, layer.width, layer.height, store.opaque)
            }
        }
        Native.windowEnd(native)
        return true
    }

    /** The window's surfaces bottom to top, then its popups; call with the server locked. */
    private fun buildScene(): List<Layer> {
        val scale = server.output.scale
        val layers = ArrayList<Layer>()
        val g = window.xdg.effectiveGeometry
        addTree(layers, window.surface, -g.x * scale, -g.y * scale, scale, inPopup = false)
        for (popup in server.popups) {
            if (server.toplevelOf(popup.surface) !== window) continue
            val origin = geometryOrigin(popup.xdg, scale) ?: continue
            val pg = popup.xdg.effectiveGeometry
            addTree(layers, popup.surface, origin.first - pg.x * scale, origin.second - pg.y * scale, scale, inPopup = true)
        }
        return layers
    }

    private fun addTree(
        layers: MutableList<Layer>,
        surface: Surface,
        x: Int,
        y: Int,
        scale: Int,
        inPopup: Boolean,
    ) {
        for (sub in surface.children) if (sub.below) addTree(layers, sub.surface, x + sub.x * scale, y + sub.y * scale, scale, inPopup)
        // Buffer pixels to window pixels: 1:1 when the client renders at our scale. The size of the pixels drawn: a
        // commit of a new size (a window resizing) has them only once they're copied, and till then the old ones would stretch.
        val store = surface.store
        val w = (store?.width ?: surface.bufferWidth) * scale / surface.bufferScale
        val h = (store?.height ?: surface.bufferHeight) * scale / surface.bufferScale
        layers += Layer(surface, store, x, y, w, h, scale, inPopup)
        for (sub in surface.children) if (!sub.below) addTree(layers, sub.surface, x + sub.x * scale, y + sub.y * scale, scale, inPopup)
    }

    /** Window-pixel position of [xdg]'s window geometry; popups slide back on screen instead of a full constraint solver. */
    private fun geometryOrigin(
        xdg: XdgSurface,
        scale: Int,
        depth: Int = 0,
    ): Pair<Int, Int>? {
        if (xdg.toplevel === window) return 0 to 0
        val popup = xdg.popup ?: return null
        // Parents that loop (a client bug) would recurse forever.
        if (depth >= MAX_NESTING) return null
        val parent = geometryOrigin(popup.parent ?: return null, scale, depth + 1) ?: return null
        val p = popup.position
        val x = (parent.first + p.x * scale).coerceIn(0, (width - p.width * scale).coerceAtLeast(0))
        val bottom = visibleHeight.takeIf { it in 1..height } ?: height
        val y = (parent.second + p.y * scale).coerceIn(0, (bottom - p.height * scale).coerceAtLeast(0))
        return x to y
    }

    /** The surface under window pixel ([x], [y]) as last drawn: popups first, then the window, whose base surface takes what's left. */
    fun hitTest(
        x: Float,
        y: Float,
    ): SurfaceHit? {
        val layers = scene
        for (l in layers.asReversed()) {
            if (l.store == null || l.surface.destroyed || x < l.x || y < l.y || x >= l.x + l.width || y >= l.y + l.height) continue
            return SurfaceHit(l.surface, (x - l.x) / l.scale, (y - l.y) / l.scale, l.inPopup)
        }
        val base = layers.firstOrNull { it.surface === window.surface } ?: return null
        return SurfaceHit(base.surface, (x - base.x) / base.scale, (y - base.y) / base.scale)
    }

    /** Whether this window has a popup open; a tap elsewhere closes it. */
    val hasPopups get() = scene.any { it.inPopup }
}
