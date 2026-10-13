package dev.omalauncher

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.ui.bitmapMask
import dev.omalauncher.widgets.PinnedShortcut
import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.Shortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where apps pin their shortcuts: a browser's "Add to Home screen" or "Install app", a contact,
 * a settings page. Android sends the request to the default launcher only; it's accepted as is,
 * like Lawnchair does, and lands on the page that's showing.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && it.isValid }
        val info = request?.shortcutInfo
        // accept() pins it system-side; without that the app can't be started from here.
        if (request != null && info != null && runCatching { request.accept() }.getOrDefault(false)) {
            val runtime = (application as App).runtime
            runtime.pinned.add(info, runtime.widgets.activePage)
            Toast.makeText(this, "Added ${info.shortLabel ?: info.`package`} to home", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

/**
 * Shortcuts other apps pinned, as [Shortcut]s with [Shortcut.pinned]. The system keeps the shortcut
 * itself; its icon is saved here, since asking for it again needs the app's shortcut to still be there.
 */
class PinnedShortcuts(
    private val context: Context,
    private val shortcuts: Shortcuts,
) {
    /** By [Shortcut.key]; masks are the silhouettes for monochrome icons. */
    private val icons = mutableStateMapOf<String, ImageBitmap>()
    private val masks = mutableStateMapOf<String, ImageBitmap>()
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val dir = File(context.filesDir, "pinned-icons")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Icon edge in px, as the drawer's (see AndroidAppCatalog). */
    private val iconPx = (52 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)

    /** Loads the saved icons, and drops those of shortcuts taken off home since. */
    fun start() {
        scope.launch {
            val keys = shortcuts.items.filter { it.pinned != null }.map { it.key }
            val loaded =
                withContext(Dispatchers.IO) {
                    dir.listFiles()?.filter { f -> keys.none { iconFile(it) == f } }?.forEach { it.delete() }
                    keys.mapNotNull { key -> BitmapFactory.decodeFile(iconFile(key).path)?.let { key to it } }
                }
            for ((key, bmp) in loaded) keep(key, bmp)
        }
    }

    fun add(
        info: ShortcutInfo,
        page: Int,
    ) {
        val profile = info.userHandle.takeIf { it != Process.myUserHandle() }?.let { userManager.getSerialNumberForUser(it) }
        val label = (info.shortLabel ?: info.longLabel ?: info.`package`).toString()
        val shortcut = shortcuts.addPinned(PinnedShortcut(info.`package`, info.id), label, profile, page)
        val bmp =
            runCatching {
                val d = launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi) ?: return@runCatching null
                Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888).also {
                    d.setBounds(0, 0, iconPx, iconPx)
                    d.draw(Canvas(it))
                }
            }.onFailure { Log.w(TAG, "no icon for ${info.id}", it) }.getOrNull() ?: return
        keep(shortcut.key, bmp)
        scope.launch(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                iconFile(shortcut.key).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.onFailure { Log.w(TAG, "cannot save the icon of ${shortcut.key}", it) }
        }
    }

    /** [shortcut]'s icon, or its silhouette when [mono]; null for initials. */
    fun icon(
        shortcut: Shortcut,
        mono: Boolean,
    ) = if (mono) masks[shortcut.key] else icons[shortcut.key]

    fun open(shortcut: Shortcut) {
        val pinned = shortcut.pinned ?: return
        val user = shortcut.profile?.let { userManager.getUserForSerialNumber(it) } ?: Process.myUserHandle()
        // The app may have removed or disabled it, or been uninstalled.
        runCatching { launcherApps.startShortcut(pinned.packageName, pinned.id, null, null, user) }.onFailure {
            Log.w(TAG, "cannot open ${shortcut.key}", it)
            Toast.makeText(context, "${shortcut.label} is no longer available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun keep(
        key: String,
        bmp: Bitmap,
    ) {
        icons[key] = bmp.asImageBitmap()
        masks[key] = bitmapMask(bmp).asImageBitmap()
    }

    /** Keys hold '/' and '@', so files are named by a hash of them. */
    private fun iconFile(key: String) = File(dir, Integer.toHexString(key.hashCode()) + ".png")

    private companion object {
        const val TAG = "PinnedShortcuts"
    }
}
