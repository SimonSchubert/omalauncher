package dev.omalauncher.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPackTest {
    @Test fun readsAppfilterComponents() {
        assertEquals("com.android.chrome" to "com.google.android.apps.chrome.Main", IconPack.appfilterComponent("ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}"))
        // Short class names count from the package, as in the manifest.
        assertEquals("org.mozilla.firefox" to "org.mozilla.firefox.App", IconPack.appfilterComponent("ComponentInfo{org.mozilla.firefox/.App}"))
    }

    @Test fun skipsWhatIsNoComponent() {
        // Packs list placeholders like these for apps they haven't drawn yet.
        assertNull(IconPack.appfilterComponent("ComponentInfo{:LAUNCHER_ACTION_APP_DRAWER}"))
        assertNull(IconPack.appfilterComponent(""))
        assertNull(IconPack.appfilterComponent("ComponentInfo{com.example/}"))
    }

    @Test fun drawableXmlListsItemDrawables() {
        val xml =
            """
            <resources>
              <iconback img1="back" />
              <item drawable="settings" />
              <item drawable="" />
              <item component="ComponentInfo{a/b}" />
              <ITEM DRAWABLE="example" extra="1"></ITEM>
              <item drawable="settings" />
            </resources>
            """.trimIndent()
        assertEquals(listOf("settings", "example"), IconPack.drawableNames(xml))
    }

    @Test fun catalogUsesDrawableXmlAndFallsBackToAppfilter() {
        // A drawable.xml wins, and isn't merged with appfilter names.
        assertEquals(listOf("a", "b"), IconPack.catalogNames(listOf("b", "a", "b"), listOf("zzz")))
        // None listed: the appfilter's names, blanks and duplicates dropped.
        assertEquals(listOf("one", "two"), IconPack.catalogNames(emptyList(), listOf("two", "one", "two", " ")))
        assertEquals(listOf("kept"), IconPack.catalogNames(listOf(" ", "kept"), listOf("other")))
    }

    @Test fun filtersDrawableNamesBySubstringIgnoringCase() {
        val names = listOf("phone", "headphone", "Mail", "discord")
        assertEquals(names, IconPack.filterDrawableNames(names, ""))
        assertEquals(names, IconPack.filterDrawableNames(names, "  "))
        assertEquals(listOf("phone", "headphone"), IconPack.filterDrawableNames(names, "Pho"))
        assertEquals(listOf("discord"), IconPack.filterDrawableNames(names, "cord"))
        assertEquals(emptyList<String>(), IconPack.filterDrawableNames(names, "zzz"))
    }
}
