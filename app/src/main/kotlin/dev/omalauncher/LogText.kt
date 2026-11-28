package dev.omalauncher

/**
 * What a log line says about text the launcher doesn't control. A release's log ends up in the bug reports people
 * share, so it names a command and a URL's host and no more; debug builds show all of it.
 */
object LogText {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** A guest command: its first word, without the arguments. */
    fun command(
        command: String,
        full: Boolean = BuildConfig.DEBUG,
    ): String = if (full) command else command.trim().substringBefore(' ').substringBefore('\n').substringAfterLast('/')

    /** A URL: its scheme and host, without the user, path, query and fragment. Other text: its scheme, or its length. */
    fun url(
        url: String,
        full: Boolean = BuildConfig.DEBUG,
    ): String {
        if (full) return url
        val scheme = SCHEME.find(url)?.value ?: return "${url.length} chars"
        val rest = url.substring(scheme.length)
        if (!rest.startsWith("//")) return scheme
        val host = rest.drop(2).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        return "$scheme//$host"
    }
}
