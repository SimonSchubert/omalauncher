package dev.omalauncher.linux

import android.util.Log

/** JNI bindings, see cpp/procstat.c: the CPU time of the processes the app can see. */
object ProcStatNative {
    private val loaded by lazy {
        try {
            System.loadLibrary("procstat")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w("ProcShim", "/proc/stat is counted without procstat", e)
            false
        }
    }

    /** User and system ticks summed over the processes in [proc] (/proc), or null to count them in Kotlin. */
    fun cpuTicks(proc: String): LongArray? = if (loaded) ticks(proc) else null

    @JvmStatic external fun ticks(proc: String): LongArray?
}
