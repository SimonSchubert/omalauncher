package dev.omalauncher

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutKeyTest {
    @Test fun idsCantPassForAnotherProfile() {
        // Ids may hold any character, the separators too.
        assertNotEquals(androidShortcutKey(ShortcutRef("com.brave.browser", "10#site"), null), androidShortcutKey(ShortcutRef("com.brave.browser", "site"), 10))
        assertNotEquals(androidShortcutKey(ShortcutRef("com.brave.browser", "#site"), null), androidShortcutKey(ShortcutRef("com.brave.browser", "site"), null))
    }

    @Test fun startsWithItsPackage() {
        // So an update of the app reloads these icons along with its own.
        assertTrue(androidShortcutKey(ShortcutRef("com.brave.browser", "site"), 10).startsWith("com.brave.browser/"))
    }
}
