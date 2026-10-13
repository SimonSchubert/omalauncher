package dev.omalauncher.linux

import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "agent-daemon"

/**
 * Claude Code's background sessions run under a daemon that a session starts
 * on demand. Started from a terminal, it lives in that window's proot, and
 * --kill-on-exit takes it and every background session down with the window.
 * This keeps it in a proot of its own instead, for as long as the launcher
 * runs, so background sessions outlive the window that started them.
 *
 * Sessions in other windows reach it over its socket in the shared /tmp. That
 * needs a proot that reports the real uid_map to readers and translates
 * SO_PEERCRED across proot instances; with an older one the daemon refuses to
 * start, and this backs off until it gives up.
 */
class AgentDaemon(
    private val proot: Proot,
    private val agents: AgentStore,
) {
    /** Whether the daemon should run; off while Linux is being removed. */
    @Volatile
    private var running = false
    private var started = false

    /** Keeps the daemon running, or resumes it after [stop]. */
    fun start() {
        running = true
        if (started) return
        started = true
        thread(name = TAG, isDaemon = true) {
            var delayMs = MIN_DELAY_MS
            var quickFailures = 0
            while (true) {
                if (!running || agents.installed.none { it.bin == "claude" }) {
                    Thread.sleep(POLL_MS)
                    continue
                }
                val startedAt = System.currentTimeMillis()
                val code =
                    runCatching {
                        val p = proot.process("exec claude daemon run").start()
                        p.inputStream.bufferedReader().useLines { lines -> lines.forEach { Log.i(TAG, it) } }
                        p.waitFor()
                    }.getOrElse {
                        Log.w(TAG, "cannot start", it)
                        -1
                    }
                val ranMs = System.currentTimeMillis() - startedAt
                Log.i(TAG, "exited with $code after ${ranMs / 1000}s")
                // Ended with the other guest processes as Linux is removed: not a failure.
                if (!running) continue
                if (ranMs >= HEALTHY_MS) {
                    quickFailures = 0
                    delayMs = MIN_DELAY_MS
                } else if (++quickFailures >= MAX_QUICK_FAILURES) {
                    Log.w(TAG, "keeps exiting right away; not restarting it until the launcher restarts")
                    return@thread
                } else {
                    delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
                }
                Thread.sleep(delayMs)
            }
        }
    }

    /** Stops restarting the daemon; the caller ends the running one along with the other guest processes. */
    fun stop() {
        running = false
    }

    private companion object {
        const val POLL_MS = 60_000L
        const val MIN_DELAY_MS = 5_000L
        const val MAX_DELAY_MS = 600_000L
        const val HEALTHY_MS = 60_000L
        const val MAX_QUICK_FAILURES = 5
    }
}
