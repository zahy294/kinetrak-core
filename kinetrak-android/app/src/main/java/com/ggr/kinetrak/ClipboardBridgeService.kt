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
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class ClipboardBridgeService : Service(), SensorEventListener {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val seqCounter = AtomicInteger(1)
    private lateinit var clipboard: ClipboardManager
    private lateinit var sensorManager: SensorManager

    // Live orientation quaternion [qw, qx, qy, qz]
    @Volatile private var qw = 1.0f
    @Volatile private var qx = 0.0f
    @Volatile private var qy = 0.0f
    @Volatile private var qz = 0.0f

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Register hardware IMU rotation fusion
        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rotSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        startForegroundServiceNotification()
        startEmissionLoop()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val q = FloatArray(4)
            SensorManager.getQuaternionFromVector(q, event.values)
            // Android returns [qw, qx, qy, qz]
            qw = q[0]
            qx = q[1]
            qy = q[2]
            qz = q[3]
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
            var tickCounter = 0
            var latchTicksRemaining = 0
            var activeLatchedAction = "NULL"

            while (isActive) {
                val seq = seqCounter.getAndIncrement()
                tickCounter++
                /*
                if (tickCounter % 45 == 0 && latchTicksRemaining == 0) {
                    activeLatchedAction = "ACTION:TEST"
                    latchTicksRemaining = 7
                }
                */
                val actionToken = if (latchTicksRemaining > 0) {
                    latchTicksRemaining--
                    activeLatchedAction
                } else {
                    activeLatchedAction = "NULL"
                    BridgeState.pendingAction.getAndSet("NULL")
                }

                val gestureState = BridgeState.currentState.get()
                val trackingState = if (BridgeState.isTracking.get()) 1 else 0

                // Stream real quaternion orientation from phone hardware
                val payload = "KT|$seq|$trackingState|0.0|0.0|0.0|%.4f|%.4f|%.4f|%.4f|$gestureState|$actionToken"
                    .format(qw, qx, qy, qz)

                withContext(Dispatchers.Main) {
                    val clip = ClipData.newPlainText("kt_stream", payload).apply {
                        description.extras = PersistableBundle().apply {
                            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                        }
                    }
                    clipboard.setPrimaryClip(clip)
                }

                delay(66) // 15Hz decimation rate
            }
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}