package com.ggr.kinetrak.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.ggr.kinetrak.BridgeState
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

class SensorFusionHub(context: Context) : SensorEventListener {

    companion object {
        const val ZERO_ORDER_HOLD_TIMEOUT_MS = 300L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    @Volatile private var lastArCoreUpdateTime: Long = 0L
    @Volatile private var lastKnownPosition = floatArrayOf(0.0f, 0.0f, 0.0f)
    private val imuQuaternion = FloatArray(4)

    init {
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /**
     * Ingests ARCore Frame telemetry.
     * - When TRACKING: Updates currentPosition with camera translation, currentRotation with
     *   camera quaternion [qw, qx, qy, qz], marks isTrackingValid = true, and records timestamp.
     * - When tracking is lost: Retains last known position via 300ms Zero-Order Hold. Beyond 300ms,
     *   sets isTrackingValid = false.
     */
    fun onArCoreFrame(frame: Frame) {
        val now = SystemClock.elapsedRealtime()
        val camera = frame.camera

        if (camera.trackingState == TrackingState.TRACKING) {
            val pose = camera.pose
            val translation = pose.translation
            val x = translation[0]
            val y = translation[1]
            val z = translation[2]

            val qw = pose.qw()
            val qx = pose.qx()
            val qy = pose.qy()
            val qz = pose.qz()

            lastKnownPosition = floatArrayOf(x, y, z)
            BridgeState.currentPosition = lastKnownPosition
            BridgeState.currentRotation = floatArrayOf(qw, qx, qy, qz)
            BridgeState.isTrackingValid = true
            lastArCoreUpdateTime = now
        } else {
            val elapsed = now - lastArCoreUpdateTime
            if (elapsed <= ZERO_ORDER_HOLD_TIMEOUT_MS && lastArCoreUpdateTime > 0L) {
                // Zero-Order Hold within 300ms window: maintain last position and valid state
                BridgeState.currentPosition = lastKnownPosition
                BridgeState.isTrackingValid = true
            } else {
                // Tracking lost beyond 300ms hold window
                BridgeState.isTrackingValid = false
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            if (!BridgeState.isTrackingValid) {
                // Fallback: extract IMU rotation quaternion [qw, qx, qy, qz]
                SensorManager.getQuaternionFromVector(imuQuaternion, event.values)
                BridgeState.currentRotation = floatArrayOf(
                    imuQuaternion[0],
                    imuQuaternion[1],
                    imuQuaternion[2],
                    imuQuaternion[3]
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun release() {
        sensorManager?.unregisterListener(this)
    }
}
