package com.ggr.kinetrak.kinematics

import android.content.Context
import com.ggr.kinetrak.BridgeState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class Waypoint(
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val qw: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float
)

class TrajectoryRecorder {

    companion object {
        const val MAX_BUFFER_SIZE = 1000
    }

    private val bufferLock = Any()
    private val ringBuffer = ArrayDeque<Waypoint>(MAX_BUFFER_SIZE)
    private val isRecording = AtomicBoolean(false)

    /**
     * Clears internal ring buffer and marks recording state as active.
     */
    fun startRecording() {
        synchronized(bufferLock) {
            ringBuffer.clear()
            isRecording.set(true)
        }
    }

    /**
     * Checks if trajectory recording is currently active.
     */
    fun isRecordingActive(): Boolean = isRecording.get()

    /**
     * Captures the current spatial position and orientation from BridgeState and appends
     * a timestamped Waypoint to the ring buffer.
     */
    fun captureCurrentPose(): Waypoint? {
        if (!isRecording.get()) return null

        val pos = BridgeState.currentPosition
        val rot = BridgeState.currentRotation

        val x = if (pos.isNotEmpty()) pos[0] else 0.0f
        val y = if (pos.size > 1) pos[1] else 0.0f
        val z = if (pos.size > 2) pos[2] else 0.0f

        val qw = if (rot.isNotEmpty()) rot[0] else 1.0f
        val qx = if (rot.size > 1) rot[1] else 0.0f
        val qy = if (rot.size > 2) rot[2] else 0.0f
        val qz = if (rot.size > 3) rot[3] else 0.0f

        val waypoint = Waypoint(
            timestamp = System.currentTimeMillis(),
            x = x,
            y = y,
            z = z,
            qw = qw,
            qx = qx,
            qy = qy,
            qz = qz
        )

        synchronized(bufferLock) {
            if (!isRecording.get()) return null
            if (ringBuffer.size >= MAX_BUFFER_SIZE) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(waypoint)
        }

        return waypoint
    }

    /**
     * Deactivates recording and returns an immutable snapshot list of recorded waypoints.
     */
    fun stopRecording(): List<Waypoint> {
        isRecording.set(false)
        return synchronized(bufferLock) {
            ringBuffer.toList()
        }
    }

    /**
     * Serializes recorded waypoints to trajectory_<timestamp>.csv in context.filesDir
     * with header: timestamp,pos_x,pos_y,pos_z,rot_qw,rot_qx,rot_qy,rot_qz
     */
    fun exportToCsv(context: Context): File {
        val waypoints = synchronized(bufferLock) { ringBuffer.toList() }
        val fileName = "trajectory_${System.currentTimeMillis()}.csv"
        val targetFile = File(context.filesDir, fileName)

        targetFile.bufferedWriter().use { writer ->
            writer.write("timestamp,pos_x,pos_y,pos_z,rot_qw,rot_qx,rot_qy,rot_qz\n")
            for (wp in waypoints) {
                writer.write(
                    String.format(
                        Locale.US,
                        "%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f\n",
                        wp.timestamp,
                        wp.x,
                        wp.y,
                        wp.z,
                        wp.qw,
                        wp.qx,
                        wp.qy,
                        wp.qz
                    )
                )
            }
        }

        return targetFile
    }

    /**
     * Serializes recorded waypoints to standard JSON in context.filesDir.
     */
    fun exportToJson(context: Context): File {
        val waypoints = synchronized(bufferLock) { ringBuffer.toList() }
        val fileName = "trajectory_${System.currentTimeMillis()}.json"
        val targetFile = File(context.filesDir, fileName)

        val jsonArray = JSONArray()
        for (wp in waypoints) {
            val jsonObject = JSONObject().apply {
                put("timestamp", wp.timestamp)
                put("pos_x", wp.x.toDouble())
                put("pos_y", wp.y.toDouble())
                put("pos_z", wp.z.toDouble())
                put("rot_qw", wp.qw.toDouble())
                put("rot_qx", wp.qx.toDouble())
                put("rot_qy", wp.qy.toDouble())
                put("rot_qz", wp.qz.toDouble())
            }
            jsonArray.put(jsonObject)
        }

        targetFile.writeText(jsonArray.toString(2))
        return targetFile
    }
}
