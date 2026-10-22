package dev.omalauncher.linux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AurSecurityTest {
    @Test
    fun aurMetadataNeedsFourteenFullDaysToBecomeMature() {
        val now = 2_000_000_000_000L
        val fourteenDays = Aur.MINIMUM_AGE_DAYS * 24L * 60L * 60L * 1000L

        assertFalse(Aur.isMature(null, now))
        val boundary = (now - fourteenDays) / 1000L
        assertFalse(Aur.isMature(boundary + 1, now))
        assertTrue(Aur.isMature(boundary, now))
        assertFalse(Aur.isMature((now + fourteenDays) / 1000L, now))
    }

    private val install = File("src/main/assets/aur/install.sh").readText()
    private val yay = File("src/main/assets/aur/yay").readText()

    @Test fun wrapperRequiresExplicitLauncherApproval() {
        assertTrue(install.contains("/dev/omalauncher-aur-approval"))
        assertTrue(yay.contains("/dev/omalauncher-aur-approval"))
        assertFalse(install.contains("OMALAUNCHER_AUR_APPROVED"))
        assertFalse(yay.contains("OMALAUNCHER_AUR_APPROVED"))
    }

    @Test fun builderDoesNotReceiveBlanketRootSudo() {
        assertTrue(yay.contains("builder ALL=(root) NOPASSWD: /usr/bin/pacman"))
        assertFalse(yay.contains("NOPASSWD: ALL"))
    }

    @Test fun builderRunsWithCleanEnvironment() {
        assertTrue(yay.contains("env -i HOME=/home/builder"))
    }
}
