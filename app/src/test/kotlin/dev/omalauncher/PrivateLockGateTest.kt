package dev.omalauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLockGateTest {
    @Test fun pendingLockBlocksAppsUntilAndroidConfirmsQuietMode() {
        val requested = PrivateLockGate().request(setOf(42))
        assertTrue(requested.blocks(42))
        // A scan may still see the old unlocked state while the system handles the request.
        assertTrue(requested.observedLocked(emptySet()).blocks(42))
        assertFalse(requested.observedLocked(setOf(42)).blocks(42))
    }

    @Test fun explicitFailureReleasesPendingLock() {
        assertFalse(PrivateLockGate().request(setOf(42)).failed(42).blocks(42))
    }

    @Test fun otherProfilesAreUnaffected() {
        val gate = PrivateLockGate().request(setOf(42, 43)).observedLocked(setOf(42))
        assertFalse(gate.blocks(42))
        assertTrue(gate.blocks(43))
        assertFalse(gate.blocks(null))
    }

    @Test fun immediateUnlockCanAcknowledgeLockBeforeCatalogScan() {
        val requested = PrivateLockGate().request(setOf(42))
        // The unlock path observes quiet=true and acknowledges it before asking Android to unlock.
        val acknowledged = requested.observedLocked(setOf(42))
        assertFalse(acknowledged.blocks(42))
        assertFalse(acknowledged.observedLocked(emptySet()).blocks(42))
    }
}
