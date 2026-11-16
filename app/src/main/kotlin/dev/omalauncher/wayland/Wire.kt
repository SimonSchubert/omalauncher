package dev.omalauncher.wayland

import java.nio.ByteBuffer

/** Wayland fixed-point 24.8 conversion. */
fun Float.toFixed(): Int = (this * 256f).toInt()

/**
 * Reads the arguments of one request from the connection's receive buffer.
 * One instance per connection, pointed at each message in turn. Fds are
 * taken from the connection's queue in order.
 */
class Args(
    private val buf: ByteBuffer,
    private val fds: ArrayDeque<Int>,
) {
    private var pos = 0
    private var end = 0

    fun reset(
        start: Int,
        end: Int,
    ) {
        pos = start
        this.end = end
    }

    fun int(): Int {
        if (pos + 4 > end) throw IllegalArgumentException("message too short")
        return buf.getInt(pos).also { pos += 4 }
    }

    fun uint(): Int = int()

    fun obj(): Int = int()

    fun newId(): Int = int()

    fun fd(): Int = fds.removeFirstOrNull() ?: -1

    fun string(): String? {
        val len = int()
        if (len == 0) return null
        if (len < 0 || len > end - pos) throw IllegalArgumentException("bad string")
        val bytes = ByteArray(len - 1)
        for (i in bytes.indices) bytes[i] = buf.get(pos + i)
        pos += pad(len)
        return String(bytes)
    }
}

private fun pad(n: Int) = (n + 3) and 3.inv()

/** Builds one event message in a buffer sized to it. */
class Event(
    objectId: Int,
    private val opcode: Int,
) {
    internal var bytes = ByteArray(32)
        private set
    internal var size = 8
        private set
    var fd = -1
        private set

    /** Whether [fd] is ours to close once sent (or dropped), rather than kept for reuse like a keymap. */
    var closeFd = false
        private set

    init {
        putAt(0, objectId)
    }

    private fun putAt(
        index: Int,
        v: Int,
    ) {
        bytes[index] = v.toByte()
        bytes[index + 1] = (v shr 8).toByte()
        bytes[index + 2] = (v shr 16).toByte()
        bytes[index + 3] = (v shr 24).toByte()
    }

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }

    fun int(v: Int) =
        apply {
            ensure(4)
            putAt(size, v)
            size += 4
            putAt(4, (size shl 16) or opcode)
        }

    fun uint(v: Int) = int(v)

    fun fixed(v: Float) = int(v.toFixed())

    fun obj(id: Int) = int(id)

    fun fd(
        fd: Int,
        close: Boolean = false,
    ) = apply {
        this.fd = fd
        closeFd = close
    }

    fun string(s: String?) =
        apply {
            if (s == null) return int(0)
            val encoded = s.toByteArray()
            bytes(encoded, encoded.size + 1)
        }

    /** Closes [fd] if it's ours to close; once only, so a sent and a dropped event can't close it twice. */
    @Synchronized
    fun releaseFd() {
        if (!closeFd) return
        closeFd = false
        Native.close(fd)
    }

    fun ints(values: IntArray) =
        apply {
            int(values.size * 4)
            for (v in values) int(v)
        }

    /** A length-prefixed blob of [len] bytes: [data], then zeros (a string's NUL), padded to 4. */
    private fun bytes(
        data: ByteArray,
        len: Int,
    ) = apply {
        int(len)
        val padded = pad(len)
        ensure(padded)
        data.copyInto(bytes, size)
        bytes.fill(0, size + data.size, size + padded)
        size += padded
        putAt(4, (size shl 16) or opcode)
    }

    init {
        putAt(4, (size shl 16) or opcode)
    }
}
