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
}