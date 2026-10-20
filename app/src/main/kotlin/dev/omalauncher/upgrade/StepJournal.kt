package dev.omalauncher.upgrade

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Which rootfs steps have run on this install, by id, and whether the first
 * setup finished. A step whose id isn't recorded runs (again): changing a
 * step's id is how an APK update reaches existing rootfs.
 */
class StepJournal(
    private val file: File,
) {
    private val done = HashSet<String>()

    var setupComplete = false
        private set

    init {
        (Versioned.read(file.takeIf { it.exists() }?.readText(), VERSION) as? JSONObject)?.let { data ->
            setupComplete = data.optBoolean("setup")
            data.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) done += a.getString(i) }
        }
    }

    operator fun contains(id: String) = id in done

    fun mark(id: String) {
        if (done.add(id)) save()
    }

    fun completeSetup() {
        setupComplete = true
        save()
    }

    /** A fresh rootfs: nothing has run on it. */
    fun reset() {
        done.clear()
        setupComplete = false
        save()
    }

    private fun save() =
        writeAtomically(
            file,
            Versioned.write(VERSION, JSONObject().put("setup", setupComplete).put("done", JSONArray(done.sorted()))).toByteArray(),
        )

    private companion object {
        const val VERSION = 1
    }
}
