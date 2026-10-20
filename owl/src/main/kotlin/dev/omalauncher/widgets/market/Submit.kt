package dev.omalauncher.widgets.market

import dev.omalauncher.widgets.lang.OwlKind
import java.net.URLEncoder

/**
 * A widget sent to the store from the browser, without an agent or a GitHub username: the file goes to
 * [Paste] for [Paste.STORE_DAYS] days, and GitHub's new-issue page opens with a link to it filled in.
 * The maintainer adds it to the store from there. The URL stays short whatever the widget's size.
 */
object Submit {
    /** [name] as a store folder: lowercase letters, digits and dashes, at most 39. */
    fun slug(name: String): String =
        name
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(39)
            .trimEnd('-')
            .ifEmpty { "widget" }

    /** The new-issue page on [repo] asking to add the widget or app [name], which is on [Paste] under [pasteId]. */
    fun issueUrl(
        repo: String,
        name: String,
        description: String,
        pasteId: String,
        kind: OwlKind = OwlKind.Widget,
    ): String {
        val body =
            buildString {
                appendLine("Please add my ${kind.word} **$name** to the store.")
                if (description.isNotBlank()) appendLine().appendLine("> $description")
                appendLine()
                appendLine("- file: https://${Paste.HOST}/$pasteId.txt (gone after ${Paste.STORE_DAYS} days)")
                appendLine("- open in Omalauncher: ${Share.pasteLink(pasteId)}")
                appendLine()
                append("Sent from Omalauncher.")
            }
        return "https://github.com/$repo/issues/new?title=${encode("Add ${kind.word}: $name")}&body=${encode(body)}"
    }

    // URLEncoder writes form encoding; %20 is a space wherever it's read, a + isn't.
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
