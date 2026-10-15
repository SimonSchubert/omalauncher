package dev.omalauncher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ThemeDirsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dirs by lazy { ThemeDirs(File(tmp.root, "builtin"), File(tmp.root, "user"), File(tmp.root, "backgrounds")) }

    private fun write(
        path: String,
        text: String = "",
    ) = File(tmp.root, path).apply {
        parentFile.mkdirs()
        writeText(text)
    }

    private fun colors(background: String) = "background = \"$background\"\nforeground = \"#ffffff\"\n"

    @Test fun findsBuiltInAndUserThemes() {
        write("builtin/nord/colors.toml", colors("#2e3440"))
        write("user/mine/colors.toml", colors("#101010"))
        write("user/no-colors/README.md")
        write("user/Bad Name/colors.toml", colors("#000000"))
        assertEquals(listOf("mine", "nord"), dirs.scan().map { it.name })
    }

    @Test fun userFilesOverrideBuiltInOnes() {
        write("builtin/nord/colors.toml", colors("#2e3440"))
        write("builtin/nord/btop.theme", "builtin")
        write("builtin/nord/backgrounds/1.jpg")
        write("user/nord/colors.toml", colors("#123456"))
        write("user/nord/btop.theme", "user")
        write("user/nord/backgrounds/2.png")
        write("backgrounds/nord/0-extra.webp")
        val theme = dirs.load("nord")!!
        assertEquals("#123456", theme.colors["background"])
        assertEquals("user", theme.guestFiles().getValue("btop.theme").readText())
        assertEquals(listOf("0-extra.webp", "1.jpg", "2.png"), theme.backgrounds().map { it.name })
    }

    @Test fun onlyExtraBackgroundsAreRemovable() {
        write("builtin/nord/colors.toml", colors("#2e3440"))
        write("builtin/nord/backgrounds/1.jpg")
        write("user/nord/backgrounds/2.png")
        write("backgrounds/nord/0-extra.webp")
        val theme = dirs.load("nord")!!
        assertEquals(listOf("0-extra.webp"), theme.backgrounds().filter(theme::isExtraBackground).map { it.name })
    }

    @Test fun overrideWithoutColorsKeepsBuiltInColors() {
        write("builtin/nord/colors.toml", colors("#2e3440"))
        write("user/nord/backgrounds/2.png")
        assertEquals("#2e3440", dirs.load("nord")!!.colors["background"])
    }

    @Test fun themeFromARepoCantShipCode() {
        write("user/evil/colors.toml", colors("#000000"))
        write("user/evil/.git/HEAD")
        for (name in listOf("foot.ini", "alacritty.toml", "kitty.conf", "ghostty.conf", "vscode.json", "neovim.lua", "btop.theme")) write("user/evil/$name")
        val theme = dirs.load("evil")!!
        assertTrue(theme.fromRepo)
        assertEquals(setOf("btop.theme", "colors.toml"), theme.guestFiles().keys)
    }

    @Test fun ownThemeMayShipAnything() {
        write("user/mine/colors.toml", colors("#000000"))
        write("user/mine/foot.ini")
        val theme = dirs.load("mine")!!
        assertFalse(theme.fromRepo)
        assertTrue("foot.ini" in theme.guestFiles())
    }

    @Test fun symlinksAreSkipped() {
        val secret = write("secret.txt", "secret")
        write("user/mine/colors.toml", colors("#000000"))
        Files.createSymbolicLink(File(tmp.root, "user/mine/btop.theme").toPath(), secret.toPath())
        Files.createSymbolicLink(File(tmp.root, "user/linked").toPath(), File(tmp.root, "user/mine").toPath())
        assertTrue("btop.theme" !in dirs.load("mine")!!.guestFiles())
        assertNull(dirs.load("linked"))
    }

    @Test fun rejectsNamesThatClimbOut() {
        write("colors.toml", colors("#000000"))
        assertNull(dirs.load(".."))
    }

    @Test fun olderThemeGetsColorsFromAlacritty() {
        val normal = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white").joinToString("\n") { "$it = \"#102030\"" }
        write("user/old/alacritty.toml", "[colors.primary]\nbackground = \"#0a0b0c\"\n[colors.normal]\n$normal\n")
        write("user/old/.omalauncher-source", "https://github.com/a/omarchy-old-theme\n")
        val theme = dirs.load("old")!!
        assertEquals("#0a0b0c", theme.colors["background"])
        assertTrue("alacritty.toml" !in theme.guestFiles())
        assertEquals("github.com/a/omarchy-old-theme", ThemeDirs.sourceOf(theme)!!.label)
        assertTrue(dirs.hasColors("old"))
    }

    @Test fun previewFallsBackLikeOmarchy() {
        write("builtin/nord/colors.toml", colors("#2e3440"))
        write("builtin/nord/preview.png")
        write("user/nord/Preview.JPG")
        write("user/bare/colors.toml", colors("#000000"))
        write("user/bare/backgrounds/b.jpg")
        write("user/bare/backgrounds/a.jpg")
        assertEquals("Preview.JPG", dirs.load("nord")!!.preview.name)
        assertEquals("a.jpg", dirs.load("bare")!!.preview.name)
    }
}
