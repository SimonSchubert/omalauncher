package dev.omalauncher.desktop

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import dev.omalauncher.AndroidApp
import dev.omalauncher.Runtime
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.ui.Arrange
import dev.omalauncher.ui.Palette

/** Home state shared by Android's native desktop Activity and approved surface hosts. */
internal class DesktopHomeSession(
    context: Context,
    private val runtime: Runtime,
    profile: String,
    pagesSideBySide: Boolean,
    inheritPhoneLayout: Boolean,
    val workspacePages: Boolean,
    screenWidthPx: Int,
    screenHeightPx: Int,
    uiScale: Float,
    private val launchApp: (AndroidApp) -> Unit,
    private val launchLinuxApp: (LinuxApp) -> Unit,
    private val launchTerminal: () -> Unit,
    private val authorized: () -> Boolean,
    private val focus: () -> Unit = {},
    private val report: (String) -> Unit = {},
) {
    var pagesSideBySide by mutableStateOf(pagesSideBySide && !workspacePages)
    var workspace by mutableStateOf(1)
    var query by mutableStateOf("")
    var drawer by mutableStateOf(false)
    var picker by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    val drawerScroll = LazyListState()
    val arrange = Arrange()
    val widgets = DesktopWidgets(context, runtime, profile, ::launch, ::uiAction, ::unavailable, authorized,
        inheritPhoneLayout, workspacePages, screenWidthPx, screenHeightPx, uiScale)

    fun start() = widgets.start()
    fun close() = widgets.close()
    fun visible(value: Boolean) { widgets.visible = value; if (!value) dismiss() }

    fun launch(app: AndroidApp) { if (authorized()) launchApp(app) }
    fun launch(app: LinuxApp) {
        if (!authorized()) return
        if (!runtime.linuxAvailable) return unavailable("Install Linux in Omalauncher on the phone first")
        launchLinuxApp(app)
        dismiss()
    }
    fun launch(app: DesktopApp) = when (app) {
        is DesktopApp.Android -> launch(app.app)
        is DesktopApp.Linux -> launch(app.app)
    }
    fun unavailable(text: String) { message = text; report(text) }

    fun dismiss() {
        drawer = false
        picker = false
        query = ""
        widgets.openScreen = null
        arrange.folder = null
        arrange.drop()
    }

    fun uiAction(action: String) {
        if (!authorized()) return
        if (workspacePages && action.startsWith("workspace:")) {
            action.substringAfter(':').toIntOrNull()?.takeIf { it in 1..10 }?.let { workspace = it }
            return
        }
        when (action) {
            "drawer", "search" -> { drawer = true; focus() }
            "widgets" -> picker = true
            "terminal" -> if (runtime.linuxAvailable) { launchTerminal(); dismiss() }
                else unavailable("Install Linux in Omalauncher on the phone first")
            "dismiss" -> dismiss()
            "focus" -> focus()
            else -> unavailable("Action is unavailable on this desktop: $action")
        }
    }

    fun search(text: String, submit: Boolean) {
        require(text.length <= 2048) { "query is too long" }
        query = text
        drawer = true
        if (submit && text.isNotBlank()) matchingApps(runtime, text).firstOrNull()?.let(::launch)
    }

    fun describe() = Bundle().apply {
        putIntArray("pages", widgets.pages.toIntArray())
        putBoolean("pagesSideBySide", pagesSideBySide)
        putBoolean("workspacePages", workspacePages)
        if (workspacePages) putInt("workspace", workspace)
        putBoolean("overlay", drawer || picker || widgets.openScreen != null || arrange.folder != null)
        putString("query", query)
        putString("message", message)
        putInt("drawerScrollIndex", drawerScroll.firstVisibleItemIndex)
        putInt("drawerScrollOffset", drawerScroll.firstVisibleItemScrollOffset)
        putString("theme", runtime.themes.picked)
        putBundle("palette", Bundle().apply {
            Palette.current.named.forEach { (name, color) -> putString(name, "#%06X".format(color.toArgb() and 0xFFFFFF)) }
            putString("mode", if (Palette.isLight) "light" else "dark")
        })
    }
}
