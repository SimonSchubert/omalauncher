package dev.omalauncher.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTreeTest {
    private val loader = "/data/app/~~x==/dev.omalauncher-y==/lib/arm64/libproot-loader.so"

    private fun status(tracer: Int) = "Name:\tbash\nState:\tR (running)\nPPid:\t1\nTracerPid:\t$tracer\nUid:\t10227\n"

    @Test
    fun aGuestProcessWithoutATracerIsLeftOver() {
        assertTrue(untracedGuest(loader, status(0)))
    }

    @Test
    fun aGuestProcessItsProotStillTracesIsKept() {
        assertFalse(untracedGuest(loader, status(20550)))
    }

    @Test
    fun processesOutsideTheGuestAreKept() {
        // proot itself, and the launcher, are never traced
        assertFalse(untracedGuest("/data/app/~~x==/dev.omalauncher-y==/lib/arm64/libproot.so", status(0)))
        assertFalse(untracedGuest("/system/bin/app_process64", status(0)))
    }

    @Test
    fun anUnreadableProcessIsKept() {
        assertFalse(untracedGuest(null, status(0)))
        assertFalse(untracedGuest(loader, null))
        assertFalse(untracedGuest(loader, "Name:\tbash\n"))
    }
}
