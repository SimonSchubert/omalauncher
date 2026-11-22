package dev.omalauncher.widgets.runtime

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

/**
 * Persisted values as a linked computer syncs them (ComputerLink): the newest save wins, so a widget mustn't save
 * values nobody changed (its starting ones, with the time of its first run), and a running one takes in values written
 * to its file from elsewhere.
 */
class PersistedSyncTest {
    @get:Rule val dir = TemporaryFolder()

    private val program = Compiler.compile("widget \"notes\" {\n meta { owl: 18 }\n state note = \"\" persist\n Text { text: note } }")

    private val services =
        Proxy.newProxyInstance(Services::class.java.classLoader, arrayOf(Services::class.java)) { _, _, _ -> null } as Services

    private val saved get() = File(dir.root, "notes.owl.json")

    private fun instance() = WidgetInstance(program, services, { }, saved, File(dir.root, "notes.owl.secret"))

    private fun WidgetInstance.note() = read(program.globals.indexOfFirst { it.name == "note" })

    /** A widget that just arrived writes nothing until its values change: a computer's values for it then still win. */
    @Test fun startingValuesArentSaved() {
        instance().stop()
        assertFalse(saved.exists())
    }

    @Test fun changedValuesAre() {
        val a = instance()
        a.setState("note", Str("milk"))
        a.stop()
        assertTrue(saved.readText().contains("milk"))
    }

    /** Values written to its file from elsewhere reach a running widget, and aren't written back as its own save. */
    @Test fun aRunningWidgetTakesInValuesWrittenElsewhere() {
        val a = instance()
        a.setState("note", Str("milk"))
        a.stop()
        val running = instance()
        saved.writeText("{\"v\":1,\"savedAt\":2000000000,\"values\":{\"note\":\"eggs\"}}")
        running.reloadPersisted()
        assertEquals(Str("eggs"), running.note())
        running.stop()
        assertTrue(saved.readText().contains("2000000000"))
    }
}
