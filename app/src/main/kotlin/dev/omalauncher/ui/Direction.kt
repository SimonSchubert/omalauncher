package dev.omalauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.omalauncher.system.AppLanguage

/**
 * [content] in the direction of the language the launcher's text is in: right to left in Arabic or Persian, whether
 * the phone's or picked in settings. Not the phone's own direction, which the manifest leaves off (no supportsRtl):
 * a phone in a right-to-left language the launcher has no text for shows English, left to right.
 *
 * Every screen's content starts in one; so does a menu or dialog opened from inside [LeftToRight].
 */
@Composable
fun LauncherDirection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides if (AppLanguage.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)
}

/**
 * [content] left to right in every language: what has sides of its own, like home's pages, a Linux window, code, or a
 * widget made left to right.
 */
@Composable
fun LeftToRight(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
