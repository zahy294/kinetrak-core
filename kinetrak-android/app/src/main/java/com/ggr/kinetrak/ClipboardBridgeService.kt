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
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * ClipboardBridgeService — Strict 15Hz Telemetry Serialization Service
 *
 * Architecture (Dev 1):
 *  - No SensorEventListener — sensor data is produced exclusively by SensorFusionHub.
 *  - Reads atomic position/rotation/state directly from BridgeState.
 *  - Enforces a hard 66ms (~15Hz) emission cycle to prevent Vivo Office Kit driver saturation.
 *  - Maintains a 7-tick (~500ms @ 15Hz) action latch window before resetting action to "NULL".
 *
 * Emission Gate (Dev 2):
 *  - Clipboard writes are gated behind BridgeState.isStreamingActive — no data is emitted
 *    until the user presses "START TRACKING" in the UI.
 *
 * Android 14+ Compliance:
 *  - Passes ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC to startForeground() on API ≥ 29.
 *
 * Wire format (12 pipe-delimited fields):
 *  KT|<seq>|<state>|<pos_x>|<pos_y>|<pos_z>|<qw>|<qx>|<qy>|<qz>|<gesture_state>|<action>
 */
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
            .setContentText("Streaming 6-DOF telemetry at 15Hz")
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
                val loopStart = System.currentTimeMillis()
                val seq = seqCounter.getAndIncrement()
                BridgeState.currentSeq.set(seq)

                // ── 7-tick action latch window (~500ms @ 15Hz) ────────────────────────
                // Consume the pending action atomically on the first tick it appears.
                val newPendingAction = BridgeState.pendingAction.get()
                if (newPendingAction != "NULL" && activeLatchedAction == "NULL") {
                    activeLatchedAction = newPendingAction
                    latchTicksRemaining = 7
                    BridgeState.pendingAction.set("NULL")
                }

                val actionToken = if (latchTicksRemaining > 0) {
                    latchTicksRemaining--
                    val token = activeLatchedAction
                    if (latchTicksRemaining == 0) {
                        activeLatchedAction = "NULL"
                    }
                    token
                } else {
                    activeLatchedAction = "NULL"
                    "NULL"
                }

                // ── Telemetry assembly ────────────────────────────────────────────────
                val trackingState = if (BridgeState.isTrackingValid) 1 else 0
                val gestureStateStr = BridgeState.gestureState  // computed from integer currentState

                // ── Emission Gate (Dev 2): only emit while user has enabled streaming ──
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
                        gestureStateStr,
                        actionToken
                    )

                    Log.i("KineTrak", "Emitting: $payload")

                    withContext(Dispatchers.Main) {
                        val clip = ClipData.newPlainText("kt_stream", payload).apply {
                            description.extras = PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        clipboard.setPrimaryClip(clip)
                    }
                }

                // ── Strict 15Hz timing: 66ms budget, minimum 10ms sleep ───────────────
                val elapsed = System.currentTimeMillis() - loopStart
                delay(maxOf(10L, 66L - elapsed))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}