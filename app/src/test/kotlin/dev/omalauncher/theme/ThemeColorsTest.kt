package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorsTest {
    private val alacritty =
        """
        [colors.primary]
        background = "#1a1b26"
        foreground = '0xC0CAF5' # a comment

        [colors.normal]
        black = "#15161e"
        red = "#f7768e"
        green = "#9ece6a"
        yellow = "#e0af68"
        blue = "#7aa2f7"
        magenta = "#bb9af7"
        cyan = "#7dcfff"
        white = "#a9b1d6"

        [colors]
        bright.red = "#ff0000"
        normal.red = "#000000"

        [colors.bright]
        black = "#414868"

        [[hints.enabled]]
        black = "#123456"
        """.trimIndent()

    @Test fun readsAlacrittyLikeOmarchy() {
        val c = ThemeColors.fromAlacritty(alacritty)!!
        assertEquals("#1a1b26", c["background"])
        assertEquals("#c0caf5", c["foreground"])
        assertEquals("#1a1b26", c["color0"])
        // The section form wins over a dotted key under [colors].
        assertEquals("#f7768e", c["color1"])
        assertEquals("#c0caf5", c["color7"])
        assertEquals("#414868", c["color8"])
        assertEquals("#ff0000", c["color9"])
        // A missing bright color falls back to the normal one.
        assertEquals("#9ece6a", c["color10"])
        assertEquals("#7aa2f7", c["accent"])
        assertEquals("#c0caf5", c["selection"])
        assertEquals("#f7768e", ThemeColors.resolve(c)["red"])
    }

    @Test fun themeTypeFollowsMode() {
        assertEquals("dark", ThemeColors.resolve(mapOf("background" to "#1a1b26"))["theme_type"])
        assertEquals("light", ThemeColors.resolve(mapOf("background" to "#1a1b26", "mode" to "light"))["theme_type"])
        // The older key alone still sets the mode.
        assertEquals("light", ThemeColors.resolve(mapOf("background" to "#1a1b26", "theme_type" to "light"))["mode"])
    }

    @Test fun needsAllNormalColors() {
        assertNull(ThemeColors.fromAlacritty("[colors.normal]\nblack = \"#000000\"\n"))
    }
}
