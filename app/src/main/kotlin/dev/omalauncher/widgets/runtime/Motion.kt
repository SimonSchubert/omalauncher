package dev.omalauncher.widgets.runtime

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlin.math.asin
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * How the phone is tilted, for `source m = android.motion`, as a tilt game or a spirit level needs it: the game rotation
 * vector (gyroscope and accelerometer together, smooth and without the compass), else gravity, else the bare
 * accelerometer. Every phone has one of them. Read only while a widget showing it is on screen, like every android
 * source, at the rate Android gives games; that rate needs no permission. Main thread only.
 */
class Motion(
    context: Context,
) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val display: Display? = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)

    private val sensor: Sensor? =
        manager?.let { m ->
            listOf(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER).firstNotNullOfOrNull { m.getDefaultSensor(it) }
        }

    /** android.motion: {available, x, y, z, pitch, roll}. */
    fun updates(): Flow<Value> {
        val manager = manager
        val sensor = sensor
        if (manager == null || sensor == null) return flowOf(value(false, 0.0, 0.0, 0.0))
        return callbackFlow {
            val matrix = FloatArray(9)
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val v = event.values
                        val rotation = display?.rotation ?: Surface.ROTATION_0
                        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                            // The matrix's last row is "up" in the phone's own axes.
                            SensorManager.getRotationMatrixFromVector(matrix, v)
                            trySend(tilt(matrix[6].toDouble(), matrix[7].toDouble(), matrix[8].toDouble(), rotation))
                        } else {
                            // Gravity and the accelerometer at rest point the other way from gravity: up, as above.
                            trySend(tilt(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), rotation))
                        }
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor,
                        accuracy: Int,
                    ) = Unit
                }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(Looper.getMainLooper()))
            awaitClose { manager.unregisterListener(listener) }
        }.conflate().distinctUntilChanged()
    }

    companion object {
        /**
         * "Up" in the phone's axes (x to its right edge, y to its top, z out of the screen, as Android's sensors give
         * it, any length) turned into the screen as it's shown, which [rotation] says: x is how far down the right
         * edge leans, y the bottom edge, both from -1 to 1 of gravity, so a ball on the screen rolls by them; z is 1
         * face up and -1 face down. pitch and roll are the same tilts in degrees.
         */
        fun tilt(
            upX: Double,
            upY: Double,
            upZ: Double,
            rotation: Int,
        ): Value {
            val length = sqrt(upX * upX + upY * upY + upZ * upZ)
            if (length < 1e-6) return value(true, 0.0, 0.0, 0.0)
            val ux = upX / length
            val uy = upY / length
            // The screen's own right and top, in the phone's axes, as the display is turned.
            val (sx, sy) =
                when (rotation) {
                    Surface.ROTATION_90 -> -uy to ux
                    Surface.ROTATION_180 -> -ux to -uy
                    Surface.ROTATION_270 -> uy to -ux
                    else -> ux to uy
                }
            return value(true, -sx, sy, upZ / length)
        }

        private fun value(
            available: Boolean,
            x: Double,
            y: Double,
            z: Double,
        ): Value {
            val cx = x.coerceIn(-1.0, 1.0)
            val cy = y.coerceIn(-1.0, 1.0)
            return Obj(
                mapOf(
                    "available" to bool(available),
                    "x" to Num(round(cx, 1000.0)),
                    "y" to Num(round(cy, 1000.0)),
                    "z" to Num(round(z.coerceIn(-1.0, 1.0), 1000.0)),
                    // Degrees: how far the right edge leans down, and the bottom edge.
                    "roll" to Num(round(Math.toDegrees(asin(cx)), 10.0)),
                    "pitch" to Num(round(Math.toDegrees(asin(cy)), 10.0)),
                ),
            )
        }

        /** Rounded, so a phone lying still doesn't redraw the widget on each tremble of the sensor. */
        private fun round(
            v: Double,
            steps: Double,
        ) = (v * steps).roundToLong() / steps + 0.0
    }
}
