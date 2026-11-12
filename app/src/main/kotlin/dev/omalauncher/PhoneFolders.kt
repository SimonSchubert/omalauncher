package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.ui.mimeOf
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files

/**
 * For Android's pickers: the phone's own storage among their places from the start. Android 10 otherwise shows only
 * Downloads and the like, with the storage behind "show internal storage" in the picker's menu.
 */
internal fun Intent.showingStorage(): Intent = putExtra("android.provider.extra.SHOW_ADVANCED", true).putExtra("android.content.extra.SHOW_ADVANCED", true)

/**
 * Where the document [id] of Android's own storage provider is in the phone's shared storage: "primary:Pictures/Trips"
 * is "Pictures/Trips". Null for another provider's, or on a memory card, which Linux has no path to.
 */
internal fun storagePathOf(
    authority: String?,
    id: String,
): String? = id.takeIf { authority == "com.android.externalstorage.documents" && it.startsWith("primary:") }?.removePrefix("primary:")?.trim('/')

/**
 * Whether a file inside a Linux folder being copied to the phone goes along. Not links, which a phone folder can't
 * hold and Android would follow on its own paths rather than Linux's (out of Linux, or in a loop); nor pipes and the
 * like, which would never finish reading.
 */
internal fun copiesOut(file: File) = !Files.isSymbolicLink(file.toPath()) && (file.isDirectory || file.isFile)

/**
 * Makes [dest] safe to copy to from the phone: a link there goes, rather than being followed by Android on its own
 * paths to anywhere, the launcher's files too; so does a pipe or the like, which writing to would block on.
 */
internal fun clearForCopy(dest: File) {
    if (Files.isSymbolicLink(dest.toPath()) || (dest.exists() && !dest.isFile && !dest.isDirectory)) dest.delete()
}

/**
 * Folders of the phone's own (anyone's photos, downloads, documents) that the user picked in Android's folder picker
 * and so let the launcher read and change, for good: Android keeps the grant across restarts and updates until it's
 * taken back here, or the app is uninstalled. Linux can't mount them (proot can't mount a content uri), so it reaches
 * them through `omalauncher` (folders, ls, get, put) and the user through Files; see [PhoneStorage] for the path
 * Linux does have, with the launcher's own files only.
 *
 * A folder is named by its own name ("Camera"), a second one of the same name by "Camera 2"; a path in one is that
 * name, then the names down to the file: "Camera/2026/a.jpg". The grants themselves are the list: nothing is saved.
 */
class PhoneFolders(
    private val context: Context,
) {
    class Folder(
        val name: String,
        val tree: Uri,
        /** The folder itself. */
        val root: Doc,
        /** Where it is in the phone's shared storage ("Pictures/Trips"); null for one elsewhere, like a cloud drive's. */
        val storagePath: String?,
    )

    /** A file or folder in a [Folder]; [path] as Linux names it, from the folder's name on. */
    class Doc(
        val tree: Uri,
        val id: String,
        val name: String,
        val path: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
    ) {
        val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private val resolver get() = context.contentResolver

    /** Goes up with each folder added or taken out, for screens that show them. */
    var changes by mutableIntStateOf(0)
        private set

    /** The folders granted, oldest first. Asks the providers for their names: off the main thread. */
    fun folders(): List<Folder> {
        val seen = HashMap<String, Int>()
        return resolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission && DocumentsContract.isTreeUri(it.uri) }
            .sortedBy { it.persistedTime }
            .mapNotNull { grant ->
                val tree = grant.uri
                val id = DocumentsContract.getTreeDocumentId(tree)
                // A folder deleted since, or on a card taken out: there's nothing to show of it until it's back.
                val root = runCatching { doc(tree, id, "") }.getOrNull() ?: return@mapNotNull null
                val n = (seen[root.name] ?: 0) + 1
                seen[root.name] = n
                val name = if (n == 1) root.name else "${root.name} $n"
                Folder(name, tree, root.copyAs(name), storagePathOf(tree.authority, id))
            }
    }

    /** Keeps the folder [tree] picked in Android's folder picker (ACTION_OPEN_DOCUMENT_TREE). */
    fun add(tree: Uri) {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        changes++
    }

    /** Gives the folder back: the launcher, and so Linux, can't reach it any more. Its files stay. */
    fun remove(folder: Folder) {
        runCatching { resolver.releasePersistableUriPermission(folder.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        changes++
    }

    /** What [path] names ("Camera/2026/a.jpg"), or null when there's no such folder or file. */
    fun find(
        path: String,
        folders: List<Folder> = folders(),
    ): Doc? {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || ".." in parts) return null
        var doc = folders.firstOrNull { it.name == parts[0] }?.root ?: return null
        for (part in parts.drop(1)) doc = child(doc, part) ?: return null
        return doc
    }

    /** The files and folders in [dir], folders first, by name. */
    fun list(dir: Doc): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(dir.tree, dir.id)
        val docs = ArrayList<Doc>()
        query(uri)?.use { c -> while (c.moveToNext()) docs += c.doc(dir.tree, dir.path) }
        return docs.sortedWith(compareBy<Doc>({ !it.isDir }, { it.name.lowercase() }))
    }

    fun child(
        dir: Doc,
        name: String,
    ): Doc? = list(dir).firstOrNull { it.name == name }

    fun mkdir(
        dir: Doc,
        name: String,
    ): Doc = create(dir, name, Document.MIME_TYPE_DIR)

    fun createFile(
        dir: Doc,
        name: String,
    ): Doc = create(dir, name, mimeOf(name) ?: "application/octet-stream")

    /** A new file or folder; one by that name already there is an error, not renamed as providers would. */
    private fun create(
        dir: Doc,
        name: String,
        mime: String,
    ): Doc {
        checkName(name)
        if (child(dir, name) != null) throw IOException("$name already exists")
        val uri = DocumentsContract.createDocument(resolver, dir.uri, mime, name) ?: throw IOException("cannot create $name")
        val doc = doc(dir.tree, DocumentsContract.getDocumentId(uri), dir.path)
        // Some providers give a new file the extension of its type (notes → notes.txt): it keeps the name it was given.
        return if (doc.name != name && !doc.isDir) rename(doc, name) else doc
    }

    fun rename(
        doc: Doc,
        name: String,
    ): Doc {
        checkName(name)
        if (doc.name == name) return doc
        val parent = doc.path.substringBeforeLast('/')
        val uri = DocumentsContract.renameDocument(resolver, doc.uri, name) ?: doc.uri
        return doc(doc.tree, DocumentsContract.getDocumentId(uri), parent)
    }

    fun delete(doc: Doc) {
        if (!DocumentsContract.deleteDocument(resolver, doc.uri)) throw IOException("cannot delete ${doc.name}")
    }

    /** [doc] into the Linux file or folder [dest], all of a folder; an existing file there is overwritten, as cp does. */
    fun copyIn(
        doc: Doc,
        dest: File,
    ) {
        clearForCopy(dest)
        if (doc.isDir) {
            if (!dest.isDirectory && !dest.mkdirs()) throw IOException("cannot create ${dest.name}")
            // A name no folder can hold would put the copy somewhere else in Linux.
            for (c in list(doc)) if (c.name.isNotBlank() && '/' !in c.name && c.name != "." && c.name != "..") copyIn(c, File(dest, c.name))
        } else {
            (resolver.openInputStream(doc.uri) ?: throw FileNotFoundException(doc.path)).use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            if (doc.modified > 0) dest.setLastModified(doc.modified)
        }
    }

    /** The Linux file or folder [src] into [dir] as [name], all of a folder; an existing file there is overwritten. */
    fun copyOut(
        src: File,
        dir: Doc,
        name: String = src.name,
    ): Doc {
        checkName(name)
        if (!src.isDirectory && !src.isFile) throw IOException("${src.name} isn't a file or folder")
        val there = child(dir, name)
        if (src.isDirectory) {
            if (there != null && !there.isDir) throw IOException("${there.path} is a file")
            val folder = there ?: mkdir(dir, name)
            for (c in src.listFiles().orEmpty()) if (copiesOut(c)) copyOut(c, folder)
            return folder
        }
        if (there?.isDir == true) throw IOException("${there.path} is a folder")
        val doc = there ?: createFile(dir, name)
        src.inputStream().use { input ->
            (resolver.openOutputStream(doc.uri, "wt") ?: throw FileNotFoundException(doc.path)).use { input.copyTo(it) }
        }
        return doc
    }

    /** Another app's doc into [dir] (a file the user picked), under its own name, or "name (2)" beside one already there. */
    fun copyIn(
        uri: Uri,
        dir: File,
    ): File {
        val name =
            query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME))?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?.substringAfterLast('/')?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"
        val dest = freeFile(dir, name)
        (resolver.openInputStream(uri) ?: throw FileNotFoundException(name)).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return dest
    }

    private fun doc(
        tree: Uri,
        id: String,
        parent: String,
    ): Doc = query(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { c -> if (c.moveToFirst()) c.doc(tree, parent) else null } ?: throw FileNotFoundException(id)

    private fun query(
        uri: Uri,
        columns: Array<String> = COLUMNS,
    ): Cursor? = resolver.query(uri, columns, null, null, null)

    private fun Cursor.doc(
        tree: Uri,
        parent: String,
    ): Doc {
        val name = getString(1).orEmpty()
        return Doc(
            tree = tree,
            id = getString(0),
            name = name,
            path = if (parent.isEmpty()) name else "$parent/$name",
            isDir = getString(2) == Document.MIME_TYPE_DIR,
            // A folder's is the provider's own business (4096 for a directory's inode).
            size = if (isNull(3) || getString(2) == Document.MIME_TYPE_DIR) 0 else getLong(3),
            modified = if (isNull(4)) 0 else getLong(4),
        )
    }

    private fun Doc.copyAs(name: String) = Doc(tree, id, name, name, isDir, size, modified)

    private companion object {
        val COLUMNS =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )

        fun checkName(name: String) {
            if (name.isBlank() || '/' in name || name == "." || name == "..") throw IOException("invalid name")
        }

        fun freeFile(
            dir: File,
            name: String,
        ): File {
            if (!File(dir, name).exists()) return File(dir, name)
            val stem = name.substringBeforeLast('.').takeIf { '.' in name && !name.startsWith('.') } ?: name
            val ext = if (stem == name) "" else "." + name.substringAfterLast('.')
            return generateSequence(2) { it + 1 }.map { File(dir, "$stem ($it)$ext") }.first { !it.exists() }
        }
    }
}
