package dev.omalauncher.linux

import java.io.File
import java.nio.file.Files

/** A directory exposed to new guest processes; PRoot binds are writable path translations. */
data class StorageBind(val hostPath: String, val guestPath: String)

object StorageBinds {
    const val MAX_BINDS = 16

    private val reserved = listOf(
        "/bin", "/sbin", "/lib", "/lib64", "/usr", "/etc", "/dev", "/proc", "/sys", "/run", "/tmp", "/var",
        "/root/.config", "/root/.local", "/root/.cache",
    )

    fun validate(binds: List<StorageBind>) {
        require(binds.size <= MAX_BINDS) { "At most $MAX_BINDS storage binds are supported" }
        for (bind in binds) {
            for (path in listOf(bind.hostPath, bind.guestPath)) {
                require(path.startsWith('/') && ':' !in path && path.none { it < ' ' || it == '\u007f' }) {
                    "Paths must be absolute and contain no colons or control characters"
                }
                require(path == "/" || path.split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }) {
                    "Paths must not contain empty, '.' or '..' components or a trailing slash"
                }
            }
            require(reserved.none { overlaps(bind.guestPath, it) }) { "${bind.guestPath} overlaps a Linux system or launcher directory" }
        }
        for ((i, bind) in binds.withIndex()) {
            require(binds.take(i).none { overlaps(it.guestPath, bind.guestPath) }) { "Storage bind destinations must not overlap" }
        }
    }

    /** Missing sources must fail, rather than silently saving into a disposable rootfs directory. */
    fun prepare(rootfs: File, binds: List<StorageBind>): List<Pair<String, File>> {
        validate(binds)
        return binds.map { bind ->
            val source = File(bind.hostPath)
            require(!source.canonicalFile.toPath().startsWith(rootfs.canonicalFile.toPath())) {
                "The Android source must be outside the Linux installation"
            }
            require(source.isDirectory && source.canRead() && source.canWrite()) {
                "Cannot access ${bind.hostPath}; check storage access or edit the bind in settings › linux"
            }
            var target = rootfs
            for (part in bind.guestPath.removePrefix("/").split('/')) {
                target = File(target, part)
                require(!Files.isSymbolicLink(target.toPath())) { "Bind destination contains a symbolic link: ${bind.guestPath}" }
            }
            require(!target.exists() || target.isDirectory && target.list()?.isEmpty() == true) {
                "${bind.guestPath} is not an empty directory; move its existing files before binding storage"
            }
            check(target.mkdirs() || target.isDirectory) { "Cannot create ${bind.guestPath}" }
            bind.guestPath to source
        }
    }

    private fun within(path: String, dir: String) = path == dir || dir == "/" || path.startsWith("$dir/")
    private fun overlaps(a: String, b: String) = within(a, b) || within(b, a)
}
