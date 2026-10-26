package dev.omadroid

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.omadroid.ui.LinuxDashboard
import dev.omadroid.ui.Palette
import dev.omadroid.ui.SystemBarsEffect
import dev.omadroid.ui.applySystemBars
import dev.omadroid.widgets.market.Share

/** The Linux environment dashboard: overview, apps, terminal, widgets, files, themes and settings.
 * A regular activity opened from the app drawer, not the Android home screen. */
class MainActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    /** A widget link opened from a browser or QR code, waiting to be read and added. */
    private val shared = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // While a backup or device transfer runs, the process has a plain Application and no runtime;
        // close until the process restarts as itself.
        if (application !is App) {
            finish()
            return
        }
        applySystemBars(this, Palette.isLight)
        runtime.start()
        setContent {
            LinuxDashboard(runtime, sharedWidget = shared.value, onSharedCleared = { shared.value = null })
            SystemBarsEffect(this)
        }
        handleDebugExec(intent)
        handleSharedWidget(intent)
    }

    /** Debug builds: `adb shell am start -n dev.omadroid/.MainActivity --es exec foot`,
     * or `--es theme kanagawa` to apply a theme. */
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

    /** A widget link (see [Share]): from a browser, or the store page's "open in Omalauncher" button. */
    private fun handleSharedWidget(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val link =
            intent.getStringExtra("widget")
                ?: intent.data?.takeIf { it.host == Share.HOST && it.path.orEmpty().startsWith(Share.PATH) }?.toString()
                ?: return
        shared.value = link
    }
}
