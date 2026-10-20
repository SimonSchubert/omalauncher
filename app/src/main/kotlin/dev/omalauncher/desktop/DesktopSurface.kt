package dev.omalauncher.desktop

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Bundle
import android.os.UserManager
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.omalauncher.AndroidApp
import dev.omalauncher.Runtime
import java.util.UUID

/** One provider-rendered home. No process-wide launcher callback or phone keyboard state changes. */
@android.annotation.TargetApi(30)
internal class DesktopSurface @RequiresApi(30) constructor(
    context: Context,
    private val runtime: Runtime,
    private val access: DesktopAccess,
    val identity: DesktopIdentity,
    display: Display,
    val profile: String,
    hostToken: IBinder,
    private val callback: IDesktopHost,
    val pagesSideBySide: Boolean,
    inheritPhoneLayout: Boolean,
    workspacePages: Boolean,
    initialUiScale: Float,
    private val systemLaunch: Boolean,
    private val remove: (String, String) -> Unit,
) {
    val id = UUID.randomUUID().toString()
    val displayId = display.displayId
    private val lease = DesktopLease(identity)
    private val handler = Handler(Looper.getMainLooper())
    private val owner = SurfaceOwner()
    private val windowContext = context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION, null)
    private val view = ComposeView(windowContext)
    private val host = SurfaceControlViewHost(windowContext, display, hostToken)
    private var configuration by mutableStateOf(Configuration(windowContext.resources.configuration))
    private val uiScale = initialUiScale
    private val death = IBinder.DeathRecipient { handler.post { remove(id, "disconnected") } }
    private var linked = false
    private var visible = false
    private val home = DesktopHomeSession(windowContext, runtime,
        "external_" + DesktopAccess.digest((identity.packageName + identity.certificate + profile).toByteArray()),
        pagesSideBySide, inheritPhoneLayout, workspacePages,
        display.mode.physicalWidth, display.mode.physicalHeight, uiScale,
        ::launch, ::authorized, { view.requestFocus() },
        { text -> runCatching { callback.onStatus(id, "unsupported_action", text) } })

    fun attach(width: Int, height: Int) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.isFocusableInTouchMode = true
        view.setContent {
            val base = windowContext.resources.displayMetrics.density
            val density = Density(base * uiScale, windowContext.resources.configuration.fontScale)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides density,
                LocalOnBackPressedDispatcherOwner provides owner) {
                DesktopHome(runtime, home)
            }
        }
        callback.asBinder().linkToDeath(death, 0)
        linked = true
        home.start()
        updateConfiguration(width, height)
        host.setView(view, width, height)
        setVisible(true)
        owner.onBackPressedDispatcher.addCallback(owner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        })
    }

    private fun updateConfiguration(width: Int, height: Int) {
        configuration = Configuration(windowContext.resources.configuration).apply {
            densityDpi = (densityDpi * uiScale).toInt().coerceAtLeast(1)
            screenWidthDp = (width * 160f / densityDpi).toInt().coerceAtLeast(1)
            screenHeightDp = (height * 160f / densityDpi).toInt().coerceAtLeast(1)
            orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
    }

    private fun authorized(): Boolean {
        if (lease.closed) return false
        if (access.identity(identity.uid) != identity || !access.approved(identity)) {
            remove(id, "revoked")
            return false
        }
        return true
    }

    fun launch(app: AndroidApp) {
        if (!authorized()) return
        // This app's UserHandle is essential: a host must use LauncherApps, including for work apps.
        try {
            val serial = windowContext.getSystemService(UserManager::class.java).getSerialNumberForUser(app.user)
            if (serial < 0 || (app.profile != null && app.profile != serial)) return home.unavailable("App profile is unavailable")
            if (systemLaunch) launchOnAndroidDesktop(windowContext, displayId, app.component, app.user, serial)
            else callback.onAndroidLaunch(id, app.component, app.user, serial)
            runtime.usage.record("a:" + app.key)
            dismiss()
        } catch (_: android.os.RemoteException) { remove(id, "disconnected") }
        catch (error: Exception) { home.unavailable("Android refused the app launch: ${error.message}") }
    }

    fun dismiss() {
        home.dismiss()
        view.clearFocus()
    }

    private fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        home.visible(value)
        owner.registry.currentState = if (value) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        view.visibility = if (value) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (!value) dismiss()
    }

    val binder = object : IDesktopSession.Stub() {
        private fun <T> checked(work: () -> T): T = caller { uid ->
            val current = access.identity(uid)
            lease.check(current, current?.let(access::approved) == true)
            work()
        }

        // A typed AIDL return carries WRITE_RETURN_VALUE, releasing the provider's parcel reference.
        override fun getSurface(): SurfaceControlViewHost.SurfacePackage = checked { checkNotNull(host.surfacePackage) }

        override fun resize(width: Int, height: Int) = checked {
            DesktopRules.size(width, height)
            updateConfiguration(width, height)
            host.relayout(width, height)
        }
        override fun setVisible(visible: Boolean) = checked { this@DesktopSurface.setVisible(visible) }
        override fun search(query: String, submit: Boolean) = checked {
            home.search(query, submit)
        }
        override fun action(action: String) = checked { require(action.length <= 32); home.uiAction(action) }
        override fun key(event: KeyEvent): Boolean = checked {
            require(event.characters.orEmpty().length <= 2048)
            if (!visible) return@checked false
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismiss(); true }
            else view.dispatchKeyEvent(event)
        }
        override fun close() = checked { remove(id, "disconnected") }

        override fun pointer(event: MotionEvent): Boolean = checked {
            require(event.pointerCount in 1..16 && event.historySize <= 32)
            for (index in 0 until event.pointerCount) {
                require(event.getX(index).isFinite() && event.getY(index).isFinite())
                require(kotlin.math.abs(event.getX(index)) <= 16384 && kotlin.math.abs(event.getY(index)) <= 16384)
            }
            if (!visible) return@checked false
            if (event.actionMasked in listOf(MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_HOVER_MOVE,
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_EXIT,
                    MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE)) view.dispatchGenericMotionEvent(event)
            else view.dispatchTouchEvent(event)
        }

        override fun describe(): Bundle = checked {
            home.describe().apply {
                putString("sessionId", id)
                putInt("displayId", displayId)
                putString("launchMode", if (systemLaunch) "system" else "host")
                putFloat("uiScale", uiScale)
            }
        }
    }

    fun dispose(reason: String) {
        if (lease.closed) return
        lease.close()
        if (linked) { runCatching { callback.asBinder().unlinkToDeath(death, 0) }; linked = false }
        home.close()
        view.disposeComposition()
        owner.registry.currentState = Lifecycle.State.DESTROYED
        owner.viewModelStore.clear()
        host.release()
        runCatching { callback.onStatus(id, reason, "Desktop session closed") }
    }
}

private class SurfaceOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore = ViewModelStore()
    override val onBackPressedDispatcher = OnBackPressedDispatcher()
    private val state = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = state.savedStateRegistry
    init {
        state.performAttach()
        state.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }
}
