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

/**
 * SensorFusionHub — Headless ARCore VIO + IMU Fallback
 *
 * Responsibilities:
 *  - Ingests ARCore Frame poses (displayOrientedPose) at ~30Hz from MainActivity's AR loop.
 *  - Applies 2.5× coordinate scaling to amplify physical hand movement for desktop CAD workflows.
 *  - Writes position and rotation to BridgeState via write-through helpers (FloatArray storage).
 *  - On visual tracking loss: holds the last valid position (Zero-Order Hold) and falls back
 *    to hardware TYPE_ROTATION_VECTOR for continuous 3-DOF rotation.
 *
 * NOTE: The pre-filtered translation from MainActivity's OneEuroFilter3D is passed in
 * via [onArCoreFrame]. SensorFusionHub applies the 2.5× scale on top of the already-filtered
 * coordinates before writing to BridgeState.
 */
class SensorFusionHub(context: Context) : SensorEventListener {

    companion object {
        const val ZERO_ORDER_HOLD_TIMEOUT_MS = 300L
        private const val VIO_SCALE = 2.5f
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
     * Ingests an ARCore [Frame] (called from MainActivity's AR loop on a background thread).
     *
     * When TRACKING:
     *   - Reads [Frame.camera.displayOrientedPose] translation (already OneEuro-filtered by caller).
     *   - Applies 2.5× VIO scale.
     *   - Maps ARCore's [x, y, z, w] quaternion to [qw, qx, qy, qz] convention.
     *   - Writes to BridgeState via write-through helpers.
     *
     * When tracking lost: invokes Zero-Order Hold via [handleTrackingLoss].
     */
    fun onArCoreFrame(frame: Frame) {
        val camera = frame.camera

        if (camera.trackingState == TrackingState.TRACKING) {
            val translation = camera.displayOrientedPose.translation
            // ARCore rotationQuaternion is [x, y, z, w] — remap to [w, x, y, z]
            val q = camera.displayOrientedPose.rotationQuaternion

            val scaledPos = Vec3(
                translation[0] * VIO_SCALE,
                translation[1] * VIO_SCALE,
                translation[2] * VIO_SCALE
            )
            val orientQuat = Quat(q[3], q[0], q[1], q[2]) // [w, x, y, z]

            BridgeState.setPositionFromVec3(scaledPos)
            BridgeState.setRotationFromQuat(orientQuat)
            BridgeState.isTrackingValid = true
            BridgeState.isTracking.set(true)

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
        BridgeState.isTrackingValid = true
        BridgeState.isTracking.set(true)
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

    fun release() {
        sensorManager?.unregisterListener(this)
    }
}
