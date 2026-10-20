package dev.omalauncher.widgets.market

import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Json
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Bool
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.WidgetError
import dev.omalauncher.widgets.lang.num
import java.time.LocalDate

/**
 * One widget in the marketplace catalog (index.json). Built by CI from the
 * reviewed files; the app checks [sha256] before installing and asks again
 * when an update changes [commands] or [requires].
 */
data class CatalogEntry(
    /** `author/name`, from the widget's folder in the marketplace repo. */
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val description: String,
    val license: String,
    /** Path of widget.owl relative to the catalog. */
    val path: String,
    val sha256: String,
    val size: Pair<Int, Int>?,
    val requires: Map<String, List<String>>,
    val commands: List<String>,
    /** Path of preview.png relative to the catalog, if the author added one. */
    val preview: String?,
    /** The owl language version it needs; launchers below it show it as needing an update. */
    val owl: Int = 1,
    /** One of [Catalog.CATEGORIES], or null: the store lists it under "other". */
    val category: String? = null,
    /** Previews drawn in each Omarchy theme, by theme name (paths relative to the catalog); [preview] is the fallback. */
    val themePreviews: Map<String, String> = emptyMap(),
    /** The UTC day of the commit that added it to the store repo, as `YYYY-MM-DD`; null when git couldn't tell. */
    val added: String? = null,
    /** Added in the last [Catalog.NEW_DAYS] days, as of the catalog's [Catalog.write]; the store publishes often enough to keep it current. */
    val new: Boolean = false,
    /** An app's `icon:` SVG, shown beside its name in the store; null for widgets and apps without one, or with an image icon. */
    val icon: String? = null,
    /** Path of an app's image icon (its `icon:` asset) relative to the catalog, written out by the store's CI. */
    val iconFile: String? = null,
    /** An app's other screens after its preview, drawn in each Omarchy theme, by theme name (paths relative to the catalog). */
    val screens: Map<String, List<String>> = emptyMap(),
) {
    /** The preview drawn in [theme], or the default one. */
    fun previewFor(theme: String?) = themePreviews[theme] ?: preview

    /** The other screens drawn in [theme], or in the default theme, like [preview], for a theme the store has none in. */
    fun screensFor(theme: String?) = screens[theme] ?: screens[Catalog.DEFAULT_THEME].orEmpty()

    val supported get() = owl <= Compiler.LEVEL

    /**
     * Runs commands in the guest or installs packages, like [dev.omalauncher.widgets.lang.Program.needsLinux]; http
     * requests, web images, sounds, fonts and what Android tells it (notifications, apps, recent apps) don't count.
     */
    val needsLinux get() = requires.isNotEmpty() || commands.any { c -> NO_LINUX.none { c.startsWith(it) } && !c.startsWith(Compiler.IF_LINUX) }
}

/** What a store item's listed commands start with when they don't run in Linux; see [CatalogEntry.needsLinux]. */
private val NO_LINUX = listOf("http GET ", "http POST ", "image GET ", "sound GET ", "font GET ", "read Android ", "read installed Android apps", "read the recently opened apps")

object Catalog {
    /**
     * Bumped only for changes older launchers can't read; new optional
     * fields don't need it. Per-widget compatibility is [CatalogEntry.owl].
     */
    const val FORMAT = 1

    /** The Omarchy theme a widget's preview.png is drawn in, and its screens are shown in for themes the store has none in. */
    const val DEFAULT_THEME = "tokyo-night"

    /** What `meta { category: ... }` may say, in the order the store shows them. */
    val CATEGORIES = listOf("news", "dev", "productivity", "time", "world", "sport", "finance", "media", "games", "pets", "fun", "home", "system")

    /** A widget is new on the day it was added and the [NEW_DAYS] - 1 days after, in UTC. */
    const val NEW_DAYS = 3L

    /** Whether a widget [added] on that day is new on [today]; a missing, malformed or future date never is. */
    fun isNew(
        added: String?,
        today: LocalDate,
    ): Boolean {
        val day = added?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return day <= today && day > today.minusDays(NEW_DAYS)
    }

    private val ID = Regex("""[a-z0-9][a-z0-9-]{0,38}/[a-z0-9][a-z0-9-]{0,38}""")

    /** Reads a catalog: index.json lists its entries under `widgets`, apps.json under `apps`. */
    fun parse(
        text: String,
        list: String = "widgets",
    ): List<CatalogEntry> {
        val root = Json.parse(text) as? Obj ?: throw WidgetError("the catalog isn't an object")
        val format = root.fields["format"]?.num()?.toInt() ?: throw WidgetError("the catalog has no format")
        if (format > FORMAT) throw WidgetError("the store needs a newer launcher; update it to browse $list again")
        return (root.fields[list] as? Lst)?.items.orEmpty().mapNotNull { (it as? Obj)?.let(::entry) }
    }

    private fun entry(o: Obj): CatalogEntry? {
        val f = o.fields
        fun s(k: String) = (f[k] as? Str)?.v
        val size = (f["size"] as? Lst)?.items?.mapNotNull { it.num()?.toInt() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        val requires =
            (f["requires"] as? Obj)?.fields.orEmpty().mapValues { (_, v) -> (v as? Lst)?.items.orEmpty().mapNotNull { (it as? Str)?.v } }
        // Both name the installed file (Market.fileFor), so only what Review lets into the store.
        val id = s("id")?.takeIf { it.matches(ID) } ?: return null
        val author = s("author")?.takeIf { it == id.substringBefore('/') } ?: return null
        return CatalogEntry(
            id = id,
            name = s("name") ?: return null,
            author = author,
            version = s("version") ?: return null,
            description = s("description").orEmpty(),
            license = s("license").orEmpty(),
            path = s("path") ?: return null,
            sha256 = s("sha256") ?: return null,
            size = size,
            requires = requires,
            commands = (f["commands"] as? Lst)?.items.orEmpty().mapNotNull { (it as? Str)?.v },
            preview = s("preview"),
            owl = f["owl"]?.num()?.toInt() ?: 1,
            category = s("category"),
            themePreviews = (f["themes"] as? Obj)?.fields.orEmpty().mapNotNull { (k, v) -> (v as? Str)?.v?.let { k to it } }.toMap(),
            added = s("added"),
            new = (f["new"] as? Bool)?.v == true,
            icon = s("icon"),
            iconFile = s("iconFile"),
            screens =
                (f["screens"] as? Obj)?.fields.orEmpty().mapValues { (_, v) -> (v as? Lst)?.items.orEmpty().mapNotNull { (it as? Str)?.v } }
                    .filterValues { it.isNotEmpty() },
        )
    }

    /**
     * Sorts [entries] the way the store lists them, by the repo's order.txt: one widget id per
     * line, shown first in that order; the rest follow by name. Ids after a `...` line go last
     * instead, in their order. Blank lines and `#` comments are ignored, and so are unknown ids.
     */
    fun ordered(
        entries: List<CatalogEntry>,
        order: String,
    ): List<CatalogEntry> {
        val lines = order.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        val split = lines.indexOf("...").takeIf { it >= 0 } ?: lines.size
        val first = lines.take(split)
        val last = lines.drop(split + 1)
        val rank = { e: CatalogEntry ->
            when (e.id) {
                in first -> first.indexOf(e.id) - first.size
                in last -> 1 + last.indexOf(e.id)
                else -> 0
            }
        }
        return entries.sortedWith(compareBy(rank, { it.name }))
    }

    /** A catalog of [entries]: index.json lists widgets under `widgets`, apps.json apps under `apps`. */
    fun write(
        entries: List<CatalogEntry>,
        generated: String,
        list: String = "widgets",
    ): String {
        val sb = StringBuilder()
        sb.append("{\n  \"format\": $FORMAT,\n  \"generated\": ${q(generated)},\n  ${q(list)}: [")
        entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(",")
            sb.append("\n    {")
            sb.append("\n      \"id\": ${q(e.id)}, \"name\": ${q(e.name)}, \"author\": ${q(e.author)}, \"version\": ${q(e.version)},")
            sb.append("\n      \"description\": ${q(e.description)}, \"license\": ${q(e.license)},")
            sb.append("\n      \"path\": ${q(e.path)}, \"sha256\": ${q(e.sha256)},")
            sb.append("\n      \"size\": ${e.size?.let { "[${it.first}, ${it.second}]" } ?: "null"},")
            sb.append("\n      \"requires\": {${e.requires.entries.joinToString(", ") { (m, p) -> "${q(m)}: [${p.joinToString(", ") { q(it) }}]" }}},")
            sb.append("\n      \"commands\": [${e.commands.joinToString(", ") { q(it) }}],")
            sb.append("\n      \"preview\": ${e.preview?.let(::q) ?: "null"},")
            sb.append("\n      \"owl\": ${e.owl}")
            e.category?.let { sb.append(",\n      \"category\": ${q(it)}") }
            if (e.themePreviews.isNotEmpty()) {
                sb.append(",\n      \"themes\": {")
                sb.append(e.themePreviews.entries.joinToString(",") { (t, p) -> "\n        ${q(t)}: ${q(p)}" })
                sb.append("\n      }")
            }
            e.added?.let { sb.append(",\n      \"added\": ${q(it)}") }
            if (e.new) sb.append(",\n      \"new\": true")
            e.icon?.let { sb.append(",\n      \"icon\": ${q(it)}") }
            e.iconFile?.let { sb.append(",\n      \"iconFile\": ${q(it)}") }
            if (e.screens.isNotEmpty()) {
                sb.append(",\n      \"screens\": {")
                sb.append(e.screens.entries.joinToString(",") { (t, l) -> "\n        ${q(t)}: [${l.joinToString(", ") { q(it) }}]" })
                sb.append("\n      }")
            }
            sb.append("\n    }")
        }
        sb.append("\n  ]\n}\n")
        return sb.toString()
    }

    /** A JSON string literal. */
    fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Dotted versions compare numerically: 1.10 > 1.9. */
    fun compareVersions(
        a: String,
        b: String,
    ): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** A meta value as text: `version: 2` and `version: "2.1"` both work. */
    fun metaText(v: Value?): String? =
        when (v) {
            is Str -> v.v.trim()
            is Num -> v.toString()
            else -> null
        }
}
