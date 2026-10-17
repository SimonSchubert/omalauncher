package dev.omalauncher

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** Reads to the end, or [limit] bytes. Not readNBytes: that's API 33, and before it the call throws. */
fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(chunk, 0, minOf(chunk.size, limit - out.size()))
        if (n < 0) break
        out.write(chunk, 0, n)
    }
    return out.toByteArray()
}

/** Lowercase hex, as sha256sum prints it. */
fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Counts the bytes read through it; [onRead] gets the running total after each read. */
class CountingInputStream(
    input: InputStream,
    private val onRead: (Long) -> Unit = {},
) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) onRead(++count) }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int =
        super.read(b, off, len).also {
            if (it > 0) {
                count += it
                onRead(count)
            }
        }
}
