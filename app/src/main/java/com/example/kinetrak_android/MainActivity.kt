package com.example.kinetrak_android

import android.app.Activity
import android.os.Bundle
import android.util.Log

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.w("KineTrak", ">>> STARTING HIGH-PRECISION NPU BENCHMARK <<<")

        Thread {
            val npu = SnapdragonNPU(application)
            val isLoaded = npu.initModelFromAssets()
            Log.w("KineTrak", "Model Loaded onto Hexagon DSP: $isLoaded")

            if (isLoaded) {
                val dummyBuffer = FloatArray(270) { it.toFloat() }

                // --- HIGH PRECISION NANOTIME LOOP ---
                val startTimeNano = System.nanoTime()
                val iterations = 1000

                for (i in 1..iterations) {
                    npu.classifyGesture(dummyBuffer)
                }

                val totalTimeMs = (System.nanoTime() - startTimeNano) / 1_000_000.0
                val avgTimeMs = totalTimeMs / iterations

                Log.w("KineTrak", "=======================================")
                Log.w("KineTrak", "TOTAL TIME ($iterations runs): %.2f ms".format(totalTimeMs))
                Log.w("KineTrak", "NPU AVG INFERENCE: %.4f ms".format(avgTimeMs))
                Log.w("KineTrak", "=======================================")
                // ------------------------------------

            } else {
                Log.e("KineTrak", "FAILED TO LOAD MODEL. Check assets folder.")
            }
        }.start()
    }
}