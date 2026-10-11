package dev.omalauncher.files

import java.io.File
import java.io.FileNotFoundException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Document ids and file mapping for the SAF provider, as plain java.io calls so
 * they can be tested on the JVM without an Android context.
 *
 * The shown root is the guest user's home (/root of the proot'd Arch system).
 * A document id is that root, or a relative path whose segments are individually
 * URL-encoded and joined with '/'. No id is ever built from a raw path, so a
 * name containing '/' or '..' (legal in a Linux filename) can't smuggle a
 * traversal into an id, and decoding is still rejected by [fileFor]'s
 * containment check as a second line of defense.
 */
class ArchDocs(val root: File) {

    /** The root document's id. */
    val rootDocId = "root"

    private val rootCanonical = root.canonicalFile.path

    /** The document id of a file under [root], null for anything outside it (links are resolved, not names taken). */
    fun docIdFor(file: File): String? {
        val canonical = file.canonicalFile
        if (canonical.path == rootCanonical) return rootDocId
        // A relative result can still climb out when the file merely shares a tmp parent (../sibling).
        return canonical.relativeToOrNull(root.canonicalFile)?.takeIf { it.path.isNotEmpty() && !it.path.startsWith("..") }?.path
            ?.split(File.separatorChar)?.joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8) }
    }

    /** The document id of a child of [parentDocId] named [name], as listing and creation give ids out. */
    fun childDocId(parentDocId: String, name: String): String =
        (if (parentDocId == rootDocId) "" else parentDocId) + "/" + URLEncoder.encode(name, Charsets.UTF_8)

    /**
     * The file a document id means, canonicalized. Anything that resolves
     * outside [root] — a hand-crafted id with ../, or a symlink there — is
     * rejected, not served: clients can only ever ask back for ids this
     * provider handed out, and those stay inside.
     */
    fun fileFor(docId: String): File {
        val file =
            if (docId == rootDocId) {
                root
            } else {
                docId.split('/')
                    .joinToString(File.separator) { URLDecoder.decode(it, Charsets.UTF_8) }
                    .split(File.separator)
                    .fold(root) { parent, segment -> File(parent, segment) }
            }
        if (file.canonicalFile.path != rootCanonical && !file.canonicalFile.path.startsWith(rootCanonical + File.separator)) {
            throw FileNotFoundException("not under the Arch root: $docId")
        }
        return file
    }

    /** A file's display name, decoded from the last id segment; the root itself shows as the guest home. */
    fun displayNameFor(docId: String): String =
        if (docId == rootDocId) "/" else URLDecoder.decode(docId.substringAfterLast('/'), Charsets.UTF_8)

    /** Children of a directory as SAF should see them: broken symlinks are skipped, everything else is shown. */
    fun visibleChildren(dir: File): List<File> = dir.listFiles().orEmpty().filter { it.exists() }.sortedBy { it.path.lowercase() }

    /**
     * [displayName] as a usable name in [dir], uniquified on collision: SAF
     * creation must not silently overwrite, and appending (2) keeps the id and
     * display name consistent for both.
     */
    fun uniqueChild(dir: File, displayName: String): File {
        if (displayName.isEmpty() || displayName == "." || displayName == ".." || displayName.contains('/')) {
            throw FileNotFoundException("not a usable name: $displayName")
        }
        var file = File(dir, displayName)
        var i = 2
        while (file.exists()) {
            file = File(dir, uniquified(displayName, i))
            i++
        }
        return file
    }

    /** "notes" with the next free number appended: "notes (3)", keeping the extension ("notes (3).txt"). */
    private fun uniquified(name: String, i: Int): String {
        if (!name.contains('.')) return "$name ($i)"
        val base = name.substringBeforeLast('.')
        return if (base.isEmpty()) "$name ($i)" else "$base ($i)." + name.substringAfterLast('.')
    }
}
