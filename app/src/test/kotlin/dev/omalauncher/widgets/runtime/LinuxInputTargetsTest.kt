package dev.omalauncher.widgets.runtime

import org.junit.Assert.*
import org.junit.Test

class LinuxInputTargetsTest {
    @Test fun namesAreScopedAndDuplicateNamesFailClosed() {
        val a = LinuxInputTargets<Any>(); val b = LinuxInputTargets<Any>()
        val first = Any(); val second = Any(); val one = Any(); val two = Any()
        a.bind(one, "game", first)
        b.bind(two, "game", second)
        assertSame(first, a.target("game")); assertSame(second, b.target("game"))
        a.bind(two, "game", second)
        assertNull(a.target("game"))
        a.unbind(two)
        assertSame(first, a.target("game"))
        a.clear()
        assertNull(a.target("game"))
    }

    @Test fun replacingOrRemovingAViewCopyKeepsTheOtherBinding() {
        val targets = LinuxInputTargets<Any>()
        val session = Any(); val a = Any(); val b = Any()
        targets.bind(a, "game", session); targets.bind(b, "game", session)
        assertSame(session, targets.target("game"))
        targets.unbind(a)
        assertSame(session, targets.target("game"))
        targets.bind(b, "changed", session)
        assertNull(targets.target("game")); assertSame(session, targets.target("changed"))
        targets.unbind(b)
        assertNull(targets.target("changed"))
    }
}
