package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxNotificationsTest {
    @Test fun unescapesNewlinesAndBackslashes() {
        assertEquals("a\nb", LinuxNotifications.unescape("a\\nb"))
        assertEquals("C:\\path", LinuxNotifications.unescape("C:\\\\path"))
        assertEquals("plain", LinuxNotifications.unescape("plain"))
        assertEquals("trailing\\", LinuxNotifications.unescape("trailing\\"))
    }
}
