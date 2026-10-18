package dev.omalauncher.linux

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import dev.omalauncher.upgrade.Managed
import dev.omalauncher.upgrade.Policy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

data class Package(
    val name: String,
    val version: String,
    val description: String,
    /** Sync repo (core, extra, alarm, ...) or [AUR]; null for the installed list. */
    val repo: String?,
    val installed: Boolean,
)

/** Installs and removes guest packages from the settings screen, one pacman at a time. */
class Pacman(
    private val context: Context,
    private val proot: Proot,
) {
    /** pacman holds a lock on its database; everything that runs it takes this first. */
    val lock = Mutex()

    /** Per-package install/remove progress. */
    val states = mutableStateMapOf<String, JobState>()

    /** Bumped after installs and removals so lists reload. */
    val revision = mutableIntStateOf(0)

    /**
     * Not offered for removal: what the launcher relies on (foot, fonts, Omarchy's apps), plus
     * `base` (`-Rns base` would take pacman and bash with it) and the keyring installs need.
     * Everything else is either safe to remove or required by something, which pacman refuses.
     */
    val core = CORE_PACKAGES + setOf("base", "archlinuxarm-keyring")

    private val localDb = File(proot.rootfs, "var/lib/pacman/local")

    /**
     * The sync repos and the AUR together, best name matches first. AUR packages
     * with the same name as a repo package are left out: pacman would win anyway.
     */
    suspend fun search(query: String): List<Package> =
        coroutineScope {
            val q = query.trim()
            val aur = async { Aur.search(q) }
            val repos = searchRepos(q)
            val names = repos.mapTo(HashSet()) { it.name }
            val local = localNames()
            val fromAur = aur.await().filter { it.name !in names }.map { it.copy(installed = it.name in local) }
            val lower = q.lowercase()
            (repos + fromAur)
                .sortedWith(
                    compareBy<Package>(
                        { it.name != lower },
                        { !it.name.startsWith(lower) },
                        { lower !in it.name },
                        { it.repo == AUR },
                        { it.name },
                    ),
                ).take(MAX_RESULTS)
        }

    /** `pacman -Ss` against the local sync databases. */
    private suspend fun searchRepos(q: String): List<Package> {
        if (q.isEmpty()) return emptyList()
        val out = output("pacman -Ss -- ${shellQuote(q)}")
        val results = mutableListOf<Package>()
        val lines = out.lines()
        var i = 0
        while (i < lines.size) {
            val header = lines[i]
            // "extra/btop 1.3.2-1 (group) [installed]" followed by an indented description.
            if (header.isEmpty() || header[0].isWhitespace() || '/' !in header.substringBefore(' ')) {
                i++
                continue
            }
            val parts = header.split(' ')
            val description = lines.getOrNull(i + 1)?.takeIf { it.startsWith(" ") }?.trim().orEmpty()
            results +=
                Package(
                    name = parts[0].substringAfter('/'),
                    version = parts.getOrElse(1) { "" },
                    description = description,
                    repo = parts[0].substringBefore('/'),
                    installed = "[installed" in header,
                )
            i += 2
        }
        return results
    }

    /**
     * Every installed package's name, from the local database's directory names:
     * `name-pkgver-pkgrel`, where neither version part may contain a dash.
     */
    private suspend fun localNames(): Set<String> =
        withContext(Dispatchers.IO) {
            localDb.list().orEmpty().mapTo(HashSet()) { it.substringBeforeLast('-').substringBeforeLast('-') }
        }

    /**
     * Explicitly installed packages (what `pacman -Qe` lists), read straight from the
     * local database: much faster than starting proot.
     */
    suspend fun installed(): List<Package> =
        withContext(Dispatchers.IO) {
            localDb
                .listFiles()
                .orEmpty()
                .mapNotNull { dir ->
                    val fields = runCatching { parseDesc(File(dir, "desc").readLines()) }.getOrNull() ?: return@mapNotNull null
                    // %REASON% 1 = installed as a dependency; explicit packages have no entry.
                    if (fields["REASON"] == "1") return@mapNotNull null
                    val name = fields["NAME"] ?: return@mapNotNull null
                    Package(name, fields["VERSION"].orEmpty(), fields["DESC"].orEmpty(), null, installed = true)
                }.sortedBy { it.name }
        }

    /** A local db `desc` file: `%KEY%` lines, each followed by its value (first line kept). */
    private fun parseDesc(lines: List<String>): Map<String, String> {
        val fields = HashMap<String, String>()
        for (i in lines.indices) {
            val key = lines[i]
            if (key.length > 2 && key.startsWith('%') && key.endsWith('%')) fields[key.trim('%')] = lines.getOrNull(i + 1).orEmpty()
        }
        return fields
    }

    /** A full sync plus the package, like the bootstrap: never a partial upgrade. */
    suspend fun install(pkg: Package) =
        if (pkg.repo == AUR) {
            run(pkg.name, "building", "/usr/local/lib/aur/install.sh ${shellQuote(pkg.name)}")
        } else {
            run(pkg.name, "installing", pacmanInstall(listOf(pkg.name)))
        }

    /**
     * The AUR build scripts (assets/aur) in the guest's /usr/local/lib/aur, the fakeroot shim in its bin,
     * and the yay wrapper that builds as a user even when called as root (by App Finder, say).
     */
    fun managed(): List<Managed> {
        val dir = File(proot.rootfs, "usr/local/lib/aur")
        return listOf(
            "install.sh" to File(dir, "install.sh"),
            "fakeroot" to File(dir, "bin/fakeroot"),
            "yay" to File(proot.rootfs, "usr/local/bin/yay"),
        ).map { (asset, target) ->
            Managed(target, Policy.Owned, executable = true) { context.assets.open("aur/$asset").use { it.readBytes() } }
        }
    }

    /** Removes the package and the dependencies nothing else needs. */
    suspend fun remove(name: String) = run(name, "removing", "pacman -Rns --noconfirm ${shellQuote(name)}")

    /** Kept between searches: starting proot costs more than the search. */
    private val shell = GuestShell(proot)

    private suspend fun output(script: String): String =
        runCatching { shell.run(script, timeoutSeconds = 30).out }.getOrElse {
            Log.w(TAG, "pacman query failed", it)
            ""
        }

    private suspend fun run(
        name: String,
        action: String,
        script: String,
    ): Boolean {
        val (ok, _) = proot.trackedJob(action, script, lock) { if (it == JobState.Idle) states.remove(name) else states[name] = it }
        Log.i(TAG, "$action $name: ${if (ok) "ok" else "failed"}")
        revision.intValue++
        return ok
    }

    companion object {
        private const val TAG = "pacman"

        /** Short AUR queries match thousands of packages. */
        private const val MAX_RESULTS = 100
    }
}
