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
}
