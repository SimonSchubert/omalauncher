package dev.omalauncher.widgets

import android.os.Parcel
import android.os.UserHandle
import dev.omalauncher.AndroidShortcut
import dev.omalauncher.ShortcutRef
import dev.omalauncher.linux.LinuxApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShortcutsTest {
    private var enabled = true
    private val profiles = HomeProfiles(FakePrefs(), WidgetLayout(FakePrefs()), WidgetLayout(FakePrefs(), 12, heal = true)) { enabled }
    private val shortcuts = Shortcuts(FakePrefs(), profiles)

    private fun app(id: String) = LinuxApp(id, id, false, id, null)

    /** Placed in both layouts and hidden in the open one, as a removal must undo. */
    private fun placeEverywhere(key: String) {
        profiles.folded.set(key, Cell(0, 0, 1, 1))
        profiles.folded.setPage(key, 2)
        profiles.open.set(key, Cell(6, 0, 1, 1))
        profiles.setOpenPage(key, 1)
        profiles.hide(key, Profile.Open)
    }

    private fun assertForgotten(key: String) {
        for (l in profiles.layouts) {
            assertNull(l.cell(key))
            assertNull(l.page(key))
        }
        assertFalse(key in profiles.hiddenKeys(Profile.Open))
    }

    @Test fun groupingWhileOpenKeepsFoldedCells() {
        shortcuts.addLinux(app("a"), 1)
        shortcuts.addLinux(app("b"), 1)
        profiles.folded.set("linux:a", Cell(0, 0, 1, 1))
        profiles.folded.set("linux:b", Cell(1, 0, 1, 1))
        profiles.open.set("linux:a", Cell(6, 0, 1, 1))
        profiles.open.set("linux:b", Cell(7, 0, 1, 1))
        profiles.active = Profile.Open
        val a = shortcuts.items.first { it.key == "linux:a" }
        val b = shortcuts.items.first { it.key == "linux:b" }
        val folder = shortcuts.group(b, a)!!
        assertEquals(Cell(0, 0, 1, 1), profiles.folded.cell(folder))
        assertEquals(Cell(6, 0, 1, 1), profiles.open.cell(folder))
        for (l in profiles.layouts) {
            assertNull(l.cell("linux:a"))
            assertNull(l.cell("linux:b"))
        }
        assertEquals(2, shortcuts.onGrid().filterIsInstance<Folder>().single().apps.size)
    }

    @Test fun removingAFolderForgetsItsAppsEverywhere() {
        shortcuts.addLinux(app("a"), 1)
        shortcuts.addLinux(app("b"), 1)
        val folder = shortcuts.group(shortcuts.items.first { it.key == "linux:b" }, shortcuts.items.first { it.key == "linux:a" })!!
        profiles.hide(folder, Profile.Open)
        shortcuts.remove(folder)
        assertTrue(shortcuts.onGrid().isEmpty())
        assertEquals(emptyList<String>(), profiles.hiddenKeys(Profile.Open))
    }

    @Test fun knowsWhatIsPinned() {
        shortcuts.addLinux(app("a"), 1)
        assertTrue(shortcuts.has(Shortcuts.linuxKey(app("a"))))
        assertFalse(shortcuts.has(Shortcuts.linuxKey(app("b"))))
    }

    @Test fun anAppDraggedOutOfAFolderWhileOpenLeavesIt() {
        shortcuts.addLinux(app("a"), 3)
        shortcuts.addLinux(app("b"), 3)
        profiles.folded.set("linux:a", Cell(2, 2, 1, 1))
        profiles.open.set("linux:a", Cell(9, 2, 1, 1))
        profiles.setOpenPage("linux:a", 2)
        profiles.active = Profile.Open
        val folder = shortcuts.group(shortcuts.items.first { it.key == "linux:b" }, shortcuts.items.first { it.key == "linux:a" })!!
        profiles.open.set("linux:b", Cell(0, 5, 1, 1))
        shortcuts.moveOpen("linux:b", 1)
        val b = shortcuts.items.first { it.key == "linux:b" }
        assertNull(b.folder)
        assertEquals(3, b.page)
        assertEquals(1, profiles.open.page("linux:b"))
        // The folder, down to one app, dissolved into it, which took its place in both profiles.
        assertTrue(shortcuts.onGrid().none { it is Folder })
        assertNull(shortcuts.items.first { it.key == "linux:a" }.folder)
        assertEquals(Cell(2, 2, 1, 1), profiles.folded.cell("linux:a"))
        assertEquals(Cell(9, 2, 1, 1), profiles.open.cell("linux:a"))
        assertEquals(2, profiles.open.page("linux:a"))
        for (l in profiles.layouts) {
            assertNull(l.cell(folder))
            assertNull(l.page(folder))
        }
    }

    @Test fun movingAnAppOnTheGridWhileOpenOnlyMovesItOpen() {
        shortcuts.addLinux(app("a"), 3)
        profiles.active = Profile.Open
        shortcuts.moveOpen("linux:a", 5)
        assertEquals(3, shortcuts.items.single().page)
        assertEquals(5, profiles.open.page("linux:a"))
    }

    @Test fun theAppLeftInAFolderKeepsACellOfItsOwn() {
        shortcuts.addLinux(app("a"), 1)
        shortcuts.addLinux(app("b"), 1)
        profiles.folded.set("linux:a", Cell(0, 0, 1, 1))
        val folder = shortcuts.group(shortcuts.items.first { it.key == "linux:b" }, shortcuts.items.first { it.key == "linux:a" })!!
        profiles.folded.set("linux:a", Cell(4, 4, 1, 1))
        shortcuts.move("linux:b", 1)
        assertEquals(Cell(4, 4, 1, 1), profiles.folded.cell("linux:a"))
        assertNull(profiles.folded.cell(folder))
    }

    @Test fun removingAnOwlAppForgetsItInBothLayouts() {
        val spec = WidgetSpec(File("notes.owl"), "notes", 1, 0, null)
        shortcuts.addOwl(spec, 2)
        val key = Shortcuts.owlKey(spec)
        placeEverywhere(key)
        shortcuts.removeOwl(spec)
        assertFalse(shortcuts.has(key))
        assertForgotten(key)
    }

    @Test fun removingAnAndroidShortcutForgetsItInBothLayouts() {
        val shortcut = AndroidShortcut("chat", ShortcutRef("com.example", "chat-1"), UserHandle(Parcel.obtain()))
        shortcuts.addAndroidShortcut(shortcut, 2)
        val key = Shortcuts.shortcutKey(shortcut)
        placeEverywhere(key)
        shortcuts.removeAndroidShortcut(shortcut)
        assertFalse(shortcuts.has(key))
        assertForgotten(key)
    }

    @Test fun removingForgetsInBothLayoutsWhileThereIsOneHome() {
        shortcuts.addLinux(app("a"), 1)
        placeEverywhere("linux:a")
        enabled = false
        shortcuts.remove("linux:a")
        enabled = true
        assertForgotten("linux:a")
    }
}
