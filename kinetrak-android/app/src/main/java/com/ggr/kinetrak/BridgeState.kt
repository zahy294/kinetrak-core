package com.ggr.kinetrak

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object BridgeState {
    val isTracking = AtomicBoolean(true)
    val currentState = AtomicReference("NULL")       // NULL, RECORDING, THINKING
    val pendingAction = AtomicReference("NULL")      // NULL, ACTION:TEST, ACTION:SPAWN
    val isProcessing = AtomicBoolean(false)          // CAS lock for Dev 3's worker
}