package dev.omalauncher.files

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * The Arch system's files under any Android app's fingers: a SAF (Storage
 * Access Framework) provider, so file managers, editors and Open/Save dialogs
 * see the guest's home (/root of the proot'd Arch rootfs) as a browsable tree,
 * with reads, writes in place, creation, deletion and renaming.
 *
 * Everything is plain host-side file operations: no Linux needs to be running,
 * and changes are visible to the guest on its next read (the launcher's own
 * watchers pick file changes up on their own).
 */
class ArchDocsProvider : DocumentsProvider() {

    private lateinit var docs: ArchDocs

    /** Where the shown tree roots; overridable so a build variant could retarget it. */
    protected open fun rootDir(context: Context): File = File(context.filesDir, "arch/root")

    override fun onCreate(): Boolean {
        val context = requireNotNull(getContext())
        docs = ArchDocs(rootDir(context))
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: rootProjection)
        cursor.newRow()
            .add(DocumentsContract.Root.COLUMN_ROOT_ID, docs.rootDocId)
            .add(DocumentsContract.Root.COLUMN_TITLE, "Omalauncher Linux")
            .add(DocumentsContract.Root.COLUMN_SUMMARY, "Arch files (/root)")
            .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, docs.rootDocId)
            .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
            .add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD,
            )
            .add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, docs.root.usableSpace)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: documentProjection)
        include(cursor, documentId)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: documentProjection)
        for (child in docs.visibleChildren(docs.fileFor(parentDocumentId))) {
            include(cursor, docs.childDocId(parentDocumentId, child.name))
        }
        return cursor
    }

    override fun getDocumentType(documentId: String) = mimeType(docs.fileFor(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        documentId == parentDocumentId ||
            documentId.startsWith("$parentDocumentId/")

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = docs.fileFor(documentId)
        // A write into a not-yet-existing document ("save as" from an editor) still gets its parent directory.
        if ('w' in mode && !file.exists()) file.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = docs.fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("not a directory: $parentDocumentId")
        val asDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val target = docs.uniqueChild(parent, displayName)
        if (asDirectory) {
            if (!target.mkdirs()) throw FileNotFoundException("could not create ${target.path}")
        } else {
            if (!target.createNewFile()) throw FileNotFoundException("could not create ${target.path}")
        }
        notify(parentDocumentId)
        return docs.childDocId(parentDocumentId, target.name)
    }

    override fun deleteDocument(documentId: String) {
        val file = docs.fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException(documentId)
        if (!file.deleteRecursively()) throw IllegalStateException("could not delete ${file.path}")
        notify(parentOf(documentId))
    }

    /** The new document id, which clients go on using for the renamed file. */
    override fun renameDocument(documentId: String, displayName: String): String? {
        if (displayName.isEmpty() || displayName == "." || displayName == ".." || displayName.contains('/')) {
            throw FileNotFoundException("not a usable name: $displayName")
        }
        val file = docs.fileFor(documentId)
        val target = docs.uniqueChild(file.parentFile ?: throw FileNotFoundException(documentId), displayName)
        // renameTo fails instead of copying across devices; the parent is the same, so that can't happen here.
        if (!file.renameTo(target)) throw IllegalStateException("could not rename ${file.path} to $displayName")
        notify(parentOf(documentId))
        return docs.childDocId(parentOf(documentId), target.name)
    }

    private fun include(cursor: MatrixCursor, documentId: String): Boolean {
        val file =
            try {
                docs.fileFor(documentId)
            } catch (_: FileNotFoundException) {
                return false
            }
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, docs.displayNameFor(documentId))
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
            .add(
                DocumentsContract.Document.COLUMN_FLAGS,
                when {
                    file.isDirectory -> DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                    else ->
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                },
            )
            .add(DocumentsContract.Document.COLUMN_SIZE, file.takeIf { it.isFile }?.length() ?: 0L)
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
        return true
    }

    private fun parentOf(documentId: String): String =
        if (documentId.contains('/')) documentId.substringBeforeLast('/') else docs.rootDocId

    /** SAF clients learn about changes by us notifying them; both the parent's listing and the row itself. */
    private fun notify(parentDocumentId: String) {
        val resolver = requireNotNull(context).contentResolver
        resolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId), null)
        resolver.notifyChange(DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId), null)
    }

    private fun mimeType(file: File): String =
        when {
            file.isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
            else ->
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                    ?: "application/octet-stream"
        }

    companion object {
        const val AUTHORITY = "dev.omalauncher.arch.documents"

        // The SDK hides its own projection arrays; these are what the methods use.
        private val rootProjection =
            arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
            )
        private val documentProjection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }
}
