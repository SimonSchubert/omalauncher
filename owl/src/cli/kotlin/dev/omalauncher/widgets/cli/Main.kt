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
                                       marketplace rules for widgets/<author>/<name>/widget.owl and
                                       apps/<author>/<name>/app.owl, Markdown on stdout; --base holds
                                       the main-branch copies
  owl programs <file.owl>...           programs and packages, one "<kind> <name>" per line
  owl index <repo> [--apps]            write index.json for every reviewed widget in <repo>/widgets,
                                       or with --apps apps.json for <repo>/apps
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
        if (!file.exists()) {
            println("### 🗑️ `$path` removed\n")
            continue
        }
        val previous = base?.let { File(it, path) }?.takeIf { it.exists() }?.readText()
        val r = Review(path, file.readText(), previous)
        if (File(file.parentFile, "preview.png").exists().not()) r.warnings += "no preview.png next to the ${r.kind.word}; the store shows a placeholder"
        println(r.markdown())
        if (r.errors.isNotEmpty()) failed++
    }
    return if (failed == 0) 0 else 1
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
 * Every widget that passes review goes into index.json, or with --apps every app into apps.json, a catalog of
 * their own that launchers from before apps never read; broken ones are left out and reported on stderr.
 * Each is dated by the commit that added it (needs the full history) and marked new for its first [Catalog.NEW_DAYS] days.
 */
private fun index(args: List<String>): Int {
    val kind = if ("--apps" in args) OwlKind.App else OwlKind.Widget
    val repo = File(args.firstOrNull { it != "--apps" } ?: usage())
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
            // screens/<n>/<omarchy theme>.webp: an app's other screens, from 2 on, in each theme.
            val screens =
                File(f.parentFile, "screens").listFiles { d -> d.isDirectory && d.name.toIntOrNull() != null }.orEmpty().sortedBy { it.name.toInt() }
                    .flatMap { d -> d.listFiles { p -> p.extension in setOf("webp", "png") }.orEmpty().map { it.nameWithoutExtension to it.relativeTo(repo).invariantSeparatorsPath } }
                    .groupBy({ it.first }, { it.second }).toSortedMap()
            val added = firstCommit(repo, path)
            r.entry(preview, themes, iconFile).copy(screens = screens).copy(added = added, new = Catalog.isNew(added, today))
        }.toList()
    val order = File(repo, "order.txt").takeIf { it.exists() }?.readText().orEmpty()
    val out = if (kind == OwlKind.App) "apps.json" else "index.json"
    File(repo, out).writeText(Catalog.write(Catalog.ordered(entries, order), Instant.now().toString(), "${kind.word}s"))
    System.err.println("$out: ${entries.size} ${kind.word}s")
    return 0
}

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
