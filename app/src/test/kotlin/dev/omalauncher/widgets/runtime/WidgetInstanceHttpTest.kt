package dev.omalauncher.widgets.runtime

import androidx.compose.runtime.snapshots.Snapshot
import dev.omalauncher.linux.ShellResult
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/** An answer that comes late doesn't land in a source or a state after what it was asked for changed (#327). */
class WidgetInstanceHttpTest {
    private val calls = Collections.synchronizedList(ArrayList<String>())

    private val base =
        Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, _, _ -> null } as Services

    /** Answers with what it was asked; a URL or an Authorization header with "slow" in it takes 600 ms. */
    private val services =
        object : Services by base {
            override suspend fun http(
                url: String,
                headers: Map<String, String>,
                timeoutSeconds: Int,
                body: ByteArray?,
                method: String,
                pin: String?,
            ): ShellResult {
                val auth = headers["Authorization"].orEmpty()
                calls += "$url [$auth]"
                if ("slow" in url || "slow" in auth) delay(600)
                return ShellResult("$url [$auth]", "", 200)
            }
        }

    /** Starts [src], whose value under watch is `s`, and gives [body] the widget, its slots by name and every value `s` had. */
    private fun run(
        src: String,
        body: suspend (WidgetInstance, (String) -> Int, List<Value>) -> Unit,
    ) = runBlocking {
        val program = Compiler.compile("widget \"t\" {\n meta { owl: 12 }\n$src\n Text { text: \"x\" } }")
        val w = WidgetInstance(program, services, { })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val slot = { name: String -> program.globals.indexOfFirst { it.name == name } }
        val seen = Collections.synchronizedList(ArrayList<Value>())
        // What Compose does on a phone: state written outside a composition reaches the sources' snapshotFlows.
        scope.launch {
            while (isActive) {
                Snapshot.sendApplyNotifications()
                delay(5)
            }
        }
        scope.launch {
            while (isActive) {
                val v = w.read(slot("s"))
                if (seen.lastOrNull() != v) seen += v
                delay(2)
            }
        }
        w.start(scope) { true }
        try {
            body(w, slot, seen)
        } finally {
            w.stop()
            scope.cancel()
        }
    }

    @Test fun lateAnswerForAnOldUrlIsDropped() =
        run(" state acct = \"slow-a\"\n source s = http(\"https://x.test/${'$'}{acct}\") every 30m") { w, slot, seen ->
            delay(100)
            w.write(slot("acct"), Str("b"))
            delay(2000)
            assertEquals(listOf("https://x.test/slow-a []", "https://x.test/b []"), calls.toList())
            assertEquals(listOf(Null, Str("https://x.test/b []")), seen.toList())
        }

    @Test fun changedHeadersFetchAgain() =
        run(" state tok = \"a\"\n source s = http(\"https://x.test/items\", {Authorization: tok}) every 30m") { w, slot, seen ->
            delay(300)
            w.write(slot("tok"), Str("b"))
            delay(2000)
            assertEquals(listOf("https://x.test/items [a]", "https://x.test/items [b]"), calls.toList())
            assertEquals(Str("https://x.test/items [b]"), seen.last())
        }

    @Test fun lateAnswerForOldHeadersIsDropped() =
        run(" state tok = \"slow-a\"\n source s = http(\"https://x.test/items\", {Authorization: tok}) every 30m") { w, slot, seen ->
            delay(100)
            w.write(slot("tok"), Str("b"))
            delay(2000)
            assertEquals(listOf(Null, Str("https://x.test/items [b]")), seen.toList())
        }

    @Test fun lastPostSentWins() =
        run(" state s = null") { w, slot, seen ->
            w.post("https://x.test/slow-first", Null, emptyMap(), slot("s"), "POST", null)
            delay(50)
            w.post("https://x.test/second", Null, emptyMap(), slot("s"), "POST", null)
            delay(1200)
            assertEquals(2, calls.size)
            assertTrue(seen.toString(), seen.none { "slow-first" in it.toString() })
            assertTrue(seen.last().toString(), "second" in seen.last().toString())
        }
}
