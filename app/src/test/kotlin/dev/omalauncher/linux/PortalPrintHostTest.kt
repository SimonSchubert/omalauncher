package dev.omalauncher.linux

import org.junit.Assert.*
import org.junit.Test

class PortalPrintHostTest {
    @Test fun destructionInvalidatesDelayedPreparationAndReplacement() {
        val owner = PortalPrintHost<Any>()
        val original = Any()
        val replacement = Any()
        assertTrue(owner.attach(original))
        assertTrue(owner.destroyed(original))
        assertFalse(owner.owns(original))
        assertFalse(owner.attach(replacement))
        assertFalse(owner.owns(replacement))
        assertFalse(owner.destroyed(original))
    }

    @Test fun liveAdapterCannotBeReboundToAnotherActivity() {
        val owner = PortalPrintHost<Any>()
        val original = Any()
        assertTrue(owner.attach(original))
        assertFalse(owner.attach(Any()))
        assertTrue(owner.owns(original))
        assertTrue(owner.attach(original))
    }

    @Test fun unrelatedDestructionDoesNotInvalidateTheCurrentHost() {
        val owner = PortalPrintHost<Any>()
        val current = Any()
        assertTrue(owner.attach(current))
        assertFalse(owner.destroyed(Any()))
        assertTrue(owner.owns(current))
    }

    @Test fun finishedAdapterSurvivesHostReplacementWithoutAnotherSubmission() {
        val owner = PortalPrintHost<Any>()
        val original = Any()
        val replacement = Any()
        assertTrue(owner.attach(original))
        owner.adapterFinished()
        assertFalse(owner.destroyed(original))
        assertTrue(owner.attach(replacement))
        assertTrue(owner.owns(replacement))
        assertFalse(owner.destroyed(original))
        assertTrue(owner.owns(replacement))
    }

    @Test fun closeWinsOverPreparationAndHandoffCallbacks() {
        val owner = PortalPrintHost<Any>()
        val original = Any()
        assertTrue(owner.attach(original))
        owner.close()
        owner.adapterFinished()
        assertFalse(owner.owns(original))
        assertFalse(owner.attach(Any()))
        assertFalse(owner.destroyed(original))
    }
}
