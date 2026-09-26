package com.golftracker.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The phone's orientation from its rotation-vector sensor: the full rotation (for turning camera
 * directions into compass directions) and the compass heading the phone is pointing.
 * Headings are from magnetic north; add the local declination for true north.
 */
class DeviceOrientation(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile
    private var rotation: FloatArray? = null

    private val _headingDegrees = MutableStateFlow<Float?>(null)
    val headingDegrees: StateFlow<Float?> = _headingDegrees.asStateFlow()

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    /** Row-major 3×3 rotation from phone axes to east/north/up, or null before the first reading. */
    fun rotationMatrix(): FloatArray? = rotation?.copyOf()

    override fun onSensorChanged(event: SensorEvent) {
        val r = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        rotation = r

        // Held flat, the phone points where its top edge points; held up, where the camera looks.
        val heading = if (r[8] > 0.7f) {
            Math.toDegrees(atan2(r[1].toDouble(), r[4].toDouble()))
        } else {
            Math.toDegrees(atan2(-r[2].toDouble(), -r[5].toDouble()))
        }
        // Smooth on the circle so the arrow doesn't jitter or spin across north.
        val previous = _headingDegrees.value
        _headingDegrees.value = if (previous == null) {
            normalize(heading).toFloat()
        } else {
            val p = Math.toRadians(previous.toDouble())
            val h = Math.toRadians(heading)
            val x = 0.8 * cos(p) + 0.2 * cos(h)
            val y = 0.8 * sin(p) + 0.2 * sin(h)
            normalize(Math.toDegrees(atan2(y, x))).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun normalize(d: Double) = ((d % 360) + 360) % 360
}
