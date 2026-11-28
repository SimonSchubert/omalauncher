package dev.omalauncher.linux

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Filesystem operations for the home DocumentsProvider, testable without Android. */
internal class LinuxDocuments(
    val home: File,
    private val fileTypeForScan: (File, Boolean) -> FileType = { file, follow -> readFileType(file, follow) },
    private val canonicalForScan: (File) -> File = { it.canonicalFile },
) {
    internal enum class FileType { REGULAR, DIRECTORY, SYMLINK, OTHER }

    fun fileOf(documentId: String): File {
        val file = File(home, relativePath(documentId))
        val real = file.canonicalFile
        val base = home.canonicalFile
        if (real != base && !real.toPath().startsWith(base.toPath())) throw FileNotFoundException("$documentId is outside home")
        if (!file.isDirectory && !file.isFile) throw FileNotFoundException(documentId)
        // Keep the lexical path: deleting or renaming a link must act on the link, not its target.
        return file
    }

    fun children(documentId: String): List<File> {
        val dir = directory(documentId)
        return (dir.listFiles() ?: throw FileNotFoundException("can't list $documentId"))
            .filter { child ->
                try {
                    isChild(documentId, childId(documentId, child.name))
                } catch (_: IOException) {
                    false
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    fun isChild(parentDocumentId: String, documentId: String): Boolean =
        try {
            val parent = directory(parentDocumentId).canonicalFile.toPath()
            val child = fileOf(documentId).canonicalFile.toPath()
            documentId.startsWith("$parentDocumentId/") && child != parent && child.startsWith(parent)
        } catch (_: IOException) {
            false
        }

    fun create(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = directory(parentDocumentId)
        val name = safeName(displayName)
        while (true) {
            val target = File(dir, freeName(dir, name))
            val made = if (mimeType == DIRECTORY_MIME_TYPE) target.mkdir() else target.createNewFile()
            if (made) return childId(parentDocumentId, target.name)
            if (!Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw FileNotFoundException("can't create ${target.name}")
        }
    }

    fun rename(documentId: String, displayName: String, invalidated: (String) -> Unit = {}): String? {
        val file = mutableFile(documentId)
        val name = safeName(displayName)
        if (name == file.name) return null
        val target = File(file.parentFile, freeName(file.parentFile!!, name))
        val staleIds = affectedIds(documentId, file)
        Files.move(file.toPath(), target.toPath())
        // The framework transfers the caller's grant before revoking the renamed document itself.
        staleIds.filter { it != documentId }.forEach(invalidated)
        return childId(parentOf(documentId), target.name)
    }

    fun delete(documentId: String, deleted: (String) -> Unit = {}) {
        val file = mutableFile(documentId)
        val path = file.toPath()
        val staleIds = affectedIds(documentId, file)
        try {
            // walkFileTree does not follow links, including links nested inside a real directory.
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                private fun remove(entry: Path): FileVisitResult {
                    Files.delete(entry)
                    val relative = path.relativize(entry).toString()
                    deleted(if (relative.isEmpty()) documentId else "$documentId/$relative")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes) = remove(file)

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    return remove(dir)
                }
            })
        } finally {
            // Partial deletion may already invalidate aliases; conservatively revoke the affected subtree.
            staleIds.forEach(deleted)
        }
    }

    private fun affectedIds(documentId: String, file: File): Set<String> {
        val affected = mutableSetOf(canonicalForScan(file))
        // A link removed inside a subtree can invalidate aliases elsewhere in home.
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isSymbolicLink) {
                    try { affected.add(canonicalForScan(file.toFile())) } catch (_: IOException) { }
                }
                return FileVisitResult.CONTINUE
            }
        })
        data class Entry(val name: String, val real: File, val directory: Boolean)
        // These are already canonical parent paths. Preserve the separator boundary,
        // without repeatedly encoding every unrelated entry as a UnixPath on Android.
        fun descendsFrom(real: File, parent: File) = real.path.startsWith(parent.path + File.separator)
        fun matches(real: File) = affected.any { real == it || descendsFrom(real, it) }
        val graph = mutableMapOf<File, List<Entry>>()
        // Discover each physical directory once, not once per lexical alias. Regular
        // entries inherit their canonical parent's path; only symlinks need resolution.
        // Keep only edges that lead to affected documents, then expand their lexical ids.
        fun discover(real: File): List<Entry> = graph.getOrPut(real) {
            try {
                val directory = real
                val children = directory.listFiles() ?: if (!directory.canExecute()) {
                    // Proot bind placeholders are intentionally mode000. No descendant
                    // can resolve through them, so they cannot hide a currently usable URI.
                    emptyArray()
                } else {
                    throw FileNotFoundException("can't list $real")
                }
                children.mapNotNull { child ->
                    try {
                        validateSegment(child.name)
                        val type = fileTypeForScan(child, false)
                        val resolved = if (type == FileType.SYMLINK) canonicalForScan(child) else child
                        // Preserve isChild's strict canonical descent, including for directory links.
                        if (!descendsFrom(resolved, real)) return@mapNotNull null
                        val target = if (type == FileType.SYMLINK) fileTypeForScan(resolved, true) else type
                        if (target != FileType.DIRECTORY && target != FileType.REGULAR) return@mapNotNull null
                        Entry(child.name, resolved, target == FileType.DIRECTORY)
                    } catch (_: IOException) {
                        null
                    }
                }.filter { matches(it.real) || (it.directory && discover(it.real).isNotEmpty()) }
            } catch (e: IOException) {
                // An unreadable directory may contain an independently granted alias.
                // Do not mutate while silently leaving that old grant valid.
                throw IOException("can't safely enumerate document aliases in $real", e)
            }
        }
        val base = canonicalForScan(home)
        val ids = mutableSetOf<String>()
        val pending = ArrayDeque<Pair<String, File>>().apply { add(HOME_ID to base) }
        while (pending.isNotEmpty()) {
            val (id, real) = pending.removeLast()
            if (!documentId.startsWith("$id/") && matches(real)) ids.add(id)
            // discover only takes directories; matched regular files are leaves.
            discover(real).forEach { child ->
                val childId = childId(id, child.name)
                if (child.directory) pending.add(childId to child.real)
                else if (!documentId.startsWith("$childId/") && matches(child.real)) ids.add(childId)
            }
        }
        return ids
    }

    fun canModify(documentId: String): Boolean = fileOf(documentId).canonicalFile != home.canonicalFile

    private fun mutableFile(documentId: String): File {
        val file = fileOf(documentId)
        if (file.canonicalFile == home.canonicalFile) throw FileNotFoundException("home can't be deleted or renamed")
        return file
    }

    private fun directory(documentId: String): File = fileOf(documentId).also {
        if (!it.isDirectory) throw FileNotFoundException("$documentId is not a directory")
    }

    companion object {
        internal fun readFileType(file: File, follow: Boolean): FileType {
            val options = if (follow) emptyArray() else arrayOf(NOFOLLOW_LINKS)
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, *options)
            return when {
                attrs.isSymbolicLink -> FileType.SYMLINK
                attrs.isDirectory -> FileType.DIRECTORY
                attrs.isRegularFile -> FileType.REGULAR
                else -> FileType.OTHER
            }
        }

        const val HOME_ID = "home"
        const val DIRECTORY_MIME_TYPE = "vnd.android.document/directory"

        fun childId(parent: String, name: String): String {
            relativePath(parent)
            validateSegment(name)
            return "$parent/$name"
        }

        fun parentOf(documentId: String): String {
            relativePath(documentId)
            return documentId.substringBeforeLast('/', HOME_ID)
        }

        // DocumentsContract encodes the entire id as a URI segment; percent and plus are literal filenames here.
        fun relativePath(documentId: String): String {
            if (documentId == HOME_ID) return ""
            if (!documentId.startsWith("$HOME_ID/")) throw FileNotFoundException(documentId)
            return documentId.removePrefix("$HOME_ID/").also { path -> path.split('/').forEach(::validateSegment) }
        }

        private fun validateSegment(name: String) {
            if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\u0000' in name || isProotLink(name)) {
                throw FileNotFoundException("not a document name: $name")
            }
        }

        // proot's link2symlink backing files must not be edited separately from their linked names.
        fun isProotLink(name: String) = name.startsWith(".l2s.")

        fun safeName(name: String): String {
            val clean = name.replace('/', '_').replace('\u0000', '_').trim()
            return (if (clean.isEmpty() || clean == "." || clean == "..") "untitled" else clean).also(::validateSegment)
        }

        fun freeName(dir: File, name: String): String {
            validateSegment(name)
            if (!Files.exists(File(dir, name).toPath(), NOFOLLOW_LINKS)) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val (base, ext) = name.substring(0, dot) to name.substring(dot)
            return generateSequence(1) { it + 1 }.map { "$base ($it)$ext" }
                .first { !Files.exists(File(dir, it).toPath(), NOFOLLOW_LINKS) }
        }
    }
}
