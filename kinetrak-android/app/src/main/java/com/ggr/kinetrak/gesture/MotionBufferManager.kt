package com.ggr.kinetrak.gesture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.util.Log
import com.ggr.kinetrak.BridgeState
import com.ggr.kinetrak.SnapdragonNPU
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MotionBufferManager — Rolling 45-frame IMU Tensor Buffer
 *
 * Maintains a rolling buffer of 6-channel sensor frames [ax, ay, az, gx, gy, gz]
 * sampled from TYPE_LINEAR_ACCELERATION and TYPE_GYROSCOPE.
 *
 * Hardware trigger flow (Volume Down key):
 *   KeyDown  → startBuffering()                    (MainActivity.onKeyDown)
 *   KeyUp    → stopAndClassifyAsync()              (MainActivity.onKeyUp)
 *
 * Classification pipeline:
 *   1. Snapdragon NPU DLC model (1500ms timeout guard)
 *   2. HeuristicGestureFallback on timeout or exception
 *
 * Result is latched into BridgeState.pendingAction and broadcast by ClipboardBridgeService.
 */
class MotionBufferManager(
    private val npu: SnapdragonNPU,
    private val heuristicFallback: HeuristicGestureFallback
) : SensorEventListener {

    companion object {
        const val BUFFER_SIZE = 45
        const val CHANNELS_PER_FRAME = 6
        const val TOTAL_TENSOR_SIZE = BUFFER_SIZE * CHANNELS_PER_FRAME
        private const val TAG = "MotionBufferManager"
    }

    private val bufferLock = Any()
    private val frameQueue = ArrayDeque<FloatArray>(BUFFER_SIZE)
    private val isRecording = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var latestLinAccelX = 0.0f
    @Volatile private var latestLinAccelY = 0.0f
    @Volatile private var latestLinAccelZ = 0.0f

    @Volatile private var latestGyroX = 0.0f
    @Volatile private var latestGyroY = 0.0f
    @Volatile private var latestGyroZ = 0.0f

    /** Clears the rolling motion queue and activates buffering. */
    fun startBuffering() {
        synchronized(bufferLock) {
            frameQueue.clear()
            isRecording.set(true)
        }
    }

    /** Returns true if motion buffering is currently active. */
    fun isBuffering(): Boolean = isRecording.get()

    /**
     * Stops buffering, flattens the 45×6 tensor, and classifies asynchronously.
     * Uses SnapdragonNPU with a 1500ms timeout guard; falls back to [HeuristicGestureFallback].
     * Latches result into [BridgeState.pendingAction] and transitions gestureState to IDLE.
     */
    fun stopAndClassifyAsync(): Job {
        isRecording.set(false)

        val snapshot: List<FloatArray> = synchronized(bufferLock) {
            frameQueue.toList()
        }

        val tensorData = FloatArray(TOTAL_TENSOR_SIZE)
        for (i in 0 until BUFFER_SIZE) {
            val frame = if (i < snapshot.size) snapshot[i] else FloatArray(CHANNELS_PER_FRAME)
            val copyLen = minOf(CHANNELS_PER_FRAME, frame.size)
            System.arraycopy(frame, 0, tensorData, i * CHANNELS_PER_FRAME, copyLen)
        }

        return scope.launch(Dispatchers.Default) {
            val resolvedAction = try {
                withTimeoutOrNull(1500L) {
                    val classIdx = npu.classifyGesture(tensorData)
                    when (classIdx) {
                        0 -> "ACTION:SPAWN"
                        1 -> "ACTION:SELECT"
                        2 -> "ACTION:DELETE"
                        3 -> "ACTION:RESET"
                        else -> null
                    }
                } ?: heuristicFallback.classify(snapshot)
            } catch (e: Exception) {
                Log.w(TAG, "NPU classification timed out or failed; invoking heuristic fallback", e)
                heuristicFallback.classify(snapshot)
            }

            BridgeState.pendingAction.set(resolvedAction)
            // Transition back to IDLE — ClipboardBridgeService handles ACTION_DISPATCHED latch
            BridgeState.currentState = BridgeState.STATE_IDLE
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                latestLinAccelX = event.values[0]
                latestLinAccelY = event.values[1]
                latestLinAccelZ = event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroX = event.values[0]
                latestGyroY = event.values[1]
                latestGyroZ = event.values[2]
            }
            else -> return
        }

        if (isRecording.get()) {
            val frame = floatArrayOf(
                latestLinAccelX, latestLinAccelY, latestLinAccelZ,
                latestGyroX,     latestGyroY,     latestGyroZ
            )
            synchronized(bufferLock) {
                if (!isRecording.get()) return
                if (frameQueue.size >= BUFFER_SIZE) frameQueue.removeFirst()
                frameQueue.addLast(frame)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun release() {
        isRecording.set(false)
        scope.cancel()
    }
}
