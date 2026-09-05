package com.ggr.kinetrak.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OneEuroFilterTest {

    @Test
    fun testInitialSampleReturnsExactValue() {
        val filter = OneEuroFilter()
        val result = filter.filter(1.234f, 0.0)
        assertEquals(1.234f, result, 1e-6f)
    }

    @Test
    fun testTremorNoiseSuppressionAtRest() {
        val filter = OneEuroFilter(minCutoff = 0.5f, beta = 0.002f, dCutoff = 0.5f)
        val initial = filter.filter(1.0f, 0.0)
        assertEquals(1.0f, initial, 1e-6f)

        // Inject 30Hz small hand tremor noise around 1.0f
        var maxError = 0.0f
        var time = 0.0
        val dt = 1.0 / 30.0

        for (i in 1..60) {
            time += dt
            val noise = if (i % 2 == 0) 0.02f else -0.02f
            val noisyInput = 1.0f + noise
            val filtered = filter.filter(noisyInput, time)

            // Filtered value should stay close to 1.0f, smoothing out high-frequency tremor
            val err = abs(filtered - 1.0f)
            if (err > maxError) maxError = err
        }

        // Tremor of amplitude 0.02 should be significantly attenuated
        assertTrue("Max error ($maxError) should be heavily smoothed compared to noise amplitude (0.02)", maxError < 0.015f)
    }

    @Test
    fun testFilterReset() {
        val filter = OneEuroFilter()
        filter.filter(1.0f, 0.0)
        filter.filter(1.05f, 0.033)
        filter.reset()

        // After reset, first sample should be passed directly without lag from previous state
        val fresh = filter.filter(5.0f, 10.0)
        assertEquals(5.0f, fresh, 1e-6f)
    }

    @Test
    fun testOneEuroFilter3D() {
        val filter3D = OneEuroFilter3D()
        val pos0 = floatArrayOf(0.1f, 0.2f, 0.3f)
        val out0 = filter3D.filter(pos0, 0.0)

        assertEquals(0.1f, out0[0], 1e-6f)
        assertEquals(0.2f, out0[1], 1e-6f)
        assertEquals(0.3f, out0[2], 1e-6f)

        val pos1 = floatArrayOf(0.105f, 0.195f, 0.302f)
        val out1 = filter3D.filter(pos1, 0.033)
        assertTrue(out1.size == 3)

        filter3D.reset()
        val posReset = floatArrayOf(2.0f, 3.0f, 4.0f)
        val outReset = filter3D.filter(posReset, 1.0)
        assertEquals(2.0f, outReset[0], 1e-6f)
        assertEquals(3.0f, outReset[1], 1e-6f)
        assertEquals(4.0f, outReset[2], 1e-6f)
    }
}
