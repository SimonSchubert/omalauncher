package dev.omalauncher.widgets

import dev.omalauncher.linux.LinuxApp
import org.junit.Assert.assertEquals
import org.junit.Test

/** [pinnedShortcuts], which the strip above the bar lists when it shows picked apps only. */
class ShortcutsTest {
    /** A pinned Android app; its key is the launcher's `app:` form. */
    private fun android(key: String, folder: String? = null) = Shortcut(key, page = 1, label = key, folder = folder)

    /** A pinned Linux app: the bar opens Android packages, so it never lists. */
    private fun linux(key: String) = Shortcut(key, page = 1, label = key, linux = LinuxApp(key, key, false, key, null))

    private fun items(vararg shortcuts: Shortcut) = shortcuts.toList()

    /** The bar's own key for an app pinned as `app:pkg`. */
    private fun barKey(pkg: String) = "a:$pkg"

    @Test
    fun `keys are the bar's, last pinned first, and the count is capped`() {
        val pinned = items(android("app:one"), android("app:two"), android("app:three"))

        assertEquals(listOf(barKey("three"), barKey("two")), pinnedShortcuts(pinned, limit = 2))
    }

    /** A pinned Linux app has no package to open, so the bar can't show it. */
    @Test
    fun `a Linux app never lists`() {
        val pinned = items(android("app:com.a"), linux("linux:btop"))

        assertEquals(listOf(barKey("com.a")), pinnedShortcuts(pinned, limit = 20))
    }

    /** A folder is on the bar as its apps, the way its tile shows them. */
    @Test
    fun `a folder lists its apps`() {
        val pinned =
            items(
                android("app:one"),
                android("app:two", folder = "folder:1"),
                android("app:three", folder = "folder:1"),
            )

        assertEquals(listOf(barKey("three"), barKey("two"), barKey("one")), pinnedShortcuts(pinned, limit = 20))
    }

    /** Two apps in one call, one of them pinned twice, still lists once. */
    @Test
    fun `an app lists once`() {
        val pinned = items(android("app:one"), android("app:two"), android("app:one", folder = "folder:1"))

        assertEquals(listOf(barKey("one"), barKey("two")), pinnedShortcuts(pinned, limit = 20))
    }
}
