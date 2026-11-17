package dev.omalauncher.ui.home

/** A space after g reserves the bar for Google; g alone is still an app search. */
internal fun googleSearchQuery(text: String): String? =
    if (text.length > 2 && text[0].lowercaseChar() == 'g' && text[1] == ' ') {
        text.substring(2).trim().takeIf { it.isNotEmpty() }
    } else {
        null
    }
