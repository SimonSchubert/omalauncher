package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.ui.EmbeddedBrowser
import dev.omalauncher.ui.EmbeddedTerminal
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetInstanceFrameTest {
    /** A widget's services; a widget with no sources never asks for them. */
    private class Nothing : Services {
        override fun browser(
            url: String?,
            js: Boolean,
            zoom: Double,
        ): EmbeddedBrowser = error("no browser")

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
    }

    private fun widget(
        src: String,
        errors: MutableList<String>,
    ) = WidgetInstance(Compiler.compile(src), Nothing(), { errors += it })

    @Test fun everyFrameMovesByTheTimeSinceTheLastFrame() {
        val errors = ArrayList<String>()
        val w = widget("widget {\n meta { owl: 21 }\n state x = 0\n every frame { x = x + 100 * dt }\n Text { text: x } }", errors)
        assertTrue(w.runsEachFrame)
        repeat(60) { w.frame(1.0 / 60) }
        assertEquals(100.0, (w.read(0) as Num).v, 1e-9)
        // Back after a minute away: one step of at most 0.1 s, not the whole minute.
        w.frame(60.0)
        assertEquals(110.0, (w.read(0) as Num).v, 1e-9)
        assertTrue(errors.isEmpty())
    }

    @Test fun manyFramesASecondAreNotHeldBack() {
        val errors = ArrayList<String>()
        val w = widget("widget {\n meta { owl: 21 }\n state n = 0\n every frame { n = n + 1 }\n Text { text: n } }", errors)
        repeat(120) { w.frame(1.0 / 120) }
        assertEquals(120.0, (w.read(0) as Num).v, 0.0)
        assertTrue(errors.isEmpty())
    }

    @Test fun aWidgetWithoutItRunsNothing() {
        val w = widget("widget {\n meta { owl: 21 }\n state n = 0\n every 1s { n = n + 1 }\n Text { text: n } }", ArrayList())
        assertTrue(!w.runsEachFrame)
        w.frame(0.016)
        assertEquals(0.0, (w.read(0) as Num).v, 0.0)
    }
}
