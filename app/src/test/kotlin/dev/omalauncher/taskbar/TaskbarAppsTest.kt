package dev.omalauncher.taskbar

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskbarAppsTest {
    private val installed = setOf("a", "b", "c", "d", "e")

    @Test fun recentPutsSeenAppsBeforeLaunchedOnes() {
        assertEquals(listOf("c", "a", "b"), taskbarApps(TaskbarMode.Recent, listOf("e"), listOf("c", "a"), listOf("a", "b"), installed))
    }

    @Test fun pinnedComeFirstAndOnlyOnce() {
        assertEquals(listOf("e", "a", "c", "b"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("e", "a"), listOf("c", "a"), listOf("b"), installed))
    }

    @Test fun uninstalledAppsAreLeftOut() {
        assertEquals(listOf("a"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("gone"), listOf("x", "a"), emptyList(), installed))
    }

    @Test fun theListIsCapped() {
        assertEquals(2, taskbarApps(TaskbarMode.Recent, emptyList(), listOf("a", "b", "c"), emptyList(), installed, limit = 2).size)
    }

    @Test fun seeingAnAppMovesItToTheFront() {
        assertEquals(listOf("b", "a", "c"), moveToFront(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("z", "a"), moveToFront(listOf("a", "b"), "z", max = 2))
    }

    @Test fun modesRoundTripTheirIds() {
        for (m in TaskbarMode.entries) assertEquals(m, TaskbarMode.of(m.id))
        assertEquals(null, TaskbarMode.of("nope"))
    }

    @Test fun launchesAreReadWithTheLaunchersKeys() {
        assertEquals(listOf("maps", "mail"), launchedAndroid(listOf("a:maps", "l:foot", "a:mail")))
        assertEquals("a:maps", usageKey("maps"))
        assertEquals(listOf("maps"), launchedAndroid(listOf(usageKey("maps"))))
    }
}
