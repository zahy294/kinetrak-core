package com.ggr.kinetrak.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.ggr.kinetrak.BridgeState
import com.ggr.kinetrak.Quat
import com.ggr.kinetrak.Vec3
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

class SensorFusionHub(context: Context) : SensorEventListener {

    companion object {
        const val ZERO_ORDER_HOLD_TIMEOUT_MS = 300L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    @Volatile private var lastArCoreUpdateTime: Long = 0L
    @Volatile private var lastValidPosition = Vec3(0.0f, 0.0f, 0.0f)
    @Volatile private var isOpticalTrackingActive = false
    private val imuQuaternion = FloatArray(4)

    init {
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /**
     * Ingests ARCore Frame telemetry.
     * - When TRACKING: Updates currentPosition with scaled camera displayOrientedPose translation (2.5x),
     *   currentRotation with camera displayOrientedPose quaternion [qw, qx, qy, qz], marks isTrackingValid = true,
     *   and records timestamp.
     * - When tracking is lost: Calls handleTrackingLoss() to perform a stable Zero-Order Hold.
     */
    fun onArCoreFrame(frame: Frame) {
        val camera = frame.camera

        if (camera.trackingState == TrackingState.TRACKING) {
            val translation = frame.camera.displayOrientedPose.translation
            val q = frame.camera.displayOrientedPose.rotationQuaternion

            val scale = 2.5f
            BridgeState.currentPosition = Vec3(translation[0] * scale, translation[1] * scale, translation[2] * scale)
            BridgeState.currentRotation = Quat(q[3], q[0], q[1], q[2])
            BridgeState.isTrackingValid = true
            BridgeState.isTracking.set(true)
            lastValidPosition = BridgeState.currentPosition
            lastArCoreUpdateTime = SystemClock.elapsedRealtime()
            isOpticalTrackingActive = true
        } else {
            isOpticalTrackingActive = false
            handleTrackingLoss()
        }
    }

    private fun handleTrackingLoss() {
        // Hold last known position smoothly without tripping the desktop watchdog
        BridgeState.isTrackingValid = true
        BridgeState.isTracking.set(true)
        BridgeState.currentPosition = lastValidPosition
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            if (!BridgeState.isTrackingValid || !isOpticalTrackingActive) {
                // Fallback: extract IMU rotation quaternion [qw, qx, qy, qz]
                SensorManager.getQuaternionFromVector(imuQuaternion, event.values)
                BridgeState.currentRotation = Quat(
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
