package dev.omalauncher.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.omalauncher.linux.Proot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StaticWallpaperTest {
    @get:Rule val tmp = TemporaryFolder()

    private class MemorySharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, *> = data
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in data
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = this }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in changes) {
                    if (v === this) data.remove(k) else data[k] = v
                }
            }
        }
    }

    private class FakeContext(private val baseDir: File) : ContextWrapper(null) {
        val sharedPrefs = MemorySharedPreferences()
        private val appInfo = android.content.pm.ApplicationInfo().apply { nativeLibraryDir = File(baseDir, "lib").path }

        override fun getApplicationInfo(): android.content.pm.ApplicationInfo = appInfo
        override fun getFilesDir(): File = baseDir
        override fun getCacheDir(): File = File(baseDir, "cache")
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = sharedPrefs
    }

    @Test fun staticWallpaperDefaultsToTrueAndPersists() {
        val context = FakeContext(tmp.root)
        val proot = Proot(context)
        val store = ThemeStore(context, proot, isLinux = { false }, onConfigChanged = {})

        // Should default to true
        assertTrue(store.staticWallpaperEnabled)

        // Toggling off should update property and persist in SharedPreferences
        store.setStaticWallpaper(false)
        assertFalse(store.staticWallpaperEnabled)
        assertFalse(context.sharedPrefs.getBoolean("static_wallpaper", true))

        // Toggling on should update property and persist
        store.setStaticWallpaper(true)
        assertTrue(store.staticWallpaperEnabled)
        assertTrue(context.sharedPrefs.getBoolean("static_wallpaper", false))
    }

    @Test fun applyStaticWallpaperGracefullyHandlesNull() {
        val context = FakeContext(tmp.root)
        val proot = Proot(context)
        val store = ThemeStore(context, proot, isLinux = { false }, onConfigChanged = {})

        // Null background file should safely no-op without exceptions
        kotlinx.coroutines.runBlocking {
            store.applyStaticWallpaper(null)
        }
    }

    @Test fun applyStaticWallpaperGracefullyHandlesCorruptedFile() = kotlinx.coroutines.runBlocking {
        val context = FakeContext(tmp.root)
        val proot = Proot(context)
        val store = ThemeStore(context, proot, isLinux = { false }, onConfigChanged = {})

        val dummyFile = File(tmp.root, "corrupt.png").apply { writeText("not an image") }
        // Should catch decode errors and not crash
        store.applyStaticWallpaper(dummyFile)
    }
}
