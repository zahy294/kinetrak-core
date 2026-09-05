package com.ggr.kinetrak

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object BridgeState {
    val isTracking = AtomicBoolean(true)
    val currentState = AtomicReference("IDLE")       // IDLE, RECORDING, THINKING, NULL
    val pendingAction = AtomicReference("NULL")      // NULL, ACTION:SPAWN, ACTION:SELECT, ACTION:DELETE, ACTION:RESET
    val isProcessing = AtomicBoolean(false)          // CAS lock for Dev 3's worker

    @Volatile var posX: Float = 0.0f
    @Volatile var posY: Float = 0.0f
    @Volatile var posZ: Float = 0.0f

    @Volatile var currentPosition: FloatArray = floatArrayOf(0.0f, 0.0f, 0.0f)
        get() = floatArrayOf(posX, posY, posZ)
        set(value) {
            field = value
            if (value.size >= 3) {
                posX = value[0]
                posY = value[1]
                posZ = value[2]
            }
        }

    @Volatile var currentRotation: FloatArray = floatArrayOf(1.0f, 0.0f, 0.0f, 0.0f)

    var isTrackingValid: Boolean
        get() = isTracking.get()
        set(value) {
            isTracking.set(value)
        }

    var gestureState: String
        get() = currentState.get()
        set(value) {
            currentState.set(value)
        }
}