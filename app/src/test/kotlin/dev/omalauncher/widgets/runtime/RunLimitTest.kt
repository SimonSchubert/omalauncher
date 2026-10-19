package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.runtime.RunLimit.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class RunLimitTest {
    @Test fun holdsBackPastTheLimitAndReportsOnce() {
        val limit = RunLimit(size = 2, max = 3)
        val t = 10_000L
        assertEquals(List(3) { Verdict.RUN }, List(3) { limit.check(0, t) })
        assertEquals(Verdict.JUST_HELD, limit.check(0, t + 10))
        assertEquals(Verdict.HELD, limit.check(0, t + 20))
        // Each trigger has its own window.
        assertEquals(Verdict.RUN, limit.check(1, t + 20))
        // A new second, a new window.
        assertEquals(Verdict.RUN, limit.check(0, t + 1001))
    }
}
