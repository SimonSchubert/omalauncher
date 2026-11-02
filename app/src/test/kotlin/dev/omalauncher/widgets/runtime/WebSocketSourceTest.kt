package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.FALSE
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.TRUE
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class WebSocketSourceTest {
    @get:Rule val dir = TemporaryFolder()

    private val program =
        Compiler.compile(
            "widget \"ws\" {\n meta { owl: 17 }\n source feed = ws(\"wss://pc.test/feed\") status\n" +
                " when feed.open { send(feed, \"hello\") }\n Text { text: feed.out } }",
        )

    private val services = Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, _, _ -> null } as Services

    private fun instance() = WidgetInstance(program, services, {}, File(dir.root, "ws.owl.json"), File(dir.root, "ws.owl.secret"))

    /** A reload connects anew: the source starts closed, with its last message, so `when feed.open` greets again. */
    @Test fun reloadStartsClosed() {
        val running = instance()
        running.write(0, Obj(mapOf("out" to Str("hi"), "open" to TRUE, "err" to Str(""))))
        val reloaded = instance().also { it.adopt(running) }
        assertEquals(Obj(mapOf("out" to Str("hi"), "open" to FALSE, "err" to Str(""))), reloaded.read(0))
    }
}
