package dev.omalauncher.nav

import android.graphics.Bitmap

/** What a recents card brings back: an app (by launching it, which resumes its task), or one of the launcher's own tasks. */
sealed interface RecentKey {
    data class App(
        val pkg: String,
    ) : RecentKey

    /** A Linux window or Files: each is a task of the launcher's own, brought back with ActivityManager.AppTask. */
    data class Task(
        val id: Int,
    ) : RecentKey
}

class RecentEntry(
    val key: RecentKey,
    var label: String,
    var icon: Bitmap?,
) {
    /** The app as it last looked, at a fraction of the screen's size; null until one is taken, or if the app forbids it. */
    var shot: Bitmap? = null
}

/**
 * The apps in the launcher's recents, most recent first. Android keeps its own list from other launchers,
 * so [NavigationService] notes apps as they come to the front, and the launcher's own tasks report
 * themselves. It lives in memory: after a restart it fills up again as apps are used. Main thread only.
 */
class RecentApps {
    private val list = mutableListOf<RecentEntry>()
    val entries: List<RecentEntry> get() = list

    /** Called when an entry comes to the front, to take its screenshot once it has drawn. */
    var onFront: ((RecentEntry) -> Unit)? = null

    fun find(key: RecentKey) = list.firstOrNull { it.key == key }

    fun toFront(entry: RecentEntry) {
        list.remove(entry)
        list.add(0, entry)
        // Screenshots are a few MB each: the older ones go, and the entries past the end.
        list.drop(MAX_SHOTS).forEach { it.shot = null }
        while (list.size > MAX) list.removeAt(list.lastIndex)
        onFront?.invoke(entry)
    }

    fun remove(key: RecentKey) {
        list.removeAll { it.key == key }
    }

    private companion object {
        const val MAX = 24
        const val MAX_SHOTS = 10
    }
}
