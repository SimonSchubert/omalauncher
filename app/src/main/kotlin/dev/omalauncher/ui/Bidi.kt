package dev.omalauncher.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Which way a text runs, from what it is rather than from the layout: right to left when it has a letter of a
 * right-to-left script (Arabic, Hebrew), else left to right. Compose gives text the layout's direction, so in an Arabic
 * layout "-1°" read "1°-" and an English sentence had its full stop at the start; numbers, paths and English stay left
 * to right here, and an Arabic sentence that starts with "linux" still reads right to left. Text with no letter or
 * digit at all, a lone "[" beside a node, takes [neutral]: the layout's direction by default, so in a mirrored row the
 * brackets around a slider still face it.
 */
fun textDirectionOf(
    text: CharSequence,
    neutral: TextDirection = TextDirection.Content,
): TextDirection =
    when {
        hasRtl(text) -> TextDirection.Rtl
        text.any { it.isLetterOrDigit() } -> TextDirection.Ltr
        else -> neutral
    }

/** Whether [text] has a letter of a right-to-left script. */
fun hasRtl(text: CharSequence): Boolean {
    var i = 0
    while (i < text.length) {
        val c = Character.codePointAt(text, i)
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
        }
        i += Character.charCount(c)
    }
    return false
}

/**
 * `start`, `center` or `end` as an edge of the layout, whichever way the text itself runs: Compose's [TextAlign.Start]
 * is the text's own start, so a number given [textDirectionOf]'s left to right would sit at the left of an Arabic layout.
 */
fun layoutAlign(
    align: String?,
    layout: LayoutDirection,
): TextAlign {
    val rtl = layout == LayoutDirection.Rtl
    return when (align) {
        "center" -> TextAlign.Center
        "end" -> if (rtl) TextAlign.Left else TextAlign.Right
        else -> if (rtl) TextAlign.Right else TextAlign.Left
    }
}

/**
 * [text] with each signed number ("-7°", "+3.5%") isolated left to right, when [text] runs right to left: in an
 * Arabic sentence the bidi algorithm put a lone sign on the far side of its number ("°7-"). An isolate keeps the
 * number whole without pulling the words beside it into its direction. Text without a right-to-left letter comes back
 * as it was.
 */
fun keepSigns(text: AnnotatedString): AnnotatedString {
    if (!hasRtl(text)) return text
    val numbers = signedNumbers(text)
    if (numbers.isEmpty()) return text
    val inserts = numbers.flatMap { listOf(it.first, it.last + 1) }
    val shifted = { offset: Int -> offset + inserts.count { it < offset } }
    val out = StringBuilder(text.length + inserts.size)
    val starts = numbers.map { it.first }.toSet()
    val ends = numbers.map { it.last + 1 }.toSet()
    for (i in 0..text.length) {
        if (i in ends) out.append(PDI)
        if (i in starts) out.append(LRI)
        if (i < text.length) out.append(text[i])
    }
    return AnnotatedString(
        out.toString(),
        text.spanStyles.map { AnnotatedString.Range(it.item, shifted(it.start), shifted(it.end)) },
        text.paragraphStyles.map { AnnotatedString.Range(it.item, shifted(it.start), shifted(it.end)) },
    )
}

private const val LRI = '\u2066'
private const val PDI = '\u2069'

/**
 * The signed numbers in [text], sign to last digit or unit (°, %): a minus or plus right before a digit and not after
 * a letter or digit, as the minus of "2-3" is.
 */
private fun signedNumbers(text: CharSequence): List<IntRange> {
    val found = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
        val sign = text[i] in "-+\u2212" && text.getOrNull(i + 1)?.isDigit() == true && text.getOrNull(i - 1)?.isLetterOrDigit() != true
        if (!sign) {
            i++
            continue
        }
        var end = i + 1
        while (end + 1 < text.length && (text[end + 1].isDigit() || text[end + 1] in ".,:" && text.getOrNull(end + 2)?.isDigit() == true)) end++
        if (end + 1 < text.length && text[end + 1] in "°%\u2030") end++
        found += i..end
        i = end + 1
    }
    return found
}

/**
 * A text field's style in a right-to-left layout: what's typed runs its own way, so a cursor after English text sits
 * after it, and it stays at the right as Android's own fields do. A left-to-right layout keeps [this] as it is.
 */
fun TextStyle.typedIn(layout: LayoutDirection): TextStyle =
    if (layout == LayoutDirection.Rtl) copy(textDirection = TextDirection.Content, textAlign = TextAlign.Right) else this
