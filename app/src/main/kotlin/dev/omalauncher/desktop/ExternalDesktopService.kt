package dev.omalauncher.desktop

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.omalauncher.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Small local API. Discovery does not touch App.runtime or return private configuration. */
class ExternalDesktopService : Service() {
    private val access get() = (application as App).desktopAccess
    private val sessions = LinkedHashMap<String, DesktopSurface>()
    private val handler = Handler(Looper.getMainLooper())
    private val revoke: (String) -> Unit = { packageName -> closeWhere("revoked") { it.identity.packageName == packageName } }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = closeWhere("disconnected") { it.displayId == displayId }
    }
    private val packageListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_PACKAGE_REMOVED && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                intent.data?.schemeSpecificPart?.let(access::revoke)
            }
            // Also rechecked before every Binder or UI action, covering a replacement racing this broadcast.
            closeWhere("revoked") { access.identity(it.identity.uid) != it.identity || !access.approved(it.identity) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        access.revocations += revoke
        displays.registerDisplayListener(displayListener, handler)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageListener, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(packageListener, filter)
    }

    private fun reply(status: String) = Bundle().apply {
        putInt("protocol", DESKTOP_PROTOCOL)
        putString("status", status)
        putStringArrayList("capabilities", if (Build.VERSION.SDK_INT >= 30) arrayListOf("home_surface", "widgets", "android_launch", "work_profiles", "search", "key", "surface_input", "pointer", "home_pages", "appearance", "multi_display", "system_launch", "linux_launch") else arrayListOf())
    }

    private fun status(protocol: Int, identity: DesktopIdentity?): String = when {
        protocol != DESKTOP_PROTOCOL || Build.VERSION.SDK_INT < 30 -> "unsupported"
        identity == null -> "denied"
        !access.approved(identity) -> "consent_required"
        else -> "ready"
    }

    private val binder = object : IDesktopBridge.Stub() {
        override fun negotiate(protocol: Int): Bundle = caller { uid -> reply(status(protocol, access.identity(uid))) }

        override fun requestConsent(): Bundle = caller { uid ->
            if (Build.VERSION.SDK_INT < 30) return@caller reply("unsupported")
            val identity = access.identity(uid) ?: return@caller reply("denied")
            if (access.approved(identity)) reply("ready") else reply("consent_required").apply { putParcelable("consent", access.consent(identity)) }
        }

        override fun revokeAccess() = caller { uid -> access.identity(uid)?.let { access.revoke(it.packageName) }; Unit }

        override fun openSession(protocol: Int, metrics: Bundle, hostToken: IBinder, host: IDesktopHost): Bundle = caller { uid ->
            if (Build.VERSION.SDK_INT < 30) return@caller reply("unsupported")
            val identity = access.identity(uid)
            val state = status(protocol, identity)
            if (state != "ready") return@caller reply(state)
            checkNotNull(identity)
            DesktopRules.size(metrics.getInt("width"), metrics.getInt("height"))
            val profile = DesktopRules.profile(metrics.getString("profile") ?: "default")
            val launchMode = DesktopRules.launchMode(metrics.getString("launchMode") ?: "host")
            val displayId = metrics.getInt("displayId", -1)
            val display = displays.getDisplay(displayId)
            if (display == null) return@caller reply("unsupported")
            if (sessions.size >= 4 || sessions.values.any { it.identity.uid == uid && it.profile == profile }) return@caller reply("busy")
            if (!host.asBinder().isBinderAlive) return@caller reply("disconnected")
            try {
                val runtime = (application as App).runtime
                val session = DesktopSurface(this@ExternalDesktopService, runtime, access, identity, display, profile, hostToken, host,
                    metrics.getBoolean("pagesSideBySide"), metrics.getBoolean("inheritPhoneLayout"),
                    metrics.getBoolean("workspacePages"),
                    DesktopRules.uiScale(metrics.getFloat("uiScale", 1f)), launchMode == "system") { id, reason ->
                    sessions.remove(id)?.dispose(reason)
                }
                sessions[session.id] = session
                session.attach(metrics.getInt("width"), metrics.getInt("height"))
                runtime.start()
                reply("connected").apply {
                    putString("sessionId", session.id)
                    putBinder("session", session.binder)
                }
            } catch (e: Exception) {
                closeWhere("failed") { it.identity.uid == uid }
                android.util.Log.w("desktop", "cannot open desktop surface", e)
                reply("failed")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun closeWhere(reason: String, predicate: (DesktopSurface) -> Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        sessions.values.filter(predicate).toList().forEach { sessions.remove(it.id); it.dispose(reason) }
    }

    override fun onDestroy() {
        closeWhere("disconnected") { true }
        access.revocations -= revoke
        displays.unregisterDisplayListener(displayListener)
        unregisterReceiver(packageListener)
        super.onDestroy()
    }
}

/** Capture Binder identity before dispatching; framework work then runs as the provider on its UI thread. */
internal fun <T> caller(work: (Int) -> T): T {
    val uid = Binder.getCallingUid()
    val token = Binder.clearCallingIdentity()
    return try { runBlocking { withContext(Dispatchers.Main.immediate) { work(uid) } } } finally { Binder.restoreCallingIdentity(token) }
}
