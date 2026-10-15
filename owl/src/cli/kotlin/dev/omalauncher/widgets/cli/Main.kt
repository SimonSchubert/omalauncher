package dev.omalauncher.widgets.cli

import dev.omalauncher.widgets.lang.CompileError
import dev.omalauncher.widgets.lang.Compiler
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
                                       Markdown on stdout; --base holds the main-branch copies
  owl programs <file.owl>...           programs and packages, one "<kind> <name>" per line
  owl index <repo>                     write index.json for every reviewed widget in <repo>/widgets
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
        if (File(file.parentFile, "preview.png").exists().not()) r.warnings += "no preview.png next to the widget; the store shows a placeholder"
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
 * Every widget that passes review goes into the catalog; broken ones are left out and reported on stderr.
 * Each is dated by the commit that added it (needs the full history) and marked new for its first [Catalog.NEW_DAYS] days.
 */
private fun index(args: List<String>): Int {
    val repo = File(args.firstOrNull() ?: usage())
    val today = LocalDate.now(ZoneOffset.UTC)
    val entries =
        File(repo, "widgets").walkTopDown().filter { it.name == "widget.owl" }.sortedBy { it.path }.mapNotNull { f ->
            val path = f.relativeTo(repo).invariantSeparatorsPath
            val r = Review(path, f.readText())
            if (r.errors.isNotEmpty()) {
                System.err.println("skipped $path: ${r.errors.first().lines().first()}")
                return@mapNotNull null
            }
            val preview = File(f.parentFile, "preview.png").takeIf { it.exists() }?.relativeTo(repo)?.invariantSeparatorsPath
            // previews/<omarchy theme>.webp: the tile drawn in each theme, for the store to match the user's
            val themes =
                File(f.parentFile, "previews").listFiles { p -> p.extension in setOf("webp", "png") }.orEmpty().sortedBy { it.name }
                    .associate { it.nameWithoutExtension to it.relativeTo(repo).invariantSeparatorsPath }
            val added = firstCommit(repo, path)
            r.entry(preview, themes).copy(added = added, new = Catalog.isNew(added, today))
        }.toList()
    val order = File(repo, "order.txt").takeIf { it.exists() }?.readText().orEmpty()
    File(repo, "index.json").writeText(Catalog.write(Catalog.ordered(entries, order), Instant.now().toString()))
    System.err.println("index.json: ${entries.size} widgets")
    return 0
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
