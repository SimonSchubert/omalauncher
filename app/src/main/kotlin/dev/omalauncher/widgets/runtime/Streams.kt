package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.killTree
import dev.omalauncher.linux.shellQuote
import dev.omalauncher.widgets.lang.WidgetError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Long-running guest commands (`source x = stream("playerctl -F ...")`), one
 * process per distinct command however many widgets listen, stopped a few
 * seconds after the last listener goes.
 */
class Streams(
    private val proot: Proot,
    private val scope: CoroutineScope,
) {
    /** [flow] carries lines, then null when the command exits. */
    private class Entry(
        val flow: SharedFlow<String?>,
        var listeners: Int = 0,
    )

    private val entries = HashMap<String, Entry>()

    suspend fun collect(
        command: String,
        onLine: (String) -> Unit,
    ) {
        val entry =
            synchronized(entries) {
                entries[command] ?: run {
                    if (entries.size >= MAX) throw WidgetError("at most $MAX different streams run at once")
                    Entry(open(command)).also { entries[command] = it }
                }
            }
        synchronized(entries) { entry.listeners++ }
        var ended = false
        try {
            // Returning tells the widget the command exited, so it restarts it after a pause.
            entry.flow
                .takeWhile { line ->
                    ended = line == null
                    !ended
                }.collect { onLine(it!!) }
        } finally {
            synchronized(entries) {
                entry.listeners--
                if (ended) {
                    // The next listener must start a new process, not replay this one's end.
                    if (entries[command] === entry) entries.remove(command)
                } else if (entry.listeners == 0) {
                    // The process lingers a few seconds for a quick return (swiping between pages); keep its entry as long.
                    scope.launch {
                        delay(LINGER)
                        synchronized(entries) { if (entry.listeners == 0 && entries[command] === entry) entries.remove(command) }
                    }
                }
            }
        }
    }

    private fun open(command: String): SharedFlow<String?> =
        callbackFlow<String?> {
            val process = proot.process("exec bash -c " + shellQuote(command)).start()
            launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { trySend(it) } }
                    trySend(null)
                } catch (e: IOException) {
                    // Stopping destroys the process mid-read: that's the normal way a stream ends.
                }
                close()
            }
            // Killing the process closes its output, which ends the reader.
            awaitClose { killTree(process) }
        }.flowOn(Dispatchers.IO)
            // The sharing coroutine lives in the engine's scope; if it failed, the app would crash.
            .catch { }
            .shareIn(scope, SharingStarted.WhileSubscribed(LINGER, 0), replay = 1)

    companion object {
        const val MAX = 4
        private const val LINGER = 5000L
    }
}
