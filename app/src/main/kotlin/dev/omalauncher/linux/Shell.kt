package dev.omalauncher.linux

import androidx.annotation.StringRes
import dev.omalauncher.R
import dev.omalauncher.widgets.lang.shQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [s] as one single-quoted shell word; the owl language's `sh()` quotes the same way. */
fun shellQuote(s: String) = shQuote(s)

/**
 * Installs [packages] with a full sync, never a partial upgrade, then drops
 * the downloaded packages: storage on phones is tight. [refresh] re-downloads
 * the package databases even if pacman thinks they're current: mirrors lag
 * each other, and a database from one names versions another no longer has.
 */
fun pacmanInstall(
    packages: Collection<String>,
    refresh: Boolean = false,
) = "pacman -Sy${if (refresh) "y" else ""}u --noconfirm --needed ${packages.joinToString(" ") { shellQuote(it) }} && $CLEAN_PACMAN_CACHE"

/** `pacman -Scc --noconfirm` answers its "remove all?" prompt with the default, No, so delete directly. */
const val CLEAN_PACMAN_CACHE = "rm -rf /var/cache/pacman/pkg/*"

/** Progress of a guest job started from the UI: installing an agent, a package, a widget's packages. */
sealed interface JobState {
    data object Idle : JobState

    data class Working(
        @StringRes val action: Int,
        val log: List<String>,
    ) : JobState

    data class Failed(
        val log: List<String>,
    ) : JobState
}

/**
 * The last [max] lines of a command's output, with progress bars kept at their latest state. A terminal's
 * bar redraws with carriage returns. Without a terminal, mise prints a whole new frame every few seconds: a
 * bar line and an indented line per tool under it; each frame replaces the one before.
 */
class OutputTail(
    // Enough for the whole log of most jobs, opened from JobLog to read or copy.
    private val max: Int = 200,
) {
    private val lines = ArrayDeque<String>()

    /** Lines dropped off the front, so [frame] can count from the start of the output. */
    private var dropped = 0

    /** The last bar line and the indented lines under it, as positions in the whole output. */
    private var frame: IntRange? = null

    /** The last line added was in [frame]: an indented line after it is too. */
    private var inFrame = false

    /** The last line ended in a carriage return: the next one draws over it. */
    private var redrawing = false

    /** Adds [raw] (one ending in `\r` is redrawn by the next); false if it was blank. */
    fun add(raw: String): Boolean {
        val redraw = raw.endsWith('\r')
        val drawn = raw.trimEnd('\r').substringAfterLast('\r')
        val text = drawn.trim()
        if (text.isEmpty()) {
            // A newline after a progress bar keeps its last state; a bare carriage return changes nothing.
            if (!redraw) redrawing = false
            return false
        }
        if (redrawing) {
            lines.removeLastOrNull()
            frame = frame?.let { it.first..minOf(it.last, end - 1) }?.takeUnless { it.isEmpty() }
        }
        redrawing = redraw
        when {
            PROGRESS_BAR.containsMatchIn(text) -> {
                frame?.let { removeRange(it) }
                frame = end..end
                inFrame = true
            }
            inFrame && drawn.first().isWhitespace() -> frame = frame?.let { it.first..end }
            else -> inFrame = false
        }
        lines.addLast(text)
        while (lines.size > max) {
            lines.removeFirst()
            dropped++
        }
        return true
    }

    /** The position the next line takes in the whole output. */
    private val end get() = dropped + lines.size

    private fun removeRange(range: IntRange) {
        val from = (range.first - dropped).coerceAtLeast(0)
        val to = (range.last - dropped).coerceAtMost(lines.size - 1)
        for (i in to downTo from) lines.removeAt(i)
    }

    fun toList() = lines.toList()

    fun clear() {
        lines.clear()
        dropped = 0
        frame = null
        inFrame = false
        redrawing = false
    }

    override fun toString() = lines.joinToString("\n")

    private companion object {
        /** A text progress bar: a run of block characters, like mise's `█████░░░░░`. */
        val PROGRESS_BAR = Regex("[░▒▓█▏▎▍▌▋▊▉━]{4,}")
    }
}

/**
 * Runs [script] in the guest to completion, blocking, feeding each output
 * line to [onLine]. Returns the exit code; a command that can't start
 * throws.
 */
fun Proot.run(
    script: String,
    onLine: (String) -> Unit = {},
): Int {
    val process = process(script).start()
    process.inputStream.bufferedReader().use { forEachOutputLine(it, onLine) }
    return process.waitFor()
}

/**
 * Splits [reader] at newlines, as they come. A carriage return ends a line
 * too, as readLine does, but stays on it, so [OutputTail] can tell a
 * progress bar's redraw from a new line.
 */
internal fun forEachOutputLine(
    reader: java.io.Reader,
    onLine: (String) -> Unit,
) {
    val line = StringBuilder()
    while (true) {
        val c = reader.read()
        if (c < 0) break
        when (c.toChar()) {
            '\n' -> onLine(line.toString())
            '\r' -> onLine(line.append('\r').toString())
            else -> {
                line.append(c.toChar())
                continue
            }
        }
        line.clear()
    }
    if (line.isNotEmpty()) onLine(line.toString())
}

/** Like [run], reporting the tail of the output to [report] as it comes. Returns success and the tail. */
fun Proot.runJob(
    script: String,
    tail: OutputTail = OutputTail(),
    report: (List<String>) -> Unit = {},
): Pair<Boolean, OutputTail> {
    val ok =
        runCatching { run(script) { if (tail.add(it)) report(tail.toList()) } == 0 }
            .getOrElse {
                tail.add(it.message ?: it.toString())
                false
            }
    return ok to tail
}

/**
 * Runs [script] as a job the UI shows: [publish] gets Working with the output
 * tail as it comes, then Idle or Failed. With [lock], it waits for it first
 * and shows waiting meanwhile. Returns success and the tail.
 */
suspend fun Proot.trackedJob(
    @StringRes action: Int,
    script: String,
    lock: Mutex? = null,
    publish: (JobState) -> Unit,
): Pair<Boolean, OutputTail> {
    if (lock != null) publish(JobState.Working(R.string.linux_job_waiting, emptyList()))
    val job: suspend () -> Pair<Boolean, OutputTail> = {
        withContext(Dispatchers.IO) {
            publish(JobState.Working(action, emptyList()))
            runJob(script) { publish(JobState.Working(action, it)) }.also { (ok, tail) ->
                publish(if (ok) JobState.Idle else JobState.Failed(tail.toList()))
            }
        }
    }
    return if (lock != null) lock.withLock { job() } else job()
}
