package com.ggr.kinetrak.ml

import kotlin.math.abs
import kotlin.math.max

class HeuristicGestureFallback {

    /**
     * Classifies a sequence of 6-channel IMU frames [ax, ay, az, gx, gy, gz]
     * into discrete action tokens using kinematic threshold rules.
     *
     * @param frames List of float arrays containing linear acceleration and gyroscope readings.
     * @return Resolved action token ("ACTION:RESET", "ACTION:SPAWN", or "ACTION:SELECT").
     */
    fun classify(frames: List<FloatArray>): String {
        if (frames.isEmpty()) {
            return "ACTION:RESET"
        }

        var peakAccelX = 0.0f
        var peakAccelY = 0.0f
        var peakAccelZ = 0.0f

        var totalAngularVelX = 0.0f
        var totalAngularVelY = 0.0f
        var totalAngularVelZ = 0.0f

        for (frame in frames) {
            val ax = if (frame.size > 0) abs(frame[0]) else 0.0f
            val ay = if (frame.size > 1) abs(frame[1]) else 0.0f
            val az = if (frame.size > 2) abs(frame[2]) else 0.0f

            val gx = if (frame.size > 3) abs(frame[3]) else 0.0f
            val gy = if (frame.size > 4) abs(frame[4]) else 0.0f
            val gz = if (frame.size > 5) abs(frame[5]) else 0.0f

            peakAccelX = max(peakAccelX, ax)
            peakAccelY = max(peakAccelY, ay)
            peakAccelZ = max(peakAccelZ, az)

            totalAngularVelX += gx
            totalAngularVelY += gy
            totalAngularVelZ += gz
        }

        // Rule 1: High angular rotation about Z-axis (totalAngularVelZ > 12.0f dominant) -> ACTION:RESET
        if (totalAngularVelZ > 12.0f && totalAngularVelZ >= totalAngularVelX && totalAngularVelZ >= totalAngularVelY) {
            return "ACTION:RESET"
        }

        // Rule 2: Outward thrust acceleration (peakAccelZ > 8.0f dominant) -> ACTION:SPAWN
        if (peakAccelZ > 8.0f && peakAccelZ >= peakAccelX && peakAccelZ >= peakAccelY) {
            return "ACTION:SPAWN"
        }

        // Rule 3: Lateral horizontal swipe (peakAccelX > 6.0f dominant) -> ACTION:SELECT
        if (peakAccelX > 6.0f && peakAccelX >= peakAccelY && peakAccelX >= peakAccelZ) {
            return "ACTION:SELECT"
        }

        // Default fallback -> ACTION:SPAWN
        return "ACTION:SPAWN"
    }
}
