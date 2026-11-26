package dev.omalauncher.wayland

/**
 * What each buffer of a window's Android surface still lacks. The surface has a few buffers that take turns, so the
 * one a frame draws into shows the window as it was a frame or two ago: it needs this frame's changes and the ones
 * it sat out. Each frame's dirty rect is added to every buffer's, and the buffer being drawn takes its own.
 *
 * A buffer is known by the address of its pixels ([repaint]'s id) and by a fingerprint of what was drawn into it
 * (see Native.windowCanary): Android may free a buffer and put a new, empty one at the same address. A buffer that
 * isn't known, or doesn't hold what we left in it, is repainted whole.
 */
internal class BufferDamage {
    private val ids = LongArray(SIZE)
    private val canaries = LongArray(SIZE)

    /** Left, top, right, bottom per buffer; nothing to draw while left >= right. */
    private val pending = Array(SIZE) { IntArray(4) }

    /** When each was last drawn, to drop the one unused longest; 0 for a free place. */
    private val used = LongArray(SIZE)
    private var clock = 0L

    /**
     * Turns [dirty] (left, top, right, bottom), what changed in the window this frame, into what has to be drawn
     * into buffer [id], whose content now has the fingerprint [canary]: the whole [width] x [height] window for a
     * buffer that isn't known.
     */
    fun repaint(
        id: Long,
        canary: Long,
        dirty: IntArray,
        width: Int,
        height: Int,
    ) {
        var at = -1
        for (i in 0 until SIZE) {
            if (used[i] == 0L) continue
            val p = pending[i]
            if (p[0] >= p[2] || p[1] >= p[3]) {
                dirty.copyInto(p)
            } else {
                p[0] = minOf(p[0], dirty[0])
                p[1] = minOf(p[1], dirty[1])
                p[2] = maxOf(p[2], dirty[2])
                p[3] = maxOf(p[3], dirty[3])
            }
            if (ids[i] == id) at = i
        }
        if (at >= 0 && canaries[at] == canary) {
            pending[at].copyInto(dirty)
        } else {
            if (at < 0) at = (0 until SIZE).minBy { used[it] }
            ids[at] = id
            dirty[0] = 0
            dirty[1] = 0
            dirty[2] = width
            dirty[3] = height
        }
        pending[at].fill(0)
        used[at] = ++clock
    }

    /** Buffer [id], just given to [repaint], is drawn and now has the fingerprint [canary]. */
    fun drawn(
        id: Long,
        canary: Long,
    ) {
        for (i in 0 until SIZE) if (used[i] != 0L && ids[i] == id) canaries[i] = canary
    }

    private companion object {
        /** More than a surface has buffers (three, usually); with fewer places, each frame would repaint everything. */
        const val SIZE = 8
    }
}
