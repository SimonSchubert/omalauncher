package dev.omalauncher.wayland

import java.util.concurrent.atomic.AtomicInteger

class CompositorResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_compositor"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> client.register(Surface(client, args.newId(), version))
            1 -> client.register(Region(client, args.newId(), version))
        }
    }
}

/** wl_region: rectangles added and subtracted in order, in surface-local logical pixels. */
class Region(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_region"

    /** Each is add (1) or subtract (0), then x, y, width, height. Replaced, never changed, so a surface can keep it. */
    var ops: List<IntArray> = emptyList()
        private set

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1, 2 -> ops = ops + intArrayOf(if (opcode == 1) 1 else 0, args.int(), args.int(), args.int(), args.int())
        }
    }
}

/** Whether a region made of [ops] (see [Region.ops]) holds the point; later rectangles win. */
internal fun regionContains(
    ops: List<IntArray>,
    x: Float,
    y: Float,
): Boolean {
    var inside = false
    for (op in ops) {
        if (x >= op[1] && y >= op[2] && x < op[1] + op[3] && y < op[2] + op[4]) inside = op[0] == 1
    }
    return inside
}

/** Something that gives a wl_surface meaning: xdg_surface or wl_subsurface. */
interface SurfaceRole {
    fun onCommit()
}

/**
 * A surface's latest pixels, kept by us (see Native.storeAlloc). Written by
 * the client's thread on commit and read by its window's render thread;
 * both hold [this] while touching the pixels.
 */
class PixelStore(
    val width: Int,
    val height: Int,
    val opaque: Boolean,
) {
    var ptr = Native.storeAlloc(width, height)
        private set

    /** Changed area since the window last drew it, in buffer pixels; see [takeDamage]. */
    private var damage: IntArray? = null

    fun addDamage(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        val d = damage
        damage =
            if (d == null) {
                intArrayOf(x, y, x + w, y + h)
            } else {
                intArrayOf(minOf(d[0], x), minOf(d[1], y), maxOf(d[2], x + w), maxOf(d[3], y + h))
            }
    }

    /** The damaged rect (left, top, right, bottom) since the last call, or null. Hold the lock. */
    fun takeDamage(): IntArray? = damage.also { damage = null }

    @Synchronized
    fun free() {
        Native.storeFree(ptr)
        ptr = 0
    }
}

class Surface(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_surface"

    private var pendingBuffer: ShmBuffer? = null
    private var pendingAttach = false
    private var pendingScale = 1
    private var pendingInput: List<IntArray>? = null
    private var pendingInputSet = false

    /** Where the surface takes input, as [Region.ops]; null for all of it. */
    @Volatile
    private var inputRegion: List<IntArray>? = null

    /** Damage since the last commit: surface-coordinate rects, then buffer-coordinate rects. */
    private val pendingDamage = ArrayList<IntArray>()
    private val pendingBufferDamage = ArrayList<IntArray>()
    private val pendingFrames = mutableListOf<Int>()
    private val frames = mutableListOf<Int>()

    /** What's on screen. Replaced (never resized) when the buffer size or opacity changes. */
    @Volatile
    var store: PixelStore? = null
        private set

    var bufferScale = 1
        private set

    /** Buffer size of the last committed content; 0 with no content. */
    var bufferWidth = 0
        private set
    var bufferHeight = 0
        private set

    /** Size in logical (surface-local) coordinates. */
    val width get() = bufferWidth / bufferScale
    val height get() = bufferHeight / bufferScale

    val hasContent get() = bufferWidth > 0

    /**
     * Whether a point in surface-local logical pixels is in the input region. GL toolkits
     * (wxWidgets: PrusaSlicer's 3D view) draw into a subsurface with an empty one, so input
     * goes to the widget beneath it.
     */
    fun takesInput(
        x: Float,
        y: Float,
    ) = inputRegion?.let { regionContains(it, x, y) } ?: true

    var role: SurfaceRole? = null

    /** Set once the client destroyed it; input and drawing skip it from then on. */
    @Volatile
    var destroyed = false
        private set

    /** Subsurfaces, drawn above the parent unless [Subsurface.below]. */
    val children = ArrayList<Subsurface>()

    private var enteredOutput = false

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> { // attach
                pendingBuffer = client.get<ShmBuffer>(args.obj())
                pendingAttach = true
            }
            2 -> pendingDamage += intArrayOf(args.int(), args.int(), args.int(), args.int())
            3 -> pendingFrames += args.newId()
            4 -> Unit // opaque region: we track opacity by format
            5 -> { // set_input_region; the region as it is now, not as it changes later
                pendingInput = args.obj().takeIf { it != 0 }?.let { client.get<Region>(it)?.ops ?: emptyList() }
                pendingInputSet = true
            }
            6 -> commit()
            7 -> Unit // buffer transform
            8 -> pendingScale = args.int().coerceIn(1, 4)
            9 -> pendingBufferDamage += intArrayOf(args.int(), args.int(), args.int(), args.int())
        }
    }

    private fun commit() {
        val scaleChanged = pendingScale != bufferScale
        bufferScale = pendingScale
        if (pendingInputSet) {
            pendingInputSet = false
            inputRegion = pendingInput
        }
        if (pendingAttach) {
            pendingAttach = false
            // A buffer destroyed since the attach counts as none (as in libwayland): its pool may be
            // gone too, and referencing it again would unmap and close the pool's fd a second time.
            val buffer = pendingBuffer?.takeIf { client.objects[it.id] === it }
            pendingBuffer = null
            if (buffer == null) {
                bufferWidth = 0
                bufferHeight = 0
                replaceStore(null)
            } else {
                bufferWidth = buffer.width
                bufferHeight = buffer.height
                upload(buffer, full = scaleChanged)
            }
        }
        pendingDamage.clear()
        pendingBufferDamage.clear()
        frames += pendingFrames
        pendingFrames.clear()
        children.forEach { it.applyPosition() }
        if (!enteredOutput) {
            enteredOutput = true
            client.server.outputsOf(client).forEach { send(event(0).obj(it.id)) }
        }
        role?.onCommit()
        client.server.surfaceCommitted(this)
    }

    /** Queues the copy of [buffer]'s damaged pixels for after the lock; the client gets the buffer back then. */
    private fun upload(
        buffer: ShmBuffer,
        full: Boolean,
    ) {
        val opaque = buffer.format == 1
        val current = store
        val fresh = current == null || current.width != buffer.width || current.height != buffer.height || current.opaque != opaque
        // A new store is only shown once its pixels are in: never a frame of uninitialized memory.
        val target = if (fresh) PixelStore(buffer.width, buffer.height, opaque) else current!!
        val rects = if (fresh || full) listOf(intArrayOf(0, 0, buffer.width, buffer.height)) else damageRects(buffer)
        buffer.pool.ref()
        client.defer {
            if (rects.isNotEmpty()) {
                val flat = IntArray(rects.size * 4)
                rects.forEachIndexed { i, r -> r.copyInto(flat, i * 4) }
                synchronized(target) {
                    if (target.ptr != 0L && buffer.copyTo(target, flat, rects.size)) {
                        for (r in rects) target.addDamage(r[0], r[1], r[2], r[3])
                    }
                }
            }
            buffer.pool.unref()
            buffer.release()
            if (fresh) {
                // Destroyed or resized meanwhile (another client thread can't, but a disconnect can): drop it.
                if (!destroyed && bufferWidth == target.width && bufferHeight == target.height) replaceStore(target) else target.free()
            }
            client.server.contentChanged(this)
        }
    }

    /** This commit's damage in buffer pixels, clamped to the buffer. */
    private fun damageRects(buffer: ShmBuffer): List<IntArray> {
        val s = bufferScale
        // Clients damage "everything" with INT32_MAX sizes: all math in Long, clamped to the buffer.
        val all =
            pendingBufferDamage.map { r -> LongArray(4) { r[it].toLong() } } +
                pendingDamage.map { r -> LongArray(4) { r[it].toLong() * s } }
        return all.mapNotNull { r ->
            val x = r[0].coerceIn(0, buffer.width.toLong())
            val y = r[1].coerceIn(0, buffer.height.toLong())
            val right = (r[0] + r[2]).coerceIn(x, buffer.width.toLong())
            val bottom = (r[1] + r[3]).coerceIn(y, buffer.height.toLong())
            if (right > x && bottom > y) intArrayOf(x.toInt(), y.toInt(), (right - x).toInt(), (bottom - y).toInt()) else null
        }
    }

    private fun replaceStore(next: PixelStore?) {
        val old = store
        store = next
        old?.free()
    }

    /** Sends wl_callback.done for pending frame callbacks: right after the window showed this surface's content. */
    fun flushFrames(timeMs: Int) {
        frames.forEach { cb ->
            client.send(Event(cb, 0).uint(timeMs))
            client.send(Event(1, 1).uint(cb))
        }
        frames.clear()
    }

    val wantsFrame get() = frames.isNotEmpty()

    override fun onDestroy() {
        destroyed = true
        // Its window loses what it showed; found now, while the surface still leads there.
        val renderer = client.server.toplevelOf(this)?.renderer
        // Callbacks that will never fire still free their ids.
        (frames + pendingFrames).forEach { client.send(Event(1, 1).uint(it)) }
        frames.clear()
        pendingFrames.clear()
        children.toList().forEach { it.parentDestroyed() }
        replaceStore(null)
        bufferWidth = 0
        role = null
        client.server.surfaceDestroyed(this)
        renderer?.requestFrame()
    }
}

class ShmResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_shm"

    init {
        send(event(0).uint(0)) // argb8888
        send(event(0).uint(1)) // xrgb8888
    }

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) {
            val newId = args.newId()
            val fd = args.fd()
            val size = args.int()
            if (fd < 0) return client.postError(id, INVALID_FD, "create_pool without an fd")
            if (size <= 0) {
                Native.close(fd)
                return client.postError(id, INVALID_STRIDE, "invalid pool size $size")
            }
            val pool = ShmPool(client, newId, fd, size)
            if (pool.ptr == 0L) {
                Native.close(fd)
                return client.postError(id, INVALID_FD, "can't map the pool")
            }
            client.register(pool)
        }
    }

    companion object {
        const val INVALID_FORMAT = 0
        const val INVALID_STRIDE = 1
        const val INVALID_FD = 2
    }
}

class ShmPool(
    client: Client,
    id: Int,
    private val fd: Int,
    size: Int,
) : Resource(client, id, 1) {
    override val iface = "wl_shm_pool"

    @Volatile
    var size = size
        private set

    @Volatile
    var ptr = Native.mmap(fd, size)
        private set

    /** The pool object, its buffers and uploads in progress each hold a reference; the mapping lives while any do. */
    private val refs = AtomicInteger(1)

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> {
                val newId = args.newId()
                val offset = args.int()
                val width = args.int()
                val height = args.int()
                val stride = args.int()
                val format = args.uint()
                if (format != 0 && format != 1) return client.postError(id, ShmResource.INVALID_FORMAT, "unsupported format $format")
                if (width <= 0 || height <= 0 || stride < width.toLong() * 4 || offset < 0 || offset.toLong() + stride.toLong() * height > size) {
                    return client.postError(id, ShmResource.INVALID_STRIDE, "invalid buffer ${width}x$height stride $stride offset $offset")
                }
                ref()
                client.register(ShmBuffer(client, newId, this, offset, width, height, stride, format))
            }
            1 -> destroy()
            2 -> {
                val newSize = args.int()
                // A pool only grows. An upload may be reading the old mapping: it holds a reference, and
                // remapping while it runs would pull the pages from under it, so wait for the copy (it's short).
                if (newSize > size) synchronized(this) {
                    val grown = Native.mmap(fd, newSize)
                    if (grown == 0L) return client.postError(id, ShmResource.INVALID_FD, "can't map the pool")
                    Native.munmap(ptr, size)
                    ptr = grown
                    size = newSize
                }
            }
        }
    }

    override fun onDestroy() = unref()

    fun ref() {
        refs.incrementAndGet()
    }

    fun unref() {
        if (refs.decrementAndGet() == 0) {
            synchronized(this) {
                Native.munmap(ptr, size)
                ptr = 0
            }
            Native.close(fd)
        }
    }
}

class ShmBuffer(
    client: Client,
    id: Int,
    val pool: ShmPool,
    private val offset: Int,
    val width: Int,
    val height: Int,
    private val stride: Int,
    val format: Int,
) : Resource(client, id, 1) {
    override val iface = "wl_buffer"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) destroy()
    }

    /** Copies [count] rects of [flat] (x, y, w, h each) into [store]. */
    fun copyTo(
        store: PixelStore,
        flat: IntArray,
        count: Int,
    ): Boolean =
        synchronized(pool) {
            // A pool can be resized smaller than a buffer by a buggy client; never read past the mapping.
            if (pool.ptr == 0L || offset + stride.toLong() * height > pool.size) return false
            Native.storeCopy(pool.ptr, offset, stride, format, store.ptr, flat, count) == 0
        }

    /** Hands the buffer back to the client; thread-safe. */
    fun release() {
        if (client.alive) send(event(0))
    }

    override fun onDestroy() = pool.unref()
}

class SubcompositorResource(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "wl_subcompositor"

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                val newId = args.newId()
                val surface = client.get<Surface>(args.obj()) ?: return
                val parent = client.get<Surface>(args.obj()) ?: return
                // A loop of subsurfaces would recurse forever when the window draws.
                if (surface === parent || surface.isAncestorOf(parent)) return client.postError(id, 0, "subsurface would be its own ancestor")
                client.register(Subsurface(client, newId, surface, parent))
            }
        }
    }

    private fun Surface.isAncestorOf(other: Surface): Boolean = children.any { it.surface === other || it.surface.isAncestorOf(other) }
}

/** Subsurfaces are treated as desynchronized: their commits apply immediately. */
class Subsurface(
    client: Client,
    id: Int,
    val surface: Surface,
    parent: Surface,
) : Resource(client, id, 1),
    SurfaceRole {
    override val iface = "wl_subsurface"

    /** Null once the parent is gone; the subsurface is then hidden. */
    var parent: Surface? = parent
        private set

    var x = 0
        private set
    var y = 0
        private set
    var below = false
        private set
    private var pendingX = 0
    private var pendingY = 0

    init {
        surface.role = this
        parent.children += this
    }

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> {
                pendingX = args.int()
                pendingY = args.int()
            }
            2 -> below = false // place_above
            3 -> below = true // place_below
            4, 5 -> Unit // set_sync / set_desync
        }
    }

    override fun onCommit() = Unit

    /** Position is double-buffered state of the parent surface. */
    fun applyPosition() {
        x = pendingX
        y = pendingY
    }

    fun parentDestroyed() {
        parent?.children?.remove(this)
        parent = null
    }

    override fun onDestroy() {
        // Looked up before detaching: afterwards the surface no longer leads to its window.
        val renderer = parent?.let { client.server.toplevelOf(it) }?.renderer
        parent?.children?.remove(this)
        if (surface.role === this) surface.role = null
        renderer?.requestFrame()
    }
}
