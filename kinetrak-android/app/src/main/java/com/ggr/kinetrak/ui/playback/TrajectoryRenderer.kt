package com.ggr.kinetrak.ui.playback

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Waypoint3D(
    val seq: Int,
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val qw: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val action: String
)

class TrajectoryRenderer : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "TrajectoryRenderer"
        private const val COORDS_PER_VERTEX = 3
        private const val COLOR_COMPONENTS = 4
        private const val VERTEX_STRIDE = (COORDS_PER_VERTEX + COLOR_COMPONENTS) * 4

        private const val VERTEX_SHADER_CODE = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                gl_PointSize = 18.0;
                vColor = aColor;
            }
        """

        private const val FRAGMENT_SHADER_CODE = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """

        fun quaternionToRotationMatrix(
            qw: Float,
            qx: Float,
            qy: Float,
            qz: Float,
            outMatrix: FloatArray
        ) {
            val mag = sqrt((qw * qw + qx * qx + qy * qy + qz * qz).toDouble()).toFloat()
            val nqw = if (mag > 0.0001f) qw / mag else 1.0f
            val nqx = if (mag > 0.0001f) qx / mag else 0.0f
            val nqy = if (mag > 0.0001f) qy / mag else 0.0f
            val nqz = if (mag > 0.0001f) qz / mag else 0.0f

            // 4x4 column-major rotation matrix for OpenGL ES:
            outMatrix[0] = 1.0f - 2.0f * (nqy * nqy + nqz * nqz)
            outMatrix[1] = 2.0f * (nqx * nqy + nqz * nqw)
            outMatrix[2] = 2.0f * (nqx * nqz - nqy * nqw)
            outMatrix[3] = 0.0f

            outMatrix[4] = 2.0f * (nqx * nqy - nqz * nqw)
            outMatrix[5] = 1.0f - 2.0f * (nqx * nqx + nqz * nqz)
            outMatrix[6] = 2.0f * (nqy * nqz + nqx * nqw)
            outMatrix[7] = 0.0f

            outMatrix[8] = 2.0f * (nqx * nqz + nqy * nqw)
            outMatrix[9] = 2.0f * (nqy * nqz - nqx * nqw)
            outMatrix[10] = 1.0f - 2.0f * (nqx * nqx + nqy * nqy)
            outMatrix[11] = 0.0f

            outMatrix[12] = 0.0f
            outMatrix[13] = 0.0f
            outMatrix[14] = 0.0f
            outMatrix[15] = 1.0f
        }
    }

    // Touch controls (thread-safe for UI touch events)
    @Volatile var touchAngleX: Float = 25f   // Azimuth / Yaw in degrees
    @Volatile var touchAngleY: Float = 20f   // Elevation / Pitch in degrees
    @Volatile var zoomScale: Float = 1.0f    // Zoom multiplier
    @Volatile var scrubProgress: Float = 1.0f // 0.0f to 1.0f for timeline scrubbing

    // Trajectory Waypoints & Bounds
    private val waypoints = mutableListOf<Waypoint3D>()
    var totalPathLengthMeters: Float = 0.0f
        private set
    var displacementMeters: Float = 0.0f
        private set
    var tortuosity: Float = 1.0f
        private set
    var detectedAction: String = "IDLE"
        private set

    private var centerX = 0.0f
    private var centerY = 0.0f
    private var centerZ = 0.0f
    private var boundsExtent = 1.0f
    private var floorY = -0.5f

    // OpenGL Buffers & Matrices
    private var trajectoryBuffer: FloatBuffer? = null
    private var gridBuffer: FloatBuffer? = null
    private var actionMarkersBuffer: FloatBuffer? = null
    private var gizmoAxesBuffer: FloatBuffer? = null
    private var chassisBuffer: FloatBuffer? = null

    private var gridVertexCount = 0
    private var actionMarkerCount = 0
    private var chassisVertexCount = 0

    private var programId = 0
    private var mvpMatrixHandle = 0
    private var positionHandle = 0
    private var colorHandle = 0

    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Reusable matrices for 6-DOF orientation gizmo
    private val rotMatrix = FloatArray(16)
    private val gizmoTranslateMatrix = FloatArray(16)
    private val gizmoModelMatrix = FloatArray(16)
    private val gizmoMvpMatrix = FloatArray(16)

    fun loadSessionCsv(csvFile: File) {
        waypoints.clear()
        if (!csvFile.exists()) {
            Log.w(TAG, "CSV file not found: ${csvFile.absolutePath}")
            return
        }

        try {
            csvFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("seq")) {
                    val parts = trimmed.split(",")
                    if (parts.size >= 6) {
                        val seq = parts[0].trim().toIntOrNull() ?: 0
                        val t = parts[1].trim().toLongOrNull() ?: 0L
                        val x = parts[3].trim().toFloatOrNull() ?: 0f
                        val y = parts[4].trim().toFloatOrNull() ?: 0f
                        val z = parts[5].trim().toFloatOrNull() ?: 0f

                        // 6-DOF Quaternions [qw, qx, qy, qz]
                        val qw = if (parts.size >= 10) parts[6].trim().toFloatOrNull() ?: 1.0f else 1.0f
                        val qx = if (parts.size >= 10) parts[7].trim().toFloatOrNull() ?: 0.0f else 0.0f
                        val qy = if (parts.size >= 10) parts[8].trim().toFloatOrNull() ?: 0.0f else 0.0f
                        val qz = if (parts.size >= 10) parts[9].trim().toFloatOrNull() ?: 0.0f else 0.0f

                        val action = if (parts.size >= 12) {
                            parts[11].trim()
                        } else if (parts.size == 11) {
                            parts[10].trim()
                        } else {
                            "NULL"
                        }

                        waypoints.add(Waypoint3D(seq, t, x, y, z, qw, qx, qy, qz, action))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing CSV file", e)
        }

        computeTrajectoryStatsAndGeometry()
    }

    private fun computeTrajectoryStatsAndGeometry() {
        if (waypoints.isEmpty()) {
            totalPathLengthMeters = 0.0f
            displacementMeters = 0.0f
            tortuosity = 1.0f
            detectedAction = "IDLE"
            return
        }

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        var distSum = 0.0f
        var latchedAction = "IDLE"

        for (i in waypoints.indices) {
            val wp = waypoints[i]
            minX = minOf(minX, wp.x)
            maxX = maxOf(maxX, wp.x)
            minY = minOf(minY, wp.y)
            maxY = maxOf(maxY, wp.y)
            minZ = minOf(minZ, wp.z)
            maxZ = maxOf(maxZ, wp.z)

            if (wp.action.isNotEmpty() && wp.action != "NULL" && wp.action != "IDLE") {
                latchedAction = wp.action
            }

            if (i > 0) {
                val prev = waypoints[i - 1]
                val dx = wp.x - prev.x
                val dy = wp.y - prev.y
                val dz = wp.z - prev.z
                distSum += sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
            }
        }

        val firstWp = waypoints.first()
        val lastWp = waypoints.last()
        val dispDx = lastWp.x - firstWp.x
        val dispDy = lastWp.y - firstWp.y
        val dispDz = lastWp.z - firstWp.z
        val displacement = sqrt((dispDx * dispDx + dispDy * dispDy + dispDz * dispDz).toDouble()).toFloat()
        val tau = distSum / maxOf(displacement, 1e-4f)

        totalPathLengthMeters = distSum
        displacementMeters = displacement
        tortuosity = tau
        detectedAction = latchedAction

        centerX = (minX + maxX) / 2.0f
        centerY = (minY + maxY) / 2.0f
        centerZ = (minZ + maxZ) / 2.0f
        boundsExtent = maxOf(maxX - minX, maxY - minY, maxZ - minZ, 0.4f)
        floorY = minY - (boundsExtent * 0.15f)

        buildTrajectoryBuffers()
        buildGridBuffers()
        buildActionMarkerBuffers()
        buildGizmoBuffers()
    }

    private fun buildTrajectoryBuffers() {
        if (waypoints.isEmpty()) return

        // Interleaved [x, y, z, r, g, b, a]
        val floatsPerVertex = 7
        val vertexData = FloatArray(waypoints.size * floatsPerVertex)

        // Color Gradient: Start = Cyan (#00E5FF) -> End = Neon Green (#00FF66)
        val startR = 0.0f; val startG = 0.898f; val startB = 1.0f
        val endR = 0.0f;   val endG = 1.0f;   val endB = 0.4f

        for (i in waypoints.indices) {
            val wp = waypoints[i]
            val t = if (waypoints.size > 1) i.toFloat() / (waypoints.size - 1) else 0f

            val r = startR + (endR - startR) * t
            val g = startG + (endG - startG) * t
            val b = startB + (endB - startB) * t
            val a = 1.0f

            val offset = i * floatsPerVertex
            vertexData[offset] = wp.x
            vertexData[offset + 1] = wp.y
            vertexData[offset + 2] = wp.z
            vertexData[offset + 3] = r
            vertexData[offset + 4] = g
            vertexData[offset + 5] = b
            vertexData[offset + 6] = a
        }

        trajectoryBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertexData)
                position(0)
            }
    }

    private fun buildGridBuffers() {
        val gridLines = 10
        val size = maxOf(boundsExtent * 1.5f, 0.8f)
        val step = size / gridLines
        val half = size / 2.0f

        val lineCount = (gridLines + 1) * 2
        val floatsPerVertex = 7
        val gridData = FloatArray(lineCount * 2 * floatsPerVertex)
        var idx = 0

        val r = 0.15f; val g = 0.28f; val b = 0.40f; val a = 0.45f

        // X-parallel lines
        for (i in 0..gridLines) {
            val curZ = centerZ - half + (i * step)

            // Point 1
            gridData[idx++] = centerX - half
            gridData[idx++] = floorY
            gridData[idx++] = curZ
            gridData[idx++] = r; gridData[idx++] = g; gridData[idx++] = b; gridData[idx++] = a

            // Point 2
            gridData[idx++] = centerX + half
            gridData[idx++] = floorY
            gridData[idx++] = curZ
            gridData[idx++] = r; gridData[idx++] = g; gridData[idx++] = b; gridData[idx++] = a
        }

        // Z-parallel lines
        for (i in 0..gridLines) {
            val curX = centerX - half + (i * step)

            // Point 1
            gridData[idx++] = curX
            gridData[idx++] = floorY
            gridData[idx++] = centerZ - half
            gridData[idx++] = r; gridData[idx++] = g; gridData[idx++] = b; gridData[idx++] = a

            // Point 2
            gridData[idx++] = curX
            gridData[idx++] = floorY
            gridData[idx++] = centerZ + half
            gridData[idx++] = r; gridData[idx++] = g; gridData[idx++] = b; gridData[idx++] = a
        }

        gridVertexCount = lineCount * 2
        gridBuffer = ByteBuffer.allocateDirect(gridData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(gridData)
                position(0)
            }
    }

    private fun buildActionMarkerBuffers() {
        val actionWps = waypoints.filter {
            it.action.isNotEmpty() && it.action != "NULL" && it.action != "IDLE"
        }
        actionMarkerCount = actionWps.size
        if (actionWps.isEmpty()) return

        val floatsPerVertex = 7
        val markerData = FloatArray(actionWps.size * floatsPerVertex)

        for (i in actionWps.indices) {
            val wp = actionWps[i]
            val offset = i * floatsPerVertex
            markerData[offset] = wp.x
            markerData[offset + 1] = wp.y
            markerData[offset + 2] = wp.z
            // High-contrast Neon Green
            markerData[offset + 3] = 0.0f
            markerData[offset + 4] = 1.0f
            markerData[offset + 5] = 0.4f
            markerData[offset + 6] = 1.0f
        }

        actionMarkersBuffer = ByteBuffer.allocateDirect(markerData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(markerData)
                position(0)
            }
    }

    /**
     * Builds local 3-axis RGB orientation gizmo and phone chassis wireframe.
     * Local space:
     * - Local X-axis (Right): Red (#FF3366, length 0.08m)
     * - Local Y-axis (Up): Green (#00FF66, length 0.08m)
     * - Local Z-axis (Forward/Facing): Cyan (#00E5FF, length 0.08m)
     */
    private fun buildGizmoBuffers() {
        val axisLen = 0.08f

        // 3 lines (6 vertices), 7 floats per vertex
        val axesData = floatArrayOf(
            // X Axis: (0,0,0) -> (axisLen, 0, 0) | Red
            0.0f, 0.0f, 0.0f, 1.0f, 0.20f, 0.40f, 1.0f,
            axisLen, 0.0f, 0.0f, 1.0f, 0.20f, 0.40f, 1.0f,

            // Y Axis: (0,0,0) -> (0, axisLen, 0) | Green
            0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.40f, 1.0f,
            0.0f, axisLen, 0.0f, 0.0f, 1.0f, 0.40f, 1.0f,

            // Z Axis: (0,0,0) -> (0, 0, axisLen) | Cyan
            0.0f, 0.0f, 0.0f, 0.0f, 0.898f, 1.0f, 1.0f,
            0.0f, 0.0f, axisLen, 0.0f, 0.898f, 1.0f, 1.0f
        )

        gizmoAxesBuffer = ByteBuffer.allocateDirect(axesData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(axesData)
                position(0)
            }

        // Phone Chassis Wireframe: rectangle in local XY plane
        val halfW = 0.035f
        val halfH = 0.065f
        val cr = 0.0f; val cg = 0.898f; val cb = 1.0f; val ca = 0.65f

        val chassisData = floatArrayOf(
            -halfW, -halfH, 0.0f, cr, cg, cb, ca,
            halfW, -halfH, 0.0f, cr, cg, cb, ca,
            halfW,  halfH, 0.0f, cr, cg, cb, ca,
            -halfW,  halfH, 0.0f, cr, cg, cb, ca
        )

        chassisVertexCount = 4
        chassisBuffer = ByteBuffer.allocateDirect(chassisData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(chassisData)
                position(0)
            }
    }

    fun getWaypointCount(): Int = waypoints.size

    fun getCurrentScrubbedWaypoint(): Waypoint3D? {
        if (waypoints.isEmpty()) return null
        val totalPts = waypoints.size
        val visibleCount = maxOf(1, (totalPts * scrubProgress.coerceIn(0.0f, 1.0f)).toInt())
        val headIdx = (visibleCount - 1).coerceIn(0, totalPts - 1)
        return waypoints[headIdx]
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.043f, 0.055f, 0.078f, 1.0f) // Dark glass background #0B0E14
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

        programId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vertexShader)
            GLES20.glAttachShader(prog, fragmentShader)
            GLES20.glLinkProgram(prog)
        }

        mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        colorHandle = GLES20.glGetAttribLocation(programId, "aColor")

        buildGizmoBuffers()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / maxOf(height, 1).toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45.0f, ratio, 0.05f, 50.0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (programId == 0) return
        GLES20.glUseProgram(programId)

        // Setup Orbit Camera Matrix
        val cameraDist = maxOf(boundsExtent * 2.2f, 1.0f) * zoomScale
        val radPitch = Math.toRadians(touchAngleY.toDouble().coerceIn(-85.0, 85.0))
        val radYaw = Math.toRadians(touchAngleX.toDouble())

        val eyeX = centerX + (cameraDist * cos(radPitch) * sin(radYaw)).toFloat()
        val eyeY = centerY + (cameraDist * sin(radPitch)).toFloat()
        val eyeZ = centerZ + (cameraDist * cos(radPitch) * cos(radYaw)).toFloat()

        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, eyeZ,
            centerX, centerY, centerZ,
            0.0f, 1.0f, 0.0f
        )

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vPMatrix, 0, modelMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        // 1. Draw Reference Floor Grid
        gridBuffer?.let { buf ->
            buf.position(0)
            GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
            GLES20.glEnableVertexAttribArray(positionHandle)

            buf.position(COORDS_PER_VERTEX)
            GLES20.glVertexAttribPointer(colorHandle, COLOR_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
            GLES20.glEnableVertexAttribArray(colorHandle)

            GLES20.glLineWidth(1.5f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertexCount)
        }

        // 2. Draw 3D Trajectory Ribbon (GL_LINE_STRIP) with Scrubbing support
        var currentHeadWp: Waypoint3D? = null
        trajectoryBuffer?.let { buf ->
            val totalPts = waypoints.size
            if (totalPts > 0) {
                val visibleCount = maxOf(1, (totalPts * scrubProgress.coerceIn(0.0f, 1.0f)).toInt())
                currentHeadWp = waypoints[(visibleCount - 1).coerceIn(0, totalPts - 1)]

                buf.position(0)
                GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(positionHandle)

                buf.position(COORDS_PER_VERTEX)
                GLES20.glVertexAttribPointer(colorHandle, COLOR_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(colorHandle)

                GLES20.glLineWidth(6.0f)
                GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, visibleCount)

                // Draw scrub head point indicator
                currentHeadWp?.let { headWp ->
                    val headData = floatArrayOf(
                        headWp.x, headWp.y, headWp.z,
                        1.0f, 0.84f, 0.0f, 1.0f // Bright Yellow (#FFD600)
                    )
                    val headBuf = ByteBuffer.allocateDirect(headData.size * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                        .apply {
                            put(headData)
                            position(0)
                        }

                    headBuf.position(0)
                    GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, headBuf)
                    headBuf.position(COORDS_PER_VERTEX)
                    GLES20.glVertexAttribPointer(colorHandle, COLOR_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE, headBuf)
                    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
                }
            }
        }

        // 3. Draw Discrete Action Markers (Points)
        actionMarkersBuffer?.let { buf ->
            if (actionMarkerCount > 0) {
                buf.position(0)
                GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(positionHandle)

                buf.position(COORDS_PER_VERTEX)
                GLES20.glVertexAttribPointer(colorHandle, COLOR_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(colorHandle)

                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, actionMarkerCount)
            }
        }

        // 4. Render 6-DOF Orientation Gizmo at Scrub Cursor
        currentHeadWp?.let { headWp ->
            quaternionToRotationMatrix(headWp.qw, headWp.qx, headWp.qy, headWp.qz, rotMatrix)

            Matrix.setIdentityM(gizmoTranslateMatrix, 0)
            Matrix.translateM(gizmoTranslateMatrix, 0, headWp.x, headWp.y, headWp.z)
            Matrix.multiplyMM(gizmoModelMatrix, 0, gizmoTranslateMatrix, 0, rotMatrix, 0)
            Matrix.multiplyMM(gizmoMvpMatrix, 0, vPMatrix, 0, gizmoModelMatrix, 0)

            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, gizmoMvpMatrix, 0)

            // Draw RGB 3-axis Gizmo (6 vertices = 3 lines)
            gizmoAxesBuffer?.let { buf ->
                buf.position(0)
                GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(positionHandle)

                buf.position(COORDS_PER_VERTEX)
                GLES20.glVertexAttribPointer(colorHandle, COLOR_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(colorHandle)

                GLES20.glLineWidth(6.0f)
                GLES20.glDrawArrays(GLES20.GL_LINES, 0, 6)
            }

            // Draw miniature oriented phone chassis wireframe (4 vertices loop)
            chassisBuffer?.let { buf ->
                buf.position(0)
                GLES20.glVertexAttribPointer(positionHandle, COORDS_PER_VERTEX, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(positionHandle)

                buf.position(COORDS_PER_VERTEX)
                GLES20.glVertexAttribPointer(colorHandle, COLOR_COMPONENTS, GLES20.GL_FLOAT, false, VERTEX_STRIDE, buf)
                GLES20.glEnableVertexAttribArray(colorHandle)

                GLES20.glLineWidth(2.5f)
                GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, chassisVertexCount)
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
