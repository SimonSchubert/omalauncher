package dev.omalauncher.desktop

import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import android.view.Display
import android.view.KeyEvent
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
    profile: String,
    hostToken: IBinder,
    private val callback: IDesktopHost,
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
    private val death = IBinder.DeathRecipient { handler.post { remove(id, "disconnected") } }
    private var linked = false
    private var visible = false
    var query by mutableStateOf("")
    var drawer by mutableStateOf(false)
    var picker by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    val widgets = DesktopWidgets(windowContext, runtime, "external_" + DesktopAccess.digest((identity.packageName + identity.certificate + profile).toByteArray()),
        ::launch, ::uiAction, ::unavailable, ::authorized)

    fun attach(width: Int, height: Int) {
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setViewTreeViewModelStoreOwner(owner)
        view.isFocusableInTouchMode = true
        view.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalOnBackPressedDispatcherOwner provides owner) {
                ExternalHome(runtime, this)
            }
        }
        callback.asBinder().linkToDeath(death, 0)
        linked = true
        widgets.start()
        updateConfiguration(width, height)
        host.setView(view, width, height)
        setVisible(true)
        owner.onBackPressedDispatcher.addCallback(owner, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = dismiss()
        })
    }

    private fun updateConfiguration(width: Int, height: Int) {
        configuration = Configuration(windowContext.resources.configuration).apply {
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
            if (serial < 0 || (app.profile != null && app.profile != serial)) return unavailable("App profile is unavailable")
            callback.onAndroidLaunch(id, app.component, app.user, serial)
            runtime.usage.record("a:" + app.key)
            dismiss()
        } catch (_: android.os.RemoteException) { remove(id, "disconnected") }
    }

    fun unavailable(text: String) {
        message = text
        runCatching { callback.onStatus(id, "unsupported_action", text) }
    }

    fun dismiss() {
        drawer = false
        picker = false
        query = ""
        widgets.openScreen = null
        view.clearFocus()
    }

    fun uiAction(action: String) {
        if (!authorized()) return
        when (action) {
            "drawer", "search" -> { drawer = true; view.requestFocus() }
            "widgets" -> picker = true
            "dismiss" -> dismiss()
            "focus" -> view.requestFocus()
            else -> unavailable("Action is unavailable on this desktop: $action")
        }
    }

    private fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        widgets.visible = value
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
            require(query.length <= 2048) { "query is too long" }
            this@DesktopSurface.query = query
            drawer = true
            if (submit && query.isNotBlank()) matchingApps(runtime, query).firstOrNull()?.let(::launch)
        }
        override fun action(action: String) = checked { require(action.length <= 32); uiAction(action) }
        override fun key(event: KeyEvent): Boolean = checked {
            require(event.characters.orEmpty().length <= 2048)
            if (!visible) return@checked false
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismiss(); true }
            else view.dispatchKeyEvent(event)
        }
        override fun close() = checked { remove(id, "disconnected") }
    }

    fun dispose(reason: String) {
        if (lease.closed) return
        lease.close()
        if (linked) { runCatching { callback.asBinder().unlinkToDeath(death, 0) }; linked = false }
        widgets.close()
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
