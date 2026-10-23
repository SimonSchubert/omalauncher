package dev.omalauncher.linux

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dev.omalauncher.R
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Linux's home in Android's document picker; apps receive only the files or trees the user selects. */
class LinuxDocumentsProvider : DocumentsProvider() {
    private val docs by lazy {
        LinuxDocuments(File(context!!.filesDir, "arch/root"), fileTypeForScan = ::scanFileType)
    }

    /** Public API21 stat calls avoid NIO's per-entry path encoding on the app runtime. */
    private fun scanFileType(file: File, follow: Boolean): LinuxDocuments.FileType {
        val mode = try {
            (if (follow) Os.stat(file.path) else Os.lstat(file.path)).st_mode
        } catch (e: ErrnoException) {
            throw IOException("can't inspect document entry", e)
        }
        return when {
            OsConstants.S_ISLNK(mode) -> LinuxDocuments.FileType.SYMLINK
            OsConstants.S_ISDIR(mode) -> LinuxDocuments.FileType.DIRECTORY
            OsConstants.S_ISREG(mode) -> LinuxDocuments.FileType.REGULAR
            else -> LinuxDocuments.FileType.OTHER
        }
    }
    private val authority get() = context!!.packageName + AUTHORITY_SUFFIX

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        if (!docs.home.isDirectory) return cursor
        cursor.addRow(cursor.columnNames.map { column ->
            when (column) {
                Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID -> LinuxDocuments.HOME_ID
                Root.COLUMN_TITLE -> "Linux"
                Root.COLUMN_SUMMARY -> "home folder"
                Root.COLUMN_ICON -> R.mipmap.ic_launcher
                Root.COLUMN_MIME_TYPES -> "*/*"
                Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY or
                    (if (docs.home.canWrite()) Root.FLAG_SUPPORTS_CREATE else 0)
                Root.COLUMN_AVAILABLE_BYTES -> docs.home.usableSpace
                else -> null
            }
        }.toTypedArray<Any?>())
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { cursor ->
            addRow(cursor, documentId, docs.fileOf(documentId))
            cursor.setNotificationUri(context!!.contentResolver, documentUri(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        for (child in docs.children(parentDocumentId)) {
            val id = LinuxDocuments.childId(parentDocumentId, child.name)
            try {
                addRow(cursor, id, docs.fileOf(id))
            } catch (_: IOException) {
                // A child may disappear or a link may change while the directory is listed.
            }
        }
        cursor.setNotificationUri(context!!.contentResolver, childrenUri(parentDocumentId))
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = docs.fileOf(documentId)
        if (file.isDirectory) throw FileNotFoundException("$documentId is a folder")
        val access = ParcelFileDescriptor.parseMode(mode)
        return if (access and ParcelFileDescriptor.MODE_WRITE_ONLY != 0) {
            ParcelFileDescriptor.open(file, access, Handler(Looper.getMainLooper())) { changed(documentId) }
        } else {
            ParcelFileDescriptor.open(file, access)
        }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        docs.create(parentDocumentId, mimeType, displayName).also(::changed)

    override fun deleteDocument(documentId: String) {
        try {
            docs.delete(documentId, ::revokeDocumentPermission)
        } finally {
            changed(documentId)
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val renamed = docs.rename(documentId, displayName, ::revokeDocumentPermission) ?: return null
        changed(documentId)
        changed(renamed)
        return renamed
    }

    override fun getDocumentType(documentId: String): String = mimeOf(docs.fileOf(documentId))

    override fun isChildDocument(parentDocumentId: String, documentId: String) = docs.isChild(parentDocumentId, documentId)

    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        val dir = file.isDirectory
        var flags = 0
        if (dir && file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (!dir && file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (docs.canModify(documentId) && file.parentFile?.canWrite() == true) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.addRow(cursor.columnNames.map { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> documentId
                Document.COLUMN_DISPLAY_NAME -> if (documentId == LinuxDocuments.HOME_ID) "home" else file.name
                Document.COLUMN_MIME_TYPE -> mimeOf(file)
                Document.COLUMN_SIZE -> if (dir) null else file.length()
                Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                Document.COLUMN_FLAGS -> flags
                else -> null
            }
        }.toTypedArray<Any?>())
    }

    private fun changed(documentId: String) {
        context!!.contentResolver.notifyChange(documentUri(documentId), null)
        context!!.contentResolver.notifyChange(childrenUri(LinuxDocuments.parentOf(documentId)), null)
    }

    private fun documentUri(documentId: String) = DocumentsContract.buildDocumentUri(authority, documentId)
    private fun childrenUri(parentDocumentId: String) = DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId)

    companion object {
        const val AUTHORITY_SUFFIX = ".linux"

        internal fun rootsChanged(context: Context) {
            context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(context.packageName + AUTHORITY_SUFFIX), null)
        }

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_FLAGS, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        private fun mimeOf(file: File): String {
            if (file.isDirectory) return Document.MIME_TYPE_DIR
            val ext = file.name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
