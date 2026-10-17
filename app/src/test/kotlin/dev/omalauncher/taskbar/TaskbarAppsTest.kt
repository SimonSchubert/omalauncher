package dev.omalauncher.taskbar

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskbarAppsTest {
    private val installed = setOf("a:a", "a:b", "a:c", "a:d", "a:e", "l:foot", "l:btop")

    @Test fun recentPutsSeenAppsBeforeLaunchedOnes() {
        assertEquals(listOf("a:c", "a:a", "a:b"), taskbarApps(TaskbarMode.Recent, listOf("a:e"), listOf("c", "a"), listOf("a:a", "a:b"), installed))
    }

    @Test fun pinnedComeFirstAndOnlyOnce() {
        assertEquals(listOf("a:e", "a:a", "a:c", "a:b"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("a:e", "a:a"), listOf("c", "a"), listOf("a:b"), installed))
    }

    @Test fun uninstalledAppsAreLeftOut() {
        assertEquals(listOf("a:a"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("a:gone", "l:gone"), listOf("x", "a"), emptyList(), installed))
    }

    @Test fun theListIsCapped() {
        assertEquals(2, taskbarApps(TaskbarMode.Recent, emptyList(), listOf("a", "b", "c"), emptyList(), installed, limit = 2).size)
    }

    @Test fun linuxAppsComeInLaunchOrderAmongAndroidOnes() {
        assertEquals(
            listOf("a:c", "l:foot", "a:a", "l:btop"),
            taskbarApps(TaskbarMode.Recent, emptyList(), listOf("c"), listOf("l:foot", "a:a", "o:clock", "s:web", "l:btop"), installed),
        )
    }

    @Test fun linuxAppsCanBePinned() {
        assertEquals(listOf("l:btop", "a:a", "a:c"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("l:btop", "a:a"), listOf("c"), listOf("l:btop"), installed))
    }

    @Test fun withoutLinuxItsAppsAreLeftOut() {
        val android = installed.filter { it.startsWith("a:") }.toSet()
        assertEquals(listOf("a:a", "a:c"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("l:btop", "a:a"), listOf("c"), listOf("l:foot"), android))
    }

    @Test fun olderPinsReadAsAndroidKeys() {
        assertEquals("a:com.maps/.Main", pinnedKey("com.maps/.Main"))
        assertEquals("a:com.maps/.Main@10", pinnedKey("a:com.maps/.Main@10"))
        assertEquals("l:foot", pinnedKey("l:foot"))
    }

    @Test fun seeingAnAppMovesItToTheFront() {
        assertEquals(listOf("b", "a", "c"), moveToFront(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("z", "a"), moveToFront(listOf("a", "b"), "z", max = 2))
    }

    @Test fun modesRoundTripTheirIds() {
        for (m in TaskbarMode.entries) assertEquals(m, TaskbarMode.of(m.id))
        assertEquals(null, TaskbarMode.of("nope"))
    }

    @Test fun keysAreTheLaunchersUsageKeys() {
        assertEquals("a:maps", usageKey("maps"))
        assertEquals("l:foot", linuxKey("foot"))
    }
}
