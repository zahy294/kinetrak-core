package com.ggr.kinetrak

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ─── Lightweight value types used by SensorFusionHub ──────────────────────────
data class Vec3(val x: Float = 0.0f, val y: Float = 0.0f, val z: Float = 0.0f) {
    val size: Int get() = 3
    fun isNotEmpty(): Boolean = true
    operator fun get(index: Int): Float = when (index) {
        0 -> x; 1 -> y; 2 -> z
        else -> throw IndexOutOfBoundsException("Index $index out of bounds for Vec3")
    }
}

data class Quat(val w: Float = 1.0f, val x: Float = 0.0f, val y: Float = 0.0f, val z: Float = 0.0f) {
    val size: Int get() = 4
    fun isNotEmpty(): Boolean = true
    operator fun get(index: Int): Float = when (index) {
        0 -> w; 1 -> x; 2 -> y; 3 -> z
        else -> throw IndexOutOfBoundsException("Index $index out of bounds for Quat")
    }
}

// ─── Unified Bridge State Object ───────────────────────────────────────────────
object BridgeState {

    // Integer state constants (Dev 2 — used by MainActivity & ClipboardBridgeService)
    const val STATE_IDLE      = 1
    const val STATE_RECORDING = 2
    const val STATE_THINKING  = 3

    // ── Emission Rate Benchmark Modes ─────────────────────────────────────────
    const val EMISSION_MODE_15HZ = 1  // 66ms delay [Baseline]
    const val EMISSION_MODE_20HZ = 2  // 50ms delay
    const val EMISSION_MODE_30HZ = 3  // 33ms delay

    @Volatile var emissionMode: Int = EMISSION_MODE_15HZ

    val emissionDelayMs: Long
        get() = when (emissionMode) {
            EMISSION_MODE_20HZ -> 50L
            EMISSION_MODE_30HZ -> 33L
            else               -> 66L // Default 15Hz Baseline
        }

    fun setEmissionModeByRate(targetHz: Int) {
        emissionMode = when (targetHz) {
            30   -> EMISSION_MODE_30HZ
            20   -> EMISSION_MODE_20HZ
            else -> EMISSION_MODE_15HZ
        }
    }

    // ── Integer state tracking (Dev 2) ─────────────────────────────────────────
    @Volatile var currentState: Int = STATE_IDLE

    // ── String gesture state alias (Dev 1 compat) ──────────────────────────────
    // Reads/writes are backed by currentState to keep a single source of truth.
    var gestureState: String
        get() = when (currentState) {
            STATE_RECORDING -> "RECORDING"
            STATE_THINKING  -> "THINKING"
            else            -> "NULL"
        }
        set(value) {
            currentState = when (value) {
                "RECORDING"         -> STATE_RECORDING
                "THINKING"          -> STATE_THINKING
                "ACTION_DISPATCHED" -> STATE_IDLE      // latch handled by ClipboardBridgeService
                "NULL"              -> STATE_IDLE
                else                -> STATE_IDLE
            }
        }

    // ── Tracking validity ──────────────────────────────────────────────────────
    // NOTE: Not @Volatile — this is a computed property (no backing field).
    // Thread-safety is provided by the underlying AtomicBoolean isTracking.
    var isTrackingValid: Boolean
        get() = isTracking.get()
        set(value) { isTracking.set(value) }

    val isTracking   = AtomicBoolean(true)
    val isProcessing = AtomicBoolean(false)

    // ── Sequence counter ───────────────────────────────────────────────────────
    val currentSeq = AtomicInteger(1)

    // ── Pending gesture action ─────────────────────────────────────────────────
    val pendingAction: AtomicReference<String> = AtomicReference("NULL")

    // ── Streaming & mic gates (Dev 2) ──────────────────────────────────────────
    @Volatile var isStreamingActive: Boolean = false
    @Volatile var isMicActive: Boolean       = false

    // ── Position — FloatArray storage (Dev 2 consumers) ───────────────────────
    val currentPosition: FloatArray = FloatArray(3)           // [x, y, z]
    val currentRotation: FloatArray = floatArrayOf(1f, 0f, 0f, 0f) // [qw, qx, qy, qz]

    // ── FloatArray convenience accessors ───────────────────────────────────────
    var posX: Float
        get()      = currentPosition[0]
        set(value) { currentPosition[0] = value }

    var posY: Float
        get()      = currentPosition[1]
        set(value) { currentPosition[1] = value }

    var posZ: Float
        get()      = currentPosition[2]
        set(value) { currentPosition[2] = value }

    // ── Vec3 / Quat write-through setters (SensorFusionHub compat) ────────────
    // SensorFusionHub assigns:  BridgeState.currentPosition = Vec3(...)
    // These computed properties write through to the FloatArray so every
    // consumer (ClipboardBridgeService, MainActivity HUD) sees the same data.
    fun setPosition(x: Float, y: Float, z: Float) {
        currentPosition[0] = x
        currentPosition[1] = y
        currentPosition[2] = z
    }

    fun setPositionFromVec3(v: Vec3) {
        currentPosition[0] = v.x
        currentPosition[1] = v.y
        currentPosition[2] = v.z
    }

    fun setRotationFromQuat(q: Quat) {
        currentRotation[0] = q.w
        currentRotation[1] = q.x
        currentRotation[2] = q.y
        currentRotation[3] = q.z
    }
}