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
        Versioned.readFile(file, VERSION)?.let { data ->
            setupComplete = data.optBoolean("setup")
            data.optJSONArray("done")?.let { done += it.strings() }
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

    private fun save() = Versioned.writeFile(file, VERSION, JSONObject().put("setup", setupComplete).put("done", JSONArray(done.sorted())))

    private companion object {
        const val VERSION = 1
    }
}
