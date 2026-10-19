package dev.omalauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.omalauncher.ui.OwlAppWindow
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.applySystemBars
import dev.omalauncher.widgets.render.LocalInputFocus

/**
 * An owl app (`app "name" { }`) as its own task and Recents card, named after it and with its icon,
 * opened from the drawer or a home icon. It stays live while it shows, home or not; its close(),
 * or the app going away (removed, or no longer compiling), closes the task.
 */
class OwlAppActivity : InstantActivity() {
    private val runtime get() = (application as App).runtime
    private var name: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.data?.schemeSpecificPart
        // Gone since Recents was built, or the process restarted before the files loaded.
        if (name == null || runtime.widgets.appInstance(name) == null) {
            finishAndRemoveTask()
            return
        }
        this.name = name
        applySystemBars(this, Palette.isLight)
        setInstantContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@OwlAppActivity, Palette.isLight) }
            LaunchedEffect(name) { runtime.widgets.appCloses.collect { if (it == name) closeInstantly() } }
            val (spec, inst) =
                runtime.widgets.appInstance(name) ?: run {
                    LaunchedEffect(Unit) { closeInstantly() }
                    return@setInstantContent
                }
            val icon = runtime.owlIcon(spec, mono = false)
            LaunchedEffect(spec.name, icon, Palette.accent) { describeTask(spec.name, icon?.asAndroidBitmap()) }
            // While the app's `back` holds, Back goes up a level inside it; otherwise Back leaves it.
            BackHandler { if (!inst.goUp()) backInstantly() }
            CompositionLocalProvider(LocalInputFocus provides true) { OwlAppWindow(spec, inst) }
        }
    }

    override fun onStart() {
        super.onStart()
        name?.let(runtime.widgets::appStarted)
        runtime.screenStarted()
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
