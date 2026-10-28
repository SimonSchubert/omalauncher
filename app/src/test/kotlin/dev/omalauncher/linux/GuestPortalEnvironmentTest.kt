package dev.omalauncher.linux

import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPortalEnvironmentTest {
    @Test fun guestEnablesGlibAsWellAsGtkPortalConsumers() {
        assertTrue(GuestPortal.ENV.contains("GTK_USE_PORTAL=1"))
        assertTrue(GuestPortal.ENV.contains("GIO_USE_PORTALS=1"))
    }
}
