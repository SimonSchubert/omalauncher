package dev.omalauncher.linux

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.system.OsConstants.*
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException
import java.util.UUID

/** Capability leases over received descriptors. No caller-provided pathname is opened.
 * Reopening our retained /proc/self/fd entry gives each consumer an independent
 * offset and enforces read-only access even if the sender supplied an O_RDWR FD. */
internal object PortalFiles {
    private data class Lease(val fd: ParcelFileDescriptor, val name: String, val mime: String, val writable: Boolean, val epoch: Int)
    private val leases = mutableMapOf<String, Lease>()
    private val handler = Handler(Looper.getMainLooper())
    private const val LIFETIME_MS = 24 * 60 * 60 * 1000L
    // Linux UAPI asm-generic/fcntl.h O_PATH. Not exposed by Android OsConstants.
    private const val LINUX_O_PATH = 0x200000

    fun readOnly(fd: ParcelFileDescriptor): ParcelFileDescriptor {
        val access = PortalFd.flags(fd.fd)
        require((access and LINUX_O_PATH) == 0 && (access and OsConstants.O_ACCMODE) != OsConstants.O_WRONLY) { "Descriptor is not readable" }
        require(OsConstants.S_ISREG(Os.fstat(fd.fileDescriptor).st_mode)) { "Not a regular file" }
        val raw = Os.open("/proc/self/fd/${fd.fd}", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        return try { ParcelFileDescriptor.dup(raw) } finally { Os.close(raw) }
    }

    @Synchronized fun register(context: Context, fd: ParcelFileDescriptor, writable: Boolean, epoch: Int): Uri {
        require(leases.size < 128) { "Too many shared documents" }
        val stat = Os.fstat(fd.fileDescriptor)
        require(S_ISREG(stat.st_mode)) { "Only regular documents can be shared" }
        val flags = PortalFd.flags(fd.fd)
        require(flags and LINUX_O_PATH == 0) { "An O_PATH descriptor cannot grant document access" }
        require(flags and O_ACCMODE != O_WRONLY) { "Document is not readable" }
        require(!writable || flags and O_ACCMODE == O_RDWR) { "Document was not opened writable" }
        val path = Os.readlink("/proc/self/fd/${fd.fd}")
        val name = path.substringAfterLast('/').removeSuffix(" (deleted)").ifBlank { "document" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
        val token = UUID.randomUUID().toString()
        leases[token] = Lease(ParcelFileDescriptor.dup(fd.fileDescriptor), name, mime, writable, epoch)
        val uri = Uri.Builder().scheme("content").authority(context.packageName + ".portal-files").appendPath(token).appendPath(name).build()
        handler.postDelayed({ revoke(context, uri) }, LIFETIME_MS)
        return uri
    }

    @Synchronized fun revoke(context: Context, uri: Uri) {
        leases.remove(uri.pathSegments.firstOrNull())?.fd?.close()
        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Synchronized fun clear(context: Context, throughEpoch: Int) {
        val expired = leases.filterValues { it.epoch <= throughEpoch }
        expired.forEach { (token, lease) ->
            val uri = Uri.Builder().scheme("content").authority(context.packageName + ".portal-files").appendPath(token).appendPath(lease.name).build()
            runCatching { revoke(context, uri) }
        }
    }

    @Synchronized fun open(uri: Uri, mode: String): ParcelFileDescriptor {
        val lease = leases[uri.pathSegments.firstOrNull()] ?: throw FileNotFoundException("Document lease has expired")
        val flags = when (mode) {
            "r" -> O_RDONLY
            "rw" -> O_RDWR
            "w", "wt" -> O_WRONLY or O_TRUNC
            "rwt" -> O_RDWR or O_TRUNC
            "wa" -> O_WRONLY or O_APPEND
            else -> throw FileNotFoundException("Unsupported document mode")
        }
        if (mode != "r" && !lease.writable) throw FileNotFoundException("Document is read-only")
        val opened = Os.open("/proc/self/fd/${lease.fd.fd}", flags or O_CLOEXEC, 0)
        return try { ParcelFileDescriptor.dup(opened) } finally { Os.close(opened) }
    }

    @Synchronized fun type(uri: Uri): String? = leases[uri.pathSegments.firstOrNull()]?.mime
    @Synchronized fun query(uri: Uri, projection: Array<out String>?): Cursor {
        val lease = leases[uri.pathSegments.firstOrNull()] ?: throw FileNotFoundException("Document lease has expired")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = Os.fstat(lease.fd.fileDescriptor).st_size
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> lease.name
            OpenableColumns.SIZE -> size
            else -> null
        } }) }
    }
}

/** Non-exported provider: Android enforces the individual URI grants. */
class PortalFileProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = PortalFiles.type(uri)
    override fun openFile(uri: Uri, mode: String) = PortalFiles.open(uri, mode)
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = PortalFiles.query(uri, projection)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
