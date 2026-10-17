package dev.omalauncher.taskbar

/**
 * Where the first windows of Linux apps dragged off the taskbar go. An app [A] dropped in a split starts like any
 * launch, and its plan [P] waits here for the app's first window, at most [ttlMs]: a window that shows up later opens
 * as usual. Times are any one clock's milliseconds. Lives on the main thread.
 */
class WindowPlans<A, P>(
    private val ttlMs: Long = WINDOW_PLAN_MS,
) {
    private class Waiting<A, P>(
        val app: A,
        val plan: P,
        val until: Long,
    )

    private val waiting = ArrayList<Waiting<A, P>>()

    /** Waits for [app]'s first window from [now] on, to open it by [plan]; a plan [app] already had is dropped. */
    fun add(
        app: A,
        plan: P,
        now: Long,
    ) {
        expire(now)
        waiting.removeAll { it.app == app }
        waiting += Waiting(app, plan, now + ttlMs)
    }

    /**
     * The plan for a window showing up at [now], of the first waiting app it [belongsTo]; taken, so it's used once.
     * Null when none waits for it.
     */
    fun take(
        now: Long,
        belongsTo: (A) -> Boolean,
    ): P? {
        expire(now)
        val i = waiting.indexOfFirst { belongsTo(it.app) }
        return if (i < 0) null else waiting.removeAt(i).plan
    }

    private fun expire(now: Long) {
        waiting.removeAll { now >= it.until }
    }
}

/** How long a Linux app dropped in a split may take to show its first window before it opens as usual. */
const val WINDOW_PLAN_MS = 10_000L
