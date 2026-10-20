package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoThemeTest {
    /** Name to whether it's light, as the bundled themes are. */
    private val themes = mapOf("tokyo-night" to false, "catppuccin" to false, "catppuccin-latte" to true, "flexoki-light" to true, "nord" to false, "white" to true)

    private val following =
        Style()
            .with(Style.AUTO_THEME, true)
            .with(Style.LIGHT.theme, "white")
            .with(Style.LIGHT.background, "1.jpg")
            .with(Style.DARK.theme, "nord")
            .with(Style.THEME, "nord")

    @Test fun switchingShowsTheModesThemeAndBackground() {
        val light = AutoTheme.switched(following, dark = false)
        assertEquals("white", light[Style.THEME])
        assertEquals("1.jpg", light[Style.BACKGROUND])
        val dark = AutoTheme.switched(light, dark = true)
        assertEquals("nord", dark[Style.THEME])
        assertNull(dark[Style.BACKGROUND])
    }

    @Test fun nothingChangesWhenNotFollowing() {
        val off = following.with(Style.AUTO_THEME, false)
        assertEquals(off, AutoTheme.switched(off, dark = false))
        val picked = off.with(Style.THEME, "catppuccin")
        assertEquals(picked, AutoTheme.remembered(picked, dark = true))
    }

    @Test fun switchingAgainChangesNothing() {
        // What lets a restart, or a second flip to the same mode, leave a pick alone.
        val light = AutoTheme.switched(following, dark = false)
        assertEquals(light, AutoTheme.switched(light, dark = false))
        val picked = AutoTheme.remembered(light.with(Style.THEME, "catppuccin-latte"), dark = false)
        assertEquals(picked, AutoTheme.switched(picked, dark = false))
    }

    @Test fun turningOffForgetsTheModesOnlyWhenAsked() {
        val kept = AutoTheme.disabled(following, forget = false)
        assertEquals(false, kept[Style.AUTO_THEME])
        assertEquals("white", kept[Style.LIGHT.theme])
        val forgotten = AutoTheme.disabled(following, forget = true)
        assertNull(forgotten[Style.LIGHT.theme])
        assertNull(forgotten[Style.LIGHT.background])
        assertNull(forgotten[Style.DARK.theme])
        // Unset everywhere but the theme, as a style that never followed.
        assertEquals(Style().with(Style.THEME, "nord"), forgotten)
    }

    @Test fun aRemovedThemeLeavesTheModes() {
        val s = AutoTheme.forgotten(following, "white")
        assertNull(s[Style.LIGHT.theme])
        assertNull(s[Style.LIGHT.background])
        assertEquals("nord", s[Style.DARK.theme])
        assertEquals(following, AutoTheme.forgotten(following, "gruvbox"))
    }

    @Test fun aModeWithoutAThemeKeepsWhatShows() {
        val s = following.with(Style.LIGHT.theme, null).with(Style.BACKGROUND, "2.png")
        val light = AutoTheme.switched(s, dark = false)
        assertEquals("nord", light[Style.THEME])
        assertEquals("nord", light[Style.LIGHT.theme])
        assertEquals("2.png", light[Style.LIGHT.background])
    }

    @Test fun pickingWhileFollowingSetsTheModesTheme() {
        val picked = AutoTheme.remembered(following.with(Style.THEME, "catppuccin").with(Style.BACKGROUND, "3.jpg"), dark = true)
        assertEquals("catppuccin", picked[Style.DARK.theme])
        assertEquals("3.jpg", picked[Style.DARK.background])
        // The other mode is left alone.
        assertEquals("white", picked[Style.LIGHT.theme])
        // Unset is the default theme.
        assertEquals(ThemeStore.DEFAULT_THEME, AutoTheme.remembered(following.with(Style.THEME, null), dark = true)[Style.DARK.theme])
    }

    @Test fun turningOnGivesTheOtherModeAPartner() {
        val s = AutoTheme.enabled(Style().with(Style.THEME, "catppuccin"), dark = true, themes)
        assertTrue(s[Style.AUTO_THEME])
        assertEquals("catppuccin", s[Style.DARK.theme])
        assertEquals("catppuccin-latte", s[Style.LIGHT.theme])
        // One kept from before stays.
        val kept = AutoTheme.enabled(following.with(Style.AUTO_THEME, false).with(Style.THEME, "catppuccin"), dark = true, themes)
        assertEquals("white", kept[Style.LIGHT.theme])
        assertEquals("catppuccin", kept[Style.DARK.theme])
    }

    @Test fun partners() {
        assertEquals("catppuccin", AutoTheme.partner("catppuccin-latte", light = false, themes))
        assertEquals(AutoTheme.DEFAULT_LIGHT, AutoTheme.partner("nord", light = true, themes))
        assertEquals(ThemeStore.DEFAULT_THEME, AutoTheme.partner("flexoki-light", light = false, themes))
        // Already that mode: itself.
        assertEquals("white", AutoTheme.partner("white", light = true, themes))
    }

    @Test fun theSettingsAreInTheFile() {
        val s = following.with(Style.DARK.background, "4.jpg")
        val table = StyleFile.encode(emptyMap(), s)
        assertEquals("nord", table["auto-theme.dark"])
        assertEquals("1.jpg", table["auto-theme.light-background"])
        assertEquals(s, StyleFile.decode(Toml.parse(Toml.write(table))).style)
        assertTrue("[auto-theme]" in StyleFile.defaultsText())
    }
}
