package dev.omalauncher.system

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppLanguageTest {
    @Test fun arabicAndPersianKeepLatinDigits() {
        assertEquals("fa-IR-u-nu-latn", AppLanguage.latinDigits(Locale.forLanguageTag("fa-IR")).toLanguageTag())
        assertEquals("ar-u-nu-latn", AppLanguage.latinDigits(Locale.forLanguageTag("ar")).toLanguageTag())
        // The numbers the launcher writes into versions, sizes and file names.
        assertEquals("1.5 GB 07", String.format(AppLanguage.latinDigits(Locale.forLanguageTag("fa-IR")), "%.1f GB %02d", 1.5, 7))
        assertEquals("12", String.format(AppLanguage.latinDigits(Locale.forLanguageTag("ar-EG")), "%d", 12))
    }

    @Test fun otherLanguagesAndAStatedChoiceStayAsTheyAre() {
        val german = Locale.GERMANY
        assertSame(german, AppLanguage.latinDigits(german))
        val arab = Locale.forLanguageTag("ar-EG-u-nu-arab")
        assertSame(arab, AppLanguage.latinDigits(arab))
    }
}
