package dev.omalauncher.ui

import dev.omalauncher.widgets.Shortcut
import dev.omalauncher.widgets.WidgetItem
import dev.omalauncher.widgets.WidgetSpec
import dev.omalauncher.widgets.lang.OwlKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeEditTest {
    private fun spec(file: String, kind: OwlKind) = WidgetSpec(File(file), file.removeSuffix(".owl"), 1, 0, null, kind = kind)

    // A pinned owl app has no cell of its own to edit: its file is the app's, as the drawer's long-press menu finds it.
    @Test fun aPinnedOwlAppEditsItsFile() {
        val app = spec("hacker-news.owl", OwlKind.App)
        assertEquals(app, homeEditSpec(Shortcut("owl:hacker-news.owl", 1, "hacker news", owl = "hacker-news.owl"), listOf(app)))
    }

    @Test fun aWidgetEditsItsOwnFile() {
        val widget = spec("hacker-news.owl", OwlKind.Widget)
        assertEquals(widget, homeEditSpec(WidgetItem(widget), listOf(widget)))
    }

    // A drawer app's pin is an activity, and a Linux one a desktop entry: nothing to open in an agent.
    @Test fun aPinnedAndroidAppHasNothingToEdit() {
        assertNull(homeEditSpec(Shortcut("app:com.example", 1, "example"), emptyList()))
    }

    // Renamed or removed since it was pinned: the bar keeps to remove rather than open a file that is gone.
    @Test fun aPinnedOwlAppThatIsGoneHasNothingToEdit() {
        assertNull(homeEditSpec(Shortcut("owl:gone.owl", 1, "gone", owl = "gone.owl"), listOf(spec("other.owl", OwlKind.App))))
    }
}
