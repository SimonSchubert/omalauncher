package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class GuestLocaleTest {
    @Test fun languageAndRegionMakeTheGlibcName() {
        assertEquals("en_GB.UTF-8", GuestLocale.glibcName(Locale("en", "GB")))
        assertEquals("de_DE.UTF-8", GuestLocale.glibcName(Locale.GERMANY))
    }

    @Test fun noRegionOrANumericOneFallsBack() {
        assertNull(GuestLocale.glibcName(Locale("en")))
        assertNull(GuestLocale.glibcName(Locale("es", "419")))
    }

    @Test fun theScriptChecksTheShortNameLocaleListsPrints() {
        val s = GuestLocale.script("en_GB.UTF-8")
        assertTrue(s.contains("grep -qx 'en_GB.utf8'"))
        assertTrue(s.contains("grep -qx 'en_GB.UTF-8 UTF-8' /usr/share/i18n/SUPPORTED"))
    }
}
