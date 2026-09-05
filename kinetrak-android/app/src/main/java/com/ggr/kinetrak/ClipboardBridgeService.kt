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
import android.os.Build
import android.os.IBinder
import android.os.PersistableBundle
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

class ClipboardBridgeService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val seqCounter = AtomicInteger(1)
    private lateinit var clipboard: ClipboardManager

    companion object {
        private const val TAG = "ClipboardBridgeService"
    }

    override fun onCreate() {
        super.onCreate()
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        startForegroundServiceNotification()
        startEmissionLoop()
    }

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

            while (isActive) {
                val seq = seqCounter.getAndIncrement()

                // Check for newly resolved actions and update 7-tick latch window (~500ms at 15Hz)
                val newPendingAction = BridgeState.pendingAction.getAndSet("NULL")
                if (newPendingAction != "NULL") {
                    activeLatchedAction = newPendingAction
                    latchTicksRemaining = 7
                }

                val activeAction = if (latchTicksRemaining > 0) {
                    latchTicksRemaining--
                    val action = activeLatchedAction
                    if (latchTicksRemaining == 0) {
                        activeLatchedAction = "NULL"
                    }
                    action
                } else {
                    activeLatchedAction = "NULL"
                    "NULL"
                }

                val pos = BridgeState.currentPosition
                val rot = BridgeState.currentRotation
                val state = if (BridgeState.isTrackingValid) 1 else 0

                val payload = "KT|$seq|$state|${pos.x}|${pos.y}|${pos.z}|${rot.w}|${rot.x}|${rot.y}|${rot.z}|${BridgeState.gestureState}|$activeAction"

                Log.i("KineTrak", "Emitting: $payload")

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
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}