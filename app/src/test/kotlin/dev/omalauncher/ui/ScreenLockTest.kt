package dev.omalauncher.ui

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.runtime.Services
import dev.omalauncher.widgets.runtime.WidgetInstance
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A widget's `lock:`, and the holds the home screen keeps from it. */
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
            method: String,
            pin: String?,
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

    /** A widget whose `lock:` says [lock], with a `playing` state to hold it by. */
    private fun widget(lock: String) =
        WidgetInstance(
            Compiler.compile("widget {\n meta { owl: 18 }\n state playing = false\n lock: $lock\n Button { text: \"x\"; onTap: playing = !playing }\n}"),
            NoServices(),
            { error(it) },
        )

    /** Taps the widget's one button, which turns `playing` over. */
    private fun WidgetInstance.toggle() = (program.root.single() as dev.omalauncher.widgets.lang.WNode.Element).handlers["onTap"]!!.run(root, emptyList())

    @Test fun aWidgetHoldsItWhileItsLockSaysSo() {
        val w = widget("playing")
        assertFalse(w.holdsHome())
        w.toggle()
        assertTrue(w.holdsHome())
        w.toggle()
        assertFalse(w.holdsHome())
        // An expression like any other: a game holds the screen while it runs and not while it's paused.
        val v = widget("playing && 1 > 0")
        v.toggle()
        assertTrue(v.holdsHome())
    }

    @Test fun aWidgetWithoutALockNeverHoldsIt() {
        val w = WidgetInstance(Compiler.compile("widget {\n Text { text: \"x\" }\n}"), NoServices(), { error(it) })
        assertFalse(w.holdsHome())
    }

    @Test fun twoWidgetsHoldItAndOneLettingGoKeepsItHeld() {
        val holds = HomeLock()
        val a = Any()
        val b = Any()
        val both = setOf(a, b)
        holds.widgets(both, both)
        assertTrue(holds.locked)
        holds.widgets(both, setOf(b))
        assertTrue(holds.locked)
        holds.widgets(both, emptySet())
        assertFalse(holds.locked)
    }

    @Test fun backOrTheCueLetsGoUntilTheLockTurnsFalse() {
        val holds = HomeLock()
        val game = Any()
        val showing = setOf(game)
        holds.widgets(showing, showing)
        holds.unlockAll()
        assertFalse(holds.locked)
        // Its lock still says true: the person let go of it, so it stays let go.
        holds.widgets(showing, showing)
        assertFalse(holds.locked)
        // Turned false and back, as the next game starts: it holds again.
        holds.widgets(showing, emptySet())
        holds.widgets(showing, showing)
        assertTrue(holds.locked)
    }

    @Test fun aPageTurnedAwayFromAndBackDoesntLockAgain() {
        val holds = HomeLock()
        val game = Any()
        val showing = setOf(game)
        holds.widgets(showing, showing)
        holds.unlockAll()
        // Out of sight, its lock isn't seen false: still let go when its page shows again, while the game still runs.
        holds.widgets(emptySet(), emptySet())
        holds.widgets(showing, showing)
        assertFalse(holds.locked)
    }
}
