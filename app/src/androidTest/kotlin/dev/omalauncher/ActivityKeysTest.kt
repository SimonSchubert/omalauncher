package dev.omalauncher

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Checks the real launcher's shortcuts and AndroidX's focused-view and Back fallback. */
class ActivityKeysTest {
    @Test fun shortcutsPreserveOrdinaryTypingAndBackDispatch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            lateinit var field: EditText
            var backCalls = 0
            instrumentation.runOnMainSync {
                field = EditText(activity)
                activity.setContentView(field)
                field.requestFocus()
                activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { backCalls++ }
                })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(field.hasFocus())
                val callback = checkNotNull(activity.window.callback)
                val time = SystemClock.uptimeMillis()
                assertTrue(callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)))
                callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0))
                assertEquals("a", field.text.toString())
                assertTrue(callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_META_ON)))
                callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.META_META_ON))
                assertEquals("A launcher shortcut must not type into the focused field", "a", field.text.toString())
                callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
                callback.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
                assertEquals("Back must reach AndroidX's dispatcher", 1, backCalls)
                assertFalse(activity.isFinishing)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
