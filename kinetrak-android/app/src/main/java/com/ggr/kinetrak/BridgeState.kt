package com.ggr.kinetrak

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class Vec3(val x: Float = 0.0f, val y: Float = 0.0f, val z: Float = 0.0f) {
    val size: Int get() = 3
    fun isNotEmpty(): Boolean = true
    operator fun get(index: Int): Float = when (index) {
        0 -> x
        1 -> y
        2 -> z
        else -> throw IndexOutOfBoundsException("Index $index out of bounds for Vec3")
    }
}

data class Quat(val w: Float = 1.0f, val x: Float = 0.0f, val y: Float = 0.0f, val z: Float = 0.0f) {
    val size: Int get() = 4
    fun isNotEmpty(): Boolean = true
    operator fun get(index: Int): Float = when (index) {
        0 -> w
        1 -> x
        2 -> y
        3 -> z
        else -> throw IndexOutOfBoundsException("Index $index out of bounds for Quat")
    }
}

object BridgeState {
    val isTracking = AtomicBoolean(true)
    val currentState = AtomicReference("IDLE")       // IDLE, RECORDING, THINKING, NULL
    val pendingAction = AtomicReference("NULL")      // NULL, ACTION:SPAWN, ACTION:SELECT, ACTION:DELETE, ACTION:RESET
    val isProcessing = AtomicBoolean(false)          // CAS lock for Dev 3's worker

    @Volatile var currentPosition: Vec3 = Vec3(0.0f, 0.0f, 0.0f)

    var posX: Float
        get() = currentPosition.x
        set(value) {
            currentPosition = currentPosition.copy(x = value)
        }

    var posY: Float
        get() = currentPosition.y
        set(value) {
            currentPosition = currentPosition.copy(y = value)
        }

    var posZ: Float
        get() = currentPosition.z
        set(value) {
            currentPosition = currentPosition.copy(z = value)
        }

    @Volatile var currentRotation: Quat = Quat(1.0f, 0.0f, 0.0f, 0.0f)

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