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

/** [keys] with [key] first, at most [max] long. */
fun moveToFront(
    keys: List<String>,
    key: String,
    max: Int = 20,
): List<String> = (listOf(key) + keys.filter { it != key }).take(max)
