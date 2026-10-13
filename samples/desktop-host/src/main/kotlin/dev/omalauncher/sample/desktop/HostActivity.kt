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
    private var wantDesktop = false
    private var presentation: Presentation? = null
    private var virtual: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var surface: SurfaceView? = null
    private var display: Display? = null
    var frameCount = 0
        private set
    var frameColors = 0
        private set
    var frameSignature = 0L
        private set

    private fun dropSession() {
        session = null
        sessionId = null
        frameColors = 0
        surface?.visibility = android.view.View.INVISIBLE
    }

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
            if (state in listOf("disconnected", "revoked", "failed")) dropSession()
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            bridge = IDesktopBridge.Stub.asInterface(service)
            show(bridge!!.negotiate(1).getString("status").orEmpty())
            if (wantDesktop) connect()
        }
        override fun onServiceDisconnected(name: ComponentName) { bridge = null; dropSession(); show("disconnected") }
        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
            if (bound) { unbindService(this); bound = false }
            if (!isFinishing) bindBridge()
        }
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
        bindBridge()
    }

    private fun bindBridge() {
        val flags = Context.BIND_AUTO_CREATE or if (android.os.Build.VERSION.SDK_INT >= 34) Context.BIND_ALLOW_ACTIVITY_STARTS else 0
        bound = bindService(Intent("dev.omalauncher.action.EXTERNAL_DESKTOP").setPackage(BuildConfig.PROVIDER_PACKAGE), connection, flags)
        if (!bound) show("Provider absent; keep using the host's normal desktop")
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        if (resultCode == RESULT_OK) connect()
        else { wantDesktop = false; show("consent_required: approval was not granted") }
    }

    fun connect() {
        wantDesktop = true
        val bridge = bridge ?: run {
            if (bound) { unbindService(connection); bound = false }
            bindBridge()
            return show(if (bound) "connecting" else "Provider unavailable")
        }
        val probe = bridge.negotiate(1)
        when (probe.getString("status")) {
            "consent_required" -> {
                val request = bridge.requestConsent()
                @Suppress("DEPRECATION")
                val consent = request.getParcelable<PendingIntent>("consent") ?: return show("Consent unavailable")
                startIntentSenderForResult(consent.intentSender, 1, null, 0, 0, 0)
            }
            "ready" -> runCatching { createDesktop() }.onFailure { disconnect(); show("failed: ${it.message}") }
            else -> show(probe.getString("status").orEmpty())
        }
    }

    private fun createDesktop() {
        if (session != null) return
        if (presentation != null) { surface?.visibility = android.view.View.VISIBLE; openSurface(); return }
        val manager = getSystemService(DisplayManager::class.java)
        // Tests capture their own display even when the emulator has another monitor.
        display = if (intent.getBooleanExtra("captureDisplay", false)) null
            else manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (display == null) {
            // Allows lifecycle/authorization tests on a phone or emulator without a physical monitor.
            reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 3).also { images ->
                images.setOnImageAvailableListener({ source ->
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val colors = HashSet<Int>()
                        var signature = 17L
                        for (y in 0 until image.height step 16) for (x in 0 until image.width step 16) {
                            val at = y * plane.rowStride + x * plane.pixelStride
                            val color = (buffer.get(at).toInt() and 255) or ((buffer.get(at + 1).toInt() and 255) shl 8) or ((buffer.get(at + 2).toInt() and 255) shl 16)
                            colors += color
                            signature = 31 * signature + color
                        }
                        frameCount++
                        frameColors = colors.size
                        frameSignature = signature
                    }
                }, Handler(Looper.getMainLooper()))
            }
            // Only instrumentation holding shell permissions can request this test flag.
            val trusted = if (intent.getBooleanExtra("trustedDisplay", false)) (1 shl 10) else 0
            virtual = manager.createVirtualDisplay("Desktop bridge test", 1280, 720, 160, reader!!.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or trusted)
            display = virtual?.display ?: return show("No external display")
        }
        presentation = object : Presentation(this, display!!) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                surface = SurfaceView(this.context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = openSurface()
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            if (session == null) openSurface() else runCatching { session?.resize(width, height) }.onFailure { dropSession(); show("disconnected: ${it.message}") }
                        }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { runCatching { session?.close() }; session = null }
                    })
                }
                // Explicit receiver: Activity.setContentView accepts a nullable View and can
                // otherwise win overload resolution over Dialog's non-null overload.
                this.setContentView(checkNotNull(surface))
            }
        }.also { it.show() }
    }

    private fun openSurface() {
        runCatching { attachSurface() }.onFailure { dropSession(); show("failed: ${it.message}") }
    }

    private fun attachSurface() {
        val view = surface ?: return
        val token = view.hostToken ?: return
        val frame = view.holder.surfaceFrame
        if (session != null || frame.width() < 1 || frame.height() < 1) return
        val metrics = Bundle().apply { putInt("displayId", display!!.displayId); putInt("width", frame.width()); putInt("height", frame.height()); putString("profile", "sample") }
        val result = bridge?.openSession(1, metrics, token, callback) ?: return
        show(result.getString("status").orEmpty())
        if (result.getString("status") != "connected") return
        sessionId = result.getString("sessionId")
        session = IDesktopSession.Stub.asInterface(result.getBinder("session"))
        val remote = session!!.getSurface()
        try { view.setChildSurfacePackage(remote) }
        catch (error: Exception) { remote.release(); runCatching { session?.close() }; throw error }
        // Ownership transfers to SurfaceView; it releases this package when replaced or detached.
    }

    private fun show(message: String) { status.text = message }

    fun disconnect() {
        wantDesktop = false
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
