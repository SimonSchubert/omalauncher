package dev.omalauncher.widgets.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool

/**
 * android.battery, and `omalauncher battery`: Android hides the battery from the guest (/sys/class/power_supply), so
 * it's exposed here.
 */
fun battery(context: Context): Value {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    // Some builds report no scale (0); dividing by it would throw inside the battery receiver.
    val scale = (intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).takeIf { it > 0 } ?: 100
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    fun extra(name: String) = intent?.getIntExtra(name, -1)?.takeIf { it >= 0 }
    val health =
        when (extra(BatteryManager.EXTRA_HEALTH)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failing"
            else -> null
        }
    val plugged =
        when (extra(BatteryManager.EXTRA_PLUGGED)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
            else -> null
        }
    return Obj(
        mapOf(
            "level" to Num(if (level >= 0) (level * 100 / scale).toDouble() else 0.0),
            "charging" to bool(charging),
            // Plugged in but not charging: full, or held below full to spare the battery.
            "status" to Str(if (charging) "charging" else if (plugged != null) "plugged in" else "on battery"),
            // Reported in tenths of a degree and in millivolts.
            "temp" to (extra(BatteryManager.EXTRA_TEMPERATURE)?.let { Num(it / 10.0) } ?: Null),
            "voltage" to (extra(BatteryManager.EXTRA_VOLTAGE)?.let { Num(it / 1000.0) } ?: Null),
            "health" to (health?.let(::Str) ?: Null),
            "plugged" to (plugged?.let(::Str) ?: Null),
            "technology" to (intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() }?.let(::Str) ?: Null),
            "cycles" to ((if (Build.VERSION.SDK_INT >= 34) extra(BatteryManager.EXTRA_CYCLE_COUNT) else null)?.let { Num(it.toDouble()) } ?: Null),
        ),
    )
}
