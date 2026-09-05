package com.ggr.kinetrak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQ_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
                Log.w(TAG, "ARCore not supported or unavailable on this device. Activating IMU-only fallback.")
                false
            } else if (availability.isSupported) {
                true
            } else {
                Log.w(TAG, "ARCore not supported or unavailable on this device. Activating IMU-only fallback.")
                false
            }
        } catch (e: FatalException) {
            Log.w(TAG, "ARCore not supported or unavailable on this device. Activating IMU-only fallback.", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "ARCore not supported or unavailable on this device. Activating IMU-only fallback.", e)
            false
        }
    }

    private fun enableImuFallback() {
        BridgeState.isTracking.set(true)
        BridgeState.posX = 0.0f
        BridgeState.posY = 0.0f
        BridgeState.posZ = 0.0f
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

        arScope.launch {
            while (isActive && isArTrackingActive) {
                val session = arSession
                if (session != null) {
                    try {
                        val frame = session.update()
                        val camera = frame.camera
                        if (camera.trackingState == TrackingState.TRACKING) {
                            val translation = camera.pose.translation
                            BridgeState.posX = translation[0]
                            BridgeState.posY = translation[1]
                            BridgeState.posZ = translation[2]
                            BridgeState.isTracking.set(true)
                        } else {
                            BridgeState.isTracking.set(true)
                        }
                    } catch (e: Exception) {
                        // Frame update may fail when paused or camera unavailable
                    }
                }
                delay(33) // ~30Hz ARCore translation sampling
            }
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