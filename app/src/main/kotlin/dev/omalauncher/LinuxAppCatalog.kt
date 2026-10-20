package dev.omalauncher

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.linux.LinuxApp
import dev.omalauncher.linux.Proot
import dev.omalauncher.linux.loadLinuxApps
import dev.omalauncher.linux.resolveLinuxIcon
import dev.omalauncher.ui.bitmapMask
import dev.omalauncher.ui.decodeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The guest's apps from their .desktop entries, with their icons; the Linux side of [AndroidAppCatalog]. */
class LinuxAppCatalog(
    private val context: Context,
    private val proot: Proot,
) {
    val apps = mutableStateListOf<LinuxApp>()

    /** Icons of [apps] by desktop id; apps without a PNG icon have no entry. */
    val icons = mutableStateMapOf<String, ImageBitmap>()

    /** Silhouettes of [icons] for the monochrome icon style. */
    val masks = mutableStateMapOf<String, ImageBitmap>()

    /** The Icon= each of [icons] was loaded from, to notice an entry changing it. */
    private val iconNames = HashMap<String, String>()

    /** Rescans .desktop entries; the list only changes (and recomposes) when entries do. */
    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) { loadLinuxApps(proot) }
        if (loaded != apps.toList()) {
            apps.clear()
            apps += loaded
        }
        val ids = loaded.mapTo(HashSet()) { it.id }
        // An entry can change its Icon= under the same id: on a fresh install, pacman's micro.desktop
        // shows up before the launcher's Editor entry replaces it.
        for (app in loaded) {
            if (iconNames[app.id].let { it != null && it != app.icon }) {
                icons -= app.id
                masks -= app.id
                iconNames -= app.id
            }
        }
        icons.keys.retainAll(ids)
        masks.keys.retainAll(ids)
        iconNames.keys.retainAll(ids)
        val missing = loaded.filter { it.icon != null && it.id !in icons }
        if (missing.isEmpty()) return
        val size = (52 * context.resources.displayMetrics.density).toInt()
        val decoded =
            withContext(Dispatchers.IO) {
                missing.mapNotNull { app ->
                    val file = resolveLinuxIcon(proot.rootfs, app.icon!!) ?: return@mapNotNull null
                    decodeIcon(file, size)?.let { Triple(app, it.asImageBitmap(), bitmapMask(it).asImageBitmap()) }
                }
            }
        for ((app, icon, mask) in decoded) {
            icons[app.id] = icon
            masks[app.id] = mask
            iconNames[app.id] = app.icon!!
        }
    }

    /** Linux was removed. */
    fun clear() {
        apps.clear()
        icons.clear()
        masks.clear()
        iconNames.clear()
    }
}
