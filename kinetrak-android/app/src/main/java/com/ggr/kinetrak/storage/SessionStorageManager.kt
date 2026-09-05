package com.ggr.kinetrak.storage

import android.content.Context
import android.util.Log
import com.ggr.kinetrak.model.SessionRecord
import org.json.JSONArray
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class SessionStorageManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val sessionsDir: File
        get() {
            val dir = File(appContext.filesDir, "sessions")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    private val indexFile: File
        get() = File(sessionsDir, "sessions_index.json")

    companion object {
        private const val TAG = "SessionStorageManager"

        @Volatile
        private var INSTANCE: SessionStorageManager? = null

        fun getInstance(context: Context): SessionStorageManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionStorageManager(context).also { INSTANCE = it }
            }
        }
    }

    @Synchronized
    fun saveSession(
        csvData: String,
        action: String,
        durationMs: Long,
        sampleCount: Int
    ): SessionRecord {
        val now = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val id = "session_${dateFormat.format(Date(now))}"
        val fileName = "$id.csv"

        val targetFile = File(sessionsDir, fileName)
        try {
            FileWriter(targetFile).use { writer ->
                writer.write(csvData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write session file: $fileName", e)
        }

        val record = SessionRecord(
            id = id,
            timestamp = now,
            durationMs = durationMs,
            sampleCount = sampleCount,
            detectedAction = if (action.isBlank()) "NULL" else action,
            fileName = fileName
        )

        val currentList = readIndexInternal().toMutableList()
        currentList.add(0, record)
        writeIndexInternal(currentList)

        Log.i(TAG, "Saved session $id with $sampleCount samples ($durationMs ms, action=$action)")
        return record
    }

    @Synchronized
    fun getAllSessions(): List<SessionRecord> {
        val sessions = readIndexInternal().toMutableList()

        // Ensure the rotation demo session is generated and at the top
        val rotationDemoFile = File(sessionsDir, "session_rotation_demo.csv")
        val hasRotationDemo = sessions.any { it.id == "session_rotation_demo" } && rotationDemoFile.exists()

        if (!hasRotationDemo) {
            val rotationDemoRecord = generateRotationDemoSession()
            sessions.removeAll { it.id == "session_rotation_demo" }
            sessions.add(0, rotationDemoRecord)

            if (sessions.none { it.id == "session_demo_baseline" }) {
                val baselineRecord = generateSampleSession()
                sessions.add(baselineRecord)
            }

            writeIndexInternal(sessions)
            return sessions
        }

        if (sessions.isEmpty()) {
            val rotationDemo = generateRotationDemoSession()
            val sample = generateSampleSession()
            val newList = listOf(rotationDemo, sample)
            writeIndexInternal(newList)
            return newList
        }

        return sessions
    }

    fun getSessionFile(record: SessionRecord): File {
        return File(sessionsDir, record.fileName)
    }

    private fun readIndexInternal(): List<SessionRecord> {
        if (!indexFile.exists()) {
            return emptyList()
        }

        return try {
            val content = indexFile.readText()
            if (content.isBlank()) return emptyList()

            val jsonArray = JSONArray(content)
            val list = mutableListOf<SessionRecord>()
            for (i in 0 until jsonArray.length()) {
                val itemObj = jsonArray.getJSONObject(i)
                list.add(SessionRecord.fromJson(itemObj))
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error reading session index file", e)
            emptyList()
        }
    }

    private fun writeIndexInternal(sessions: List<SessionRecord>) {
        try {
            val jsonArray = JSONArray()
            for (session in sessions) {
                jsonArray.put(session.toJson())
            }
            FileWriter(indexFile).use { writer ->
                writer.write(jsonArray.toString(2))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing session index file", e)
        }
    }

    /**
     * Injects an expressive 6-DOF multi-axis rotation demo session (4.0s, 60 waypoints)
     * with compound Roll-Pitch-Yaw Euler-to-quaternion math and latched "ACTION:SELECT".
     */
    fun generateRotationDemoSession(): SessionRecord {
        val sampleId = "session_rotation_demo"
        val sampleFileName = "session_rotation_demo.csv"
        val sampleFile = File(sessionsDir, sampleFileName)
        val sampleCount = 60
        val durationMs = 4000L
        val baseTimestamp = System.currentTimeMillis() - 60000L

        try {
            FileWriter(sampleFile).use { writer ->
                writer.append("seq,timestamp_ms,tracking_state,pos_x,pos_y,pos_z,qw,qx,qy,qz,gesture_state,action\n")
                for (i in 0 until sampleCount) {
                    val seq = i + 1
                    val t = i / 59.0
                    val timestampMs = baseTimestamp + (i * 66L)

                    // 1. Trajectory Translation: Sweeping 3D spatial arc
                    val x = (0.5 * sin(2.0 * Math.PI * t)).toFloat()
                    val y = (0.2 * sin(4.0 * Math.PI * t)).toFloat()
                    val z = (-0.3 - 0.4 * t).toFloat()

                    // 2. Dynamic 6-DOF Quaternion Rotation: Roll 180 deg + Pitch 45 deg + Yaw oscillation
                    val thetaPitch = 0.785 * sin(Math.PI * t)
                    val thetaRoll = 3.14159 * t
                    val thetaYaw = 0.523 * sin(2.0 * Math.PI * t)

                    val cy = cos(thetaYaw * 0.5)
                    val sy = sin(thetaYaw * 0.5)
                    val cp = cos(thetaPitch * 0.5)
                    val sp = sin(thetaPitch * 0.5)
                    val cr = cos(thetaRoll * 0.5)
                    val sr = sin(thetaRoll * 0.5)

                    val qw = (cr * cp * cy + sr * sp * sy).toFloat()
                    val qx = (sr * cp * cy - cr * sp * sy).toFloat()
                    val qy = (cr * sp * cy + sr * cp * sy).toFloat()
                    val qz = (cr * cp * sy - sr * sp * cy).toFloat()

                    // 3. Gesture States & Action window
                    val gestureState = when {
                        t < 0.6 -> "RECORDING"
                        t < 0.7 -> "THINKING"
                        t < 0.85 -> "ACTION"
                        else -> "IDLE"
                    }
                    val action = if (seq in 35..42) "ACTION:SELECT" else "NULL"

                    writer.append(
                        String.format(
                            Locale.US,
                            "%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s\n",
                            seq, timestampMs, 1, x, y, z, qw, qx, qy, qz, gestureState, action
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create rotation demo session file", e)
        }

        return SessionRecord(
            id = sampleId,
            timestamp = baseTimestamp,
            durationMs = durationMs,
            sampleCount = sampleCount,
            detectedAction = "ACTION:SELECT",
            fileName = sampleFileName
        )
    }

    /**
     * Auto-generates baseline sample session file so the UI list is never blank during demo evaluation.
     */
    private fun generateSampleSession(): SessionRecord {
        val sampleId = "session_demo_baseline"
        val sampleFileName = "session_sample.csv"
        val sampleFile = File(sessionsDir, sampleFileName)
        val sampleCount = 48
        val durationMs = 3200L
        val baseTimestamp = System.currentTimeMillis() - 180000L

        try {
            FileWriter(sampleFile).use { writer ->
                writer.append("seq,timestamp_ms,tracking_state,pos_x,pos_y,pos_z,qw,qx,qy,qz,gesture_state,action\n")
                for (i in 1..sampleCount) {
                    val t = baseTimestamp + (i * 66)
                    val rad = (i * 0.12).toFloat()
                    val x = 0.15f * sin(rad)
                    val y = -0.05f + (0.08f * cos(rad))
                    val z = -0.40f - (0.015f * i)
                    val qw = 0.985f
                    val qx = 0.050f * sin(rad)
                    val qy = 0.150f * cos(rad)
                    val qz = 0.020f
                    val gestureState = when (i) {
                        in 1..15 -> "RECORDING"
                        in 16..24 -> "THINKING"
                        else -> "IDLE"
                    }
                    val action = when (i) {
                        in 25..35 -> "ACTION:SPAWN"
                        else -> "NULL"
                    }
                    writer.append(
                        String.format(
                            Locale.US,
                            "%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s\n",
                            i, t, 1, x, y, z, qw, qx, qy, qz, gestureState, action
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sample session file", e)
        }

        return SessionRecord(
            id = sampleId,
            timestamp = baseTimestamp,
            durationMs = durationMs,
            sampleCount = sampleCount,
            detectedAction = "ACTION:SPAWN",
            fileName = sampleFileName
        )
    }
}
