package dev.omalauncher.widgets.cli

import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
import dev.omalauncher.widgets.lang.OwlKind
import dev.omalauncher.widgets.lang.Source
import dev.omalauncher.widgets.market.Catalog
import dev.omalauncher.widgets.market.Review
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

private const val USAGE = """owl: the widget language checker

  owl check <file.owl>...              compile; exit 1 on errors
  owl review <repo> <path>... [--base <dir>]
                                       marketplace rules for widgets/<author>/<name>/widget.owl,
                                       faces/<author>/<name>/face.owl (and its page-2.owl, ...) and
                                       apps/<author>/<name>/app.owl, Markdown on stdout; --base holds
                                       the main-branch copies
  owl programs <file.owl>...           programs and packages, one "<kind> <name>" per line
  owl index <repo> [--faces|--apps]    write index.json for every reviewed widget in <repo>/widgets,
                                       or with --faces faces.json for <repo>/faces, with --apps apps.json for <repo>/apps
"""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: usage()
    val rest = args.drop(1)
    val code =
        when (cmd) {
            "check" -> check(rest)
            "review" -> review(rest)
            "programs" -> programs(rest)
            "index" -> index(rest)
            else -> usage()
        }
    exitProcess(code)
}

private fun usage(): Nothing {
    System.err.print(USAGE)
    exitProcess(2)
}

private fun check(files: List<String>): Int {
    if (files.isEmpty()) usage()
    var failed = 0
    for (f in files) {
        val text = File(f).readText()
        try {
            val p = Compiler.compile(text)
            println("ok  $f  (${p.name}, ${p.nodeCount} nodes)")
            for (w in p.warnings) println("  warning: $w")
        } catch (e: CompileError) {
            failed++
            println("ERR $f:" + Source(text).format(e))
        }
    }
    return if (failed == 0) 0 else 1
}

private fun review(args: List<String>): Int {
    val base = args.indexOf("--base").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val plain = args.filterIndexed { i, a -> a != "--base" && (i == 0 || args[i - 1] != "--base") }
    val repo = File(plain.firstOrNull() ?: usage())
    val paths = plain.drop(1).ifEmpty { usage() }
    var failed = 0
    for (path in paths) {
        val file = File(repo, path)
        val previous = base?.let { File(it, path) }?.takeIf { it.exists() }?.readText()
        if (!file.exists()) {
            println("### 🗑️ `$path` removed\n")
            pageChange(repo, base, path, previous, null)?.let {
                println("- ❌ $it\n")
                failed++
            }
            continue
        }
        val r = Review(path, file.readText(), previous)
        if (r.page == 1 && File(file.parentFile, "preview.png").exists().not()) r.warnings += "no preview.png next to the ${r.kind.word}; the store shows a placeholder"
        if (r.page > 2 && !File(file.parentFile, "page-${r.page - 1}.owl").exists()) r.errors += "there's no page-${r.page - 1}.owl before it: pages are numbered from 2 without gaps"
        pageChange(repo, base, path, previous, r.text)?.let { r.errors += it }
        println(r.markdown())
        if (r.errors.isNotEmpty()) failed++
    }
    return if (failed == 0) 0 else 1
}

/**
 * A face's later page has no version of its own, so adding, changing or removing one at [path] of a face already in the
 * store needs face.owl's version to go up, or launchers wouldn't offer the update; the error when it doesn't.
 */
private fun pageChange(
    repo: File,
    base: String?,
    path: String,
    previous: String?,
    text: String?,
): String? {
    if (base == null || Review.PAGE.matchEntire(path) == null || previous == text) return null
    val face = path.substringBeforeLast('/') + "/face.owl"
    val old = File(base, face).takeIf { it.exists() } ?: return null
    // Removed with its face.owl, it goes with the whole face.
    val new = File(repo, face).takeIf { it.exists() } ?: return null
    val oldVersion = Review(face, old.readText()).version
    val version = Review(face, new.readText()).version
    if (Catalog.compareVersions(version, oldVersion) > 0) return null
    return "this changes a page of an existing face, so the version in its face.owl must go up (main has $oldVersion)"
}

private fun programs(files: List<String>): Int {
    for (f in files) {
        val p =
            try {
                Compiler.compile(File(f).readText())
            } catch (e: CompileError) {
                continue
            }
        p.programs.forEach { println("program $it") }
        p.requires.forEach { (m, l) -> l.forEach { println("$m $it") } }
    }
    return 0
}

/**
 * Every widget that passes review goes into index.json, or with --faces every face into faces.json and with --apps
 * every app into apps.json, catalogs of their own that launchers from before faces or apps never read; broken ones are left out and reported on stderr.
 * Each is dated by the commit that added it (needs the full history) and marked new for its first [Catalog.NEW_DAYS] days.
 */
private fun index(args: List<String>): Int {
    val kind =
        when {
            "--apps" in args -> OwlKind.App
            "--faces" in args -> OwlKind.Face
            else -> OwlKind.Widget
        }
    val repo = File(args.firstOrNull { !it.startsWith("--") } ?: usage())
    val today = LocalDate.now(ZoneOffset.UTC)
    // Both install as market-<author>-<name>.owl, so an app can't take a widget's id: widgets were there first.
    val widgetIds =
        if (kind == OwlKind.App) File(repo, "widgets").listFiles().orEmpty().flatMap { a -> a.listFiles().orEmpty().map { "${a.name}/${it.name}" } }.toSet() else emptySet()
    val entries =
        File(repo, "${kind.word}s").walkTopDown().filter { it.name == "${kind.word}.owl" }.sortedBy { it.path }.mapNotNull { f ->
            val path = f.relativeTo(repo).invariantSeparatorsPath
            val r = Review(path, f.readText())
            if (r.errors.isNotEmpty()) {
                System.err.println("skipped $path: ${r.errors.first().lines().first()}")
                return@mapNotNull null
            }
            if (r.id in widgetIds) {
                System.err.println("skipped $path: ${r.id} is a widget's already")
                return@mapNotNull null
            }
            val preview = File(f.parentFile, "preview.png").takeIf { it.exists() }?.relativeTo(repo)?.invariantSeparatorsPath
            // previews/<omarchy theme>.webp: the tile (an app's view) drawn in each theme, for the store to match the user's
            val themes =
                File(f.parentFile, "previews").listFiles { p -> p.extension in setOf("webp", "png") }.orEmpty().sortedBy { it.name }
                    .associate { it.nameWithoutExtension to it.relativeTo(repo).invariantSeparatorsPath }
            // An image icon (an asset) is written out beside the app for the store to load, like a preview; an SVG goes in the catalog.
            val iconFile =
                r.program?.iconImage?.let { bytes ->
                    val ext = imageExtension(bytes) ?: return@let null
                    File(f.parentFile, "store-icon.$ext").apply { writeBytes(bytes) }.relativeTo(repo).invariantSeparatorsPath
                }
            // screens/<n>/<omarchy theme>.webp: an app's other screens, or a face's other pages, from 2 on, in each theme.
            val screens =
                File(f.parentFile, "screens").listFiles { d -> d.isDirectory && d.name.toIntOrNull() != null }.orEmpty().sortedBy { it.name.toInt() }
                    .flatMap { d -> d.listFiles { p -> p.extension in setOf("webp", "png") }.orEmpty().map { it.nameWithoutExtension to it.relativeTo(repo).invariantSeparatorsPath } }
                    .groupBy({ it.first }, { it.second }).toSortedMap()
            // page-2.owl, page-3.owl, ...: a face's other pages, numbered without gaps; one that fails review takes its face out.
            val pageFiles =
                if (kind != OwlKind.Face) emptyList() else f.parentFile.listFiles().orEmpty().mapNotNull { p -> PAGE_FILE.matchEntire(p.name)?.let { it.groupValues[1].toInt() to p } }.sortedBy { it.first }
            if (pageFiles.map { it.first } != (2 until 2 + pageFiles.size).toList()) {
                System.err.println("skipped $path: its pages aren't numbered page-2.owl, page-3.owl, ... without gaps")
                return@mapNotNull null
            }
            val pages = pageFiles.map { (_, p) -> Review(p.relativeTo(repo).invariantSeparatorsPath, p.readText()) }
            pages.firstOrNull { it.errors.isNotEmpty() }?.let {
                System.err.println("skipped $path: ${it.path}: ${it.errors.first().lines().first()}")
                return@mapNotNull null
            }
            val added = firstCommit(repo, path)
            r.entry(preview, themes, iconFile, pages).copy(screens = screens).copy(added = added, new = Catalog.isNew(added, today))
        }.toList()
    val order = File(repo, "order.txt").takeIf { it.exists() }?.readText().orEmpty()
    val out = if (kind == OwlKind.Widget) "index.json" else "${kind.word}s.json"
    File(repo, out).writeText(Catalog.write(Catalog.ordered(entries, order), Instant.now().toString(), "${kind.word}s"))
    System.err.println("$out: ${entries.size} ${kind.word}s")
    return 0
}

private val PAGE_FILE = Regex("""page-(\d{1,3})\.owl""")

/** The file extension for an image asset's bytes, by their signature; null for anything a store icon can't be. */
private fun imageExtension(bytes: ByteArray): String? {
    fun starts(vararg b: Int) = bytes.size >= b.size && b.indices.all { bytes[it] == b[it].toByte() }
    return when {
        starts(0x89, 'P'.code, 'N'.code, 'G'.code) -> "png"
        starts(0xff, 0xd8, 0xff) -> "jpg"
        starts('G'.code, 'I'.code, 'F'.code) -> "gif"
        starts('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.size >= 12 && String(bytes, 8, 4) == "WEBP" -> "webp"
        else -> null
    }
}

/**
 * The UTC day [path] was first committed to [repo], or null outside git or in a shallow clone, where every
 * file looks added by the one commit there: undated rather than all new at once.
 */
private fun firstCommit(
    repo: File,
    path: String,
): String? =
    runCatching {
        fun git(vararg a: String) =
            ProcessBuilder("git", "-C", repo.path, *a).redirectErrorStream(true).apply { environment()["TZ"] = "UTC" }.start().let { p ->
                p.inputStream.bufferedReader().readText().trim().takeIf { p.waitFor() == 0 }
            }
        if (git("rev-parse", "--is-shallow-repository") != "false") return null
        git("log", "--diff-filter=A", "--follow", "--date=format-local:%Y-%m-%d", "--format=%cd", "--", path)?.lines()?.lastOrNull()?.takeIf { it.isNotBlank() }
    }.getOrNull()
