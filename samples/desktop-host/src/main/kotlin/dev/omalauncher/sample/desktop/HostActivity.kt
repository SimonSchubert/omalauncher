package dev.omalauncher.sample.desktop

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.LauncherApps
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.view.Display
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.omalauncher.desktop.IDesktopBridge
import dev.omalauncher.desktop.IDesktopHost
import dev.omalauncher.desktop.IDesktopSession

/** Separate UID and signing key are supported. This sample has no embedding or profile privileges. */
class HostActivity : Activity() {
    var bridge: IDesktopBridge? = null
        private set
    var session: IDesktopSession? = null
        private set
    var sessionId: String? = null
        private set
    var lastLaunch: Triple<String, ComponentName, UserHandle>? = null
        private set
    var lastSerial: Long? = null
        private set
    var lastStatus: String? = null
        private set
    var performAppLaunch = true
    private lateinit var status: TextView
    private lateinit var query: EditText
    private var bound = false
    private var consenting = false
    private var presentation: Presentation? = null
    private var virtual: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var surface: SurfaceView? = null
    private var display: Display? = null

    private val callback = object : IDesktopHost.Stub() {
        override fun onAndroidLaunch(id: String, component: ComponentName, user: UserHandle, serial: Long) = runOnUiThread {
            if (id != sessionId) return@runOnUiThread
            lastLaunch = Triple(id, component, user)
            lastSerial = serial
            if (performAppLaunch) {
                val users = getSystemService(UserManager::class.java)
                if (users.getSerialNumberForUser(user) != serial) return@runOnUiThread show("App profile was removed")
                if (users.isQuietModeEnabled(user)) {
                    runCatching { users.requestQuietModeEnabled(false, user) }
                        .onFailure { show("Enable work apps on the phone: ${it.message}") }
                        .onSuccess { show("Work apps are resuming; select the app again") }
                    return@runOnUiThread
                }
                val options = ActivityOptions.makeBasic().apply { launchDisplayId = display?.displayId ?: Display.DEFAULT_DISPLAY }
                runCatching { getSystemService(LauncherApps::class.java).startMainActivity(component, user, null, options.toBundle()) }
                    .onFailure { show("Android refused the launch: ${it.message}") }
            }
        }
        override fun onStatus(id: String, state: String, message: String) = runOnUiThread {
            if (id != sessionId) return@runOnUiThread
            lastStatus = state
            show("$state: $message")
            if (state in listOf("disconnected", "revoked", "failed")) { session = null; surface?.visibility = android.view.View.INVISIBLE }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bridge = IDesktopBridge.Stub.asInterface(service)
            show(bridge!!.negotiate(1).getString("status").orEmpty())
        }
        override fun onServiceDisconnected(name: ComponentName) { bridge = null; session = null; surface?.visibility = android.view.View.INVISIBLE; show("disconnected") }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)
        query = EditText(this).apply { hint = "Search personal or work apps"; setSingleLine() }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        column.addView(status)
        fun button(label: String, action: () -> Unit) { column.addView(Button(this).apply { text = label; setOnClickListener { runCatching(action).onFailure { show(it.message.orEmpty()) } } }) }
        button("Connect desktop", ::connect)
        column.addView(query)
        button("Search") { session?.search(query.text.toString(), false) }
        button("Open first match") { session?.search(query.text.toString(), true) }
        button("Widgets") { session?.action("widgets") }
        button("Dismiss") { session?.action("dismiss") }
        button("Disconnect") { disconnect() }
        button("Revoke host approval") { bridge?.revokeAccess() }
        setContentView(column)
        bound = bindService(Intent("dev.omalauncher.action.EXTERNAL_DESKTOP").setPackage("dev.omalauncher"), connection, Context.BIND_AUTO_CREATE)
        if (!bound) show("Provider absent; keep using the host's normal desktop")
    }

    override fun onResume() {
        super.onResume()
        if (consenting) { consenting = false; connect() }
    }

    fun connect() {
        val bridge = bridge ?: return show("Provider unavailable")
        val probe = bridge.negotiate(1)
        when (probe.getString("status")) {
            "consent_required" -> {
                val request = bridge.requestConsent()
                @Suppress("DEPRECATION")
                val consent = request.getParcelable<PendingIntent>("consent") ?: return show("Consent unavailable")
                consenting = true
                startIntentSenderForResult(consent.intentSender, 1, null, 0, 0, 0)
            }
            "ready" -> createDesktop()
            else -> show(probe.getString("status").orEmpty())
        }
    }

    private fun createDesktop() {
        if (session != null) return
        if (presentation != null) { surface?.visibility = android.view.View.VISIBLE; openSurface(); return }
        val manager = getSystemService(DisplayManager::class.java)
        display = manager.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        if (display == null) {
            // Allows lifecycle/authorization tests on a phone or emulator without a physical monitor.
            reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3).also { images ->
                images.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
            }
            virtual = manager.createVirtualDisplay("Desktop bridge test", 1280, 720, 160, reader!!.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
            display = virtual?.display ?: return show("No external display")
        }
        presentation = object : Presentation(this, display!!) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                surface = SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = openSurface()
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (session == null) openSurface() else session?.resize(width, height)
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { session?.close() }; session = null }
                    })
                }
                setContentView(surface)
            }
        }.also { it.show() }
    }

    private fun openSurface() {
        val view = surface ?: return
        val token = view.hostToken ?: return
        val frame = view.holder.surfaceFrame
        if (session != null || frame.width() < 1 || frame.height() < 1) return
        val metrics = Bundle().apply { putInt("displayId", display!!.displayId); putInt("width", frame.width()); putInt("height", frame.height()); putString("profile", "sample") }
        val result = bridge?.openSession(1, metrics, token, callback) ?: return
        result.classLoader = SurfaceControlViewHost.SurfacePackage::class.java.classLoader
        show(result.getString("status").orEmpty())
        if (result.getString("status") != "connected") return
        sessionId = result.getString("sessionId")
        session = IDesktopSession.Stub.asInterface(result.getBinder("session"))
        @Suppress("DEPRECATION")
        val remote = result.getParcelable<SurfaceControlViewHost.SurfacePackage>("surface") ?: return
        view.setChildSurfacePackage(remote)
        // SurfaceView retains its own native reference.
        remote.release()
    }

    private fun show(message: String) { status.text = message }

    fun disconnect() {
        runCatching { session?.close() }
        session = null
        sessionId = null
        presentation?.dismiss(); presentation = null; surface = null
        virtual?.release(); virtual = null
        reader?.close(); reader = null
        display = null
    }

    override fun onDestroy() {
        disconnect()
        if (bound) unbindService(connection)
        super.onDestroy()
    }
}
