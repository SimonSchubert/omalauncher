package dev.omalauncher.linux

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.LauncherData
import dev.omalauncher.R
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileNotFoundException

/**
 * Opt-in GPU acceleration for Linux apps. The device node picks the driver:
 * PanVK-kbase on /dev/mali0 (Mali v10+: Pixel 7 and newer, recent MediaTek),
 * or Turnip on /dev/kgsl-3d0 when that node is an Adreno 6xx or newer.
 * OpenGL is zink on top of that Vulkan driver, through a patched libEGL that
 * keeps zink when the compositor only offers wl_shm. Frames still reach
 * us as wl_shm buffers (MESA_VK_WSI_DEBUG=sw), so the compositor doesn't change.
 *
 * Each driver is a prerelease and talks to a kernel interface that Android
 * updates can change, so it is only used after a check passes, and checked
 * again after every system update; until then apps render in software.
 */
class Gpu(
    private val context: Context,
    private val proot: Proot,
    private val pacman: Pacman,
) {
    private val prefs = LauncherData.prefs(context)

    /** The pinned driver for this phone, or null when we have none. */
    private val spec: GpuDriver? = gpuKind(
        File("/dev/mali0").exists(),
        File("/dev/kgsl-3d0").exists(),
        runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText() }.getOrNull(),
    )?.let(GpuDrivers::of)

    /** The phone has a GPU we have a driver for. */
    val supported = spec != null

    /** "Mali" or "Adreno", for the settings row. Empty when [supported] is false. */
    val driverLabel: String get() = spec?.label ?: ""

    /** The user turned it on; it may still be inactive until [recheck] passes. */
    var wanted by mutableStateOf(prefs.getBoolean(WANTED, false))
        private set

    /** Linux apps started from now on render on the GPU. */
    var active by mutableStateOf(false)
        private set

    /** The GPU the last check found, e.g. "Mali-G715 MC7". */
    var device by mutableStateOf(prefs.getString(DEVICE, null))
        private set

    var state by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** What changes under the driver: the kernel and Android's own GPU driver come with system updates. */
    private val build get() = Build.FINGERPRINT + " " + System.getProperty("os.version")

    /**
     * Activates the GPU if it passed its check on this system build. After
     * a system update, rechecks it first (in the background: apps render in
     * software meanwhile) and stays off if the check fails.
     */
    suspend fun recheck() {
        val driver = spec ?: return
        if (!wanted || active || state is JobState.Working) return
        if (!proot.hostFile(driver.driverPath).isFile) return turnOff()
        // The APK carries a different Mesa than the guest has, or none was installed
        // yet (Mali before it used the patched Mesa). The env names that Mesa.
        val mesaCurrent = runCatching { proot.hostFile(GpuDrivers.MESA_ZINK_STAMP).readText().trim() }
            .getOrNull() == GpuDrivers.MESA_ZINK_TAR_SHA256
        if (mesaCurrent && prefs.getString(CHECKED_BUILD, null) == build) {
            // An older install wrote Mali's manifest on pacman's path. The env now
            // names the /usr/local file, so that file has to exist before we activate.
            if (!proot.hostFile(driver.icdPath).isFile) {
                retireLegacyManifest(driver)
                if (!run(R.string.linux_job_installing, GpuDrivers.manifestScript(driver))) {
                    proot.graphicsEnv = SOFTWARE_ENV
                    conceal(driver)
                    return
                }
            }
            return activate(driver)
        }
        // Install and check in one run: run() records the build as checked on success.
        val ok = if (mesaCurrent) {
            Log.i(TAG, "system changed since the last GPU check, checking again")
            run(R.string.linux_job_checking, GpuDrivers.probeScript(driver))
        } else {
            Log.i(TAG, "the patched Mesa changed, installing it and checking again")
            stageMesaBundle()
            run(R.string.linux_job_installing, GpuDrivers.mesaZinkScript() + " && " + GpuDrivers.probeScript(driver))
        }
        if (ok) {
            activate(driver)
        } else {
            proot.graphicsEnv = SOFTWARE_ENV
            conceal(driver)
        }
    }

    /** Installs the driver and Mesa, then checks them; turns on only if the check passes. */
    suspend fun turnOn() {
        val driver = spec ?: return
        if (state is JobState.Working) return
        prefs.edit().putBoolean(WANTED, true).apply()
        wanted = true
        stageMesaBundle()
        retireLegacyManifest(driver)
        val script = GpuDrivers.installScript(driver) + " && " + GpuDrivers.probeScript(driver)
        // The install script runs pacman.
        if (run(R.string.linux_job_installing, script, pacman.lock)) {
            activate(driver)
        } else {
            // Nothing to come back to on the next start; the failure stays on screen.
            prefs.edit().putBoolean(WANTED, false).apply()
            wanted = false
            active = false
            proot.graphicsEnv = SOFTWARE_ENV
            conceal(driver)
        }
    }

    /**
     * Back to software rendering for apps started from now on. The library
     * stays, so turning on again is quick. The ICD manifest does not: while
     * it exists the Vulkan loader finds it with no env var set.
     */
    fun turnOff() {
        prefs.edit().putBoolean(WANTED, false).remove(CHECKED_BUILD).apply()
        wanted = false
        active = false
        state = JobState.Idle
        proot.graphicsEnv = SOFTWARE_ENV
        spec?.let(::conceal)
    }

    private fun activate(driver: GpuDriver) {
        active = true
        proot.graphicsEnv = driver.env
    }

    /** Drops the manifest so a later app cannot load the driver by the default search path. */
    private fun conceal(driver: GpuDriver) {
        proot.hostFile(driver.icdPath).delete()
        retireLegacyManifest(driver)
    }

    /** Before writing the /usr/local manifest, and on the way out. */
    private fun retireLegacyManifest(driver: GpuDriver) = GpuDrivers.retireLegacyManifest(driver, proot.hostFile(GpuDrivers.LEGACY_MALI_ICD))

    /**
     * The patched Mesa tar (scripts/mesa/build-aarch64.sh) is in the APK's assets.
     * A build without that asset leaves the install script to fail closed on the missing file.
     */
    private fun stageMesaBundle() {
        val dest = proot.hostFile(GpuDrivers.MESA_ZINK_BUNDLE)
        val input = try {
            context.assets.open("mesa-zink/mesa-zink.tar.xz")
        } catch (_: FileNotFoundException) {
            return
        }
        dest.parentFile?.mkdirs()
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
    }

    /** Runs [script]; on success remembers the build it passed on and the GPU's name from vulkaninfo. */
    private suspend fun run(
        @StringRes action: Int,
        script: String,
        lock: Mutex? = null,
    ): Boolean {
        val (ok, tail) = proot.trackedJob(action, script, lock) { state = it }
        val name = tail.toList().firstNotNullOfOrNull { DEVICE_NAME.find(it)?.groupValues?.get(1) }
        Log.i(TAG, "${context.getString(action)}: ${if (ok) "ok, $name" else "failed"}")
        if (ok) {
            device = name
            prefs.edit().putString(CHECKED_BUILD, build).putString(DEVICE, name).apply()
        }
        return ok
    }

    companion object {
        private const val TAG = "gpu"
        private const val WANTED = "gpu_wanted"
        private const val CHECKED_BUILD = "gpu_checked_build"
        private const val DEVICE = "gpu_device"

        private val DEVICE_NAME = Regex("""deviceName\s*=\s*(.+)""")
    }
}
