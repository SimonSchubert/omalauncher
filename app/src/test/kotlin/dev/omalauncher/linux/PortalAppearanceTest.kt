package dev.omalauncher.linux

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PortalAppearanceTest {
    @Test fun serializesLightAndDarkWithNoAccessibilityPreference() {
        assertEquals("light\n#123456\n0\n0\n", PortalAppearance.text(true, 0xff123456.toInt()))
        assertEquals("dark\n#abcdef\n0\n0\n", PortalAppearance.text(false, 0xffabcdef.toInt()))
    }

    @Test fun ignoresAlphaWithoutLosingLeadingZeroes() {
        assertEquals("light\n#00000f\n0\n0\n", PortalAppearance.text(true, 0x1200000f))
    }

    @Test fun higherContrastAndDisabledAnimationsBecomePortalPreferences() {
        assertEquals(PortalAppearance.Preferences(true, true), PortalAppearance.preferences(0.5f, 0f))
        assertEquals(PortalAppearance.Preferences(true, true), PortalAppearance.preferences(1f, -0f))
    }

    @Test fun normalAndLowerContrastAreNotInventedHigherContrast() {
        for (contrast in listOf(0f, -0.5f, -1f)) {
            assertFalse(PortalAppearance.preferences(contrast, 1f).highContrast)
        }
    }

    @Test fun enabledAnimationsDoNotClaimReducedMotion() {
        for (scale in listOf(0.1f, 0.5f, 1f, 10f)) {
            assertFalse(PortalAppearance.preferences(0f, scale).reducedMotion)
        }
    }

    @Test fun missingOrInvalidAndroidValuesMeanNoPreference() {
        assertEquals(PortalAppearance.Preferences(), PortalAppearance.preferences(null, null))
        for (contrast in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.1f, -1.1f)) {
            assertFalse(PortalAppearance.preferences(contrast, null).highContrast)
        }
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertFalse(PortalAppearance.preferences(null, scale).reducedMotion)
        }
    }

    @Test fun preferencesAreIndependentAndUseExactUnsignedPortalValues() {
        assertEquals("dark\n#000000\n1\n0\n", PortalAppearance.text(false, 0, PortalAppearance.Preferences(true, false)))
        assertEquals("dark\n#000000\n0\n1\n", PortalAppearance.text(false, 0, PortalAppearance.Preferences(false, true)))
    }

    @Test fun writesAndReplacesTheCompleteSnapshot() {
        val dir = Files.createTempDirectory("portal-appearance-test").toFile()
        try {
            PortalAppearance.write(dir, true, 0xff102030.toInt(), PortalAppearance.Preferences(true, true))
            val snapshot = dir.resolve(PortalAppearance.FILE_NAME)
            assertEquals("light\n#102030\n1\n1\n", snapshot.readText())
            PortalAppearance.write(dir, false, 0xffabcdef.toInt())
            assertEquals("dark\n#abcdef\n0\n0\n", snapshot.readText())
            assertEquals(listOf(PortalAppearance.FILE_NAME), dir.listFiles()!!.map { it.name })
        } finally { dir.deleteRecursively() }
    }

    @Test fun replacesDestinationSymlinkWithoutWritingItsTarget() {
        val dir = Files.createTempDirectory("portal-appearance-test").toFile()
        val outside = Files.createTempFile("portal-appearance-target", ".txt")
        try {
            Files.writeString(outside, "unchanged")
            val snapshot = dir.resolve(PortalAppearance.FILE_NAME).toPath()
            Files.createSymbolicLink(snapshot, outside)
            PortalAppearance.write(dir, false, 0x010203, PortalAppearance.Preferences(true, true))
            assertFalse(Files.isSymbolicLink(snapshot))
            assertEquals("unchanged", Files.readString(outside))
            assertEquals("dark\n#010203\n1\n1\n", Files.readString(snapshot))
        } finally {
            dir.deleteRecursively()
            Files.deleteIfExists(outside)
        }
    }

    @Test fun failedRenameLeavesNoTemporarySnapshot() {
        val dir = Files.createTempDirectory("portal-appearance-test").toFile()
        try {
            dir.resolve(PortalAppearance.FILE_NAME).mkdir()
            dir.resolve(PortalAppearance.FILE_NAME).resolve("keep").writeText("unchanged")
            try {
                PortalAppearance.write(dir, true, 0)
                fail("must not replace a nonempty directory")
            } catch (_: java.io.IOException) { }
            assertEquals(listOf(PortalAppearance.FILE_NAME), dir.listFiles()!!.map { it.name })
            assertEquals("unchanged", dir.resolve(PortalAppearance.FILE_NAME).resolve("keep").readText())
        } finally { dir.deleteRecursively() }
    }
}
