package dev.omalauncher.widgets.market

import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Source
import java.security.MessageDigest

/**
 * The marketplace rules for one submitted widget: it compiles, it lives at
 * `widgets/<author>/<name>/widget.owl`, its meta describes it, and anything
 * risky in its commands is pointed out to the reviewer.
 */
class Review(
    /** Path relative to the marketplace repo root. */
    val path: String,
    val text: String,
    /** The same widget on the main branch, when this is an update. */
    val previous: String? = null,
) {
    val errors = ArrayList<String>()
    val warnings = ArrayList<String>()
    var program: Program? = null
        private set
    var author = ""
        private set
    var name = ""
        private set

    val id get() = "$author/$name"
    val version get() = Catalog.metaText(program?.meta?.get("version")).orEmpty()
    val sha256 get() = sha256(text.toByteArray())

    init {
        run()
    }

    private fun run() {
        val m = PATH.matchEntire(path)
        if (m == null) {
            errors += "widgets go at widgets/<author>/<name>/widget.owl in lowercase letters, digits and dashes (got $path)"
        } else {
            author = m.groupValues[1]
            name = m.groupValues[2]
        }
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > MAX_BYTES) errors += "the file is ${size / 1024} KB; the limit is ${MAX_BYTES / 1024} KB"
        val p =
            try {
                Compiler.compile(text)
            } catch (e: CompileError) {
                errors += "doesn't compile: " + Source(text).format(e)
                return
            }
        program = p
        val meta = p.meta
        val description = Catalog.metaText(meta["description"])
        when {
            description == null -> errors += "meta needs a description: meta { description: \"what it shows\" }"
            description.length !in 10..200 -> errors += "the description should be 10-200 characters"
        }
        val version = Catalog.metaText(meta["version"])
        if (version == null || !VERSION.matches(version)) errors += "meta needs a version like 1 or \"1.2.0\""
        if (Catalog.metaText(meta["license"]).isNullOrBlank()) errors += "meta needs a license, e.g. license: \"MIT\""
        when (val category = Catalog.metaText(meta["category"])) {
            null -> warnings += "no category: the store lists it under \"other\" (meta { category: \"news\" })"
            !in Catalog.CATEGORIES -> errors += "category should be one of ${Catalog.CATEGORIES.joinToString(", ")} (got \"$category\")"
        }
        if (p.size == null) warnings += "no size: set size: 3x2 so it's placed at the size you designed it for"
        warnings += risks(p.commands)

        val before = previous
        if (before != null && version != null) {
            val old = runCatching { Compiler.compile(before) }.getOrNull()
            val oldVersion = Catalog.metaText(old?.meta?.get("version"))
            if (oldVersion != null && Catalog.compareVersions(version, oldVersion) <= 0) {
                errors += "this changes an existing widget, so its version must go up (main has $oldVersion)"
            }
            if (old != null && (old.commands != p.commands || old.requires != p.requires)) {
                warnings += "the commands or packages changed since the last version: installed users will be asked again"
            }
        }
    }

    fun entry(
        preview: String?,
        themePreviews: Map<String, String> = emptyMap(),
    ): CatalogEntry {
        val p = program!!
        return CatalogEntry(
            id = id,
            name = p.name,
            author = author,
            version = version,
            description = Catalog.metaText(p.meta["description"]).orEmpty(),
            license = Catalog.metaText(p.meta["license"]).orEmpty(),
            path = path,
            sha256 = sha256,
            size = p.size,
            requires = p.requires,
            commands = p.commands,
            preview = preview,
            owl = p.level,
            category = Catalog.metaText(p.meta["category"]),
            themePreviews = themePreviews,
        )
    }

    /** A Markdown summary for the pull request, so the reviewer sees what the widget runs without reading it all. */
    fun markdown(): String {
        val p = program
        val sb = StringBuilder()
        sb.append("### ${if (errors.isEmpty()) "✅" else "❌"} `$id`")
        if (p != null) sb.append(" — ${p.name} ${version.ifEmpty { "?" }}")
        sb.append("\n\n")
        for (e in errors) sb.append("- ❌ ${e.replace("\n", "\n  ")}\n")
        for (w in warnings) sb.append("- ⚠️ $w\n")
        if (p != null) {
            Catalog.metaText(p.meta["description"])?.let { sb.append("\n> $it\n") }
            sb.append("\n**Packages:** ")
            sb.append(if (p.requires.isEmpty()) "none" else p.requires.entries.joinToString("; ") { (m, l) -> "$m: ${l.joinToString(", ")}" })
            sb.append("\n\n**Programs it runs:** ${p.programs.joinToString(", ").ifEmpty { "none" }}\n")
            sb.append("\n**Commands** (${p.commands.size}):\n")
            for (c in p.commands) sb.append("\n```sh\n${c.trimEnd()}\n```\n")
        }
        return sb.toString()
    }

    companion object {
        const val MAX_BYTES = 256 * 1024
        val PATH = Regex("""widgets/([a-z0-9][a-z0-9-]{0,38})/([a-z0-9][a-z0-9-]{0,38})/widget\.owl""")
        private val VERSION = Regex("""\d+(\.\d+){0,2}""")

        /** What in [commands] matches [RISKS], one line each: "command pipes a download into a shell: curl ... | sh". */
        fun risks(commands: List<String>): List<String> =
            commands.flatMap { cmd -> RISKS.filter { (pattern, _) -> pattern.containsMatchIn(cmd) }.map { (_, why) -> "command $why: ${cmd.lines().first().take(80)}" } }

        private val IF_LINUX = Regex.escape(Compiler.IF_LINUX)

        /** Patterns a reviewer should look at twice. Warnings, not errors: some widgets have good reasons. */
        val RISKS =
            listOf(
                Regex("""(curl|wget)[^|\n]*\|\s*(ba|z)?sh\b""") to "pipes a download into a shell",
                Regex("""\brm\s+-[a-zA-Z]*r[a-zA-Z]*f|\brm\s+-[a-zA-Z]*f[a-zA-Z]*r""") to "deletes recursively",
                Regex("""\bsudo\b|\bsu\s""") to "asks for root",
                Regex("""\bbase64\s+(-d|--decode)""") to "decodes base64 (hidden code?)",
                Regex("""\beval\b""") to "uses eval",
                Regex("""\b(dd|mkfs|shred)\b""") to "writes raw disks or destroys data",
                Regex("""chmod\s+(-R\s+)?[0-7]*7[0-7]{2}\b|chmod\s+\+s""") to "loosens permissions",
                Regex(""">\s*~?/?\.(bashrc|profile|zshrc|ssh)""") to "writes shell or ssh config",
                Regex("""\b(nc|ncat|socat)\b.*-e\b""") to "may open a remote shell",
                Regex("""^($IF_LINUX)?read file .*(/\.ssh|/\.gnupg|/\.aws|\.netrc|/\.config/gh|token|secret|passw)""", RegexOption.IGNORE_CASE) to "reads a private file",
                // What the compiler lists for a command that isn't fixed text (Compiler's `commands`): it could be
                // anything, a download for one, and can change after anyone read the widget.
                Regex("""(\(…\)|^Terminal command) built from widget values$""") to "is put together while the widget runs, so what it runs can't be shown here",
                Regex("""^http GET \(a URL built from widget values\)$""") to "fetches a URL put together while the widget runs, which can send its data anywhere",
                Regex("""^($IF_LINUX)?read file \(a path built from widget values\)$""") to "reads a file whose path is put together while the widget runs",
                Regex("""^read Android notifications""") to "sees every notification on the phone; check nothing sends them anywhere",
                Regex("""^read Android location""") to "reads your approximate location; check nothing sends it anywhere",
            )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
