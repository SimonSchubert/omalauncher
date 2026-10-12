package dev.omalauncher.widgets.runtime

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The next alarm the user set in any clock app, for `source a = android.alarm`. Needs no permission. */
class Alarm(
    private val context: Context,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun value(): Value {
        val next = alarms.nextAlarmClock
        return Obj(
            mapOf(
                "set" to bool(next != null),
                // Unix seconds, like clock, so fmtTime and `at` take it as is.
                "time" to (next?.let { Num(Math.floor(it.triggerTime / 1000.0)) } ?: Null),
                // The clock app that set it, when it says; androidApp(a.app) opens it.
                "app" to (next?.showIntent?.creatorPackage?.let(::Str) ?: Null),
            ),
        )
    }

    /** Emits whenever an alarm is set, changed, dismissed or goes off. */
    fun updates(): Flow<Value> =
        callbackFlow {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        c: Context,
                        intent: Intent,
                    ) {
                        trySend(Unit)
                    }
                }
            val filter = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
            trySend(Unit)
            awaitClose { context.unregisterReceiver(receiver) }
        }.conflate().map { value() }.distinctUntilChanged()
}
