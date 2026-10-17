package dev.omalauncher.linux

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.upgrade.Versioned
import dev.omalauncher.upgrade.mapObjects
import dev.omalauncher.upgrade.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "updates"

/** Checked at most this often on its own; opening settings or "check" asks right away. */
private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

/** After a failed check (offline), the next automatic one waits this long. */
private const val RETRY_MS = 15 * 60 * 1000L

/** A tool mise can upgrade: a coding agent (by its name) or something else it installed, like node. */
data class ToolUpdate(
    val name: String,
    val current: String,
    val latest: String,
)

/** What the last check found. [error] is set when it couldn't reach the mirrors. */
data class PendingUpdates(
    val packages: List<String>,
    val tools: List<ToolUpdate>,
    val checkedAt: Long,
    val error: String? = null,
) {
    val count get() = packages.size + tools.size
}

/**
 * Updates for the guest: pacman packages and the tools mise installed (the
 * coding agents). Checking never touches the real package databases or
 * takes pacman's lock; updating is one job the user starts, never automatic:
 * Arch upgrades are big and now and then need attention.
 */
class SystemUpdates(
    private val proot: Proot,
    private val pacman: Pacman,
    private val agents: AgentStore,
) {
    private val file = File(proot.filesDir, "updates.json")

    var pending by mutableStateOf<PendingUpdates?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var job by mutableStateOf<JobState>(JobState.Idle)
        private set

    /** Reads the last result, so the settings dot shows right after a restart. */
    fun load() {
        val data = Versioned.read(file.takeIf { it.exists() }?.readText(), VERSION) as? JSONObject ?: return
        pending =
            PendingUpdates(
                packages = data.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                tools =
                    data.optJSONArray("tools")?.mapObjects { ToolUpdate(it.getString("name"), it.getString("current"), it.getString("latest")) }.orEmpty(),
                checkedAt = data.optLong("checkedAt"),
            )
    }

    /** Linux was removed: nothing is pending any more. */
    fun forget() {
        file.delete()
        pending = null
        job = JobState.Idle
        lastAttempt = 0L
    }

    /** When a check last ran, successful or not; a failed one isn't repeated right away (offline). */
    private var lastAttempt = 0L

    /** Checks unless the last check is recent; [force] checks anyway. */
    suspend fun check(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (checking || job is JobState.Working) return
        if (!force && (now - (pending?.checkedAt ?: 0L) < CHECK_INTERVAL_MS || now - lastAttempt < RETRY_MS)) return
        lastAttempt = now
        checking = true
        try {
            val result = withContext(Dispatchers.IO) { runCheck() }
            pending = result
            if (result.error == null) withContext(Dispatchers.IO) { save(result) }
        } finally {
            checking = false
        }
    }

    private fun runCheck(): PendingUpdates {
        val out = StringBuilder()
        val code = runCatching { proot.run(CHECK_SCRIPT) { out.appendLine(it) } }.getOrElse { -1 }
        val text = out.toString()
        val now = System.currentTimeMillis()
        if (code != 0 || "#pacman-error" in text) {
            Log.w(TAG, "check failed ($code): ${text.takeLast(300)}")
            return PendingUpdates(pending?.packages.orEmpty(), pending?.tools.orEmpty(), pending?.checkedAt ?: 0L, error = "couldn't reach the mirrors")
        }
        val packages =
            text
                .substringAfter("#pacman\n")
                .substringBefore("#mise")
                .lines()
                .map { it.trim() }
                // "name old -> new"
                .filter { it.contains(" -> ") }
                .map { it.substringBefore(' ') }
        return PendingUpdates(packages, parseMise(text.substringAfter("#mise\n", "")), now)
    }

    /** `mise outdated --json`: `{tool: {current, latest, ...}}`, named after the agent it is. */
    private fun parseMise(json: String): List<ToolUpdate> {
        val root = runCatching { JSONObject(json.trim().ifEmpty { "{}" }) }.getOrElse { return emptyList() }
        return root.keys().asSequence().mapNotNull { key ->
            val o = root.optJSONObject(key) ?: return@mapNotNull null
            val latest = o.optString("latest").ifEmpty { return@mapNotNull null }
            val name = AGENTS.firstOrNull { it.pkg == key || it.pkg == o.optString("name") }?.name ?: key
            ToolUpdate(name, o.optString("current").ifEmpty { "?" }, latest)
        }.toList()
    }

    private fun save(p: PendingUpdates) {
        val data =
            JSONObject()
                .put("packages", JSONArray(p.packages))
                .put("tools", JSONArray(p.tools.map { JSONObject().put("name", it.name).put("current", it.current).put("latest", it.latest) }))
                .put("checkedAt", p.checkedAt)
        runCatching { writeAtomically(file, Versioned.write(VERSION, data).toByteArray()) }
    }

    /** Upgrades everything: a full pacman sync, then mise's tools. Returns whether it worked. */
    suspend fun updateAll(): Boolean {
        if (job is JobState.Working) return false
        val (ok, _) = proot.trackedJob("updating", UPDATE_SCRIPT, pacman.lock) { job = it }
        // Agents may have changed, and what's left to update is worth knowing either way.
        withContext(Dispatchers.IO) { agents.load() }
        check(force = true)
        return ok
    }

    private companion object {
        const val VERSION = 1

        /**
         * pacman against a private copy of the sync databases in /tmp (what checkupdates does,
         * without fakeroot): the real ones stay as they are, so this can't cause a partial
         * upgrade and never waits on pacman's lock. Then mise, if it's installed.
         */
        val CHECK_SCRIPT =
            """
            t=/tmp/omalauncher-updates
            mkdir -p "${'$'}t"
            ln -sfn /var/lib/pacman/local "${'$'}t/local"
            if pacman -Sy --dbpath "${'$'}t" --logfile /dev/null >/dev/null 2>&1; then
              echo '#pacman'
              pacman -Qu --dbpath "${'$'}t" 2>/dev/null
            else
              echo '#pacman-error'
            fi
            echo '#mise'
            if command -v mise >/dev/null; then mise outdated --json 2>/dev/null; fi
            true
            """.trimIndent()

        /** Databases refreshed (-Syy) even if pacman thinks they're current: mirrors lag each other. */
        val UPDATE_SCRIPT =
            "pacman -Syyu --noconfirm && $CLEAN_PACMAN_CACHE && " +
                "if command -v mise >/dev/null; then $MISE_SELF_UPDATE && $MISE_NODE_FOR_AGENTS && mise upgrade && mise prune -y && mise cache clear; fi"
    }
}
