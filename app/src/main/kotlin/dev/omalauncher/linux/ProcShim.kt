package dev.omalauncher.linux

import android.os.SystemClock
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Android's SELinux policy hides several /proc files from apps (stat, uptime,
 * loadavg, ...), which tools like btop require. For each unreadable one we
 * bind a stand-in over it, like proot-distro, and keep it current: /proc/stat
 * reports the CPU time of our own process tree (launcher + everything in the
 * guest), the only CPU usage an app is allowed to see.
 */
class ProcShim(
    private val dir: File,
) {
    private val cpus = Runtime.getRuntime().availableProcessors()
    private val startMs = SystemClock.elapsedRealtime()
    private val kernel = System.getProperty("os.version") ?: "unknown"

    /** Names of the /proc files we can't read directly. */
    private val hidden: Set<String> by lazy {
        listOf("stat", "uptime", "loadavg", "version", "vmstat").filterNot { readable("/proc/$it") }.toSet()
    }

    /** Guest path -> host stand-in, only for files we can't read directly. */
    val binds: Map<String, File> by lazy {
        dir.mkdirs()
        // These never change; update() keeps only stat and uptime current.
        write("loadavg") { "0.00 0.00 0.00 1/1 1\n" }
        write("version") { "Linux version $kernel (android) #1 SMP PREEMPT\n" }
        write("vmstat") { "pgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n" }
        update()
        hidden.associate { "/proc/$it" to File(dir, it) }
    }

    private var started = false
    private val lock = Object()

    /** Only kept current while something can show it: a Linux window or the home screen's widgets. */
    private var active = true

    fun start() {
        if (started || binds.isEmpty()) return
        started = true
        thread(name = "proc-shim", isDaemon = true) {
            while (true) {
                synchronized(lock) { while (!active) lock.wait() }
                runCatching { update() }
                Thread.sleep(1000)
            }
        }
    }

    fun setActive(on: Boolean) =
        synchronized(lock) {
            active = on
            lock.notify()
        }

    private fun readable(path: String) = runCatching { File(path).inputStream().use { it.read() } }.isSuccess

    private fun update() {
        val uptimeS = SystemClock.elapsedRealtime() / 1000.0
        val (user, system) = ownCpuTicks()
        // Ticks are 1/100 s. Everything we can't attribute to our processes counts as idle.
        val total = ((SystemClock.elapsedRealtime() - startMs) / 10) * cpus
        val idle = (total - user - system).coerceAtLeast(0)
        write("stat") {
            buildString {
                appendLine("cpu  $user 0 $system $idle 0 0 0 0 0 0")
                for (i in 0 until cpus) appendLine("cpu$i ${user / cpus} 0 ${system / cpus} ${idle / cpus} 0 0 0 0 0 0")
                appendLine("intr 0")
                appendLine("ctxt 0")
                appendLine("btime ${System.currentTimeMillis() / 1000 - uptimeS.toLong()}")
                appendLine("processes 0")
                appendLine("procs_running 1")
                appendLine("procs_blocked 0")
            }
        }
        write("uptime") { "%.2f %.2f\n".format(Locale.ROOT, uptimeS, uptimeS * cpus) }
    }

    /** Sums utime/stime over all processes we can see, which Android limits to our own uid. */
    private fun ownCpuTicks(): Pair<Long, Long> {
        var user = 0L
        var system = 0L
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }.orEmpty().forEach { p ->
            val stat = runCatching { File(p, "stat").readText() }.getOrNull() ?: return@forEach
            // Fields after the parenthesised command name; utime/stime are fields 14/15.
            val fields = stat.substringAfterLast(')').trim().split(' ')
            user += fields.getOrNull(11)?.toLongOrNull() ?: 0
            system += fields.getOrNull(12)?.toLongOrNull() ?: 0
        }
        return user to system
    }

    private inline fun write(
        name: String,
        content: () -> String,
    ) {
        if (name !in hidden) return
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content())
        tmp.renameTo(File(dir, name))
    }
}
