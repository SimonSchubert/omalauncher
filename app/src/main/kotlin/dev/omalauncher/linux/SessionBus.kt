package dev.omalauncher.linux

import android.os.SystemClock
import android.util.Log
import kotlin.concurrent.thread

private const val TAG = "dbus"
private const val MAX_BACKOFF_MS = 60_000L

/**
 * One guest session bus for all proot commands, rather than a bus per app.
 * It lives in its own proot so closing a Linux window does not stop it.
 *
 * The socket is under the launcher's private runtime directory. Anonymous
 * authentication is needed because proot reports uid 0 to each guest client,
 * while the kernel reports Android's app uid to dbus-daemon across proots.
 */
class SessionBus(private val proot: Proot) {
    @Volatile private var running = false
    private var started = false

    /** Start once per runtime; resume the supervisor after Linux is reinstalled. */
    @Synchronized
    fun start() {
        proot.hostFile(CONFIG).writeText(config)
        running = true
        if (started) return
        started = true
        thread(name = "dbus", isDaemon = true) { supervise() }
    }

    /** Prevent a restart before the caller kills guest processes and removes Linux. */
    @Synchronized
    fun stop() {
        running = false
    }

    private fun supervise() {
        var backoff = 1_000L
        while (true) {
            while (!running) Thread.sleep(1_000)
            val startedAt = SystemClock.elapsedRealtime()
            // Serialize launch against stop(): killAll() follows stop(), so it
            // cannot miss a bus proot that was just about to be started.
            val code = runCatching {
                val process = synchronized(this) { if (running) proot.process(SCRIPT).start() else null }
                if (process == null) -1 else {
                    process.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
                    process.waitFor()
                }
            }.getOrElse { Log.w(TAG, "dbus-daemon failed", it); -1 }
            if (SystemClock.elapsedRealtime() - startedAt > MAX_BACKOFF_MS) backoff = 1_000L
            if (running) Log.w(TAG, "dbus-daemon exited $code; restarting in ${backoff / 1_000} s")
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** The standard service dirs allow independently installed guest D-Bus services to activate. */
    private val config =
        """
        <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
         "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
        <busconfig>
          <type>session</type>
          <keep_umask/>
          <listen>$ADDRESS</listen>
          <auth>ANONYMOUS</auth>
          <allow_anonymous/>
          <standard_session_servicedirs/>
          <policy context="default">
            <allow send_destination="*" eavesdrop="true"/>
            <allow eavesdrop="true"/>
            <allow own="*"/>
          </policy>
        </busconfig>
        """.trimIndent() + "\n"

    companion object {
        const val ADDRESS = "unix:path=${Proot.GUEST_RUNTIME_DIR}/bus"
        private const val CONFIG = "/tmp/omalauncher-dbus.conf"
        private const val SCRIPT = "rm -f ${Proot.GUEST_RUNTIME_DIR}/bus && exec dbus-daemon --config-file=$CONFIG --nofork --nopidfile"
    }
}
