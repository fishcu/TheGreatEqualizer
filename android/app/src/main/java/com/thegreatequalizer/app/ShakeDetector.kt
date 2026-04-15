package com.thegreatequalizer.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    companion object {
        private const val THRESHOLD = 15f          // m/s² delta magnitude
        private const val SHAKES_REQUIRED = 3
        private const val SHAKE_WINDOW_MS = 500L
        private const val COOLDOWN_MS = 1500L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasPrevious = false

    private val shakeTimestamps = mutableListOf<Long>()
    private var lastShakeTriggered = 0L

    fun start() {
        hasPrevious = false
        shakeTimestamps.clear()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!hasPrevious) {
            lastX = x; lastY = y; lastZ = z
            hasPrevious = true
            return
        }

        val dx = x - lastX
        val dy = y - lastY
        val dz = z - lastZ
        lastX = x; lastY = y; lastZ = z

        val magnitude = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        if (magnitude < THRESHOLD) return

        val now = System.currentTimeMillis()

        // Cooldown check
        if (now - lastShakeTriggered < COOLDOWN_MS) return

        // Record this shake event and prune old ones
        shakeTimestamps.add(now)
        shakeTimestamps.removeAll { now - it > SHAKE_WINDOW_MS }

        if (shakeTimestamps.size >= SHAKES_REQUIRED) {
            shakeTimestamps.clear()
            lastShakeTriggered = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
