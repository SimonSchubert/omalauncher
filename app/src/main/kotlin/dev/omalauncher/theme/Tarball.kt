package dev.omalauncher.theme

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FilterInputStream
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Unpacks a GitHub tarball at [url] into [into], without the top directory
 * GitHub wraps it in. Takes regular files whose path [keep] accepts, and
 * none that would land outside [into]. [onProgress] gets the megabytes read
 * so far; past [maxMegabytes] it gives up. Blocking.
 */
internal fun unpackTarball(
    url: String,
    into: File,
    onProgress: (Int) -> Unit = {},
    maxMegabytes: Int = Int.MAX_VALUE,
    keep: (String) -> Boolean,
) {
    val conn = URL(url).openConnection()
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    var read = 0L
    val counting =
        object : FilterInputStream(conn.getInputStream()) {
            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int =
                super.read(b, off, len).also {
                    if (it > 0) {
                        val before = read shr 20
                        read += it
                        if (read shr 20 != before) {
                            check(read shr 20 <= maxMegabytes) { "bigger than $maxMegabytes MB" }
                            onProgress((read shr 20).toInt())
                        }
                    }
                }
        }
    val root = into.canonicalFile
    TarArchiveInputStream(GZIPInputStream(counting.buffered(1 shl 16))).use { tar ->
        while (true) {
            val entry = tar.nextEntry ?: break
            // Entries look like <repo>-<ref>/path...
            val path = entry.name.substringAfter('/')
            if (!entry.isFile || path.isEmpty() || !keep(path)) continue
            val out = File(root, path)
            if (!out.canonicalPath.startsWith(root.path + File.separator)) continue
            out.parentFile?.mkdirs()
            out.outputStream().use { tar.copyTo(it) }
        }
    }
}
