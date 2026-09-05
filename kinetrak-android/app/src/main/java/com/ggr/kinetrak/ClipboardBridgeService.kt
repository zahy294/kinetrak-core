package com.ggr.kinetrak

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class ClipboardBridgeService : Service(), SensorEventListener {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val seqCounter = AtomicInteger(1)
    private lateinit var clipboard: ClipboardManager
    private lateinit var sensorManager: SensorManager
    private lateinit var snapdragonNPU: SnapdragonNPU

    // Live orientation quaternion [qw, qx, qy, qz]
    @Volatile private var qw = 1.0f
    @Volatile private var qx = 0.0f
    @Volatile private var qy = 0.0f
    @Volatile private var qz = 0.0f

    // Live IMU sensor values (3-axis accel + 3-axis gyro)
    @Volatile private var ax = 0.0f
    @Volatile private var ay = 0.0f
    @Volatile private var az = 0.0f
    @Volatile private var gx = 0.0f
    @Volatile private var gy = 0.0f
    @Volatile private var gz = 0.0f

    companion object {
        private const val TAG = "ClipboardBridgeService"
    }

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Instantiate Snapdragon NPU and load quantized model
        snapdragonNPU = SnapdragonNPU(application)
        scope.launch(Dispatchers.Default) {
            val initialized = snapdragonNPU.initModelFromAssets("gesture_model_quantized.dlc")
            if (initialized) {
                Log.i(TAG, "Snapdragon NPU loaded gesture_model_quantized.dlc successfully")
            } else {
                Log.e(TAG, "Failed to load gesture_model_quantized.dlc on Snapdragon NPU")
            }
        }

        // Register hardware IMU rotation fusion
        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Register 3-axis accelerometer
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Register 3-axis gyroscope
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        startForegroundServiceNotification()
        startEmissionLoop()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val q = FloatArray(4)
                SensorManager.getQuaternionFromVector(q, event.values)
                // Android returns [qw, qx, qy, qz]
                qw = q[0]
                qx = q[1]
                qy = q[2]
                qz = q[3]
                BridgeState.currentRotation[0] = qw
                BridgeState.currentRotation[1] = qx
                BridgeState.currentRotation[2] = qy
                BridgeState.currentRotation[3] = qz
            }
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]
                ay = event.values[1]
                az = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]
                gy = event.values[1]
                gz = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startForegroundServiceNotification() {
        val channelId = "kinetrak_stream_v2"
        val channel = NotificationChannel(
            channelId,
            "KineTrak Telemetry",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("KineTrak Active")
            .setContentText("Streaming 15Hz spatial bridge to Office Kit")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1001, notification)
        }
    }

    private fun startEmissionLoop() {
        scope.launch {
            var latchTicksRemaining = 0
            var activeLatchedAction = "NULL"

            // 45-frame motion buffer matching gesture_model_quantized.dlc tensor dimension (shape [1, 45])
            val motionBuffer = FloatArray(45)
            var bufferedFrames = 0

            while (isActive) {
                val seq = seqCounter.getAndIncrement()
                BridgeState.currentSeq.set(seq)
                val currentState = BridgeState.currentState
                val gestureState = BridgeState.gestureState

                // State Machine: buffer IMU frames during RECORDING
                if (currentState == BridgeState.STATE_RECORDING || gestureState == "RECORDING") {
                    val accelMagnitude = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
                    motionBuffer[bufferedFrames] = accelMagnitude
                    bufferedFrames++

                    if (bufferedFrames >= 45) {
                        // Switch to THINKING and classify gesture
                        BridgeState.currentState = BridgeState.STATE_THINKING
                        BridgeState.gestureState = "THINKING"
                        val bufferSnapshot = motionBuffer.copyOf()
                        bufferedFrames = 0

                        scope.launch(Dispatchers.Default) {
                            if (BridgeState.isProcessing.compareAndSet(false, true)) {
                                try {
                                    val classIdx = snapdragonNPU.classifyGesture(bufferSnapshot)
                                    val resolvedAction = when (classIdx) {
                                        0 -> "ACTION:SPAWN"
                                        1 -> "ACTION:SELECT"
                                        2 -> "ACTION:DELETE"
                                        3 -> "ACTION:RESET"
                                        else -> "NULL"
                                    }
                                    if (resolvedAction != "NULL") {
                                        BridgeState.pendingAction.set(resolvedAction)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Gesture classification failed", e)
                                } finally {
                                    BridgeState.currentState = BridgeState.STATE_IDLE
                                    BridgeState.gestureState = "IDLE"
                                    BridgeState.isProcessing.set(false)
                                }
                            }
                        }
                    }
                } else if (currentState != BridgeState.STATE_THINKING && gestureState != "THINKING") {
                    bufferedFrames = 0
                }

                // Check for newly resolved actions and update 7-tick latch window (~500ms at 15Hz)
                val newPendingAction = BridgeState.pendingAction.get()
                if (newPendingAction != "NULL" && activeLatchedAction == "NULL") {
                    activeLatchedAction = newPendingAction
                    latchTicksRemaining = 7
                }

                val actionToken = if (latchTicksRemaining > 0) {
                    latchTicksRemaining--
                    val action = activeLatchedAction
                    if (latchTicksRemaining == 0) {
                        activeLatchedAction = "NULL"
                        BridgeState.pendingAction.set("NULL")
                        BridgeState.currentState = BridgeState.STATE_IDLE
                        BridgeState.gestureState = "IDLE"
                    }
                    action
                } else {
                    activeLatchedAction = "NULL"
                    BridgeState.pendingAction.set("NULL")
                    "NULL"
                }

                val curGestureState = BridgeState.gestureState
                val trackingState = if (BridgeState.isTrackingValid) 1 else 0

                // Emission Gating: Only emit 12-field telemetry if tracking/streaming is active
                if (BridgeState.isStreamingActive) {
                    val payload = String.format(
                        Locale.US,
                        "KT|%d|%d|%.4f|%.4f|%.4f|%.4f|%.4f|%.4f|%.4f|%s|%s",
                        seq,
                        trackingState,
                        BridgeState.currentPosition[0],
                        BridgeState.currentPosition[1],
                        BridgeState.currentPosition[2],
                        BridgeState.currentRotation[0],
                        BridgeState.currentRotation[1],
                        BridgeState.currentRotation[2],
                        BridgeState.currentRotation[3],
                        curGestureState,
                        actionToken
                    )

                    Log.i("KineTrak", "Emitting: $payload")

                    withContext(Dispatchers.Main) {
                        val clip = ClipData.newPlainText("kt_stream", payload).apply {
                            description.extras = PersistableBundle().apply {
                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                            }
                        }
                        clipboard.setPrimaryClip(clip)
                    }
                }

                delay(66) // 15Hz decimation rate
            }
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        snapdragonNPU.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}