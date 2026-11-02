package dev.omalauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

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
