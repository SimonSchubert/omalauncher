package dev.omalauncher

import org.junit.Assert.*
import org.junit.Test

class AndroidProfileTest {
    @Test fun removedProfileNeverTargetsAReusedUserId() {
        assertTrue(sameAppProfile(10, 10))
        assertFalse(sameAppProfile(10, 11))
        assertFalse(sameAppProfile(10, -1))
    }

    @Test fun personalAppsRequireAnExistingOwnerProfile() {
        assertTrue(sameAppProfile(null, 0))
        assertTrue(sameAppProfile(null, 20))
        assertFalse(sameAppProfile(null, -1))
    }
}
