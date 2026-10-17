package dev.omalauncher.taskbar

/** What the taskbar lists. Saved in preferences by [id]: never rename. */
enum class TaskbarMode(
    val id: String,
    val label: String,
) {
    Recent("recent", "recent apps"),
    PinnedAndRecent("pinned", "pinned + recent"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * The app keys the taskbar shows, at most [limit]: the apps lately [seen] in front, then those [launched] from home;
 * in [TaskbarMode.PinnedAndRecent] the [pinned] ones before them all. Each once, and only [installed] ones.
 */
fun taskbarApps(
    mode: TaskbarMode,
    pinned: List<String>,
    seen: List<String>,
    launched: List<String>,
    installed: Set<String>,
    limit: Int = 12,
): List<String> {
    val recent = seen + launched
    val all = if (mode == TaskbarMode.PinnedAndRecent) pinned + recent else recent
    return all.filter { it in installed }.distinct().take(limit)
}

/** What the launcher's usage keys of Android apps start with; Linux apps' start with "l:". */
private const val ANDROID_USAGE = "a:"

/** The launcher's usage key for the Android app [appKey]: the taskbar's launches count as the launcher's do. */
fun usageKey(appKey: String) = ANDROID_USAGE + appKey

/** The Android app keys among the launcher's usage [keys] (see [usageKey]), in order. */
fun launchedAndroid(keys: List<String>): List<String> = keys.filter { it.startsWith(ANDROID_USAGE) }.map { it.removePrefix(ANDROID_USAGE) }

/** [keys] with [key] first, at most [max] long. */
fun moveToFront(
    keys: List<String>,
    key: String,
    max: Int = 20,
): List<String> = (listOf(key) + keys.filter { it != key }).take(max)
