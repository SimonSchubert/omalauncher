package dev.omalauncher.linux

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Ends a proot started with [Proot.process] and every guest process under it. [Process.destroy] only sends
 * SIGTERM, which proot ignores, so a stream or a hung shell kept running after the launcher let go of it; and
 * a SIGKILLed proot leaves its tracees running on untraced (a `while :; do echo; done` spins at full speed), so
 * each process in the tree is SIGKILLed, children before the proot that would otherwise reap them.
 */
fun killTree(process: Process) {
    val root = pidOf(process)
    if (root != null && process.isAlive) {
        // A few rounds: a shell may fork a new child between reading /proc and the kill.
        repeat(3) {
            val tree = descendants(root)
            if (tree.isEmpty()) return@repeat
            for (pid in tree) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
        }
        runCatching { Os.kill(root, OsConstants.SIGKILL) }
    } else {
        // The proot died on its own (killed by Android, say) while its guest ran a command: that command's
        // processes went to init, out of the tree, and spin untraced until the phone restarts (#317).
        killUntracedGuests()
    }
    // Closes the pipes too.
    process.destroy()
}

/**
 * SIGKILLs every guest process no proot traces any more. Proot never lets go of a live tracee (a guest's own
 * PTRACE_DETACH is emulated), so one without a tracer is always left from a proot that died.
 */
private fun killUntracedGuests() {
    for (dir in File("/proc").listFiles().orEmpty()) {
        val pid = dir.name.toIntOrNull() ?: continue
        val exe = runCatching { Os.readlink(File(dir, "exe").path) }.getOrNull()
        val status = runCatching { File(dir, "status").readText() }.getOrNull()
        if (untracedGuest(exe, status)) runCatching { Os.kill(pid, OsConstants.SIGKILL) }
    }
}

/** Whether a process with this /proc exe link and status is a guest process (run by proot's loader) without a tracer. */
internal fun untracedGuest(
    exe: String?,
    status: String?,
): Boolean {
    if (exe == null || status == null || !exe.endsWith("/libproot-loader.so")) return false
    val tracer = status.lineSequence().firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()
    return tracer == "0"
}

/** Android's Process says its pid only in its text, "Process[pid=1234, hasExited=false]". */
private fun pidOf(process: Process): Int? = Regex("""pid=(\d+)""").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()

/**
 * The fields of a /proc/<pid>/stat after the process name, from its state on, so ppid is [1] and utime/stime are
 * [11]/[12]; null for a process we can't read. "pid (comm) state ppid …": the name may hold spaces and parentheses,
 * so this reads after its last ')'.
 */
internal fun statFields(proc: File): List<String>? =
    runCatching { File(proc, "stat").readText() }.getOrNull()?.substringAfterLast(')')?.trim()?.split(' ')

/** Every process under [root], from /proc's parent links; our own are the only ones we can read. */
private fun descendants(root: Int): List<Int> {
    val children = HashMap<Int, MutableList<Int>>()
    for (dir in File("/proc").listFiles().orEmpty()) {
        val pid = dir.name.toIntOrNull() ?: continue
        val ppid = statFields(dir)?.getOrNull(1)?.toIntOrNull() ?: continue
        children.getOrPut(ppid) { ArrayList() }.add(pid)
    }
    val out = ArrayList<Int>()
    val queue = ArrayDeque(listOf(root))
    while (queue.isNotEmpty()) {
        for (c in children[queue.removeFirst()].orEmpty()) {
            out.add(c)
            queue.add(c)
        }
    }
    return out
}
