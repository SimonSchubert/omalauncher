package dev.omalauncher.linux

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Owns one proot invocation, including cancellation before the first window appears. */
class EmbeddedAppProcess(
    private val builder: () -> ProcessBuilder,
    private val onStopTimeout: () -> Unit = {},
    private val stopTimeoutMs: Long = 3000,
    private val onExit: (Int, String) -> Unit,
) {
    private val lock = Any()
    private var started = false
    private var cancelled = false
    private var stopRequested = false
    private var process: Process? = null

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        thread(name = "embedded-linux-app", isDaemon = true) {
            val output = BoundedAppOutput()
            var code = -1
            try {
                if (synchronized(lock) { cancelled }) return@thread
                val child = builder().redirectErrorStream(true).start()
                val cancel = synchronized(lock) {
                    process = child
                    cancelled
                }
                val reader = thread(name = "embedded-linux-output", isDaemon = true) {
                    try {
                        child.inputStream.reader().use { stream ->
                            val buffer = CharArray(1024)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                output.append(buffer, count)
                            }
                        }
                    } catch (e: Exception) {
                        if (!synchronized(lock) { cancelled }) output.append("\n${e.message.orEmpty()}")
                    }
                }
                if (cancel) requestStop(child)
                // Stream closure on cancellation must not report an exit while the process still lives.
                code = child.waitFor()
                // A foreground command should close its output on exit. An inherited pipe must not hang cleanup.
                reader.join(1000)
            } catch (e: Exception) {
                output.append("\n${e.message.orEmpty()}")
            } finally {
                synchronized(lock) { process = null }
                onExit(code, output.tail())
            }
        }
    }

    /** SIGTERM permits proot's --kill-on-exit to reap tracees. No blocking work on the UI thread. */
    fun close() {
        val child = synchronized(lock) {
            cancelled = true
            process
        }
        child?.let(::requestStop)
    }

    private fun requestStop(child: Process) {
        synchronized(lock) {
            if (stopRequested) return
            stopRequested = true
        }
        thread(name = "embedded-linux-stop", isDaemon = true) { child.destroy() }
        thread(name = "embedded-linux-stop-watch", isDaemon = true) {
            if (!child.waitFor(stopTimeoutMs, TimeUnit.MILLISECONDS)) onStopTimeout()
        }
        // Never SIGKILL just proot: its guest processes would be left running untraced (see Proot.killAll).
        // On timeout keep ownership until the actual exit, and let the UI report that stopping is incomplete.
    }
}

/** A bounded character ring, including when the client never writes a newline. */
internal class BoundedAppOutput(private val capacity: Int = 1800) {
    private val buffer = CharArray(capacity)
    private var next = 0
    private var size = 0

    @Synchronized fun append(chars: CharArray, count: Int) {
        for (i in 0 until count) {
            buffer[next] = chars[i]
            next = (next + 1) % capacity
            if (size < capacity) size++
        }
    }

    fun append(text: String) = append(text.toCharArray(), text.length)

    @Synchronized fun tail(): String {
        val start = (next - size + capacity) % capacity
        val text = CharArray(size) { buffer[(start + it) % capacity] }.concatToString()
        return text.trimEnd('\n', '\r').lines().takeLast(6).joinToString("\n") { it.takeLast(300) }
    }
}
