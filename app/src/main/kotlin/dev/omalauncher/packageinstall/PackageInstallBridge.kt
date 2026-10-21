package dev.omalauncher.packageinstall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.omalauncher.R
import dev.omalauncher.linux.Proot
import dev.omalauncher.upgrade.writeAtomically
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class ApkInstallInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sha256: String,
    val certificateSha256: String,
    val size: Long,
    val permissions: List<String>,
)

data class PendingPackageInstall(
    val request: PackageInstallRequest,
    val file: File,
    val info: ApkInstallInfo,
    val createdAt: Long = System.currentTimeMillis(),
)

sealed interface PackageInstallApproval {
    data object Started : PackageInstallApproval

    data class Rejected(val message: String) : PackageInstallApproval
}

/**
 * Host-owned queue between Linux and Android's package installer.
 *
 * Linux can submit a request, but it cannot approve one: the request is parsed,
 * inspected, and displayed by this Android-side object. The guest sees only the
 * response file after the user or PackageInstaller has made a decision.
 */
class PackageInstallBridge(
    private val context: Context,
    private val proot: Proot,
) {
    private val pending = ConcurrentHashMap<String, PendingPackageInstall>()
    private val approving = ConcurrentHashMap.newKeySet<String>()
    private val started = AtomicBoolean(false)
    private val inbox get() = File(proot.runtimeDir, INBOX_NAME)
    private val stagingDir = File(context.filesDir, STAGING_DIRECTORY)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // A process death can leave a snapshot behind, but PackageInstaller has
        // its own copy after commit. Do not accumulate guest-built APKs.
        stagingDir.listFiles().orEmpty().forEach { it.delete() }
        thread(name = "package-install-bridge", isDaemon = true) {
            while (started.get()) {
                scan()
                expire()
                Thread.sleep(POLL_MS)
            }
        }
    }

    fun pending(id: String): PendingPackageInstall? = pending[id]

    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Starts a PackageInstaller session only after the approval activity calls this. */
    fun approve(id: String): PackageInstallApproval {
        val request = pending[id] ?: return PackageInstallApproval.Rejected("request is no longer pending")
        if (!canRequestPackageInstalls()) {
            return PackageInstallApproval.Rejected("allow installs from this source first")
        }
        if (!approving.add(id)) return PackageInstallApproval.Rejected("installation is already starting")
        try {
            val current = verifySnapshot(request).getOrElse {
                rejectNow(id, "APK changed or disappeared")
                return PackageInstallApproval.Rejected("APK changed or disappeared")
            }
            if (current != request.info) {
                rejectNow(id, "APK changed after the approval request")
                return PackageInstallApproval.Rejected("APK changed after the approval request")
            }

            val reply = replyFile(id)
            val installer = context.packageManager.packageInstaller
            var sessionId: Int? = null
            try {
                InstallRecordStore.save(context, id, reply)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(request.info.packageName)
                    setAppLabel(request.info.label)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // Even if this app is later granted install-from-source, the system must ask again.
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                }
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    FileInputStream(request.file).use { input ->
                        session.openWrite("base.apk", 0, request.file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                    val status =
                        Intent(context, PackageInstallReceiver::class.java)
                            .setPackage(context.packageName)
                            .setData(Uri.parse("omalauncher://package-install/result/$id"))
                            .putExtra(PackageInstallReceiver.EXTRA_REQUEST_ID, id)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    val receiver = PendingIntent.getBroadcast(context, id.hashCode(), status, flags)
                    session.commit(receiver.intentSender)
                }
                pending.remove(id, request)
                request.file.delete()
                cancelNotification(id)
                return PackageInstallApproval.Started
            } catch (e: Exception) {
                sessionId?.let { runCatching { installer.abandonSession(it) } }
                InstallRecordStore.remove(context, id)
                rejectNow(id, "could not start Android installer: ${e.message}")
                return PackageInstallApproval.Rejected("could not start Android installer")
            }
        } finally {
            approving.remove(id)
        }
    }

    fun reject(
        id: String,
        message: String = "rejected by user",
    ) {
        if (approving.contains(id)) return
        rejectNow(id, message)
    }

    private fun scan() {
        val dir = safeInbox(create = true) ?: return
        dir.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(REQUEST_SUFFIX) && it.name.removeSuffix(REQUEST_SUFFIX).matches(REQUEST_ID) }
            .forEach(::readRequest)
    }

    private fun readRequest(file: File) {
        val dir = safeInbox(create = false) ?: return
        if (file.parentFile?.canonicalFile != dir.canonicalFile) return
        if (Files.isSymbolicLink(file.toPath())) {
            runCatching { Files.deleteIfExists(file.toPath()) }
            return
        }
        val id = file.name.removeSuffix(REQUEST_SUFFIX)
        val bytes = readBounded(file, PackageInstallProtocol.MAX_REQUEST_BYTES)
        file.delete()
        val request = runCatching { PackageInstallProtocol.parse(id, bytes ?: error("request is too large")) }.getOrElse {
            writeReply(id, PackageInstallProtocol.response(it.message ?: "invalid request"))
            return
        }
        if (pending.containsKey(id)) return
        val result = inspect(request).getOrElse {
            writeReply(id, PackageInstallProtocol.response(it.message ?: "invalid APK"))
            return
        }
        pending[id] = result
        prompt(result)
    }

    private fun inspect(request: PackageInstallRequest): Result<PendingPackageInstall> = runCatching {
        val source = proot.guestFile(request.guestPath)?.canonicalFile ?: error("path is outside Linux")
        require(proot.guestVisible(source)) { "path is outside Linux" }
        require(source.isFile) { "APK is not a file" }
        require(source.name.endsWith(".apk", ignoreCase = true)) { "path is not an APK" }
        require(source.length() in 1..MAX_APK_BYTES) { "APK size is invalid" }
        val snapshot = snapshot(request.id, source)
        try {
            PendingPackageInstall(request, snapshot, apkInfo(snapshot))
        } catch (e: Exception) {
            snapshot.delete()
            throw e
        }
    }

    private fun verifySnapshot(request: PendingPackageInstall): Result<ApkInstallInfo> = runCatching {
        require(request.file.canonicalFile.parentFile?.canonicalFile == stagingDir.canonicalFile) { "APK snapshot is invalid" }
        require(request.file.isFile) { "APK snapshot is gone" }
        require(request.file.length() in 1..MAX_APK_BYTES) { "APK snapshot is invalid" }
        apkInfo(request.file)
    }

    /** Copy the exact bytes inspected by the approval UI outside guest-visible storage. */
    private fun snapshot(
        id: String,
        source: File,
    ): File {
        stagingDir.mkdirs()
        val target = File(stagingDir, "$id.apk")
        val temp = Files.createTempFile(stagingDir.toPath(), ".$id-", ".tmp")
        try {
            var copied = 0L
            FileInputStream(source).use { input ->
                Files.newOutputStream(temp, StandardOpenOption.WRITE).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= MAX_APK_BYTES) { "APK size is invalid" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(copied > 0) { "APK size is invalid" }
            runCatching {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            target.delete()
            throw e
        }
    }

    private fun rejectNow(
        id: String,
        message: String,
    ) {
        pending.remove(id)?.file?.delete()
        cancelNotification(id)
        writeReply(id, PackageInstallProtocol.response(message))
    }

    private fun apkInfo(file: File): ApkInstallInfo {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("APK is not valid")
        val application = packageInfo.applicationInfo ?: error("APK has no application metadata")
        application.sourceDir = file.path
        application.publicSourceDir = file.path
        val signatures = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        require(signatures.isNotEmpty()) { "APK is unsigned" }
        val certificate = signatures.first().toByteArray().sha256Value()
        return ApkInstallInfo(
            packageName = packageInfo.packageName,
            label = application.loadLabel(context.packageManager).toString().replace(Regex("[\\r\\n]"), " ").take(120),
            versionName = packageInfo.versionName.orEmpty().take(120),
            versionCode = packageInfo.longVersionCode,
            sha256 = file.sha256Value(),
            certificateSha256 = certificate,
            size = file.length(),
            permissions = packageInfo.requestedPermissions.orEmpty().take(MAX_PERMISSIONS),
        )
    }

    private fun prompt(request: PendingPackageInstall) {
        val intent =
            Intent(context, PackageInstallActivity::class.java)
                .putExtra(PackageInstallActivity.EXTRA_REQUEST_ID, request.request.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.i(TAG, "approval activity deferred to notification", e)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "APK install approvals", NotificationManager.IMPORTANCE_HIGH))
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    request.request.id.hashCode(),
                    intent.setData(Uri.parse("omalauncher://package-install/approval/${request.request.id}")),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            manager.notify(
                request.request.id,
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("APK install approval")
                    .setContentText("${request.info.label} · ${request.info.packageName}")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        pending.values.filter { now - it.createdAt > REQUEST_TIMEOUT_MS }.forEach { reject(it.request.id, "request expired") }
    }

    private fun writeReply(
        id: String,
        reply: String,
    ) {
        if (!REQUEST_ID.matches(id)) return
        val dir = safeInbox(create = false) ?: return
        val target = File(dir, "$id.done")
        val temp = runCatching { Files.createTempFile(dir.toPath(), ".reply-", ".tmp") }.getOrNull() ?: return
        try {
            Files.newByteChannel(temp, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(reply.toByteArray()))
            }
            runCatching {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            Log.w(TAG, "can't answer install request", e)
        }
    }

    private fun replyFile(id: String) = File(safeInbox(create = true) ?: error("install request inbox unavailable"), "$id.done")

    private fun safeInbox(create: Boolean): File? {
        val runtime = proot.runtimeDir
        if (Files.isSymbolicLink(runtime.toPath())) return null
        if (create) runtime.mkdirs()
        if (!Files.isDirectory(runtime.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val dir = inbox
        if (create && !Files.exists(dir.toPath(), LinkOption.NOFOLLOW_LINKS)) runCatching { Files.createDirectory(dir.toPath()) }
        if (!Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(dir.toPath())) return null
        if (dir.canonicalFile.parentFile != runtime.canonicalFile) return null
        return dir
    }

    private fun cancelNotification(id: String) = context.getSystemService(NotificationManager::class.java).cancel(id, NOTIFICATION_ID)

    companion object {
        private const val TAG = "package-install"
        private const val CHANNEL = "package-install"
        private const val NOTIFICATION_ID = 1
        private const val INBOX_NAME = "package-install"
        private const val STAGING_DIRECTORY = "package-install/staged"
        private const val REQUEST_SUFFIX = ".req"
        private val REQUEST_ID = Regex("[A-Za-z0-9._-]{1,96}")
        private const val POLL_MS = 200L
        private const val REQUEST_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private const val MAX_PERMISSIONS = 16

        private fun readBounded(file: File, max: Int): ByteArray? {
            if (!file.isFile || file.length() > max) return null
            return runCatching {
                FileInputStream(file).use { input ->
                    val out = ByteArray(max + 1)
                    var count = 0
                    while (count < out.size) {
                        val read = input.read(out, count, out.size - count)
                        if (read < 0) break
                        count += read
                    }
                    if (count > max) null else out.copyOf(count)
                }
            }.getOrNull()
        }

    }

    private fun File.sha256Value(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.sha256Value(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    /** Guest command installed beside the other launcher-owned helpers. */
    fun managed(): List<dev.omalauncher.upgrade.Managed> {
        val bin = File(proot.rootfs, "usr/local/bin")
        return listOf(
            dev.omalauncher.upgrade.Managed.text(File(bin, "omalauncher-install"), dev.omalauncher.upgrade.Policy.Owned, executable = true) {
                """
                #!/bin/bash
                # Ask Omalauncher to install a locally built APK. The Android host
                # validates and displays the APK before PackageInstaller is called.
                set -u
                if [ "${'$'}#" -lt 1 ] || [ "${'$'}#" -gt 2 ]; then
                  echo "usage: omalauncher-install /absolute/path/app.apk [reason]" >&2
                  exit 2
                fi
                path=${'$'}(realpath -- "${'$'}1" 2>/dev/null) || { echo "APK path cannot be resolved" >&2; exit 1; }
                case "${'$'}path" in *.apk) ;; *) echo "path must end in .apk" >&2; exit 2 ;; esac
                reason=${'$'}{2-}
                reason=${'$'}{reason//$'\n'/ }
                reason=${'$'}{reason//$'\r'/ }
                dir="${Proot.GUEST_RUNTIME_DIR}/package-install"
                mkdir -p -- "${'$'}dir"
                id="${'$'}(date +%s%N)-${'$'}${'$'}"
                tmp="${'$'}dir/.${'$'}id.tmp"
                req="${'$'}dir/${'$'}id.req"
                done="${'$'}dir/${'$'}id.done"
                {
                  printf '%s\n' "${'$'}path"
                  printf '%.240s\n' "${'$'}reason"
                } >"${'$'}tmp" || exit 1
                mv -- "${'$'}tmp" "${'$'}req" || exit 1
                for _ in ${'$'}(seq 1 6000); do
                  if [ -f "${'$'}done" ]; then
                    reply=${'$'}(cat -- "${'$'}done")
                    rm -f -- "${'$'}done"
                    [ "${'$'}reply" = ok ] && exit 0
                    echo "${'$'}reply" >&2
                    exit 1
                  fi
                  sleep 0.1
                done
                rm -f -- "${'$'}req"
                echo "Omalauncher did not answer the install request" >&2
                exit 1
                """.trimIndent() + "\n"
            },
        )
    }
}

/** Small app-private record surviving activity/process recreation during system confirmation. */
internal object InstallRecordStore {
    private const val DIRECTORY = "package-install/pending"

    fun save(
        context: Context,
        id: String,
        reply: File,
    ) {
        writeAtomically(record(context, id), reply.path.toByteArray())
    }

    fun remove(context: Context, id: String) = record(context, id).delete()

    fun complete(
        context: Context,
        id: String,
        response: String,
    ) {
        val record = record(context, id)
        val reply = runCatching { File(record.readText()) }.getOrNull()
        if (reply != null && reply.canonicalFile.path.startsWith(context.filesDir.canonicalFile.path + File.separator)) {
            val dir = reply.parentFile
            if (dir != null && Files.isDirectory(dir.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dir.toPath())) {
                val temp = runCatching { Files.createTempFile(dir.toPath(), ".reply-", ".tmp") }.getOrNull()
                if (temp != null) {
                    runCatching {
                        Files.newByteChannel(temp, StandardOpenOption.WRITE).use { it.write(ByteBuffer.wrap(response.toByteArray())) }
                        runCatching { Files.move(temp, reply.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                            .getOrElse { Files.move(temp, reply.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                    }.onFailure { Files.deleteIfExists(temp) }
                }
            }
        }
        record.delete()
    }

    private fun record(context: Context, id: String): File {
        require(Regex("[A-Za-z0-9._-]{1,96}").matches(id))
        return File(File(context.filesDir, DIRECTORY), id)
    }
}
