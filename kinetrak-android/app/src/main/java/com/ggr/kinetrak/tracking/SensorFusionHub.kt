package com.ggr.kinetrak.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.ggr.kinetrak.BridgeState
import com.ggr.kinetrak.Quat
import com.ggr.kinetrak.Vec3
import com.ggr.kinetrak.math.OneEuroFilter3D
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState

/**
 * SensorFusionHub — Headless ARCore VIO + IMU Fallback
 *
 * Responsibilities:
 *  - Ingests ARCore Frame poses (displayOrientedPose) at ~30Hz from MainActivity's AR loop.
 *  - Filters raw translation using OneEuroFilter3D to suppress high-frequency jitter.
 *  - Applies 2.5× coordinate scaling to amplify physical hand movement for desktop CAD workflows.
 *  - Writes position and rotation to BridgeState via write-through helpers (FloatArray storage).
 *  - Logs VIO position and rotation for Logcat verification.
 *  - On visual tracking loss: holds the last valid position (Zero-Order Hold) and falls back
 *    to hardware TYPE_ROTATION_VECTOR for continuous 3-DOF rotation.
 */
class SensorFusionHub(context: Context) : SensorEventListener {

    companion object {
        const val ZERO_ORDER_HOLD_TIMEOUT_MS = 300L
        private const val VIO_SCALE = 1.0f
        private const val TAG = "KineTrak"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val oneEuroFilter3D = OneEuroFilter3D(minCutoff = 1.2f, beta = 0.08f)

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
     * Ingests an ARCore [Frame] (called from MainActivity's AR loop on a background thread).
     *
     * When TRACKING:
     *   - Reads [Frame.camera.displayOrientedPose] raw translation array.
     *   - Passes the raw translation array into [OneEuroFilter3D.filter].
     *   - Applies 1.0× VIO scale factor (raw metric).
     *   - Maps ARCore's [x, y, z, w] quaternion to [qw, qx, qy, qz] convention.
     *   - Writes non-zero values into [BridgeState.currentPosition] via [BridgeState.setPositionFromVec3].
     *   - Writes rotation into [BridgeState.currentRotation] via [BridgeState.setRotationFromQuat].
     *   - Emits explicit Log.d("KineTrak", "VIO Pos: ${pos[0]}, ${pos[1]}, ${pos[2]} | Rot: ${rot[0]}, ${rot[1]}, ${rot[2]}, ${rot[3]}").
     *
     * When tracking lost: invokes Zero-Order Hold via [handleTrackingLoss].
     */
    fun onArCoreFrame(frame: Frame) {
        val camera = frame.camera

        if (camera.trackingState == TrackingState.TRACKING) {
            val rawTranslation = camera.displayOrientedPose.translation
            val timestampSec = if (frame.timestamp > 0L) {
                frame.timestamp / 1_000_000_000.0
            } else {
                System.nanoTime() / 1_000_000_000.0
            }

            val filteredTranslation = oneEuroFilter3D.filter(rawTranslation, timestampSec)

            val scaledPos = Vec3(
                filteredTranslation[0] * VIO_SCALE,
                filteredTranslation[1] * VIO_SCALE,
                filteredTranslation[2] * VIO_SCALE
            )
            // ARCore rotationQuaternion is [x, y, z, w] — remap to [w, x, y, z]
            val q = camera.displayOrientedPose.rotationQuaternion
            val orientQuat = Quat(q[3], q[0], q[1], q[2]) // [w, x, y, z]

            BridgeState.setPositionFromVec3(scaledPos)
            BridgeState.setRotationFromQuat(orientQuat)
            BridgeState.isTrackingValid = true
            BridgeState.isTracking.set(true)

            val pos = BridgeState.currentPosition
            val rot = BridgeState.currentRotation
            Log.d(TAG, "VIO Pos: ${pos[0]}, ${pos[1]}, ${pos[2]} | Rot: ${rot[0]}, ${rot[1]}, ${rot[2]}, ${rot[3]}")

            lastValidPosition = scaledPos
            lastArCoreUpdateTime = SystemClock.elapsedRealtime()
            isOpticalTrackingActive = true
        } else {
            isOpticalTrackingActive = false
            handleTrackingLoss()
        }
    }

    /**
     * Zero-Order Hold failover: retains last valid position and continues broadcasting,
     * preventing the desktop watchdog from snapping the model to the screen centre.
     */
    private fun handleTrackingLoss() {
        val elapsed = SystemClock.elapsedRealtime() - lastArCoreUpdateTime
        if (lastArCoreUpdateTime > 0L && elapsed <= ZERO_ORDER_HOLD_TIMEOUT_MS) {
            BridgeState.isTrackingValid = true
            BridgeState.isTracking.set(true)
        } else {
            BridgeState.isTrackingValid = false
            BridgeState.isTracking.set(false)
        }
        BridgeState.setPositionFromVec3(lastValidPosition)
    }

    /**
     * Hardware IMU fallback for 3-DOF rotation when optical tracking is lost.
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            if (!BridgeState.isTrackingValid || !isOpticalTrackingActive) {
                SensorManager.getQuaternionFromVector(imuQuaternion, event.values)
                // Android returns [qw, qx, qy, qz]
                BridgeState.setRotationFromQuat(
                    Quat(imuQuaternion[0], imuQuaternion[1], imuQuaternion[2], imuQuaternion[3])
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun resetFilter() {
        oneEuroFilter3D.reset()
    }

    fun release() {
        sensorManager?.unregisterListener(this)
    }
}
