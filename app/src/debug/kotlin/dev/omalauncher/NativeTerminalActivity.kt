package dev.omalauncher

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.omalauncher.terminal.TerminalSession
import dev.omalauncher.ui.ExtraKeys
import dev.omalauncher.ui.KeyboardSink
import dev.omalauncher.ui.LeftToRight
import dev.omalauncher.ui.Palette
import dev.omalauncher.ui.terminal.NativeTerminalView
import dev.omalauncher.ui.terminal.TerminalColors
import dev.omalauncher.ui.terminal.TerminalFaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds: the built-in terminal on a screen of its own, to try NativeTerminalView before terminal windows use it
 * (docs/plans/NATIVE-TERMINAL.md, step 4). A guest shell, or `bash -c` the `cmd` extra:
 * `adb shell am start -n dev.omalauncher/.NativeTerminalActivity --es cmd btop`. Closes when the program ends.
 */
class NativeTerminalActivity : InstantActivity() {
    private var view: NativeTerminalView? = null
    private var session: TerminalSession? = null

    override fun create(savedInstanceState: Bundle?) {
        val command = intent.getStringExtra("cmd")
        // Opened straight from adb, before home: Linux, the theme and the terminal font's screen metrics, as home starts them.
        runtime.start()
        val font = runtime.terminalFont
        setInstantContent {
            LeftToRight { Screen(command) }
            BackHandler { backInstantly() }
            val theme = runtime.themes.current
            LaunchedEffect(theme) { view?.colors = TerminalColors.from(theme?.colors.orEmpty()) }
            // Also once the font has the screen's metrics, if this screen came first.
            LaunchedEffect(font.size) {
                view?.emPx = font.emPx
                view?.padPx = font.padPx
            }
        }
        lifecycleScope.launch {
            val faces = withContext(Dispatchers.IO) { TerminalFaces.load(runtime.proot, font.family) }
            fontReady = true
            view?.let { v ->
                v.faces = faces
                startWhenReady(v, command)
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen(command: String?) {
        var sink by remember { mutableStateOf<KeyboardSink?>(null) }
        val density = LocalDensity.current
        val keyboard = (WindowInsets.imeAnimationTarget.getBottom(density) - WindowInsets.navigationBars.getBottom(density)).coerceAtLeast(0)
        Box(Modifier.fillMaxSize().background(Palette.background).systemBarsPadding()) {
            AndroidView(factory = { KeyboardSink(it, runtime.keyboard).also { v -> sink = v } }, modifier = Modifier.size(1.dp))
            Column(Modifier.fillMaxSize().padding(bottom = with(density) { keyboard.toDp() })) {
                AndroidView(
                    factory = { context ->
                        NativeTerminalView(context).also { v ->
                            view = v
                            v.emPx = runtime.terminalFont.emPx
                            v.padPx = runtime.terminalFont.padPx
                            v.colors = TerminalColors.from(runtime.themes.current?.colors.orEmpty())
                            v.onTap = { sink?.takeUnless { it.showing }?.show() }
                            v.onTitle = { Log.i(TAG, "title: $it") }
                            v.onGrid = { _, _, _, _ -> startWhenReady(v, command) }
                            if (hasWindowFocus()) takeKeyboard()
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (keyboard > 0) ExtraKeys(runtime, Modifier.background(Palette.background))
            }
        }
    }

    /** Whether the guest's font is loaded: until then the cells have the fallback font's size. */
    private var fontReady = false

    /**
     * Starts the program once the view has a grid in the guest's font. Started earlier, it would be resized as the
     * font comes, under its first frame, which Claude Code doesn't draw again whole.
     */
    private fun startWhenReady(
        v: NativeTerminalView,
        command: String?,
    ) {
        if (session != null || !fontReady || v.columns == 0) return
        val cols = v.columns
        val rows = v.rows
        val cellWidth = v.cellWidth
        val cellHeight = v.cellHeight
        val s =
            runCatching {
                TerminalSession.guest(runtime.proot, command, cols, rows, cellWidth, cellHeight, onExit = { code ->
                    Log.i(TAG, "exited $code")
                    runOnUiThread { closeInstantly() }
                })
            }.getOrElse {
                Log.w(TAG, "can't start", it)
                closeInstantly()
                return
            }
        session = s
        v.session = s
    }

    private fun takeKeyboard() {
        val v = view ?: return
        runtime.keyboard.target = v
        v.keyboardFocus = true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            takeKeyboard()
        } else {
            if (runtime.keyboard.target === view) runtime.keyboard.target = null
            view?.keyboardFocus = false
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (runtime.keyboard.target === view && runtime.keyboard.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (runtime.keyboard.target === view) runtime.keyboard.target = null
        if (isFinishing) session?.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "native-terminal"
    }
}
