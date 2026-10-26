package dev.omadroid.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeRepoTest {
    @Test fun namesThemesLikeOmarchy() {
        for (url in listOf(
            "https://github.com/bjarneo/omarchy-ash-theme",
            "https://github.com/bjarneo/omarchy-ash-theme.git",
            "github.com/bjarneo/omarchy-ash-theme/",
            "git@github.com:bjarneo/omarchy-ash-theme.git",
            "  https://www.github.com/bjarneo/Omarchy-Ash-Theme  ",
        )) {
            val repo = ThemeRepo.parse(url)!!
            assertEquals(url, "ash", repo.themeName)
        }
        assertEquals("https://codeload.github.com/bjarneo/omarchy-ash-theme/tar.gz/HEAD", ThemeRepo.parse("https://github.com/bjarneo/omarchy-ash-theme")!!.tarball)
        assertEquals("black_arch", ThemeRepo.parse("https://github.com/ankur311sudo/black_arch")!!.themeName)
    }

    @Test fun refusesWhatIsntAGitHubRepo() {
        for (url in listOf(
            "https://gitlab.com/a/b",
            "https://github.com/a",
            "https://github.com/a/b/tree/main",
            "https://github.com/a/..",
            "https://github.com/a/-theme",
            "--upload-pack=x",
            "",
        )) assertNull(url, ThemeRepo.parse(url))
    }
}
