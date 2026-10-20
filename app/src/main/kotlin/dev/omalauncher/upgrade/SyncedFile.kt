package dev.omalauncher.upgrade

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.widgets.runtime.watchFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A file the launcher writes as things change on the phone, and an agent or
 * the user edits too: style.toml, layout.toml. It tells the launcher's own
 * writes from theirs, never writes over an edit it hasn't read, or over a file
 * that didn't parse, and keeps errors.txt next to it saying what was ignored.
 * [render] gives the file's text when a save goes out. Main thread.
 */
class SyncedFile(
    val file: File,
    private val errorsFile: File,
    private val what: String,
    private val render: () -> String,
) {
    private val tag = file.name

    /** Why parts of the file were ignored: the last read's, then what [report] adds. */
    private var problems = emptyList<String>()
    private val reported = LinkedHashSet<String>()

    /** What errors.txt says, for home to show: without a terminal nobody would see it. */
    var errors by mutableStateOf(emptyList<String>())
        private set

    /**
     * The file's texts we wrote, or read at the start, oldest first, to tell them from others' when the
     * file changes: a write's change can arrive after the next write.
     */
    private val ours = ArrayDeque<String>()

    /**
     * The file's text as we last knew it, written or read, without its end. A save finding something
     * else there leaves it be: someone wrote it since, and the watch will read it.
     */
    @Volatile private var onDisk: String? = null

    /** Bumped when the watch takes in someone else's text; a save started before it doesn't go out. */
    @Volatile private var external = 0

    /** Whether the file parsed when last read. Until it does, nothing is saved over it: it holds the user's work. */
    var readable = true

    /** Called with each text written, once it's on disk; on the IO thread for [save]. */
    var onWritten: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val writing = Mutex()
    private var saving: Job? = null
    private var watching: Job? = null

    /** The file's text, or null when there's none yet; errors.txt as an earlier run left it, so the first read deletes it if nothing's wrong now. */
    fun open(): String? {
        errors = runCatching { errorsFile.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() } }.getOrNull().orEmpty()
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return null
        synchronized(ours) { ours += text }
        onDisk = text.trimEnd()
        return text
    }

    /** Writes the file now, once: the watch started next mustn't take a missing file for one deleted. */
    fun create() {
        val written = render()
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomically(file, written.toByteArray())
        }.onSuccess { onWritten(written) }
            .onFailure { Log.w(tag, "can't save $what", it) }
        synchronized(ours) { ours += written }
        onDisk = written.trimEnd()
        saveErrors()
    }

    /**
     * Watches the file for others' edits and hands each one's text to [changed]; a deleted file reads as "",
     * and [exists] says which it was.
     */
    fun start(changed: (String) -> Unit) {
        if (watching != null) return
        watching =
            scope.launch {
                watchFile(file).collect { text ->
                    // Ours: it and anything we wrote before it are through. watchFile trims the end, so compare without it.
                    val mine =
                        synchronized(ours) {
                            val i = ours.indexOfFirst { it.trimEnd() == text }
                            repeat(i + 1) { ours.removeFirst() }
                            i >= 0
                        }
                    if (mine) return@collect
                    // The file is theirs now; a save waiting to go out, or already going, would overwrite it.
                    external++
                    saving?.cancel()
                    onDisk = text
                    reported.clear()
                    changed(text)
                }
            }
    }

    val exists get() = file.isFile

    /** Writes the file once changes stop coming: a stepper tapped five times writes it once. */
    fun save() {
        saving?.cancel()
        if (!readable) return
        val since = external
        saving =
            scope.launch {
                delay(SAVE_DELAY_MS)
                val text = render()
                synchronized(ours) {
                    ours += text
                    while (ours.size > MAX_OURS) ours.removeFirst()
                }
                // Not cancellable once it starts, so it checks instead that nobody wrote the file meanwhile.
                withContext(Dispatchers.IO + NonCancellable) {
                    writing.withLock {
                        val there = runCatching { file.takeIf { it.isFile }?.readText() ?: "" }.getOrNull()?.trimEnd()
                        if (since != external || there != onDisk) {
                            synchronized(ours) { ours.remove(text) }
                            return@withLock
                        }
                        runCatching {
                            file.parentFile?.mkdirs()
                            writeAtomically(file, text.toByteArray())
                        }.onSuccess {
                            onDisk = text.trimEnd()
                            onWritten(text)
                        }.onFailure { Log.w(tag, "can't save $what", it) }
                    }
                }
            }
    }

    /** What the last read found wrong. */
    fun setProblems(all: List<String>) {
        problems = all
        saveErrors()
    }

    /**
     * Notes a problem with a value that read fine but doesn't fit the phone,
     * like a theme that isn't installed. Cleared when the file changes next.
     */
    fun report(problem: String) {
        if (reported.add(problem)) saveErrors()
    }

    /** Drops what no longer holds, as after the launcher changed what [stale] problems are about. */
    fun drop(stale: (String) -> Boolean) {
        problems = problems.filterNot(stale)
        reported.removeAll(stale)
        saveErrors()
    }

    private fun saveErrors() {
        val all = problems + reported
        if (all == errors) return
        errors = all
        scope.launch(Dispatchers.IO) {
            writing.withLock {
                runCatching { if (all.isEmpty()) errorsFile.delete() else writeAtomically(errorsFile, (all.joinToString("\n") + "\n").toByteArray()) }
                    .onFailure { Log.w(tag, "can't write the errors of $what", it) }
            }
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 400L

        /** Writes whose change we may not have seen yet; more would mean the watch has stopped. */
        const val MAX_OURS = 8
    }
}
