package dev.omalauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowOwnersTest {
    private val owners = WindowOwners<String, String>()

    @Test fun theOnlyActivityOwnsItsWindow() {
        owners.take("terminal", "old")
        assertTrue(owners.release("terminal", "old"))
    }

    @Test fun anOlderActivityLeavingKeepsTheWindowForTheNewer() {
        owners.take("terminal", "old")
        owners.take("terminal", "new")
        assertFalse(owners.release("terminal", "old"))
        assertTrue(owners.release("terminal", "new"))
    }

    @Test fun aWindowIsReleasedOnce() {
        owners.take("terminal", "old")
        assertTrue(owners.release("terminal", "old"))
        assertFalse(owners.release("terminal", "old"))
    }

    @Test fun windowsHaveOwnersOfTheirOwn() {
        owners.take("terminal", "a")
        owners.take("btop", "b")
        assertFalse(owners.release("btop", "a"))
        assertTrue(owners.release("terminal", "a"))
    }
}
