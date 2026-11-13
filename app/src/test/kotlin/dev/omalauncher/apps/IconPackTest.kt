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
}
