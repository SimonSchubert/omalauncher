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

/** The last [max] lines of a command's output. Progress bars redraw with carriage returns; only their latest state is kept. */
class OutputTail(
    private val max: Int = 12,
) {
    private val lines = ArrayDeque<String>()

    /** Adds [raw]; false if it was blank after dropping redrawn progress. */
    fun add(raw: String): Boolean {
        val text = raw.substringAfterLast('\r').trim()
        if (text.isEmpty()) return false
        lines.addLast(text)
        while (lines.size > max) lines.removeFirst()
        return true
    }

    fun toList() = lines.toList()

    fun clear() = lines.clear()

    override fun toString() = lines.joinToString("\n")
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
    process.inputStream.bufferedReader().forEachLine(onLine)
    return process.waitFor()
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
