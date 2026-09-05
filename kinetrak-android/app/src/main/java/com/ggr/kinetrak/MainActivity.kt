package com.ggr.kinetrak

import com.ggr.kinetrak.tracking.SensorFusionHub
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.FatalException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val arScope = CoroutineScope(Dispatchers.Default + Job())
    private var arSession: Session? = null
    private var isArTrackingActive = false
    private val volumeKeyLock = AtomicBoolean(false)
    private lateinit var sensorFusionHub: SensorFusionHub

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var cameraTextureId: Int = -1

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQ_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorFusionHub = SensorFusionHub(this)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (neededPermissions.isEmpty()) {
            onPermissionsGranted()
        } else {
            ActivityCompat.requestPermissions(
                this,
                neededPermissions.toTypedArray(),
                PERMISSION_REQ_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            val cameraGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {
                onPermissionsGranted()
            } else {
                Log.w(TAG, "Camera permission was not granted; ARCore tracking unavailable. Falling back to IMU telemetry.")
                enableImuFallback()
                startBridgeService()
            }
        }
    }

    private fun onPermissionsGranted() {
        setupArSession()
        startBridgeService()
        resumeArTracking()
    }

    private fun checkArCoreAvailability(): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            availability != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE
        } catch (e: Exception) {
            true
        }
    }

    private fun enableImuFallback() {
        BridgeState.isTracking.set(true)
        BridgeState.posX = 0.0f
        BridgeState.posY = 0.0f
        BridgeState.posZ = 0.0f
    }

    private fun initOffscreenGl(): Int {
        return try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return -1
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return -1

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            if (numConfigs[0] == 0 || configs[0] == null) return -1

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return -1

            val pbufferAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return -1

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            cameraTextureId = textures[0]
            Log.i(TAG, "Generated offscreen camera texture ID: $cameraTextureId")
            cameraTextureId
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize offscreen EGL context for ARCore: ${e.message}", e)
            -1
        }
    }

    private fun releaseOffscreenGl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
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
            Log.w(TAG, "Error releasing offscreen EGL: ${e.message}")
        }
    }

    private fun setupArSession() {
        if (arSession != null) return
        try {
            if (!checkArCoreAvailability()) {
                enableImuFallback()
                return
            }
            val session = Session(this)
            val config = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
            session.configure(config)
            arSession = session
            Log.i(TAG, "ARCore Session successfully initialized and configured")
        } catch (e: FatalException) {
            Log.w(TAG, "ARCore not supported or unavailable on this device. Activating IMU-only fallback.", e)
            arSession = null
            enableImuFallback()
        } catch (e: Exception) {
            Log.w(TAG, "ARCore not supported or unavailable on this device. Activating IMU-only fallback.", e)
            arSession = null
            enableImuFallback()
        }
    }

    private fun resumeArTracking() {
        val session = arSession
        if (session == null) {
            enableImuFallback()
            return
        }
        try {
            session.resume()
            startArTrackingLoop()
        } catch (e: Exception) {
            Log.w(TAG, "Error resuming ARCore Session: ${e.message}. Falling back to IMU tracking.", e)
            enableImuFallback()
        }
    }

    private fun startArTrackingLoop() {
        if (isArTrackingActive) return
        isArTrackingActive = true

        arScope.launch(Dispatchers.Default) {
            val textureId = initOffscreenGl()
            if (textureId > 0 && arSession != null) {
                try {
                    arSession?.setCameraTextureName(textureId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind camera texture name: ${e.message}")
                }
            }

            while (isActive && isArTrackingActive) {
                val session = arSession
                if (session != null) {
                    try {
                        val frame = session.update()
                        sensorFusionHub.onArCoreFrame(frame)
                    } catch (e: Exception) {
                        // Frame update may fail when paused or camera unavailable
                    }
                }
                delay(33) // ~30Hz ARCore translation sampling
            }

            releaseOffscreenGl()
        }
    }

    private fun startBridgeService() {
        val serviceIntent = Intent(this, ClipboardBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            if (arSession == null) {
                setupArSession()
            }
            resumeArTracking()
        } else {
            enableImuFallback()
        }
    }

    override fun onPause() {
        isArTrackingActive = false
        try {
            arSession?.pause()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing ARCore session", e)
        }
        super.onPause()
    }

    override fun onDestroy() {
        isArTrackingActive = false
        arScope.cancel()
        releaseOffscreenGl()
        if (::sensorFusionHub.isInitialized) {
            sensorFusionHub.release()
        }
        try {
            arSession?.close()
            arSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ARCore session", e)
        }
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event == null || event.repeatCount == 0) {
                if (volumeKeyLock.compareAndSet(false, true)) {
                    try {
                        val current = BridgeState.currentState.get()
                        if (current == "RECORDING") {
                            BridgeState.currentState.set("IDLE")
                        } else if (current == "IDLE" || current == "NULL") {
                            BridgeState.currentState.set("RECORDING")
                        }
                    } finally {
                        volumeKeyLock.set(false)
                    }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}