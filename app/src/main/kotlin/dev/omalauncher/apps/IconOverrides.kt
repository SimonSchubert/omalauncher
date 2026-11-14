package dev.omalauncher.apps

import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.omalauncher.theme.Toml
import java.io.File

/** "package/class", as appfilter.xml and [IconOverrides] name a launcher activity. */
fun iconComponentKey(component: ComponentName): String = "${component.packageName}/${component.className}"

/**
 * Per-pack drawable overrides for components the appfilter missed or the user remapped. Stored as
 * `[[override]]` tables so component names (with `/`) fit the launcher's TOML subset:
 *
 * ```
 * [[override]]
 * pack = "com.example.iconpack"
 * component = "com.app/com.app.Main"
 * drawable = "some_drawable"
 * ```
 *
 * Agent suggestions use the same shape in [SUGGESTIONS] until accepted here. [revision] changes whenever
 * the overrides do, so a screen that reads it redraws its lists on the same frame.
 */
class IconOverrides(
    dir: File,
) {
    private val file = File(dir, FILE)
    private val suggestionsFile = File(dir, SUGGESTIONS)

    /** pack → (component → drawable). */
    private var table = LinkedHashMap<String, LinkedHashMap<String, String>>()

    /** Bumps on every change to [table]. Compose reads this; the map itself is not snapshot state. */
    var revision by mutableIntStateOf(0)
        private set

    init {
        load()
    }

    fun get(
        pack: String,
        component: String,
    ): String? = table[pack]?.get(component)

    fun forPack(pack: String): Map<String, String> = table[pack]?.toMap() ?: emptyMap()

    /** Sets [drawable] for [component] in [pack]. A null or blank [drawable] removes the row. */
    fun set(
        pack: String,
        component: String,
        drawable: String?,
    ) {
        val section = table.getOrPut(pack) { linkedMapOf() }
        val next = drawable?.trim()?.takeIf { it.isNotEmpty() }
        val changed = if (next == null) section.remove(component) != null else section.put(component, next) != next
        if (section.isEmpty()) table.remove(pack)
        if (!changed) return
        save()
        revision++
    }

    fun putAll(
        pack: String,
        mappings: Map<String, String>,
    ) {
        if (mappings.isEmpty()) return
        val section = table.getOrPut(pack) { linkedMapOf() }
        var changed = false
        for ((component, drawable) in mappings) {
            val name = drawable.trim()
            if (name.isEmpty()) continue
            if (section.put(component, name) != name) changed = true
        }
        if (section.isEmpty()) table.remove(pack)
        if (!changed) return
        save()
        revision++
    }

    fun readSuggestions(): Map<String, String> {
        val text = runCatching { suggestionsFile.readText() }.getOrNull() ?: return emptyMap()
        return runCatching { parseSuggestionMap(Toml.parse(text)) }.getOrDefault(emptyMap())
    }

    /** Drops one accepted row from [SUGGESTIONS], and the file when none remain. */
    fun dropSuggestion(component: String) {
        val all = readSuggestions()
        if (component !in all) return
        writeSuggestions(all.filterKeys { it != component })
    }

    fun clearSuggestions() {
        suggestionsFile.delete()
    }

    fun suggestionsPath(): String = suggestionsFile.absolutePath

    /** Reads [FILE] again. An external edit shows up as a new [revision]. */
    fun reload() {
        load()
        revision++
    }

    private fun load() {
        table = LinkedHashMap()
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val parsed = runCatching { Toml.parse(text) }.getOrNull() ?: return
        for (row in rows(parsed["override"])) {
            val pack = row["pack"] as? String ?: continue
            val component = row["component"] as? String ?: continue
            val drawable = (row["drawable"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            table.getOrPut(pack) { linkedMapOf() }[component] = drawable
        }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val sb = StringBuilder("# Icon pack overrides: pack + component → drawable name.\n")
        for ((pack, section) in table.toSortedMap()) {
            for ((component, drawable) in section.toSortedMap()) {
                sb.append("[[override]]\n")
                sb.append("pack = ").append(Toml.format(pack)).append('\n')
                sb.append("component = ").append(Toml.format(component)).append('\n')
                sb.append("drawable = ").append(Toml.format(drawable)).append('\n')
                sb.append('\n')
            }
        }
        file.writeText(sb.toString())
    }

    private fun writeSuggestions(mappings: Map<String, String>) {
        if (mappings.isEmpty()) {
            suggestionsFile.delete()
            return
        }
        suggestionsFile.parentFile?.mkdirs()
        val sb = StringBuilder()
        for ((component, drawable) in mappings) {
            sb.append("[[override]]\n")
            sb.append("component = ").append(Toml.format(component)).append('\n')
            sb.append("drawable = ").append(Toml.format(drawable)).append('\n')
            sb.append('\n')
        }
        suggestionsFile.writeText(sb.toString())
    }

    companion object {
        const val FILE = "icon-overrides.toml"
        const val SUGGESTIONS = "icon-suggestions.toml"

        /**
         * Suggestions may be `[[override]]` rows (component + drawable; pack optional)
         * or a flat `component = "drawable"` map. A blank drawable is skipped.
         */
        fun parseSuggestionMap(parsed: Map<String, Any>): Map<String, String> {
            val out = linkedMapOf<String, String>()
            for (row in rows(parsed["override"])) {
                val component = row["component"] as? String ?: continue
                val drawable = (row["drawable"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                out[component] = drawable
            }
            for ((k, v) in parsed) {
                if (k == "override" || v !is String || v.isBlank()) continue
                out[k] = v.trim()
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        private fun rows(value: Any?): List<Map<String, Any>> =
            when (value) {
                is List<*> -> value.mapNotNull { it as? Map<String, Any> }
                is Map<*, *> -> listOf(value as Map<String, Any>)
                else -> emptyList()
            }
    }
}

/**
 * Apps split by the current pack. An override wins over the pack's own icon.
 * [missing] is what the suggest brief sends: no override, and the pack doesn't cover the app.
 */
data class IconAppGroups<T>(
    val overridden: List<T>,
    val covered: List<T>,
    val missing: List<T>,
)

fun <T> partitionIconApps(
    apps: List<T>,
    hasOverride: (T) -> Boolean,
    covers: (T) -> Boolean,
): IconAppGroups<T> {
    val overridden = ArrayList<T>()
    val covered = ArrayList<T>()
    val missing = ArrayList<T>()
    for (app in apps) {
        when {
            hasOverride(app) -> overridden += app
            covers(app) -> covered += app
            else -> missing += app
        }
    }
    return IconAppGroups(overridden, covered, missing)
}

/**
 * Label and component rows for the suggest brief. [overridden] and [covered] are component keys (`package/class`).
 * An app in both is left out, the same as [partitionIconApps] putting it with the overrides.
 */
fun suggestBriefRows(
    apps: List<Pair<String, String>>,
    overridden: Set<String>,
    covered: Set<String>,
): List<Pair<String, String>> =
    partitionIconApps(apps, hasOverride = { it.second in overridden }, covers = { it.second in covered }).missing
