package dev.omalauncher.widgets.runtime

import android.os.FileObserver
import dev.omalauncher.readUpTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A watched file is read up to this much; widgets show a few values, not whole logs. */
private const val MAX_FILE_BYTES = 1 shl 20

/**
 * [file]'s text now and after every change, for `source x = file(...)`.
 * Changes arrive through inotify on its folder, so a file replaced by a rename
 * (how editors and `jq ... > tmp && mv` save) is seen too. A stat every few
 * seconds covers a folder that doesn't exist yet or was replaced. A missing
 * file reads as "".
 */
fun watchFile(file: File): Flow<String> =
    callbackFlow {
        val changed = Channel<Unit>(Channel.CONFLATED)
        var observer: FileObserver? = null
        var watched: File? = null

        fun watch() {
            val dir = file.parentFile ?: return
            if (observer != null && watched == dir && dir.isDirectory) return
            observer?.stopWatching()
            observer = null
            if (!dir.isDirectory) return
            val mask = FileObserver.CLOSE_WRITE or FileObserver.MODIFY or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or FileObserver.CREATE or FileObserver.DELETE
            observer =
                object : FileObserver(dir, mask) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if (path == file.name) changed.trySend(Unit)
                    }
                }.also { it.startWatching() }
            watched = dir
        }

        val loop =
            launch {
                var stamp: Pair<Long, Long>? = null
                var force = true
                while (isActive) {
                    watch()
                    val now = file.lastModified() to file.length()
                    if (force || now != stamp) {
                        stamp = now
                        send(read(file))
                    }
                    force = withTimeoutOrNull(if (observer != null) 30_000L else 5_000L) { changed.receive() } != null
                    // A writer may still be at it; read once it's done.
                    if (force) delay(100)
                }
            }
        awaitClose {
            loop.cancel()
            observer?.stopWatching()
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

private fun read(file: File): String =
    runCatching {
        if (!file.isFile) return ""
        String(file.inputStream().use { it.readUpTo(MAX_FILE_BYTES) }, Charsets.UTF_8).trimEnd()
    }.getOrDefault("")
