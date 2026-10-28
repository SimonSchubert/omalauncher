package dev.omalauncher.linux

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.view.WindowManager
import dev.omalauncher.PortalActivity
import dev.omalauncher.PortalNotificationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.WeakHashMap

/** Native portal transport. Only this app's kernel UID may connect. Received FDs
 * are capabilities; guest-provided paths never authorize Android file access. */
internal class AndroidPortals(private val context: Context, private val proot: Proot) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(80))
    private val handler = Handler(Looper.getMainLooper())
    private var connections: PortalConnections<LocalSocket>? = null
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var generation = 0
    private val requests = mutableMapOf<String, Request>() // main thread only
    private val activities = WeakHashMap<Activity, Boolean>()
    private var inhibitorsAllowed = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val notificationOwners = mutableMapOf<String, Pair<Long, String>>()
    var onOpenFiles: ((String) -> Unit)? = null

    init {
        // A new Android process starts a new Linux session. Remove only our own
        // orphaned portal notifications; ordinary app/legacy notifications stay.
        notifications.activeNotifications.filter { it.tag?.startsWith("portal:") == true }.forEach { notifications.cancel(it.tag, it.id) }
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { activities[activity] = false; updateIdleFlags() }
            override fun onActivityResumed(activity: Activity) { updateIdleFlags() }
            override fun onActivityDestroyed(activity: Activity) { activities.remove(activity) }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
    }

    @Synchronized fun start() {
        if (server != null) return
        val name = context.packageName + ".portal." + Process.myUid()
        val listener = LocalServerSocket(name)
        val epoch = ++generation
        val clients = PortalConnections<LocalSocket>(64,
            authorizePeer = { it.peerCredentials.uid == Process.myUid() }) { socket ->
            runCatching { socket.shutdownInput() }
            runCatching { socket.shutdownOutput() }
            runCatching { socket.close() }
        }
        connections = clients
        server = listener
        try {
            val tmp = Files.createTempFile(proot.runtimeDir.toPath(), ".portal-socket-", ".tmp")
            try {
                Files.write(tmp, name.toByteArray(Charsets.UTF_8))
                Files.move(tmp, File(proot.runtimeDir, SOCKET_FILE).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(tmp) }
        } catch (e: Exception) { server = null; connections = null; clients.stop(); listener.close(); throw e }
        scope.launch {
            while (server === listener) {
                val socket = try { listener.accept() } catch (e: IOException) {
                    if (server === listener) Log.w(TAG, "portal accept failed", e)
                    break
                }
                if (clients.admit(socket)) scope.launch { receive(socket, epoch, clients) }
            }
        }
    }

    @Synchronized fun stop() {
        val oldEpoch = generation
        val stoppedAt = ++generation
        val old = server; server = null
        val clients = connections; connections = null
        old?.let {
            runCatching { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { it.close() }
        }
        clients?.stop()
        File(proot.runtimeDir, SOCKET_FILE).delete()
        handler.post {
            requests.values.filter { it.epoch <= oldEpoch }.toList().forEach { it.finish(2) }
            if (generation == stoppedAt) releaseInhibitors()
            PortalFiles.clear(context, oldEpoch)
        }
    }

    private suspend fun receive(socket: LocalSocket, epoch: Int, clients: PortalConnections<LocalSocket>) {
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        var request: Request? = null
        try {
            require(socket.peerCredentials.uid == Process.myUid()) { "Wrong portal UID" }
            socket.soTimeout = 15_000
            val input = socket.inputStream
            fun readExactly(bytes: ByteArray) {
                var at = 0
                while (at < bytes.size) {
                    val count = input.read(bytes, at, bytes.size - at)
                    val received = socket.ancillaryFileDescriptors
                    try {
                        received?.forEach { raw ->
                            require(descriptors.size < PortalWire.MAX_FDS) { "Too many descriptors" }
                            descriptors += ParcelFileDescriptor.dup(raw)
                        }
                    } finally { received?.forEach { raw -> runCatching { Os.close(raw) } } }
                    if (count < 0) throw EOFException()
                    at += count
                }
            }
            val header = ByteArray(4); readExactly(header)
            val length = ByteBuffer.wrap(header).int
            require(length in 1..PortalWire.MAX_BYTES) { "Invalid packet size" }
            val body = ByteArray(length); readExactly(body)
            val fields = PortalWire.decode(body)
            require(fields["fd-count"]?.toIntOrNull() == descriptors.size) { "Descriptor count mismatch" }
            withContext(Dispatchers.Main) {
                check(epoch == generation && server != null) { "Portal session stopped" }
                request = Request(UUID.randomUUID().toString(), socket, fields, descriptors.toList(), epoch)
                val r = request!!
                requests[r.id] = r
                dispatch(r)
            }
            socket.soTimeout = 0
            // The native side never sends more data. EOF is Close, caller loss,
            // timeout or portal death; any unexpected bytes also cancel safely.
            input.read()
        } catch (e: Exception) {
            if (request == null) Log.w(TAG, "rejected portal packet", e)
        } finally {
            try { withContext(Dispatchers.Main) { request?.cancel() } }
            finally {
                descriptors.forEach { runCatching { it.close() } }
                clients.release(socket)
            }
        }
    }

    private fun dispatch(r: Request) {
        when (r.fields["operation"]) {
            "notification" -> {
                try { postNotification(r) } catch (e: Exception) { Log.w(TAG, "cannot post portal notification", e); r.finish(2) }
            }
            "scheme-supported" -> {
                val scheme = r.fields["uri"].orEmpty().lowercase(java.util.Locale.ROOT)
                val supported = scheme in PortalWire.schemes && context.packageManager.resolveActivity(viewIntent("$scheme:"), 0) != null
                r.finish(if (supported) 0 else 1)
            }
            "inhibit" -> {
                val flags = r.fields["flags"]?.toIntOrNull() ?: 0
                if (!inhibitorsAllowed || flags == 0 || flags and 12 != flags || r.fds.isNotEmpty()) { r.finish(2); return }
                r.inhibitFlags = flags
                try { updateInhibitors(); r.acknowledge() } catch (e: Exception) { Log.w(TAG, "inhibition failed", e); r.finish(2) }
            }
            "open-directory" -> openGuestDirectory(r, containing = true)
            "open-file" -> {
                try {
                    if (OsConstants.S_ISDIR(Os.fstat(r.fds.single().fileDescriptor).st_mode))
                        openGuestDirectory(r, containing = false)
                    else showActivity(r)
                } catch (e: Exception) { Log.w(TAG, "cannot open guest file", e); r.finish(2) }
            }
            else -> showActivity(r)
        }
    }

    private fun openGuestDirectory(r: Request, containing: Boolean) {
        try {
            val path = PortalWire.directoryToOpen(mappedGuestPath(r.fds.single()), containing)
            onOpenFiles?.invoke(path) ?: error("Files is unavailable")
            r.finish(0)
        } catch (e: Exception) { Log.w(TAG, "cannot open guest directory", e); r.finish(2) }
    }

    private fun showActivity(r: Request) {
        val activity = when (PortalWire.uiTarget(r.fields["operation"])) {
            PortalWire.UiTarget.OPEN -> PortalActivity::class.java
            PortalWire.UiTarget.PRINT -> dev.omalauncher.PortalPrintActivity::class.java
            null -> { r.finish(2); return }
        }
        try {
            // Android task identity excludes extras; don't reuse an abandoned chooser task.
            context.startActivity(Intent(context, activity)
                .setData(android.net.Uri.fromParts("omalauncher-portal-request", r.id, null))
                .putExtra("request", r.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        catch (e: Exception) { Log.w(TAG, "cannot show portal UI", e); r.finish(2) }
    }

    /** The FD's actual kernel path is used only to navigate Files, never to open
     * a guest-supplied host path. Reject deleted/unmapped/replaced documents. */
    fun mappedGuestPath(fd: ParcelFileDescriptor): String {
        val actual = Os.readlink("/proc/self/fd/${fd.fd}")
        require(!actual.endsWith(" (deleted)")) { "Document was deleted" }
        val guest = proot.guestPath(File(actual)) ?: error("Document is outside Linux")
        val mapped = proot.guestFs.host(guest)
        val stat = Os.stat(mapped.path); val original = Os.fstat(fd.fileDescriptor)
        require(stat.st_dev == original.st_dev && stat.st_ino == original.st_ino) { "Document changed" }
        return guest
    }

    fun get(id: String?): Request? = requests[id]?.takeIf { it.epoch == generation && server != null }
    fun selected(id: String?) { get(id)?.finish(0) }
    fun notificationAction(id: String?, index: Int) {
        val r = get(id) ?: return
        if (index !in 0..3 || r.notificationTag == null) return
        try { r.event("A\t$index\n") } catch (_: IOException) { r.cancel(); return }
        if (index == 0) r.cancel()
    }
    fun dismissNotification(id: String?) { requests[id]?.cancel() }

    private fun postNotification(r: Request) {
        require(r.fds.isEmpty()) { "Unexpected notification descriptors" }
        check(notifications.areNotificationsEnabled()) { "Notifications are disabled" }
        if (android.os.Build.VERSION.SDK_INT >= 33) check(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { "Notification permission denied" }
        val app = requireNotNull(r.fields["application-id"])
        val id = requireNotNull(r.fields["notification-id"])
        val revision = requireNotNull(r.fields["notification-revision"]?.toLongOrNull())
        val tag = "portal:$app:$id"
        r.notificationTag = tag
        val previous = notificationOwners[tag]
        if (previous != null && previous.first > revision) { r.finish(0); return }
        val priority = r.fields["priority"].orEmpty()
        val importance = when (priority) { "urgent", "high" -> NotificationManager.IMPORTANCE_HIGH; "low" -> NotificationManager.IMPORTANCE_LOW; else -> NotificationManager.IMPORTANCE_DEFAULT }
        val channel = "portal-linux-$importance"
        notifications.createNotificationChannel(NotificationChannel(channel, "Linux notifications ($priority)", importance))
        fun action(index: Int): PendingIntent = PendingIntent.getActivity(context, index,
            Intent(context, PortalNotificationActivity::class.java).setData(android.net.Uri.parse("portal-notification://${r.id}/$index")).putExtra("request", r.id).putExtra("index", index),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, 0,
            Intent(context, dev.omalauncher.PortalChoiceReceiver::class.java).setAction("portal-dismiss").setData(android.net.Uri.parse("portal-dismiss://${r.id}")).putExtra("request", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = r.fields["title"].orEmpty().ifEmpty { "Linux app" }
        val body = r.fields["body"].orEmpty()
        val builder = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(action(0)).setDeleteIntent(dismiss).setOnlyAlertOnce(true).setAutoCancel(false)
        for (index in 1..3) r.fields["button-$index"]?.let { label ->
            @Suppress("DEPRECATION")
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_view, label, action(index)).build())
        }
        // Install ownership before posting; a superseded connection's delayed
        // EOF must not cancel the replacement, even if socket tasks reorder.
        notificationOwners[tag] = revision to r.id
        notifications.notify(tag, 0, builder.build())
        r.acknowledge()
    }
    fun allowInhibitors() { inhibitorsAllowed = true }
    fun releaseInhibitors() {
        inhibitorsAllowed = false
        requests.values.filter { it.inhibitFlags != 0 }.toList().forEach { it.finish(2) }
        updateInhibitors()
    }
    private val renewWakeLock = object : Runnable {
        override fun run() { updateInhibitors() }
    }
    private fun updateInhibitors() {
        handler.removeCallbacks(renewWakeLock)
        val suspend = requests.values.any { !it.finished && it.inhibitFlags and 4 != 0 }
        if (suspend) {
            val lock = wakeLock ?: context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, context.packageName + ":portal").also { it.setReferenceCounted(false); wakeLock = it }
            lock.acquire(120_000L)
            handler.postDelayed(renewWakeLock, 60_000L)
        } else { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        updateIdleFlags()
    }
    private fun updateIdleFlags() {
        val idle = requests.values.any { !it.finished && it.inhibitFlags and 8 != 0 }
        for ((activity, added) in activities.toMap()) {
            if (idle && !added && activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activities[activity] = true
            } else if (!idle && added) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); activities[activity] = false
            }
        }
    }

    inner class Request internal constructor(val id: String, private val socket: LocalSocket, val fields: Map<String, String>, val fds: List<ParcelFileDescriptor>, val epoch: Int) {
        var finished = false; private set
        var activity: WeakReference<Activity>? = null
        var inhibitFlags = 0
        var notificationTag: String? = null
        var printSession: PortalPrintSession? = null
        private val grants = mutableListOf<android.net.Uri>()
        var chooserCallback: android.app.PendingIntent? = null
        fun share(fd: ParcelFileDescriptor, writable: Boolean = false): android.net.Uri = PortalFiles.register(context, fd, writable, epoch).also { grants += it }
        fun acknowledge() { socket.outputStream.write("0\n".toByteArray(Charsets.US_ASCII)); socket.outputStream.flush() }
        fun event(value: String) { socket.outputStream.write(value.toByteArray(Charsets.US_ASCII)); socket.outputStream.flush() }
        fun finish(status: Int) {
            if (finished) return
            try { socket.outputStream.write("$status\n".toByteArray(Charsets.US_ASCII)); socket.outputStream.flush() }
            catch (_: IOException) { /* Caller already cancelled. */ }
            cleanup(keepGrants = status == 0)
        }
        fun cancel() { if (!finished) cleanup(keepGrants = false) }
        private fun cleanup(keepGrants: Boolean) {
            finished = true; requests.remove(id)
            chooserCallback?.cancel(); chooserCallback = null
            notificationTag?.let { tag ->
                if (notificationOwners[tag]?.second == id) { notificationOwners.remove(tag); notifications.cancel(tag, 0) }
            }
            if (!keepGrants) grants.forEach { PortalFiles.revoke(context, it) }
            printSession?.close(); printSession = null
            runCatching { socket.close() }
            activity?.get()?.finish(); activity = null
            if (inhibitFlags != 0) updateInhibitors()
        }
    }

    companion object {
        private const val TAG = "portal"
        private const val SOCKET_FILE = "omalauncher-portal-socket"
        fun viewIntent(value: String): Intent {
            val uri = android.net.Uri.parse(PortalWire.normalizeUriScheme(value))
            return Intent(if (uri.scheme == "mailto") Intent.ACTION_SENDTO else Intent.ACTION_VIEW, uri).apply {
                if (uri.scheme == "http" || uri.scheme == "https") addCategory(Intent.CATEGORY_BROWSABLE)
            }
        }
    }
}
