package dev.omalauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.nav.gestureNav
import dev.omalauncher.ui.FilesScreen
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.applySystemBars

/** The Files app for the Linux file system: its own task and Recents card, opened from the drawer or `omarchy-files` in a terminal. */
class FilesActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime
    private var startPath by mutableStateOf(DEFAULT_PATH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Files shows the Linux file system; without Linux (e.g. a Recents card from before it was removed) there's nothing to show.
        if (!runtime.bootstrap.wanted) {
            finishAndRemoveTask()
            return
        }
        startPath = intent.getStringExtra(EXTRA_PATH) ?: DEFAULT_PATH
        applySystemBars(this, Palette.isLight)
        setContent {
            LaunchedEffect(Palette.isLight) { applySystemBars(this@FilesActivity, Palette.isLight) }
            FilesScreen(runtime, startPath, onExit = { moveTaskToBack(true) })
        }
    }

    override fun onResume() {
        super.onResume()
        gestureNav.ownTaskResumed(taskId, "Files")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) gestureNav.ownTaskGone(taskId)
    }

    /** Opened again with a path (e.g. `omarchy-files ~/Downloads`): jump there. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PATH)?.let { startPath = it }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val DEFAULT_PATH = "/root"
    }
}
