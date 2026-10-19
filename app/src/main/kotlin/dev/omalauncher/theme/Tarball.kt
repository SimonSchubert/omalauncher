package dev.omalauncher.theme

import dev.omalauncher.CountingInputStream
import dev.omalauncher.openHttp
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    val conn = openHttp(url)
    try {
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
        var megabytes = 0L
        val counting =
            CountingInputStream(conn.inputStream) { read ->
                if (read shr 20 != megabytes) {
                    megabytes = read shr 20
                    check(megabytes <= maxMegabytes) { "bigger than $maxMegabytes MB" }
                    onProgress(megabytes.toInt())
                }
            }
        unpack(counting, into, keep)
    } finally {
        conn.disconnect()
    }
}

private fun unpack(
    input: InputStream,
    into: File,
    keep: (String) -> Boolean,
) {
    val root = into.canonicalFile
    TarArchiveInputStream(GZIPInputStream(input.buffered(1 shl 16))).use { tar ->
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
