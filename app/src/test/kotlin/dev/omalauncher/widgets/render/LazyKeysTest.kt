package dev.omalauncher.widgets.render

import org.junit.Assert.assertEquals
import org.junit.Test

class LazyKeysTest {
    private fun assertUnique(keys: List<String>) = assertEquals(keys.toString(), keys.size, keys.toSet().size)

    @Test fun keysAsGivenWhenTheyreUnique() {
        assertEquals(listOf("2:a", "2:b"), lazyKeys(2, listOf("a", "b")))
    }

    @Test fun repeatedKeysGetANumber() {
        assertEquals(listOf("0:a", "0:a#1", "0:a#2"), lazyKeys(0, listOf("a", "a", "a")))
    }

    @Test fun aKeyLikeAnAddedNumberStillDiffers() {
        // An element whose own key is what a repeat would be given, before and after it.
        assertUnique(lazyKeys(0, listOf("a", "a", "a#1")))
        assertUnique(lazyKeys(0, listOf("a#1", "a", "a", "a#1", "a#1#1", "a")))
        assertUnique(lazyKeys(0, List(500) { if (it % 3 == 0) "x" else "x#" + it % 7 }))
    }

    @Test fun slicesNeverShareAKey() {
        // A for at 1 and one at 11: "1:1:x" can't be "11:x"; a node listed as it is gets a plain "1", with no colon.
        val all = lazyKeys(1, listOf("1:x", "", "null")) + lazyKeys(11, listOf("x", "", "null")) + listOf("0", "2")
        assertUnique(all)
    }
}
