package dev.omalauncher.linux

import android.content.Context
import dev.omalauncher.LauncherData
import dev.omalauncher.upgrade.writeAtomically
import java.io.File

/** Paths and process launching for the proot'd Arch rootfs. */
class Proot(
    private val context: Context,
) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    val filesDir: File = context.filesDir
    val rootfs = File(filesDir, "arch")
    val tmpDir = File(filesDir, "tmp")

    /** Bound to XDG_RUNTIME_DIR inside the guest; holds the Wayland socket and the bridges' FIFOs. */
    val runtimeDir = File(filesDir, "xdg")
    val waylandSocket = File(runtimeDir, "wayland-0")
    val keymap = File(filesDir, "keymap.xkb")

    /** The guest user's home, /root, and the launcher's places in it. */
    val home = File(rootfs, "root")
    val applications = File(home, ".local/share/applications")
    val launcherConfig = File(home, ".config/omalauncher")

    /**
     * A listable stand-in for /sys: apps may only read /sys/devices/system/cpu,
     * and tools like btop abort when they can't walk the directories above it.
     */
    private val sysDir = File(filesDir, "sys")

    /** Stand-ins for /proc files Android hides from apps. */
    val procShim = ProcShim(File(filesDir, "proc"))

    /** How guest apps render OpenGL and Vulkan: in software, or on the GPU once [Gpu] turns it on. */
    @Volatile var graphicsEnv: List<String> = Gpu.SOFTWARE_ENV

    /** Replaces /usr/bin/bwrap in the guest, see assets/bwrap-shim.sh. */
    private val bwrapShim = File(filesDir, "bwrap-shim")

    /** Launcher data the guest sees in its home, kept outside the rootfs; see [LauncherData]. */
    private val dataBinds =
        listOf(
            "$GUEST_HOME/.local/share/omarchy" to LauncherData.omarchyDir(context),
            "$GUEST_HOME/.config/omalauncher/widgets" to LauncherData.widgetsDir(context),
            "$GUEST_HOME/.config/omalauncher/style" to LauncherData.styleDir(context),
            "$GUEST_HOME/.config/omarchy/themes" to LauncherData.userThemesDir(context),
            "$GUEST_HOME/.config/omarchy/backgrounds" to LauncherData.userBackgroundsDir(context),
        )

    /**
     * Android's Chinese, Japanese and Korean font, which the guest has none of (Arch's is ~300 MB).
     * Absent on some devices; the guest then goes without, as before.
     */
    private val cjkFont = File("/system/fonts/NotoSansCJK-Regular.ttc")

    /** Host directories bound over guest paths by [command]. */
    private val binds = listOf("/tmp" to tmpDir, "/dev/shm" to tmpDir, GUEST_RUNTIME_DIR to runtimeDir) + dataBinds

    /**
     * Where an absolute guest path lives on the Android side, following the
     * directory binds in [command]: /tmp in the guest isn't rootfs/tmp.
     */
    fun hostFile(guestPath: String): File {
        for ((guest, host) in binds) {
            if (guestPath == guest || guestPath.startsWith("$guest/")) return File(host, guestPath.removePrefix(guest).removePrefix("/"))
        }
        return File(rootfs, guestPath.removePrefix("/"))
    }

    /** The guest path of a host file, the reverse of [hostFile]; null if the guest can't see it. */
    fun guestPath(host: File): String? {
        for ((guest, dir) in binds) {
            if (host.startsWith(dir)) return (guest + "/" + host.relativeTo(dir).path).trimEnd('/')
        }
        return if (host.startsWith(rootfs)) "/" + host.relativeTo(rootfs).path else null
    }

    /**
     * Resets what only lives as long as our process (guest processes die with
     * it): /tmp, the runtime dir, a stale pacman lock. Writes the host-side
     * stand-ins every guest command needs. Call once, before any guest runs.
     */
    fun prepare() {
        for (dir in listOf(tmpDir, runtimeDir)) {
            LauncherData.deleteTree(dir)
            dir.mkdirs()
        }
        // proot skips a bind whose host directory is missing, and the guest would then write into the rootfs.
        // The empty mount points make the bound directories show up in listings of their parents.
        for ((guest, host) in dataBinds) {
            host.mkdirs()
            if (rootfs.isDirectory) File(rootfs, guest.removePrefix("/")).mkdirs()
        }
        File(rootfs, "var/lib/pacman/db.lck").delete()
        // An empty stand-in, so fontconfig lists the bound font when it reads the directory.
        if (rootfs.isDirectory) {
            val stub = File(rootfs, GUEST_CJK_FONT.removePrefix("/"))
            if (cjkFont.isFile) {
                stub.parentFile?.mkdirs()
                stub.createNewFile()
            } else {
                stub.delete()
            }
        }
        for (sub in listOf("devices/system/cpu", "class", "block", "bus", "dev", "fs", "kernel")) File(sysDir, sub).mkdirs()
        val shim = context.assets.open("bwrap-shim.sh").use { it.readBytes() }
        if (!bwrapShim.isFile || !bwrapShim.readBytes().contentEquals(shim)) writeAtomically(bwrapShim, shim, executable = true)
    }

    private fun command(script: String): List<String> =
        listOf(
            File(nativeDir, "libproot.so").path,
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r",
            rootfs.path,
        ) + procShim.binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf(
                "-b", "/dev",
                "-b", "/proc",
                "-b", "${sysDir.path}:/sys",
                "-b", "/sys/devices/system/cpu",
            ) + (if (cjkFont.isFile) listOf("-b", "${cjkFont.path}:$GUEST_CJK_FONT") else emptyList()) +
            binds.flatMap { (guest, host) -> listOf("-b", "${host.path}:$guest") } +
            listOf(
                "-b", "${bwrapShim.path}:/usr/bin/bwrap",
                "-w", GUEST_HOME,
                "/usr/bin/env", "-i",
                "HOME=$GUEST_HOME",
                "USER=root",
                "LANG=C.UTF-8",
                // Android's zone name (e.g. Europe/Berlin); the guest otherwise runs on UTC.
                "TZ=${java.util.TimeZone.getDefault().id}",
                "TERM=xterm-256color",
                // mise-installed tools (coding agents, node) live in the last two.
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/root/.local/bin:/root/.local/share/mise/shims",
                // As (fake) root, GNU tar restores directory modes with fchmodat(AT_SYMLINK_NOFOLLOW),
                // which fails with ENOENT under proot on newer kernels and fails the extraction (mise.run).
                "TAR_OPTIONS=--no-same-permissions",
                // mise's default npm installer hardlinks packages out of its store in ~/.cache/mise; under proot
                // (--link2symlink) those become symlinks into the store, and `mise cache clear` after installs and
                // updates then breaks them (Grok: "no platform binary installed"). npm copies the files.
                "MISE_NPM_PACKAGE_MANAGER=npm",
                // Coding agents come from mise and update with the system (SystemUpdates); updating themselves
                // too would bypass that and leave a second copy on a phone that's short on storage.
                "DISABLE_AUTOUPDATER=1",
                "OPENCODE_DISABLE_AUTOUPDATE=1",
                "GROK_DISABLE_AUTOUPDATER=1",
                "BROWSER=xdg-open",
                // micro takes taps and scrolling; yazi and git open files with it.
                "EDITOR=micro",
                "XDG_RUNTIME_DIR=$GUEST_RUNTIME_DIR",
                "WAYLAND_DISPLAY=${waylandSocket.name}",
                "XDG_SESSION_TYPE=wayland",
                "GDK_BACKEND=wayland",
                "QT_QPA_PLATFORM=wayland",
                "MOZ_ENABLE_WAYLAND=1",
                "SDL_VIDEODRIVER=wayland",
            ) + graphicsEnv + listOf("/bin/sh", "-c", script)

    /**
     * Kills every proot we started and every guest process: before the rootfs
     * is deleted. A SIGKILLed proot doesn't take its tracees along
     * (--kill-on-exit needs a clean exit); they run on untraced and spin, so
     * they go too, found by their executable, proot's loader. Our own
     * processes are the only ones whose /proc entries we can read.
     */
    fun killAll() {
        val proot = File(nativeDir, "libproot.so").path
        val loader = File(nativeDir, "libproot-loader.so").path
        for (dir in File("/proc").listFiles().orEmpty()) {
            val pid = dir.name.toIntOrNull() ?: continue
            val argv0 = runCatching { File(dir, "cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().decodeToString() }.getOrNull()
            val exe = runCatching { android.system.Os.readlink(File(dir, "exe").path) }.getOrNull()
            if (argv0 == proot || exe == loader) runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
        }
    }

    /** A guest `sh -c [script]`, stderr merged into stdout. Needs [prepare] to have run. */
    fun process(script: String): ProcessBuilder =
        ProcessBuilder(command(script)).apply {
            environment().apply {
                clear()
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
                put("PROOT_TMP_DIR", tmpDir.path)
                put("LD_LIBRARY_PATH", nativeDir.path)
            }
            redirectErrorStream(true)
        }

    companion object {
        const val GUEST_HOME = "/root"

        /** XDG_RUNTIME_DIR in the guest, bound to [runtimeDir]. */
        const val GUEST_RUNTIME_DIR = "/run/user/0"

        private const val GUEST_CJK_FONT = "/usr/share/fonts/android/NotoSansCJK-Regular.ttc"
    }
}
