package dev.omalauncher.linux

import java.io.File

/**
 * File types as Linux names them, from the guest's shared-mime-info: by file
 * name (globs2), with aliases folded together (application/vnd.ms-3mfdocument
 * is model/3mf). Android often calls a model or a G-code file
 * application/octet-stream; the name tells Linux apps' types apart.
 */
class GuestMime(
    private val globs: List<Pair<String, String>>,
    private val aliases: Map<String, String>,
) {
    /** The type of a file called [name], or null when no pattern matches. Longest pattern first, as shared-mime-info does. */
    fun forName(name: String): String? {
        val lower = name.lowercase()
        return globs.firstOrNull { (pattern, _) -> matches(pattern, lower) }?.second
    }

    fun canonical(mime: String) = aliases[mime] ?: mime

    /** Whether an app that opens [declared] types opens [mime]: the same type, an alias of it, or a wildcard like image/ *. */
    fun opens(
        declared: List<String>,
        mime: String,
    ): Boolean {
        val want = canonical(mime)
        return declared.any { d ->
            canonical(d) == want || (d.endsWith("/*") && want.startsWith(d.dropLast(1)))
        }
    }

    private fun matches(
        pattern: String,
        name: String,
    ): Boolean =
        when {
            pattern.startsWith("*.") && '*' !in pattern.drop(1) && '[' !in pattern -> name.endsWith(pattern.drop(1))
            '*' !in pattern && '?' !in pattern && '[' !in pattern -> name == pattern
            else -> false // the rare complex globs: the extension ones cover what's opened
        }

    companion object {
        fun load(rootfs: File): GuestMime {
            val mime = File(rootfs, "usr/share/mime")
            val globs =
                runCatching { File(mime, "globs2").readLines() }.getOrDefault(emptyList())
                    .filter { !it.startsWith("#") }
                    .mapNotNull { line ->
                        val parts = line.split(':')
                        if (parts.size < 3) null else parts[2].lowercase() to parts[1]
                    }.sortedByDescending { it.first.length }
            val aliases =
                runCatching { File(mime, "aliases").readLines() }.getOrDefault(emptyList())
                    .mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
                    .toMap()
            return GuestMime(globs, aliases)
        }
    }
}
