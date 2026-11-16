package dev.omalauncher.widgets

import dev.omalauncher.widgets.lang.OwlKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedAppTest {
    private fun spec(file: String, kind: OwlKind) = WidgetSpec(File(file), file.removeSuffix(".owl"), 1, 0, null, kind = kind)

    // What home's edit bar edits, and what a pinned tile's icon and name come from.
    @Test fun aPinnedOwlAppFindsItsFile() {
        val app = spec("hacker-news.owl", OwlKind.App)
        assertEquals(app, listOf(spec("clock.owl", OwlKind.Widget), app).app("hacker-news.owl"))
    }

    // Renamed or removed since it was pinned: the bar keeps to remove rather than open a file that is gone.
    @Test fun aPinnedOwlAppThatIsGoneFindsNothing() {
        assertNull(listOf(spec("other.owl", OwlKind.App)).app("gone.owl"))
    }

    // Its first word changed to widget: it's a home tile now, not the app the shortcut opens.
    @Test fun aFileThatIsNoLongerAnAppFindsNothing() {
        assertNull(listOf(spec("hacker-news.owl", OwlKind.Widget)).app("hacker-news.owl"))
    }
}
