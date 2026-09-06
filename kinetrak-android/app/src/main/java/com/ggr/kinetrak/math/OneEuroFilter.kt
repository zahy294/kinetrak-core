package com.ggr.kinetrak.math

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * High-Accuracy 1€ (One Euro) Filter on translation coordinates.
 * Tuned specifically for maximum tracking stability and trajectory fidelity over raw zero-latency.
 */
class OneEuroFilter(
    var minCutoff: Float = 1.2f,
    var beta: Float = 0.08f,
    var dCutoff: Float = 0.5f
) {
    private var prevX: Float? = null
    private var prevDx: Float = 0.0f
    private var prevTimestamp: Double? = null

    fun filter(x: Float, timestampSec: Double): Float {
        val lastTimestamp = prevTimestamp
        val lastX = prevX

        if (lastTimestamp == null || lastX == null) {
            prevX = x
            prevDx = 0.0f
            prevTimestamp = timestampSec
            return x
        }

        val dt = max((timestampSec - lastTimestamp).toFloat(), 1e-4f)
        val dx = (x - lastX) / dt
        val alphaD = 1.0f / (1.0f + (1.0f / (2.0f * PI.toFloat() * dCutoff * dt)))
        val edx = alphaD * dx + (1.0f - alphaD) * prevDx
        val cutoff = minCutoff + beta * abs(edx)
        val alpha = 1.0f / (1.0f + (1.0f / (2.0f * PI.toFloat() * cutoff * dt)))
        val result = alpha * x + (1.0f - alpha) * lastX

        prevX = result
        prevDx = edx
        prevTimestamp = timestampSec

        return result
    }

    fun reset() {
        prevX = null
        prevDx = 0.0f
        prevTimestamp = null
    }
}

/**
 * 3D container managing three OneEuroFilter instances for X, Y, and Z translational axes.
 */
class OneEuroFilter3D(
    minCutoff: Float = 1.2f,
    beta: Float = 0.08f,
    dCutoff: Float = 0.5f
) {
    private val filterX = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterY = OneEuroFilter(minCutoff, beta, dCutoff)
    private val filterZ = OneEuroFilter(minCutoff, beta, dCutoff)

    fun filter(rawPos: FloatArray, timestampSec: Double): FloatArray {
        if (rawPos.size < 3) return rawPos
        return floatArrayOf(
            filterX.filter(rawPos[0], timestampSec),
            filterY.filter(rawPos[1], timestampSec),
            filterZ.filter(rawPos[2], timestampSec)
        )
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
        filterZ.reset()
    }
}
