package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.ui.EmbeddedBrowser
import dev.omalauncher.ui.EmbeddedTerminal
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetInstanceBrowserTest {
    /** A widget's services with only the pages real; a widget with no sources never asks for the rest. */
    private class Pages : Services {
        val made = ArrayList<EmbeddedBrowser>()

        override fun browser(
            url: String?,
            js: Boolean,
            zoom: Double,
        ) = EmbeddedBrowser(null, url, js, zoom).also { made += it }

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

    private fun widget(errors: MutableList<String>): WidgetInstance =
        WidgetInstance(
            Program(
                name = "browser-test",
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
            ),
            Pages(),
            { errors += it },
        )

    @Test fun thePageAskedForLongestAgoGivesWayAndSaysSoOnce() {
        val errors = ArrayList<String>()
        val w = widget(errors)
        val a = Any()
        val b = Any()
        val c = Any()

        val first = w.browser(a, "https://a.example", false, 1.0)
        val second = w.browser(b, "https://b.example", false, 1.0)
        // Asking again for a page it already has is no new page, and makes it the newest.
        assertSame(first, w.browser(a, "https://a.example", false, 1.0))
        assertTrue(errors.isEmpty())

        val third = w.browser(c, "https://c.example", false, 1.0)
        // b waited longest, so its page ended; asking for it again builds a new one instead of handing back a closed one.
        assertNotSame(second, w.browser(b, "https://b.example", false, 1.0))
        assertSame(third, w.browser(c, "https://c.example", false, 1.0))
        assertEquals(1, errors.size)
        assertTrue(errors[0], errors[0].startsWith("a widget holds 2 Browser pages at once"))
    }

    @Test fun aChangedUrlEndsTheOldPageWithoutCountingAsANewOne() {
        val errors = ArrayList<String>()
        val w = widget(errors)
        val a = Any()
        val b = Any()

        val first = w.browser(a, "https://a.example", false, 1.0)
        w.browser(b, "https://b.example", false, 1.0)
        // a's url changed: its own page ends and a new one takes its place, so nothing else gives way.
        assertNotSame(first, w.browser(a, "https://a2.example", false, 1.0))
        assertTrue(errors.isEmpty())
    }

    @Test fun stoppingClosesThemAllSoARerenderBuildsNewOnes() {
        val errors = ArrayList<String>()
        val w = widget(errors)
        val a = Any()

        val page = w.browser(a, "https://a.example", false, 1.0)
        w.stop()
        assertNotSame(page, w.browser(a, "https://a.example", false, 1.0))
        assertTrue(errors.isEmpty())
    }
}
