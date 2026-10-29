package dev.omalauncher.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI text lives in res/values/strings_*.xml so it can be translated: this catches words written straight into a
 * text call. Text built only from data and glyphs ("✗ $it", "[ $label ]") starts with neither, so it passes.
 */
class HardcodedTextTest {
    private val call =
        Regex(
            """\b(BasicText|BracketAction|PrimaryButton|OverlayPanel|PanelTitle|Heading|TextDialog|ConfirmDialog)\(\s*"[A-Za-z]|""" +
                """\bmakeText\([^,]+,\s*"[A-Za-z]|""" +
                """\b(contentDescription|hint|placeholder|subtitle|confirmLabel)\s*=\s*"[A-Za-z]""",
        )

    @Test
    fun noHardcodedText() {
        val hits =
            File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                file.readLines().withIndex().filter { (_, line) -> call.containsMatchIn(line) }.map { (i, line) -> "${file.path}:${i + 1}: ${line.trim()}" }
            }.toList()
        assertTrue("move these into res/values/strings_*.xml:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
