package dev.omalauncher.taskbar

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskbarAppsTest {
    private val installed = setOf("a:a", "a:b", "a:c", "a:d", "a:e", "l:foot", "l:btop")

    @Test fun recentPutsSeenAppsBeforeLaunchedOnes() {
        assertEquals(listOf("a:c", "a:a", "a:b"), taskbarApps(TaskbarMode.Recent, listOf("a:e"), listOf("a:c", "a:a"), listOf("a:a", "a:b"), installed))
    }

    @Test fun pinnedComeFirstAndOnlyOnce() {
        assertEquals(listOf("a:e", "a:a", "a:c", "a:b"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("a:e", "a:a"), listOf("a:c", "a:a"), listOf("a:b"), installed))
    }

    @Test fun uninstalledAppsAreLeftOut() {
        assertEquals(listOf("a:a"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("a:gone", "l:gone"), listOf("a:x", "a:a"), emptyList(), installed))
    }

    @Test fun theListIsCapped() {
        assertEquals(2, taskbarApps(TaskbarMode.Recent, emptyList(), listOf("a:a", "a:b", "a:c"), emptyList(), installed, limit = 2).size)
    }

    @Test fun linuxAppsComeInLaunchOrderAmongAndroidOnes() {
        assertEquals(
            listOf("a:c", "l:foot", "a:a", "l:btop"),
            taskbarApps(TaskbarMode.Recent, emptyList(), listOf("a:c"), listOf("l:foot", "a:a", "o:clock", "s:web", "l:btop"), installed),
        )
    }

    @Test fun linuxAppsCanBePinned() {
        assertEquals(listOf("l:btop", "a:a", "a:c"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("l:btop", "a:a"), listOf("a:c"), listOf("l:btop"), installed))
    }

    @Test fun withoutLinuxItsAppsAreLeftOut() {
        val android = installed.filter { it.startsWith("a:") }.toSet()
        assertEquals(listOf("a:a", "a:c"), taskbarApps(TaskbarMode.PinnedAndRecent, listOf("l:btop", "a:a"), listOf("a:c"), listOf("l:foot"), android))
    }

    @Test fun olderPinsReadAsAndroidKeys() {
        assertEquals("a:com.maps/.Main", savedKey("com.maps/.Main"))
        assertEquals("a:com.maps/.Main@10", savedKey("a:com.maps/.Main@10"))
        assertEquals("l:foot", savedKey("l:foot"))
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

    @Test fun aLinuxAppSeenOnScreenComesBeforeLaunchedOnes() {
        assertEquals(listOf("l:btop", "a:c", "l:foot"), taskbarApps(TaskbarMode.Recent, emptyList(), listOf("l:btop", "a:c"), listOf("l:foot", "l:btop"), installed))
    }

    private data class App(val id: String, val name: String)
    private val apps = listOf(App("org.gnome.Calculator", "Calculator"), App("btop", "btop"))

    @Test fun aLinuxWindowBelongsToTheAppItsIdMatches() {
        assertEquals(apps[0], appOfWindow(apps, "org.gnome.Calculator", listOf("Calc"), { a, id -> a.id == id }, { it.name }))
    }

    @Test fun elseToTheAppItsNamed() {
        // A terminal app: its window's app id is the launcher's own, its name the app's.
        assertEquals(apps[1], appOfWindow(apps, "omarchy.btop", listOf("BTOP", "htop"), { a, id -> a.id == id }, { it.name }))
        assertEquals(null, appOfWindow(apps, "", listOf("", "Files"), { a, id -> a.id == id }, { it.name }))
    }

    @Test fun theTaskInFrontGoesByItsClassElseIsTheOnlyOne() {
        val window = "dev.omalauncher.LinuxWindowActivity"
        val tasks = listOf("dev.omalauncher.TerminalWindow", "dev.omalauncher.BtopWindow")
        assertEquals(tasks[1], frontTask(tasks, "dev.omalauncher.BtopWindow", window) { it })
        assertEquals(null, frontTask(tasks, window, window) { it })
        assertEquals(tasks[0], frontTask(tasks.take(1), window, window) { it })
        assertEquals(tasks[0], frontTask(tasks.take(1), null, window) { it })
        // Files coming forward next to a Linux window isn't that window.
        assertEquals(null, frontTask(tasks.take(1), "dev.omalauncher.FilesActivity", window) { it })
    }
}
