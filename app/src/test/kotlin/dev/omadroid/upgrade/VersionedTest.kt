package dev.omadroid.upgrade

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VersionedTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundTrips() {
        val text = Versioned.write(1, JSONObject().put("a", 1))
        assertEquals(1, (Versioned.read(text, 1) as JSONObject).getInt("a"))
    }

    @Test fun migratesStepByStep() {
        val text = Versioned.write(1, JSONObject().put("n", 1))
        val seen = ArrayList<Int>()
        val data =
            Versioned.read(text, 3) { from, d ->
                seen += from
                JSONObject().put("n", (d as JSONObject).getInt("n") * 10)
            } as JSONObject
        assertEquals(listOf(1, 2), seen)
        assertEquals(100, data.getInt("n"))
    }

    @Test fun unreadableIsNull() {
        assertNull(Versioned.read(null, 1))
        assertNull(Versioned.read("", 1))
        assertNull(Versioned.read("{not json", 1))
        assertNull(Versioned.read("[1,2]", 1))
    }

    @Test fun readsNewerDocumentsAsIs() {
        val text = Versioned.write(5, JSONObject().put("a", 1))
        assertEquals(1, (Versioned.read(text, 1) as JSONObject).getInt("a"))
    }

    @Test fun badEntriesAreSkippedNotFatal() {
        val array = JSONArray().put(JSONObject().put("x", 1)).put(JSONObject()).put("junk").put(JSONObject().put("x", 3))
        assertEquals(listOf(1, 3), array.mapObjects { it.getInt("x") })
        val obj = JSONObject().put("a", JSONObject().put("x", 1)).put("b", JSONObject()).put("c", 7)
        assertEquals(mapOf("a" to 1), obj.mapEntries { _, o -> o.getInt("x") })
    }

    @Test fun journalRecordsSteps() {
        val file = File(tmp.root, "steps.json")
        StepJournal(file).apply {
            mark("etc-1")
            mark("packages-1")
            completeSetup()
        }
        val reread = StepJournal(file)
        assertTrue("etc-1" in reread)
        assertTrue(reread.setupComplete)
        reread.reset()
        assertFalse("etc-1" in StepJournal(file))
        assertFalse(StepJournal(file).setupComplete)
    }

    @Test fun unwrappedDocumentsAreVersionZero() {
        val legacy = JSONArray().put(JSONObject().put("key", "app:x")).toString()
        val data = Versioned.read(legacy, 1) { from, d ->
            assertEquals(0, from)
            d
        } as JSONArray
        assertEquals("app:x", data.getJSONObject(0).getString("key"))
    }
}
