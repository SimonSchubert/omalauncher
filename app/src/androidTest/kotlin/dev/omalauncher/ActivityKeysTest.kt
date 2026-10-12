package dev.omalauncher

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real public Window.Callback path and AndroidX's focused-view fallback. */
@RunWith(AndroidJUnit4::class)
class ActivityKeysTest {
    @Test fun shortcutsConsumeTheirKeysAndOrdinaryTypingReachesTheFocusedView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            lateinit var field: EditText
            instrumentation.runOnMainSync {
                field = EditText(activity)
                activity.setContentView(field)
                field.requestFocus()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(field.hasFocus())
                val callback = activity.window.callback
                val time = SystemClock.uptimeMillis()
                assertTrue(callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)))
                callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0))
                assertEquals("a", field.text.toString())
                assertTrue(callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_META_ON)))
                callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_META_ON))
                assertEquals("shortcut must not type into the focused field", "a", field.text.toString())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun completeApkExtractsItsNativeRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val libraries = java.io.File(context.applicationInfo.nativeLibraryDir)
        for (name in listOf("libproot.so", "libproot-loader.so", "libtalloc.so", "libandroid-shmem.so", "libwlbridge.so")) {
            assertTrue("APK must extract $name", java.io.File(libraries, name).isFile)
        }
    }
}
