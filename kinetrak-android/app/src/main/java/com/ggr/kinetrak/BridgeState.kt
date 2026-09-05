package com.ggr.kinetrak

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object BridgeState {
    const val STATE_IDLE = 1
    const val STATE_RECORDING = 2
    const val STATE_THINKING = 3

    @Volatile var currentState: Int = STATE_IDLE
    @Volatile var gestureState: String = "IDLE"
    @Volatile var isTrackingValid: Boolean = true

    val currentPosition: FloatArray = FloatArray(3) // [x, y, z]
    val currentRotation: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f) // [qw, qx, qy, qz]

    val pendingAction: AtomicReference<String> = AtomicReference("NULL")

    // Concurrency & compatibility properties
    val isTracking = AtomicBoolean(true)
    val isProcessing = AtomicBoolean(false)
    val currentSeq = java.util.concurrent.atomic.AtomicInteger(1)
    val isMicActive = AtomicBoolean(false)

    var posX: Float
        get() = currentPosition[0]
        set(value) { currentPosition[0] = value }

    var posY: Float
        get() = currentPosition[1]
        set(value) { currentPosition[1] = value }

    var posZ: Float
        get() = currentPosition[2]
        set(value) { currentPosition[2] = value }
}