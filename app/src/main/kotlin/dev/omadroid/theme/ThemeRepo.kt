package dev.omadroid.theme

/**
 * A theme's git repo, as omarchy-theme-install takes it. We have no git, so
 * we fetch GitHub's tarball of it instead; every theme on omarchy.org/themes
 * is on GitHub.
 */
class ThemeRepo private constructor(
    /** What the user gave us, kept in the theme to update it from. */
    val url: String,
    val owner: String,
    val repo: String,
) {
    /** Named like omarchy-theme-install names it: the repo, without omarchy- and -theme. */
    val themeName get() = repo.lowercase().removePrefix("omarchy-").removeSuffix("-theme")

    val tarball get() = "https://codeload.github.com/$owner/$repo/tar.gz/HEAD"

    /** github.com/owner/repo, for showing where a theme came from. */
    val label get() = "github.com/$owner/$repo"

    companion object {
        private val PART = Regex("^[A-Za-z0-9_.-]+$")

        /**
         * https://github.com/o/r, with or without .git, a trailing slash or
         * the scheme, or git@github.com:o/r.git. Null for anything else.
         */
        fun parse(input: String): ThemeRepo? {
            val url = input.trim()
            val path =
                when {
                    url.startsWith("git@github.com:") -> url.removePrefix("git@github.com:")
                    else ->
                        url
                            .removePrefix("https://")
                            .removePrefix("http://")
                            .removePrefix("www.")
                            .takeIf { it.startsWith("github.com/") }
                            ?.removePrefix("github.com/")
                } ?: return null
            val parts = path.trimEnd('/').removeSuffix(".git").split('/')
            if (parts.size != 2 || parts.any { !PART.matches(it) || it.startsWith('.') }) return null
            return ThemeRepo(url, parts[0], parts[1]).takeIf { ThemeDirs.isValidName(it.themeName) }
        }
    }
}
