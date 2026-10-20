package dev.omalauncher.widgets.market

import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Global
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.lang.Program
import dev.omalauncher.widgets.lang.Source
import java.security.MessageDigest

/**
 * The marketplace rules for one submitted widget or app: it compiles, it lives at
 * `widgets/<author>/<name>/widget.owl` (an app at `apps/<author>/<name>/app.owl`),
 * its meta describes it, and anything risky in its commands is pointed out to the reviewer.
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

    /** What the path says it is: under apps/ an app, under faces/ a face, else a widget. */
    val kind get() = OwlKind.entries.firstOrNull { path.startsWith(it.word + "s/") } ?: OwlKind.Widget

    val id get() = "$author/$name"
    val version get() = Catalog.metaText(program?.meta?.get("version")).orEmpty()
    val sha256 get() = sha256(text.toByteArray())

    init {
        run()
    }

    private fun run() {
        val m = PATH.matchEntire(path)
        if (m == null) {
            errors += "widgets go at widgets/<author>/<name>/widget.owl, faces at faces/<author>/<name>/face.owl and apps at apps/<author>/<name>/app.owl, " +
                "in lowercase letters, digits and dashes (got $path)"
        } else {
            author = m.groupValues[2]
            name = m.groupValues[3]
        }
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > MAX_BYTES) errors += "the file is ${size / 1024} KB; the limit is ${MAX_BYTES / 1024} KB"
        val p =
            try {
                Compiler.compile(text)
            } catch (e: CompileError) {
                errors += "doesn't compile: " + Source(text).format(e)
                return
            } catch (e: RuntimeException) {
                // A compiler bug, not the file's: turned down all the same rather than taking the share screen or CI with it.
                errors += "doesn't compile: $e"
                return
            }
        program = p
        if (p.kind != kind) errors += "this is ${if (p.kind == OwlKind.App) "an app" else "a ${p.kind.word}"}: it goes at ${p.kind.word}s/<author>/<name>/${p.kind.word}.owl"
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
        if (p.size == null && p.kind == OwlKind.Widget) warnings += "no size: set size: 3x2 so it's placed at the size you designed it for"
        // Shared links warn about these; the store doesn't take them: what they run can change after review.
        val unquoted = p.commands.filter { UNQUOTED.containsMatchIn(it) }
        for (c in unquoted) {
            errors += "${c.removeSuffix(" built from widget values")} is put together from widget values that aren't quoted: " +
                "write each one as ${'$'}{sh(value)}, outside any quotes, so it stays one word and can't run as code " +
                "(run(\"notify-send ${'$'}{sh(title)}\"), not '${'$'}{title}' or \"${'$'}{sh(title)}\"). " +
                "Other functions go inside it, ${'$'}{sh(upper(title))}, and a heredoc (<<) can't take values. " +
                "See \"Values in commands\" in the widgets README"
        }
        warnings += risks(p.commands - unquoted.toSet())
        warnings += costs(p)

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
        iconFile: String? = null,
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
            icon = p.icon,
            iconFile = iconFile,
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
        val PATH = Regex("""(widget|face|app)s/([a-z0-9][a-z0-9-]{0,38})/([a-z0-9][a-z0-9-]{0,38})/\1\.owl""")
        private val VERSION = Regex("""\d+(\.\d+){0,2}""")

        /** What in [commands] matches [RISKS], one line each: "command pipes a download into a shell: curl ... | sh". */
        fun risks(commands: List<String>): List<String> =
            commands.flatMap { cmd -> RISKS.filter { (pattern, _) -> pattern.containsMatchIn(cmd) }.map { (_, why) -> "command $why: ${cmd.lines().first().take(80)}" } }

        /**
         * What makes a widget cost more than it needs to, one line each: sources that poll very often, a
         * command that sleeps while it holds one of the few shells every widget shares, curl run once per item.
         */
        fun costs(p: Program): List<String> {
            val out = ArrayList<String>()
            for (g in p.globals) {
                if (g !is Global.Source) continue
                val every = "every ${g.every.toInt()}s"
                val cmd = g.arg?.const?.toString()
                when {
                    g.kind == "cmd" && g.every <= 5 -> out += "source ${g.name} runs a command $every, which costs battery while its page shows: does it need to be that often?"
                    g.kind == "http" && g.every <= 10 -> out += "source ${g.name} fetches $every, which costs battery and data while its page shows: does it need to be that often?"
                }
                if (g.kind == "cmd" && g.every <= 30 && cmd != null && SLEEP.containsMatchIn(cmd)) {
                    out += "source ${g.name} sleeps while it runs $every, holding one of the few shells all widgets share: " +
                        "sample once per run and compare with the last one through prev() instead"
                }
            }
            for (c in p.commands) {
                // A loop that breaks out is waiting for something, like a server starting, not fetching per item;
                // one that puts its work in the background (&) already fetches in parallel.
                if (LOOP.findAll(c).any { CURL_OUT.containsMatchIn(it.value) && !BREAK.containsMatchIn(it.value) && !BACKGROUND.containsMatchIn(it.value) }) {
                    out += "command runs curl once per item in a loop, one after another: give one curl all the URLs, or use an http() source: ${c.lines().first().take(80)}"
                }
            }
            return out
        }

        private val SLEEP = Regex("""\bsleep\s+[0-9.]+""")
        /** A loop's body, up to its first `done`. */
        private val LOOP = Regex("""\b(for|while)\b[^\n]*?\bdo\b(?:(?!\bdone\b)[\s\S])*""")

        /** A curl that prints what it fetches; one saving to a file (-o, -O) is a cache filled once, which is fine. */
        private val CURL_OUT = Regex("""\bcurl\b(?![^\n|;]*(\s-[a-zA-Z]*[oO]\b|--output|--remote-name))""")
        private val BREAK = Regex("""\bbreak\b""")
        private val BACKGROUND = Regex("""&\s*$""", RegexOption.MULTILINE)

        private val IF_LINUX = Regex.escape(Compiler.IF_LINUX)

        /** What the compiler lists for a command that isn't fixed text and doesn't quote its values (Compiler's `listCommand`). */
        private val UNQUOTED = Regex("""(\(…\)|^Terminal command) built from widget values$""")

        /** Programs that run code they're given: shells and script interpreters. */
        private const val INTERPRETERS = "(ba|z|da|fi)?sh|python3?|node|perl|ruby|php|lua|bun|deno|tclsh|awk -f"

        /** Patterns a reviewer should look at twice. Warnings, not errors: some widgets have good reasons. */
        val RISKS =
            listOf(
                Regex("""(curl|wget)[^|\n]*\|\s*($INTERPRETERS)\b(?!\s+-c\b)""") to "pipes a download into a shell or interpreter",
                Regex("""\b($INTERPRETERS|source)\s+<\(\s*(curl|wget)\b|-c\s+["']?\$\(\s*(curl|wget)\b""") to "runs a download as code",
                // Saved first, then run: what it runs is on a server and can change after anyone read the widget.
                Regex("""(?s)\b(curl|wget)\b.*(^|[\s;&|(])($INTERPRETERS|source)\s+[^-\s<]""", RegexOption.MULTILINE) to "downloads something and runs a file",
                Regex("""(?s)\b(curl|wget)\b.*\bchmod\s+(-\S+\s+)*([ugoa]*\+[rws]*x|[0-7]*[1357][0-7]{2}\b)""") to "downloads something and makes a file executable",
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
                UNQUOTED to "is put together while the widget runs, so what it runs can't be shown here",
                // The compiler's line for ask(...) with text that isn't fixed: the agent acts on whatever it is, a download's
                // text for one.
                Regex("""\bask the agent something built from widget values$""") to "gives the agent text made while the widget runs, which could come from the web",
                // sh(...) keeps a value one word, but not one that is the program, or code for one.
                Regex("""(^|[;&|(`\n]|\$\()\s*${Compiler.VALUE}""") to "runs a program a widget value names",
                Regex("""\b(eval|source|$INTERPRETERS)\s+(-\S+\s+)*${Compiler.VALUE}""") to "runs a widget value as code",
                Regex("""^http GET \(a URL built from widget values\)$""") to "fetches a URL put together while the widget runs, which can send its data anywhere",
                Regex("""^http POST \(a URL built from widget values\)$""") to "sends to a URL put together while the widget runs, which can send its data anywhere",
                // An image, sound or font URL whose host comes from widget values (Compiler's webFile): it can go anywhere too.
                Regex("""^(image|sound|font) GET https://[^/\s]*…$""") to "loads a file from a server put together while the widget runs, which can send its data anywhere",
                Regex("""^($IF_LINUX)?read file \(a path built from widget values\)$""") to "reads a file whose path is put together while the widget runs",
                Regex("""^read Android notifications""") to "sees every notification on the phone; check nothing sends them anywhere",
            )

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
