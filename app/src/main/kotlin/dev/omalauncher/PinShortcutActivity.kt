package dev.omalauncher

import android.content.pm.LauncherApps
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.omalauncher.ui.KeyIndicated
import dev.omalauncher.ui.PinShortcutDialog

/**
 * Asks before an app puts a shortcut on the home screen (ShortcutManager.requestPinShortcut): a website installed
 * from a browser, a chat, a contact. Android leaves asking to the default home app, and apps only offer shortcuts
 * when it can: without this, browsers don't install websites at all. Added, the shortcut goes on the home page last
 * shown, and into the drawer.
 */
class PinShortcutActivity : ComponentActivity() {
    private val runtime get() = (application as App).runtime

    /** In the language picked in settings. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        AppLanguage.applyTo(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request =
            getSystemService(LauncherApps::class.java)
                .getPinItemRequest(intent)
                ?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && it.isValid }
        val info = request?.shortcutInfo
        if (request == null || info == null) {
            finish()
            return
        }
        val catalog = runtime.androidApps
        setContent {
            KeyIndicated {
                PinShortcutDialog(
                    label = shortcutLabel(info),
                    from = catalog.apps.firstOrNull { it.component.packageName == info.`package` && it.user == info.userHandle }?.label,
                    loadIcon = { catalog.iconOf(info) },
                    onAdd = {
                        catalog.accept(request)?.let { runtime.widgets.shortcuts.addAndroidShortcut(it, runtime.widgets.activePage) }
                        finish()
                    },
                    onDismiss = ::finish,
                )
            }
        }
    }
}
