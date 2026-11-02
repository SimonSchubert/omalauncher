package dev.omalauncher.ui

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.FALSE
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.TRUE
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A widget asking for the screen lock, and the holds it leaves behind. */
class ScreenLockTest {
    /** A widget's services with nothing behind them; one that only runs actions never asks for the rest. */
    private class NoServices : Services {
        override suspend fun cmd(
            command: String,
            timeoutSeconds: Int,
        ): ShellResult = error("no shell")

        override suspend fun http(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Int,
            body: ByteArray?,
        ): ShellResult = error("no http")

        override fun run(
            command: String,
            done: (ShellResult?) -> Unit,
        ) = error("no run")

        override suspend fun stream(
            command: String,
            onLine: (String) -> Unit,
        ) = error("no stream")

        override fun android(name: String): Value = error("no android")

        // lock() is the widget's own, answered before this.
        override fun act(
            name: String,
            args: List<Value>,
            done: () -> Unit,
        ) = error("no act")

        override fun guestFile(path: String): File = error("no guest")

        override fun fileUpdates(path: String): Flow<String> = error("no files")

        override fun terminal(
            command: String?,
            fontSize: Double?,
        ): EmbeddedTerminal = error("no terminal")

        override fun browser(
            url: String?,
            js: Boolean,
            zoom: Double,
        ): EmbeddedBrowser = error("no browser")
    }

    private fun program() =
        Program(
            name = "lock-test",
            meta = emptyMap(),
            level = Compiler.LEVEL,
            globals = emptyList(),
            triggers = emptyList(),
            commands = emptyList(),
            requires = emptyMap(),
            needsLinux = false,
            programs = emptySet(),
            animated = false,
            nodeCount = 0,
        )

    /** A widget wired as the engine wires it: the hold is held by the instance that asked for it. */
    private fun widget(
        holds: HomeLock,
        errors: MutableList<String>,
    ): WidgetInstance {
        lateinit var inst: WidgetInstance
        inst = WidgetInstance(program(), NoServices(), { errors += it }, onLock = { on -> holds.lock(inst, on) })
        return inst
    }

    private fun WidgetInstance.lock(on: Value) = act("lock", listOf(on))

    @Test fun twoWidgetsHoldItAndOneLettingGoKeepsItHeld() {
        val holds = HomeLock()
        val errors = ArrayList<String>()
        val a = widget(holds, errors)
        val b = widget(holds, errors)
        assertFalse(holds.locked)

        a.lock(TRUE)
        b.lock(TRUE)
        a.lock(FALSE)
        assertTrue(holds.locked)

        b.lock(FALSE)
        assertFalse(holds.locked)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun askingTwiceIsAskingOnce() {
        val holds = HomeLock()
        val w = widget(holds, ArrayList())
        w.lock(TRUE)
        w.lock(TRUE)
        w.lock(FALSE)
        assertFalse(holds.locked)
    }

    @Test fun theArgumentIsCoercedLikeAnyOtherBoolean() {
        val holds = HomeLock()
        val w = widget(holds, ArrayList())
        w.lock(Str("editing"))
        assertTrue(holds.locked)
        w.lock(Str(""))
        assertFalse(holds.locked)
    }

    @Test fun aStoppedWidgetGivesBackOnlyItsOwnHold() {
        val holds = HomeLock()
        val errors = ArrayList<String>()
        val a = widget(holds, errors)
        val b = widget(holds, errors)
        a.lock(TRUE)
        b.lock(TRUE)

        a.stop()
        assertTrue(holds.locked)

        b.stop()
        assertFalse(holds.locked)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test fun theCuesButtonGivesBackEveryHold() {
        val holds = HomeLock()
        val a = widget(holds, ArrayList())
        val b = widget(holds, ArrayList())
        a.lock(TRUE)
        b.lock(TRUE)

        holds.unlockAll()
        assertFalse(holds.locked)
        // A widget that asks again after the cue is honoured as before.
        a.lock(TRUE)
        assertTrue(holds.locked)
    }

    @Test fun aPreviewSaysItHasNoHomeScreenToLock() {
        val errors = ArrayList<String>()
        // No onLock: a preview has no home to hold.
        val w = WidgetInstance(program(), NoServices(), { errors += it })
        w.lock(TRUE)
        assertEquals(listOf("a preview has no home screen to lock"), errors)
    }
}
