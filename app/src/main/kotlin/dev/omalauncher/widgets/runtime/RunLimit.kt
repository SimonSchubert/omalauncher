package dev.omalauncher.widgets.runtime

/**
 * At most [max] runs per one-second window for each of [size] triggers: a trigger
 * that changes what it watches would otherwise loop for as long as the widget runs.
 */
internal class RunLimit(
    size: Int,
    private val max: Int,
) {
    enum class Verdict { RUN, HELD, JUST_HELD }

    private val windows = LongArray(size)
    private val runs = IntArray(size)

    /** Whether trigger [i] may run at [nowMs]; [Verdict.JUST_HELD] the first time a window holds it back, to report once. */
    fun check(
        i: Int,
        nowMs: Long,
    ): Verdict {
        if (nowMs - windows[i] > 1000) {
            windows[i] = nowMs
            runs[i] = 0
        }
        return when {
            ++runs[i] <= max -> Verdict.RUN
            runs[i] == max + 1 -> Verdict.JUST_HELD
            else -> Verdict.HELD
        }
    }
}
