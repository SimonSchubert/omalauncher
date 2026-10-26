package dev.omadroid

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.omadroid.linux.LinuxApp
import dev.omadroid.linux.Proot
import dev.omadroid.linux.loadLinuxApps
import dev.omadroid.linux.resolveLinuxIcon
import dev.omadroid.ui.bitmapMask
import dev.omadroid.ui.decodeIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Whether [apps] has been read once, or there's no Linux to read them from; like [AndroidAppCatalog.listed]. */
    var listed by mutableStateOf(false)

    /** One rescan at a time: at start one runs before Linux is up and another once it is, and both would draw the same icons. */
    private val refreshing = Mutex()

    /** Rescans .desktop entries; the list only changes (and recomposes) when entries do. */
    suspend fun refresh() = refreshing.withLock { rescan() }

    private suspend fun rescan() {
        val loaded = withContext(Dispatchers.IO) { loadLinuxApps(proot) }
        if (loaded != apps.toList()) {
            apps.clear()
            apps += loaded
        }
        listed = true
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
        // Each one as it's ready, rather than all once the last is: the recent strip shows as soon as the list is read.
        for (app in missing) {
            val decoded =
                withContext(Dispatchers.IO) {
                    resolveLinuxIcon(proot.rootfs, app.icon!!)?.let { decodeIcon(it, size) }?.let { it.asImageBitmap() to bitmapMask(it).asImageBitmap() }
                } ?: continue
            icons[app.id] = decoded.first
            masks[app.id] = decoded.second
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
