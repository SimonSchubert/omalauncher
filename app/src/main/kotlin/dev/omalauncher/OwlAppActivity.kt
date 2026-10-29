package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.omalauncher.ui.MicPermission
import dev.omalauncher.ui.OwlAppWindow
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.holdsKeys
import dev.omalauncher.ui.showStatusBar
import dev.omalauncher.widgets.render.LocalInputFocus

/**
 * An owl app (`app "name" { }`) as its own task and Recents card, named after it and with its icon,
 * opened from the drawer or a home icon. It stays live while it shows, home or not; its close(),
 * or the app going away (removed, or no longer compiling), closes the task.
 */
class OwlAppActivity : InstantActivity() {
    private var name: String? = null
    private val mic = MicPermission(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.data?.schemeSpecificPart
        // Gone since Recents was built, or the process restarted before the files loaded.
        if (name == null || runtime.widgets.appInstance(name) == null) {
            finishAndRemoveTask()
            return
        }
        this.name = name
        setInstantContent {
            LaunchedEffect(name) { runtime.widgets.appCloses.collect { if (it == name) closeInstantly() } }
            val (spec, inst) =
                runtime.widgets.appInstance(name) ?: run {
                    LaunchedEffect(Unit) { closeInstantly() }
                    return@setInstantContent
                }
            val icon = runtime.owlIcon(spec, mono = false)
            LaunchedEffect(spec.name, icon, Palette.accent) { describeTask(spec.name, icon?.asAndroidBitmap()) }
            // `statusBar: false` hides the status bar while the app shows, as on a face; a swipe down brings it back for a moment.
            val statusBar = inst.showsStatusBar()
            LaunchedEffect(statusBar) { showStatusBar(this@OwlAppActivity, statusBar) }
            // While the app's `back` holds, Back goes up a level inside it; otherwise Back leaves it.
            BackHandler { if (!inst.goUp()) backInstantly() }
            // Keys start in it, and an arrow after a tap starts there again.
            CompositionLocalProvider(LocalInputFocus provides true) { Box(Modifier.holdsKeys()) { OwlAppWindow(spec, inst) } }
        }
    }

    override fun onStart() {
        super.onStart()
        name?.let(runtime.widgets::appStarted)
        runtime.screenStarted()
    }

    override fun onResume() {
        super.onResume()
        runtime.dictation.askMic = mic::ask
    }

    override fun onPause() {
        super.onPause()
        runtime.dictation.askMic = null
    }

    override fun onStop() {
        super.onStop()
        name?.let(runtime.widgets::appStopped)
        runtime.screenStopped()
    }

    companion object {
        /** The app in file [name]; the data names the document, so each app gets one task. */
        fun intent(
            context: Context,
            name: String,
        ): Intent = Intent(context, OwlAppActivity::class.java).setData(Uri.fromParts("owl", name, null))
    }
}
