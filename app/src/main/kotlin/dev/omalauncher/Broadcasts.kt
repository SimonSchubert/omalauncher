package dev.omalauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler

/** Registers [onReceive] for the system's broadcasts matching [filter], not other apps'; unregister what it returns. */
fun Context.registerNotExported(
    filter: IntentFilter,
    onReceive: (Intent) -> Unit,
): BroadcastReceiver {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context,
                intent: Intent,
            ) = onReceive(intent)
        }
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(receiver, filter)
    return receiver
}

/** A content observer that calls [changed] on [handler]'s thread for every change it's told of; register it with a content resolver. */
fun changeObserver(
    handler: Handler,
    changed: () -> Unit,
): ContentObserver =
    object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = changed()
    }
