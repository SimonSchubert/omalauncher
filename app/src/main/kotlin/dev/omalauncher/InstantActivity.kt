package dev.omalauncher

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.view.KeyEvent
import android.window.SplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import dev.omalauncher.ui.KeyIndicated
import dev.omalauncher.ui.keyPressed
import dev.omalauncher.ui.keysRoot
import dev.omalauncher.ui.keysFocused
import dev.omalauncher.ui.keysStartAgain
import dev.omalauncher.ui.movesFocus
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.SystemBarsEffect
import dev.omalauncher.ui.applySystemBars
import java.util.Collections
import java.util.WeakHashMap

/**
 * The launcher's own screens with their own task (settings, Files, the editor, Linux
 * windows) come and go at once, as part of the launcher rather than another app.
 * Opening is [startInstantly]; leaving is [closeInstantly] or [backInstantly].
 */
abstract class InstantActivity : ComponentActivity() {
    /** True while the window is see-through and empty, on its way out. */
    private var hidden by mutableStateOf(false)
    private var leaving = false
    private var background: Drawable? = null

    protected val runtime get() = (application as App).runtime

    /** In the language picked in settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this)
    }

    /** Like setContent, with the system bars in the theme's colors, but drawing nothing while the window leaves. */
    protected fun setInstantContent(content: @Composable () -> Unit) {
        applySystemBars(this, Palette.isLight)
        setContent {
            SystemBarsEffect(this)
            if (!hidden) KeyIndicated { Box(Modifier.keysRoot()) { content() } }
        }
    }

    // Lint flags super.dispatchKeyEvent: ComponentActivity marks its override @RestrictTo, but Activity's is public API.
    /**
     * An arrow with nothing focused (as after a tap) starts in the screen's panel again, and so does typing, which then
     * goes on to where it started, like the editor's text; Esc that nothing took is Back.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) keyPressed(window.decorView)
        if (down && movesFocus(event.keyCode) && keysStartAgain(window.decorView)) return true
        if (down && event.isPrintingKey && !keysFocused(window.decorView)) keysStartAgain(window.decorView)
        return super.dispatchKeyEvent(event) || escapeAsBack(event)
    }

    /** Closes the task, without the slide Android gives a closing task. */
    fun closeInstantly() = leave { finishAndRemoveTask() }

    /** Back to whatever was before, the task kept for Recents, without the slide either. */
    fun backInstantly() = leave { moveTaskToBack(true) }

    /**
     * Android animates a task closing or going to the back whatever the app asks,
     * so the window turns see-through and empty first: what slides is invisible.
     */
    private fun leave(then: () -> Unit) {
        if (leaving) return
        leaving = true
        if (Build.VERSION.SDK_INT < 30) return then()
        setTranslucent(true)
        // Until the system has made it see-through, an empty window shows black.
        window.decorView.postDelayed({
            hidden = true
            background = window.decorView.background
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.decorView.postOnAnimation(then)
        }, SEE_THROUGH_MS)
    }

    /** Names the Recents card [label], with [appIcon], else its initials in the theme colors. */
    protected fun describeTask(
        label: String,
        appIcon: Bitmap?,
    ) {
        val icon = appIcon ?: taskIcon(label, Palette.background.toArgb(), Palette.accent.toArgb())
        val description =
            if (Build.VERSION.SDK_INT >= 37) {
                ActivityManager.TaskDescription
                    .Builder()
                    .setLabel(label)
                    .setIcon(Icon.createWithBitmap(icon))
                    .setPrimaryColor(Palette.background.toArgb())
                    .build()
            } else {
                // Builder.setIcon(Icon) is API 37+; the bitmap constructor still works before that.
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(label, icon, Palette.background.toArgb())
            }
        setTaskDescription(description)
    }

    /** Back from Recents after [backInstantly]: solid again, so what's behind it can stop drawing. */
    override fun onRestart() {
        super.onRestart()
        if (!leaving || isFinishing) return
        leaving = false
        background?.let(window::setBackgroundDrawable)
        hidden = false
        if (Build.VERSION.SDK_INT >= 30) setTranslucent(false)
    }

    companion object {
        private const val SEE_THROUGH_MS = 80L

        /** Starts one of the launcher's own screens in its own task: no open-an-app animation, and no splash with the launcher's icon. */
        fun startInstantly(
            context: Context,
            intent: Intent,
        ) {
            val options = if (Build.VERSION.SDK_INT >= 33) ActivityOptions.makeBasic().setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR).toBundle() else null
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION), options)
        }
    }
}

/**
 * Esc that nothing else took is Back, as on a desktop: it closes what's open. Android leaves it unhandled. Shift doesn't
 * matter (the emulator's keyboard can report it held); Ctrl, Alt or Super make it another shortcut.
 */
internal fun ComponentActivity.escapeAsBack(event: KeyEvent): Boolean {
    if (event.keyCode != KeyEvent.KEYCODE_ESCAPE) return false
    // Only a press seen here: the Esc of Super+Esc, which left another window, lifts in this one.
    if (event.action == KeyEvent.ACTION_DOWN) {
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        escapesDown += this
        return true
    }
    if (!escapesDown.remove(this)) return false
    if (!event.isCanceled) onBackPressedDispatcher.onBackPressed()
    return true
}

/** The activities an Esc went down in that hasn't lifted yet. */
private val escapesDown = Collections.newSetFromMap(WeakHashMap<ComponentActivity, Boolean>())
