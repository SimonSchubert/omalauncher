package dev.omalauncher.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.Window

/** Intercepts shortcuts before focused views, keeping AndroidX's normal keyboard/back dispatch intact. */
internal fun Activity.interceptKeys(handle: (KeyEvent) -> Boolean) {
    val original = checkNotNull(window.callback)
    window.callback = object : Window.Callback by original {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = handle(event) || original.dispatchKeyEvent(event)
    }
}
