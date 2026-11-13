package dev.omalauncher.ui

import dev.omalauncher.linux.LinuxState
import dev.omalauncher.linux.Stage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxPhaseTest {
    @Test fun installLinesDontChangeThePhase() {
        val states =
            flowOf(
                LinuxState.Checking,
                LinuxState.NotInstalled,
                LinuxState.Installing(Stage.Download, "a", 0.1f, listOf("a")),
                LinuxState.Installing(Stage.Download, "b", 0.5f, listOf("a", "b")),
                LinuxState.Installing(Stage.Extract, "c", null, listOf("a", "b", "c")),
                LinuxState.Installed,
            )
        val phases = runBlocking { states.phases().toList() }
        assertEquals(listOf(LinuxPhase.Checking, LinuxPhase.Absent, LinuxPhase.Installing, LinuxPhase.Installed), phases)
    }

    @Test fun failedRemovingAndNotInstalledAreAllAbsent() {
        val states = flowOf(LinuxState.Failed(Stage.Packages, "x", emptyList()), LinuxState.Removing, LinuxState.NotInstalled)
        assertEquals(listOf(LinuxPhase.Absent), runBlocking { states.phases().toList() })
    }
}
