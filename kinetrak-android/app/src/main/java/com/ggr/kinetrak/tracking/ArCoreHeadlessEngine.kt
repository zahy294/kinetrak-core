package com.ggr.kinetrak.tracking

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.ggr.kinetrak.BridgeState
import com.ggr.kinetrak.math.OneEuroFilter3D
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless ARCore VIO Engine running on a dedicated HandlerThread ("ARCoreWorker").
 *
 * Configures an offscreen 1x1 PBuffer EGL context and external OES texture,
 * initializes ARCore with BLOCKING update mode and AUTO focus mode,
 * sets a realistic display geometry (1080x1920) to prevent optical flow projection collapse,
 * and continuously runs VIO pose updates with OneEuro filtering.
 */
class ArCoreHeadlessEngine(
    private val context: Context,
    private val sensorFusionHub: SensorFusionHub? = null
) {
    companion object {
        private const val TAG = "ArCoreHeadlessEngine"
        private const val VIO_TAG = "KineTrak"
        private const val VIO_SCALE = 1.0f
        private const val ZERO_ORDER_HOLD_TIMEOUT_MS = 500L
    }

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val isRunning = AtomicBoolean(false)
    private var arSession: Session? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesTextureId: Int = -1

    private val oneEuroFilter3D = OneEuroFilter3D(minCutoff = 1.2f, beta = 0.08f)
    private val lastValidPos = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var lastTrackingTimestamp: Long = 0L

    @Synchronized
    fun start() {
        if (isRunning.get()) {
            Log.w(TAG, "ArCoreHeadlessEngine is already running.")
            return
        }
        isRunning.set(true)
        oneEuroFilter3D.reset()

        val thread = HandlerThread("ARCoreWorker").apply { start() }
        workerThread = thread
        val handler = Handler(thread.looper)
        workerHandler = handler

        handler.post {
            try {
                if (!initEglContext()) {
                    Log.e(TAG, "Failed to initialize EGL context on worker thread.")
                    return@post
                }
                if (!initArCore()) {
                    Log.e(TAG, "Failed to initialize ARCore session.")
                    releaseEgl()
                    return@post
                }
                startLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Exception during worker thread execution: ${e.message}", e)
            } finally {
                cleanupResources()
            }
        }
    }

    @Synchronized
    fun stop() {
        Log.i(TAG, "stop() called on ArCoreHeadlessEngine (isRunning was ${isRunning.get()})")
        if (!isRunning.get()) return
        isRunning.set(false)

        try {
            arSession?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing ARCore session: ${e.message}")
        }

        workerThread?.quitSafely()
        try {
            workerThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workerThread = null
        workerHandler = null
    }

    private fun initEglContext(): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay returned EGL_NO_DISPLAY")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            return false
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0 || configs[0] == null
        ) {
            Log.e(TAG, "eglChooseConfig failed")
            return false
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed")
            return false
        }

        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface failed")
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "eglMakeCurrent failed during init")
            return false
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        oesTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        Log.i(TAG, "EGL context initialized, OES textureId=$oesTextureId")
        return true
    }

    private fun initArCore(): Boolean {
        return try {
            val session = Session(context)
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.BLOCKING
                focusMode = Config.FocusMode.AUTO
            }
            session.configure(config)
            session.setCameraTextureName(oesTextureId)
            // CRITICAL: Call session.setDisplayGeometry(Surface.ROTATION_0, 1080, 1920)
            // NEVER pass 1x1, as that collapses the optical flow projection matrix.
            session.setDisplayGeometry(Surface.ROTATION_0, 1080, 1920)

            var resumed = false
            var retryCount = 0
            while (!resumed && retryCount < 10) {
                try {
                    session.resume()
                    resumed = true
                    Log.i(TAG, "ARCore session resumed on attempt ${retryCount + 1}")
                } catch (e: CameraNotAvailableException) {
                    retryCount++
                    Log.w(TAG, "Camera2 HAL busy (attempt $retryCount/10), retrying in 100ms: ${e.message}")
                    Thread.sleep(100)
                } catch (e: Exception) {
                    retryCount++
                    Log.w(TAG, "ARCore resume retry (attempt $retryCount/10): ${e.message}")
                    Thread.sleep(100)
                }
            }

            if (!resumed) {
                Log.e(TAG, "Failed to acquire camera sensor after 10 retries.")
                session.close()
                return false
            }

            arSession = session
            true
        } catch (e: Exception) {
            Log.e(TAG, "ARCore initialization failed: ${e.message}", e)
            false
        }
    }

    private fun startLoop() {
        Log.i(TAG, "Starting ARCore headless processing loop on ARCoreWorker thread.")
        while (isRunning.get() && !Thread.currentThread().isInterrupted) {
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE && eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                }

                val session = arSession ?: break
                val frame = session.update() ?: continue

                processFrame(frame)
            } catch (e: SessionPausedException) {
                Log.d(TAG, "ARCore session paused; sleeping 50ms")
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "ARCoreWorker thread interrupted cleanly.")
                break
            } catch (t: Throwable) {
                Log.w(TAG, "Transient update warning: ${t.javaClass.simpleName} - ${t.message}")
                try {
                    Thread.sleep(16)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        Log.i(TAG, "ARCore processing loop ended. isRunning=${isRunning.get()}, interrupted=${Thread.currentThread().isInterrupted}")
    }

    /**
     * Telemetry Guard: Validates trackingState and trackingFailureReason before emitting poses.
     * Applies a 500ms Zero-Order Hold (ZOH) on tracking drop or failure to prevent degraded frame emission.
     * Uses raw frame.camera.pose directly for gravity-aligned world coordinates.
     */
    private fun processFrame(frame: Frame) {
        val trackingState = frame.camera.trackingState
        val failureReason = frame.camera.trackingFailureReason

        if (trackingState == TrackingState.TRACKING && failureReason == TrackingFailureReason.NONE) {
            // Use raw frame.camera.pose directly for gravity-aligned world coordinates
            val pose = frame.camera.pose
            val rawX = pose.tx()
            val rawY = pose.ty()
            val rawZ = pose.tz()

            val timestampSec = if (frame.timestamp > 0L) {
                frame.timestamp / 1_000_000_000.0
            } else {
                System.nanoTime() / 1_000_000_000.0
            }

            val filtered = oneEuroFilter3D.filter(floatArrayOf(rawX, rawY, rawZ), timestampSec)
            val fx = filtered[0] * VIO_SCALE
            val fy = filtered[1] * VIO_SCALE
            val fz = filtered[2] * VIO_SCALE

            lastValidPos[0] = fx
            lastValidPos[1] = fy
            lastValidPos[2] = fz
            lastTrackingTimestamp = SystemClock.elapsedRealtime()

            BridgeState.isTrackingValid = true
            BridgeState.isTracking.set(true)
            BridgeState.setPosition(fx, fy, fz)
            sensorFusionHub?.onArCoreFrame(frame)

            Log.d(VIO_TAG, "VIO Pos: $fx, $fy, $fz")
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastTrackingTimestamp
            if (lastTrackingTimestamp > 0L && elapsed <= ZERO_ORDER_HOLD_TIMEOUT_MS) {
                // Apply Zero-Order Hold: repeat the last known position for up to 500ms
                BridgeState.isTrackingValid = true
                BridgeState.isTracking.set(true)
                BridgeState.setPosition(lastValidPos[0], lastValidPos[1], lastValidPos[2])
            } else {
                // Tracking lost beyond 500ms: mark invalid, but retain last known coordinates (do not reset to zero)
                BridgeState.isTrackingValid = false
                BridgeState.isTracking.set(false)
                BridgeState.setPosition(lastValidPos[0], lastValidPos[1], lastValidPos[2])
            }
            sensorFusionHub?.onArCoreFrame(frame)
            Log.d(TAG, "ARCore frame degraded/lost, trackingState=$trackingState, trackingFailureReason=$failureReason")
        }
    }

    private fun cleanupResources() {
        try {
            arSession?.pause()
        } catch (_: Exception) {}
        try {
            arSession?.close()
        } catch (_: Exception) {}
        arSession = null

        releaseEgl()
        Log.i(TAG, "ArCoreHeadlessEngine resources cleaned up.")
    }

    private fun releaseEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
                if (oesTextureId != -1) {
                    val textures = intArrayOf(oesTextureId)
                    GLES20.glDeleteTextures(1, textures, 0)
                    oesTextureId = -1
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing EGL: ${e.message}")
        }
    }
}
