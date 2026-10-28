package dev.omalauncher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.omalauncher.ui.ImagePicker
import dev.omalauncher.ui.MicPermission
import dev.omalauncher.ui.Palette
import dev.omalauncher.theme.Style
import dev.omalauncher.ui.KeyIndicated
import dev.omalauncher.ui.keyPressed
import dev.omalauncher.ui.keysFocused
import dev.omalauncher.ui.keysStartAgain
import dev.omalauncher.ui.movesFocus
import dev.omalauncher.ui.panelOf
import dev.omalauncher.ui.LauncherStyle
import dev.omalauncher.ui.SystemBarsEffect
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.ui.showStatusBar
import dev.omalauncher.ui.LauncherRoot
import dev.omalauncher.ui.LauncherState
import dev.omalauncher.ui.PromptRequest
import dev.omalauncher.widgets.market.Share

/** The home screen: widgets, pinned apps and the drawer; Linux windows open as their own tasks. */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private val state = LauncherState()
    private val imagePicker = ImagePicker(this) { runtime }
    private val mic = MicPermission(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // While a backup or device transfer runs, the process has a plain Application and no runtime;
        // Home starts this activity in it anyway. Close until the process restarts as itself.
        if (application !is App) {
            finish()
            return
        }
        applySystemBars(this, Palette.isLight)
        runtime.start()
        state.onSettings = { page, homePage -> runtime.openSettings(page, homePage) }
        setContent {
            KeyIndicated { LauncherRoot(runtime, state) }
            SystemBarsEffect(this@MainActivity)
            // A face whose statusBar is false takes the status bar's room too.
            val fullScreen by remember { derivedStateOf { state.fullScreen && runtime.widgets.openScreen == null } }
            val statusBar = LauncherStyle.style[Style.STATUS_BAR_SHOWN] && !fullScreen
            LaunchedEffect(statusBar) { showStatusBar(this@MainActivity, statusBar) }
        }
        handleDebugExec(intent)
        handleSharedWidget(intent)
        // Asked on arriving at the home screen, not over the welcome screen of a fresh install,
        // and once nothing else is in front: not over the notification permission dialog that
        // opting into Linux there brings up.
        lifecycleScope.launch {
            snapshotFlow { runtime.launcherReady }.first { it }
            withResumed { requestHomeRole() }
        }
    }

    /** Asks once to become the default home app; Android shows its own chooser. */
    private fun requestHomeRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME) || roles.isRoleHeld(RoleManager.ROLE_HOME)) return
        val prefs = LauncherData.prefs(this)
        if (prefs.getBoolean("home_role_asked", false)) return
        prefs.edit().putBoolean("home_role_asked", true).apply()
        @Suppress("DEPRECATION")
        startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 1)
    }

    /**
     * Debug builds: `adb shell am start -n dev.omalauncher/.MainActivity --es exec foot`,
     * or `--es theme kanagawa` to apply a theme (scripts/collage-captures.sh).
     */
    private fun handleDebugExec(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        intent.getStringExtra("exec")?.let(runtime::launch)
        intent.getStringExtra("theme")?.let { name ->
            lifecycleScope.launch {
                snapshotFlow { runtime.themes.loaded }.first { it }
                runtime.themes.themes.firstOrNull { it.name == name }?.let { runtime.themes.apply(it) }
                    ?: Log.w("debug", "no theme $name")
            }
        }
    }

    /**
     * A widget link (see [Share]): from the camera, a browser, or the store page's "open in Omalauncher" button,
     * which can't keep the link's fragment and passes the data as the `widget` extra instead.
     */
    private fun handleSharedWidget(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data
        val link =
            intent.getStringExtra("widget")
                ?: data?.takeIf { it.host == Share.HOST && it.path.orEmpty().startsWith(Share.PATH) }?.toString()
                ?: return
        state.shared = link
    }

    override fun onResume() {
        super.onResume()
        away = false
        runtime.wm.focus(null)
        runtime.keyboard.imagePicker = imagePicker
        runtime.dictation.askMic = mic::ask
        removeStaleWindowTasks()
        // Made the default home app (or no longer it) while away: only then does Android show us pinned shortcuts.
        runtime.androidApps.checkShortcutAccess()
        // The user may have set or replaced the wallpaper while we were in the background.
        lifecycleScope.launch { runtime.themes.refreshLiveWallpaper() }
    }

    /**
     * Drops Recents cards whose Linux window no longer exists (app exited or process restarted). Asking for the tasks
     * goes through the system for each one, a few ms on the main thread as home comes back: they're listed off it.
     */
    private fun removeStaleWindowTasks() {
        lifecycleScope.launch {
            val windows =
                withContext(Dispatchers.IO) {
                    getSystemService(ActivityManager::class.java).appTasks.mapNotNull { task ->
                        val data = runCatching { task.taskInfo?.baseIntent?.data }.getOrNull() ?: return@mapNotNull null
                        // By its data rather than its class: windows start through aliases too.
                        if (data.scheme == "omarchy" && data.host == "window") task to data.lastPathSegment else null
                    }
                }
            val stale = windows.filter { (_, handle) -> runtime.wm.windowByHandle(handle) == null }.map { it.first }
            // A window that just closed removes its own task meanwhile; finishing it again throws.
            if (stale.isNotEmpty()) withContext(Dispatchers.IO) { stale.forEach { runCatching { it.finishAndRemoveTask() } } }
        }
    }

    /** Whether the launcher was out of sight since it was last in front: Home then only brings it back. */
    private var away = false

    /**
     * Pressing Home while already home closes the menu, like other launchers; with nothing open, it goes back to
     * page 1 (an open folder closes first). Coming back from an app keeps the page it was left on.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val nothingOpen =
            state.drawer.value == 0f && state.pulled == null && !state.homeMenuOpen && state.shared == null &&
                state.sharing == null && runtime.widgets.openScreen == null && state.editing == null
        if (!away && nothingOpen && intent.hasCategory(Intent.CATEGORY_HOME)) state.onHomeAgain()
        state.close()
        // The swipe-down panel only for a Home pressed on home: coming back from an app opened from it keeps it.
        if (!away) state.closePull()
        state.closeScreens()
        runtime.widgets.openScreen = null
        state.editing = null
        handleDebugExec(intent)
        handleSharedWidget(intent)
    }

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    /**
     * Launcher shortcuts (Super+key), then typing into a terminal tile that has the keyboard. Back stays the launcher's,
     * and so does Esc that nothing else took ([escapeAsBack]): it closes the drawer, a panel or a widget's screen.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) keyPressed(window.decorView)
        if (event.isMetaPressed && event.action == KeyEvent.ACTION_DOWN && shortcut(event.keyCode)) return true
        if (event.keyCode != KeyEvent.KEYCODE_BACK && runtime.terminals.active != null && runtime.keyboard.handle(event)) return true
        // Nothing in the window has the keys (yet, or since a tap), so an arrow has nowhere to move from: it starts in the
        // open panel, or at home's first tile. One a tap opened starts it too, though the keys are still behind it.
        if (movesFocus(event.keyCode) && event.action == KeyEvent.ACTION_DOWN) {
            if (keysStartAgain(window.decorView) || !keysFocused(window.decorView) && state.focusHome()) return true
        }
        return super.dispatchKeyEvent(event) || escapeAsBack(event)
    }

    /** Widgets only refresh while the home screen is visible. */
    override fun onStart() {
        super.onStart()
        runtime.widgets.homeStarted()
        runtime.screenStarted()
    }

    /** A terminal tile's keys held as home goes, like Super, would stay down for it: Android sends their lift elsewhere. */
    override fun onPause() {
        super.onPause()
        runtime.keyboard.releaseHeld()
        if (runtime.keyboard.imagePicker === imagePicker) runtime.keyboard.imagePicker = null
        runtime.dictation.askMic = null
    }

    override fun onStop() {
        super.onStop()
        away = true
        state.closeNow()
        // An app was just opened from home: it moves to the front of the strip now, out of sight.
        runtime.usage.settle()
        runtime.widgets.homeStopped()
        runtime.screenStopped()
    }

    private fun shortcut(keyCode: Int): Boolean {
        when (keyCode) {
            // Lets go of the terminal tile that has the keys, as Back or a tap outside does, where Esc itself is its own.
            KeyEvent.KEYCODE_ESCAPE -> if (runtime.terminals.active != null) runtime.terminals.deactivate() else return false
            // Super+D and Super+T, as Android keeps Omarchy's Super+Space (the Assistant on a Pixel) and Super+Enter (Home)
            // for itself; those stay for phones that pass them on.
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SPACE -> state.toggle()
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_ENTER -> runtime.terminal(null, "Terminal")
            // The panel swiping down opens, and closed again: it comes from above, as the drawer from the bar.
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (state.pulled != null) {
                    state.closePull()
                } else {
                    state.openPull(runtime.panelOf(runtime.gestures.swipeDown) ?: return false)
                }
            KeyEvent.KEYCODE_A -> state.promptRequest = PromptRequest.Type
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> state.requestedPage = keyCode - KeyEvent.KEYCODE_0
            else -> return false
        }
        return true
    }
}
