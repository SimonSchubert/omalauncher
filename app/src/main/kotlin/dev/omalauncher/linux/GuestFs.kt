package dev.omalauncher.linux

import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** One row in a folder listing. [path] is the guest path of the entry itself (links not followed). */
data class GuestEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val modified: Long,
    /** Entries in the folder; null for files and unreadable folders. */
    val children: Int?,
)

/**
 * The guest filesystem seen from Android. Guest symlinks are absolute guest paths
 * (/bin -> usr/bin, /etc/localtime -> /usr/share/zoneinfo/...), so they're resolved
 * here against the rootfs rather than by the host, which would escape it. Changes
 * never follow links: deleting a link removes the link, not what it points to.
 */
class GuestFs(
    /** The Android file for an absolute guest path, following proot's binds (see [Proot.hostFile]). */
    private val hostOf: (String) -> File,
    /** Whether a resolved file, links followed by Android, is still one the guest can see (see [Proot.guestVisible]). */
    private val inside: (File) -> Boolean = { true },
) {
    /** Collapses ".", ".." and duplicate slashes; always absolute. */
    fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> out.removeLastOrNull()
                else -> out.addLast(part)
            }
        }
        return "/" + out.joinToString("/")
    }

    fun parent(path: String): String? = if (path == "/") null else normalize("$path/..")

    fun child(
        dir: String,
        name: String,
    ) = normalize("$dir/$name")

    /**
     * The guest path with every symlink resolved inside the guest. With
     * [followLast] false the final component is left as is (to act on a link itself).
     */
    fun resolve(
        path: String,
        followLast: Boolean = true,
    ): String {
        val pending = ArrayDeque(normalize(path).split('/').filter { it.isNotEmpty() })
        var current = ""
        var hops = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part == "..") {
                current = current.substringBeforeLast('/', "")
                continue
            }
            val candidate = "$current/$part"
            val host = hostOf(candidate).toPath()
            val follow = followLast || pending.isNotEmpty()
            if (follow && Files.isSymbolicLink(host)) {
                // Like the kernel's ELOOP: left unfollowed, Android would follow the link itself, out of the rootfs.
                if (++hops > 40) throw IOException("too many levels of symbolic links: $path")
                val target = Files.readSymbolicLink(host).toString()
                if (target.startsWith("/")) current = ""
                target.split('/').filter { it.isNotEmpty() && it != "." }.asReversed().forEach(pending::addFirst)
            } else {
                current = candidate
            }
        }
        return current.ifEmpty { "/" }
    }

    /**
     * The Android file for a guest path, symlinks resolved in the guest. It's never one the guest
     * can't see, like the launcher's own files: a link swapped in after [resolve] fails here.
     */
    fun host(
        path: String,
        followLast: Boolean = true,
    ): File {
        val file = hostOf(resolve(path, followLast))
        // Acting on a link itself, only where it is has to be inside.
        val real = (if (followLast) file else file.parentFile)?.canonicalFile
        if (real != null && !inside(real)) throw IOException("outside Linux: $path")
        return file
    }

    fun isDir(path: String) = host(path).isDirectory

    fun list(
        dir: String,
        showHidden: Boolean,
    ): List<GuestEntry> {
        val base = resolve(dir)
        val names = hostOf(base).list().orEmpty()
        return names
            .filter { showHidden || !it.startsWith('.') }
            .map { name ->
                val path = child(base, name)
                val self = hostOf(path)
                val isLink = Files.isSymbolicLink(self.toPath())
                // A link in a loop or out of Linux shows as broken.
                val target = if (isLink) runCatching { host(path) }.getOrNull() else self
                val isDir = target?.isDirectory == true
                GuestEntry(
                    name = name,
                    path = path,
                    isDir = isDir,
                    isLink = isLink,
                    size = if (isDir) 0 else target?.length() ?: 0,
                    modified = target?.lastModified() ?: 0,
                    children = if (isDir) target?.list()?.count { showHidden || !it.startsWith('.') } else null,
                )
            }.sortedWith(compareBy<GuestEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    fun mkdir(
        dir: String,
        name: String,
    ): String {
        checkName(name)
        val path = child(dir, name)
        if (!host(path, followLast = false).mkdir()) throw IOException("cannot create $name")
        return path
    }

    fun createFile(
        dir: String,
        name: String,
    ): String {
        checkName(name)
        val path = child(dir, name)
        try {
            Files.createFile(host(path, followLast = false).toPath())
        } catch (e: FileAlreadyExistsException) {
            throw IOException("$name already exists")
        }
        return path
    }

    fun rename(
        path: String,
        name: String,
    ): String {
        checkName(name)
        val target = child(parent(path) ?: "/", name)
        val dest = host(target, followLast = false)
        if (Files.exists(dest.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IOException("$name already exists")
        Files.move(host(path, followLast = false).toPath(), dest.toPath())
        return target
    }

    fun delete(path: String) = deleteHost(host(path, followLast = false))

    private fun deleteHost(file: File) {
        val p = file.toPath()
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) file.listFiles()?.forEach(::deleteHost)
        Files.deleteIfExists(p)
    }

    /** Copies or moves [paths] into [dir], renaming to "name (copy)" on clashes. */
    fun paste(
        paths: List<String>,
        dir: String,
        move: Boolean,
    ) {
        val destDir = resolve(dir)
        for (path in paths) {
            val src = host(path, followLast = false)
            val srcResolved = resolve(path, followLast = false)
            if (destDir == srcResolved || destDir.startsWith("$srcResolved/")) throw IOException("can't put a folder inside itself")
            val name = freeName(destDir, src.name)
            val dest = host(child(destDir, name), followLast = false)
            if (move) {
                if (parent(srcResolved) == destDir) continue // already here
                runCatching { Files.move(src.toPath(), dest.toPath()) }.getOrElse {
                    copyHost(src, dest)
                    deleteHost(src)
                }
            } else {
                copyHost(src, dest)
            }
        }
    }

    private fun freeName(
        dir: String,
        name: String,
    ): String {
        fun exists(n: String) = Files.exists(hostOf(child(dir, n)).toPath(), LinkOption.NOFOLLOW_LINKS)
        if (!exists(name)) return name
        val stem = name.substringBeforeLast('.').takeIf { '.' in name && !name.startsWith('.') } ?: name
        val ext = if (stem == name) "" else "." + name.substringAfterLast('.')
        return generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) "$stem (copy)$ext" else "$stem (copy $n)$ext" }
            .first { !exists(it) }
    }

    private fun copyHost(
        src: File,
        dest: File,
    ) {
        val p = src.toPath()
        when {
            Files.isSymbolicLink(p) -> Files.createSymbolicLink(dest.toPath(), Files.readSymbolicLink(p))
            Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> {
                dest.mkdir()
                src.listFiles()?.forEach { copyHost(it, File(dest, it.name)) }
            }
            else -> Files.copy(p, dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
        }
    }

    private fun checkName(name: String) {
        if (name.isBlank() || '/' in name || name == "." || name == "..") throw IOException("invalid name")
    }
}
