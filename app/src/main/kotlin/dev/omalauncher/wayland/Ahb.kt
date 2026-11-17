package dev.omalauncher.wayland

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** DRM fourccs of the formats offered: bytes R, G, B, A (AHardwareBuffer's R8G8B8A8), and B, G, R, A where that's safe. */
internal const val DRM_FORMAT_ABGR8888 = 0x34324241
internal const val DRM_FORMAT_XBGR8888 = 0x34324258
internal const val DRM_FORMAT_ARGB8888 = 0x34325241
internal const val DRM_FORMAT_XRGB8888 = 0x34325258

/** AHardwareBuffer formats: R8G8B8A8_UNORM, and HAL_PIXEL_FORMAT_BGRA_8888, which the NDK doesn't name. */
private const val AHB_RGBA = 1
private const val AHB_BGRA = 5

/** The DRM formats this device's allocator gives us, with their AHardwareBuffer format; found once. */
private val formats: Map<Int, Int> by lazy {
    buildMap {
        put(DRM_FORMAT_ABGR8888, AHB_RGBA)
        put(DRM_FORMAT_XBGR8888, AHB_RGBA)
        // Zink renders GL into BGRA swapchains: without it, GL apps' frames are copied. Only with an Adreno GPU: on
        // Mali (Dimensity 7300), SurfaceFlinger aborts when it draws a BGRA buffer itself (a screenshot, an
        // animation), which restarts the phone's whole UI. Gralloc allocating one doesn't tell.
        if (File("/dev/kgsl-3d0").exists() && Native.ahbSupported(AHB_BGRA)) {
            put(DRM_FORMAT_ARGB8888, AHB_BGRA)
            put(DRM_FORMAT_XRGB8888, AHB_BGRA)
        }
    }
}

/** Largest width or height of a buffer, as for a pixel store. */
private const val MAX_SIZE = 16384

/**
 * oma_ahb_allocator_v1 (scripts/zero-copy/oma-ahb-v1.xml): buffers we allocate as AHardwareBuffers and the client
 * renders into through their dma-bufs (GPU apps, through a Vulkan layer in the guest). A surface showing one is a
 * layer of its own on screen, see [WindowRenderer]: the pixels are never copied, unlike wl_shm's.
 */
class AhbAllocator(
    client: Client,
    id: Int,
    version: Int,
) : Resource(client, id, version) {
    override val iface = "oma_ahb_allocator_v1"

    init {
        for (format in formats.keys) send(event(2).uint(format))
    }

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        when (opcode) {
            0 -> destroy()
            1 -> allocate(args.newId(), args.int(), args.int(), args.uint())
        }
    }

    private fun allocate(
        bufferId: Int,
        width: Int,
        height: Int,
        format: Int,
    ) {
        val ahbFormat = formats[format] ?: return client.postError(id, INVALID_FORMAT, "format $format")
        if (width !in 1..MAX_SIZE || height !in 1..MAX_SIZE) return client.postError(id, INVALID_SIZE, "size ${width}x$height")
        val info = IntArray(3)
        // A few milliseconds under the server lock, a handful of times per window: not worth deferring.
        val ahb = Native.ahbAllocate(width, height, ahbFormat, info)
        // Registered either way: the id is the client's, and a failed buffer still needs destroying.
        val opaque = format == DRM_FORMAT_XBGR8888 || format == DRM_FORMAT_XRGB8888
        client.register(AhbBuffer(client, bufferId, ahb, width, height, opaque))
        if (ahb == 0L) return send(event(1).obj(bufferId))
        // fd, offset, stride, modifier (linear: 0, 0); the fd isn't part of the message body.
        send(event(0).obj(bufferId).fd(info[0], close = true).uint(info[1]).uint(info[2]).uint(0).uint(0))
    }

    private companion object {
        const val INVALID_FORMAT = 0
        const val INVALID_SIZE = 1
    }
}

/**
 * The wl_buffer of an AHardwareBuffer from [AhbAllocator]. The client gets it back (wl_buffer.release) once nothing
 * uses its pixels: its surface moved on to another buffer, no copy of it is in progress, and the display let go.
 */
class AhbBuffer(
    client: Client,
    id: Int,
    ahb: Long,
    val width: Int,
    val height: Int,
    /** An X format: alpha is to be ignored. */
    val opaque: Boolean,
) : Resource(client, id, 1) {
    override val iface = "wl_buffer"

    /** The AHardwareBuffer, 0 if allocating failed or the client destroyed it. Hold [this] while using it. */
    @Volatile
    var ptr = ahb
        private set

    private val holds = AtomicInteger()

    fun hold() {
        holds.incrementAndGet()
    }

    fun unhold() {
        if (holds.decrementAndGet() != 0) return
        // Under the lock onDestroy takes: once the client destroyed the buffer, its delete_id is on the way and the
        // client may reuse the id, so a release must not follow it. (The display's hold ends on a binder thread.)
        synchronized(this) { if (ptr != 0L && client.alive) send(event(0)) }
    }

    /**
     * Holds the buffer if something still does (its surface, say); false once the client got it back, when it may be
     * drawing into it again: then it's not to be shown or copied.
     */
    fun tryHold(): Boolean {
        while (true) {
            val n = holds.get()
            if (n <= 0) return false
            if (holds.compareAndSet(n, n + 1)) return true
        }
    }

    /** A hold for the display, which gives it back by running it once the buffer is off screen; null as [tryHold]. */
    fun displayHold(): Runnable? = if (tryHold()) Runnable(::unhold) else null

    override fun request(
        opcode: Int,
        args: Args,
    ) {
        if (opcode == 0) destroy()
    }

    // The display keeps its own reference to a buffer it shows, so the client may destroy one that's still on screen.
    override fun onDestroy() =
        synchronized(this) {
            Native.ahbRelease(ptr)
            ptr = 0
        }
}
