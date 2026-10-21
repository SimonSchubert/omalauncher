package dev.omalauncher.desktop

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.view.Display
import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.omalauncher.AndroidApp
import dev.omalauncher.App
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.applySystemBars
import androidx.compose.runtime.LaunchedEffect

/** Android owns window management, cursor and IME; this Activity supplies only the home. */
class NativeDesktopActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    internal var desktopSession by mutableStateOf<DesktopHomeSession?>(null)
        private set
    internal var sessionDisplayId = -1
        private set

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display = if (Build.VERSION.SDK_INT >= 30) checkNotNull(display) else windowManager.defaultDisplay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureSession()
        runtime.start()
        val original = checkNotNull(window.callback)
        window.callback = object : Window.Callback by original {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.isMetaPressed && event.keyCode in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER)) {
                    if (event.action == KeyEvent.ACTION_DOWN) desktopSession?.uiAction(
                        if (event.keyCode == KeyEvent.KEYCODE_ENTER) "terminal" else "search")
                    return true
                }
                return original.dispatchKeyEvent(event)
            }
        }
        // A display move can retain identical bounds/density and not produce a public
        // Activity configuration callback. Recheck the Activity's display before drawing.
        window.decorView.viewTreeObserver.addOnPreDrawListener {
            if (!isFinishing && !isDestroyed && sessionDisplayId != currentDisplay().displayId) {
                window.decorView.post { if (!isFinishing && !isDestroyed) { ensureSession(); desktopSession?.visible(true) } }
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val home = desktopSession ?: return
                if (home.describe().getBoolean("overlay")) home.dismiss()
                else if (!intent.hasCategory("android.intent.category.SECONDARY_HOME")) finish()
            }
        })
        setContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@NativeDesktopActivity, Palette.isLight) }
            BoxWithConstraints(Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                desktopSession?.let { home ->
                    // A small freeform window pages normally; a wide desktop shows all homes.
                    val sideBySide = maxWidth >= 900.dp
                    SideEffect { home.pagesSideBySide = sideBySide }
                    DesktopHome(runtime, home)
                }
            }
        }
    }

    private fun ensureSession() {
        val displayId = currentDisplay().displayId
        if (desktopSession != null && sessionDisplayId == displayId) return
        desktopSession?.close()
        sessionDisplayId = displayId
        desktopSession = DesktopHomeSession(this, runtime, "native_display_$displayId", true, true,
            false, 0, 0, 1f, ::launch,
            { app -> runtime.launch(app, displayId) { desktopSession?.unavailable(it) } },
            { runtime.terminal(null, "Terminal", displayId = displayId) { desktopSession?.unavailable(it) } },
            { !isFinishing && !isDestroyed }).also { it.start() }
    }

    private fun launch(app: AndroidApp) {
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(app.user)
        if (app.profile != null && app.profile != serial) {
            desktopSession?.unavailable("App profile is unavailable")
            return
        }
        runCatching { launchOnAndroidDesktop(this, currentDisplay().displayId, app.component, app.user, serial) }
            .onSuccess { runtime.usage.record("a:" + app.key); desktopSession?.dismiss() }
            .onFailure { desktopSession?.unavailable("Android refused the app launch: ${it.message}") }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ensureSession()
        desktopSession?.visible(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
    }

    override fun onStart() { super.onStart(); ensureSession(); desktopSession?.visible(true) }
    override fun onResume() { super.onResume(); ensureSession(); desktopSession?.visible(true) }
    override fun onStop() { desktopSession?.visible(false); super.onStop() }
    override fun onDestroy() { desktopSession?.close(); desktopSession = null; super.onDestroy() }
}
