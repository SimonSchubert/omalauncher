package dev.omalauncher.wayland

import android.view.Surface
import java.nio.ByteBuffer

/** JNI bindings, see cpp/wlbridge.c. Negative int results are -errno; 0 pointers mean failure. */
object Native {
    init {
        System.loadLibrary("wlbridge")
        installSigbusGuard()
    }

    @JvmStatic external fun listen(path: String): Int

    @JvmStatic external fun accept(fd: Int): Int

    /** Reads into direct [buf] from [off]; [fds] gets the count, then the fds received. */
    @JvmStatic external fun recv(fd: Int, buf: ByteBuffer, off: Int, fds: IntArray): Int

    /** Writes [len] bytes of direct [buf], passing the first [nfds] of [fds] along. */
    @JvmStatic external fun send(fd: Int, buf: ByteBuffer, len: Int, fds: IntArray, nfds: Int): Int

    @JvmStatic external fun close(fd: Int)

    @JvmStatic external fun shutdown(fd: Int)

    @JvmStatic external fun openReadOnly(path: String): Int

    @JvmStatic external fun mmap(fd: Int, size: Int): Long

    @JvmStatic external fun munmap(ptr: Long, size: Int)

    @JvmStatic private external fun installSigbusGuard()

    @JvmStatic external fun storeAlloc(width: Int, height: Int): Long

    @JvmStatic external fun storeFree(ptr: Long)

    /** Copies [count] rects (x, y, w, h in buffer pixels) of a shm buffer into a store of its size. */
    @JvmStatic external fun storeCopy(shm: Long, offset: Int, stride: Int, format: Int, store: Long, rects: IntArray, count: Int): Int

    @JvmStatic external fun windowCreate(surface: Surface, width: Int, height: Int, translucent: Boolean): Long

    @JvmStatic external fun windowRelease(window: Long)

    /** Locks [dirty] (left, top, right, bottom; updated to what was locked) and fills it with [background]. */
    @JvmStatic external fun windowBegin(window: Long, dirty: IntArray, background: Int): Boolean

    @JvmStatic external fun windowBlit(window: Long, store: Long, x: Int, y: Int, width: Int, height: Int, opaque: Boolean)

    @JvmStatic external fun windowEnd(window: Long)

    // AHardwareBuffers, see cpp/ahb.c and Ahb.kt.

    /** Whether buffers of AHardwareBuffer [format] (1 RGBA, 5 BGRA) can be allocated for clients here. */
    @JvmStatic external fun ahbSupported(format: Int): Boolean

    /** A [width] x [height] buffer of AHardwareBuffer [format]; [info] gets its pixels' dma-buf fd (ours to close), offset and bytes per row. 0 on failure. */
    @JvmStatic external fun ahbAllocate(width: Int, height: Int, format: Int, info: IntArray): Long

    @JvmStatic external fun ahbRelease(ahb: Long)

    /** Copies a buffer into the locked window, as [windowBlit] does a store. */
    @JvmStatic external fun windowBlitAhb(window: Long, ahb: Long, x: Int, y: Int, width: Int, height: Int, opaque: Boolean)

    /** A layer of its own above the window's pixels, for a buffer to show without a copy; 0 on failure. */
    @JvmStatic external fun overlayCreate(window: Long): Long

    @JvmStatic external fun txBegin(): Long

    /** Shows [ahb] on [overlay]; [done] runs once the display is done with it, after a later buffer or [txRemove] replaced it. */
    @JvmStatic external fun txSetBuffer(tx: Long, overlay: Long, ahb: Long, done: Runnable, opaque: Boolean)

    /** Shows [overlay]'s whole [srcWidth] x [srcHeight] buffer at [x], [y], scaled to [width] x [height]; [z] from 1 up. */
    @JvmStatic external fun txPlace(tx: Long, overlay: Long, srcWidth: Int, srcHeight: Int, x: Int, y: Int, width: Int, height: Int, z: Int)

    /** Takes [overlay] off screen and frees it. */
    @JvmStatic external fun txRemove(tx: Long, overlay: Long)

    /** Applies and frees [tx]. */
    @JvmStatic external fun txApply(tx: Long)
}
