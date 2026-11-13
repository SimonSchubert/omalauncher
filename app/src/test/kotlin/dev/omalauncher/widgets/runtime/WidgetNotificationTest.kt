package dev.omalauncher.widgets.runtime

import dev.omalauncher.linux.ShellResult
import dev.omalauncher.ui.EmbeddedBrowser
import dev.omalauncher.ui.EmbeddedTerminal
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.FALSE
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WidgetNotificationTest {
    private fun of(vararg fields: Pair<String, Value>) = WidgetNotification.of(Obj(mapOf(*fields)), "timer")

    private fun error(vararg fields: Pair<String, Value>) = of(*fields).let { assertNull(it.notification); it.error.orEmpty() }

    @Test fun nullOrFalseIsNone() {
        for (v in listOf(Null, FALSE, Str(""))) assertEquals(WidgetNotification.Parsed(null), WidgetNotification.of(v, "timer"))
        assertTrue(WidgetNotification.of(Str("tea"), "timer").error!!.contains("it's a map"))
    }

    @Test fun aMapIsOne() {
        val n = of("text" to Str("ends 14:35"), "until" to Num(1_800_000_000.0), "buttons" to Lst(listOf(Str("pause"), Obj(mapOf("text" to Str("+1m"), "value" to Obj(mapOf("do" to Str("add")))))))).notification!!
        // Titled with the widget's name unless it says otherwise.
        assertEquals("timer", n.title)
        assertEquals("ends 14:35", n.text)
        assertEquals(1_800_000_000.0, n.until!!, 0.0)
        assertNull(n.since)
        // A text button's value is its text.
        assertEquals(listOf(WidgetNotification.Button("pause", Str("pause")), WidgetNotification.Button("+1m", Obj(mapOf("do" to Str("add"))))), n.buttons)
        assertEquals("tea", of("title" to Str("tea"), "since" to Num(1.0)).notification!!.title)
        // A time of 0 or null is none, as at's is.
        val none = of("until" to Num(0.0), "since" to Null).notification!!
        assertNull(none.until)
        assertNull(none.since)
        // The same map is the same notification, so it isn't posted again.
        assertEquals(of("title" to Str("tea")), of("title" to Str("tea")))
    }

    @Test fun whatsWrongIsSaid() {
        assertTrue(error("countdown" to Num(1.0)).contains("it has no 'countdown'; it takes title, text, until, since, buttons"))
        assertTrue(error("until" to Str("soon")).contains("until is a Unix time"))
        assertTrue(error("since" to Num(-5.0)).contains("since is a Unix time"))
        assertTrue(error("until" to Num(2.0), "since" to Num(1.0)).contains("give one of them"))
        assertTrue(error("buttons" to Str("stop")).contains("buttons is a list"))
        assertTrue(error("buttons" to Lst(List(4) { Str("b$it") })).contains("up to 3 buttons, not 4"))
        assertTrue(error("buttons" to Lst(listOf(Obj(mapOf("value" to Num(1.0)))))).contains("a button needs its text"))
        assertTrue(error("buttons" to Lst(listOf(Obj(mapOf("text" to Str("a"), "icon" to Str("x")))))).contains("a button has no 'icon'"))
        assertTrue(error("buttons" to Lst(listOf(Null))).contains("a button needs its text"))
    }

    /** A widget's services; a press asks for none. */
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

    @Test fun aPressRunsOnNotificationWithTheTimeNow() {
        val errors = ArrayList<String>()
        val src =
            "widget {\n meta { owl: 23 }\n source now = clock every 1s\n state got = null\n state at = 0\n" +
                " notification: {title: \"t\", buttons: [\"stop\"]}\n onNotification: { got = value; at = now }\n Text { text: got } }"
        val w = WidgetInstance(Compiler.compile(src), Nothing(), { errors += it })
        // Off screen the clock hasn't run: the press sets it before onNotification reads it.
        assertEquals(Null, w.read(0))
        val before = Math.floor(System.currentTimeMillis() / 1000.0)
        w.pressNotification(Obj(mapOf("do" to Str("stop"))))
        assertEquals(Obj(mapOf("do" to Str("stop"))), w.read(1))
        val at = (w.read(2) as Num).v
        assertTrue("$at", at >= before && at <= before + 2)
        assertEquals(at, (w.read(0) as Num).v, 0.0)
        assertTrue(errors.toString(), errors.isEmpty())
    }
}
