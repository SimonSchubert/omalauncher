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

    /** Drops every waiting plan: the user went on to something else. */
    fun clear() {
        waiting.clear()
    }

    private fun expire(now: Long) {
        waiting.removeAll { now >= it.until }
    }
}

/** How long a Linux app dropped in a split may take to show its first window before it opens as usual. */
const val WINDOW_PLAN_MS = 6_000L

/**
 * Whether a split planned on a drop over [dropped] still fits the screen [now], when the dragged app's window shows up:
 * the partner is still the one app on screen, or the split whose half was to be replaced is still there. Else the
 * window opens as usual rather than bring back a screen the user has left.
 */
fun planStillFits(
    dropped: ScreenState,
    now: ScreenState,
): Boolean =
    when (dropped) {
        is ScreenState.Single -> now is ScreenState.Single && now.packageName == dropped.packageName
        is ScreenState.Split -> now is ScreenState.Split
        ScreenState.Home, ScreenState.Unknown -> false
    }
