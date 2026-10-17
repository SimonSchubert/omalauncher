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
 * The keys of the apps the taskbar shows, at most [limit]: the apps lately [seen] in front (an Android app's window, or
 * one of a Linux app's), then those [launched] from home or the taskbar; in [TaskbarMode.PinnedAndRecent] the [pinned]
 * ones before them all. Android and Linux alike, each once, and only [installed] ones. Keys are the launcher's usage keys
 * ([usageKey], [linuxKey]).
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

/** What the launcher's usage keys of Android apps start with. */
private const val ANDROID_USAGE = "a:"

/** What the launcher's usage keys of Linux apps start with. */
private const val LINUX_USAGE = "l:"

/** The launcher's usage key for the Android app [appKey]: the taskbar's launches count as the launcher's do. */
fun usageKey(appKey: String) = ANDROID_USAGE + appKey

/** The launcher's usage key for the Linux app with desktop id [id], as the drawer counts it. */
fun linuxKey(id: String) = LINUX_USAGE + id

/**
 * A pinned or seen app's key as saved: those from before Linux apps could be listed are plain Android app keys, and read
 * as [usageKey]s. A package name has no colon, so no Android app key starts like a prefixed one.
 */
fun savedKey(saved: String) = if (saved.startsWith(ANDROID_USAGE) || saved.startsWith(LINUX_USAGE)) saved else usageKey(saved)

/**
 * Which of [apps] a Linux window with app id [appId] belongs to, as the launcher tells when it opens an app that already
 * has a window: by app id ([matches]), else by one of the window's [names] (what the launcher named it, its title).
 */
fun <A> appOfWindow(
    apps: List<A>,
    appId: String,
    names: List<String>,
    matches: (A, String) -> Boolean,
    nameOf: (A) -> String,
): A? =
    apps.firstOrNull { appId.isNotEmpty() && matches(it, appId) }
        ?: apps.firstOrNull { app -> names.any { it.isNotEmpty() && it.equals(nameOf(app), ignoreCase = true) } }

/**
 * Which of the Linux window [tasks] on screen just came to the front, from the system's [className] for it: the one whose
 * activity alias [classOf] gives that name; else, when it's the window activity itself ([windowClass]) or unnamed, the
 * only one. Null when that can't tell, or for another of the launcher's screens (Files, say).
 */
fun <T> frontTask(
    tasks: List<T>,
    className: String?,
    windowClass: String,
    classOf: (T) -> String?,
): T? =
    tasks.firstOrNull { className != null && classOf(it) == className }
        ?: tasks.singleOrNull()?.takeIf { className == null || className == windowClass }

/** [keys] with [key] first, at most [max] long. */
fun moveToFront(
    keys: List<String>,
    key: String,
    max: Int = 20,
): List<String> = (listOf(key) + keys.filter { it != key }).take(max)
