package com.ggr.kinetrak

import android.app.Application
import android.util.Log
import com.qualcomm.qti.snpe.NeuralNetwork
import com.qualcomm.qti.snpe.SNPE
import com.qualcomm.qti.snpe.FloatTensor
import java.io.InputStream

class SnapdragonNPU(private val application: Application) {

    private var neuralNetwork: NeuralNetwork? = null
    private var inputTensorName: String = ""
    private var outputTensorName: String = ""
    private var hasLoggedShape: Boolean = false

    /**
     * Initializes the SNPE Runtime loading gesture_model_quantized.dlc onto Hexagon NPU DSP
     */
    fun initModelFromAssets(assetFileName: String = "gesture_model_quantized.dlc"): Boolean {
        return try {
            val inputStream: InputStream = application.assets.open(assetFileName)
            val size = inputStream.available()

            // Build SNPE Neural Network using exact Java API builder
            neuralNetwork = SNPE.NeuralNetworkBuilder(application)
                .setModel(inputStream, size)
                .setRuntimeOrder(
                    NeuralNetwork.Runtime.DSP, // Hexagon NPU
                    NeuralNetwork.Runtime.GPU,
                    NeuralNetwork.Runtime.CPU
                )
                .setCpuFallbackEnabled(true)
                .build()

            inputStream.close()

            // Resolve Input and Output Tensor Names
            inputTensorName = neuralNetwork?.inputTensorsNames?.firstOrNull() ?: "input"
            outputTensorName = neuralNetwork?.outputTensorsNames?.firstOrNull() ?: "output"

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Runs forward-propagation on rolling 45-feature buffer matching model shape [1, 45]
     */
    fun classifyGesture(inputBuffer: FloatArray): Int {
        val nn = neuralNetwork ?: return -1

        return try {
            // 1. Get the shape the model expects (shape [1, 45])
            val shape = nn.inputTensorsShapes[inputTensorName] ?: intArrayOf(1, 45)

            val totalFloatsNeeded = 45

            // Log the shape ONCE so we can see it in Logcat
            if (!hasLoggedShape) {
                Log.w("KineTrak", "Model expects shape: ${shape.contentToString()} (total floats: $totalFloatsNeeded)")
                hasLoggedShape = true
            }

            // 2. Create tensor matching exact shape
            val inputTensor = nn.createFloatTensor(*shape)

            // 3. Slice or resize buffer so it NEVER overflows the tensor
            val safeBuffer = if (inputBuffer.size >= 45) {
                inputBuffer.copyOf(45)
            } else {
                val padded = FloatArray(45)
                System.arraycopy(inputBuffer, 0, padded, 0, inputBuffer.size)
                padded
            }

            // 4. Write to tensor safely
            inputTensor.write(safeBuffer, 0, safeBuffer.size)

            // 5. Execute forward propagation
            val inputsMap = mapOf(inputTensorName to inputTensor)
            val outputsMap = nn.execute(inputsMap)

            val outputTensor = outputsMap[outputTensorName]
            val probabilities = FloatArray(4)
            outputTensor?.read(probabilities, 0, probabilities.size)

            var maxIdx = 0
            var maxProb = probabilities[0]
            for (i in 1 until probabilities.size) {
                if (probabilities[i] > maxProb) {
                    maxProb = probabilities[i]
                    maxIdx = i
                }
            }
            maxIdx
        } catch (e: Exception) {
            e.printStackTrace()
            -1
        }
    }

    fun release() {
        neuralNetwork?.release()
        neuralNetwork = null
    }
}
