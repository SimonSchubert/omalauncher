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
 * The keys of the apps the taskbar shows, at most [limit]: the Android apps lately [seen] in front, then those
 * [launched] from home or the taskbar, Android and Linux alike; in [TaskbarMode.PinnedAndRecent] the [pinned] ones
 * before them all. Each once, and only [installed] ones. Keys are the launcher's usage keys ([usageKey], [linuxKey]);
 * [seen] holds plain Android app keys, as the taskbar sees them on screen.
 */
fun taskbarApps(
    mode: TaskbarMode,
    pinned: List<String>,
    seen: List<String>,
    launched: List<String>,
    installed: Set<String>,
    limit: Int = 12,
): List<String> {
    val recent = seen.map(::usageKey) + launched
    val all = if (mode == TaskbarMode.PinnedAndRecent) pinned + recent else recent
    return all.filter { it in installed }.distinct().take(limit)
}

/** What the launcher's usage keys of Android apps start with. */
private const val ANDROID_USAGE = "a:"

/** What the launcher's usage keys of Linux apps start with. */
private const val LINUX_USAGE = "l:"

/** The launcher's usage key for the Android app [appKey]: the taskbar's launches count as the launcher's do. */
fun usageKey(appKey: String) = ANDROID_USAGE + appKey

/** The launcher's usage key for the Linux app with desktop id [id], as the drawer counts it. */
fun linuxKey(id: String) = LINUX_USAGE + id

/**
 * A pinned app's key as saved: pins from before Linux apps could be pinned are plain Android app keys, and read as
 * [usageKey]s. A package name has no colon, so no Android app key starts like a prefixed one.
 */
fun pinnedKey(saved: String) = if (saved.startsWith(ANDROID_USAGE) || saved.startsWith(LINUX_USAGE)) saved else usageKey(saved)

/** [keys] with [key] first, at most [max] long. */
fun moveToFront(
    keys: List<String>,
    key: String,
    max: Int = 20,
): List<String> = (listOf(key) + keys.filter { it != key }).take(max)
