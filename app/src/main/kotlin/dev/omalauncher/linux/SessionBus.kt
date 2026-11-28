package dev.omalauncher.linux

import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "session-bus"

/** Restarts of a crashing dbus-daemon back off up to this. */
private const val MAX_BACKOFF_MS = 60_000L

/**
 * The guest's D-Bus session bus, which a desktop's login starts and the guest
 * has no login for. Without one every GTK app and Chromium complained that
 * it couldn't reach the bus, and what needs it (the portals, settings shared
 * between an app's windows) didn't work. One dbus-daemon runs for as long as
 * Linux does, at the address [Proot] puts into every guest command's
 * environment. Services start on it on demand, as their packages register them.
 */
class SessionBus(
    private val proot: Proot,
) {
    /** Whether the bus should run; off while Linux is stopped or being removed. Guarded by [runLock]. */
    private var running = false
    private val runLock = Object()
    private var started = false

    /** Starts the bus, or resumes it after [stop]. */
    fun start() {
        synchronized(runLock) {
            running = true
            runLock.notify()
        }
        if (started) return
        started = true
        thread(name = "session-bus", isDaemon = true) { supervise() }
    }

    /** Stops restarting the bus; the caller ends the running one along with the other guest processes. */
    fun stop() = synchronized(runLock) { running = false }

    /** Keeps dbus-daemon running. It replaces a socket file an earlier one left behind. */
    private fun supervise() {
        var backoff = 1000L
        while (true) {
            synchronized(runLock) { while (!running) runLock.wait() }
            val started = SystemClock.elapsedRealtime()
            val code =
                runCatching {
                    proot.run("exec dbus-daemon --session --address=$ADDRESS --nofork --nopidfile") { Log.i(TAG, "[dbus-daemon] $it") }
                }.getOrElse { -1 }
            if (SystemClock.elapsedRealtime() - started > MAX_BACKOFF_MS) backoff = 1000L
            Log.w(TAG, "dbus-daemon exited $code; restarting in ${backoff / 1000} s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    companion object {
        /** DBUS_SESSION_BUS_ADDRESS in the guest: where systemd puts a user's bus, so what looks there without asking finds it too. */
        const val ADDRESS = "unix:path=${Proot.GUEST_RUNTIME_DIR}/bus"
    }
}
