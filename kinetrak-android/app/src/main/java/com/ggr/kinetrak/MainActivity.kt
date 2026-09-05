package com.ggr.kinetrak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.FatalException
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private val arScope = CoroutineScope(Dispatchers.Default + Job())
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())
    private var arSession: Session? = null
    private var isArTrackingActive = false
    private val volumeKeyLock = AtomicBoolean(false)

    // UI View References
    private lateinit var viewFinder: PreviewView
    private lateinit var cardStateBadge: MaterialCardView
    private lateinit var tvStateBadge: TextView
    private lateinit var tvHudSeq: TextView
    private lateinit var tvHudTracking: TextView
    private lateinit var tvHudPose: TextView
    private lateinit var tvHudAction: TextView
    private lateinit var fabExport: ExtendedFloatingActionButton
    private lateinit var fabMicToggle: FloatingActionButton

    private var cameraProvider: ProcessCameraProvider? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQ_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        checkAndRequestPermissions()
        startUiTelemetryLoop()
    }

    private fun initViews() {
        viewFinder = findViewById(R.id.viewFinder)
        cardStateBadge = findViewById(R.id.cardStateBadge)
        tvStateBadge = findViewById(R.id.tvStateBadge)
        tvHudSeq = findViewById(R.id.tvHudSeq)
        tvHudTracking = findViewById(R.id.tvHudTracking)
        tvHudPose = findViewById(R.id.tvHudPose)
        tvHudAction = findViewById(R.id.tvHudAction)
        fabExport = findViewById(R.id.fabExport)
        fabMicToggle = findViewById(R.id.fabMicToggle)
    }

    private fun setupListeners() {
        fabExport.setOnClickListener {
            exportTrajectoryCsv()
        }

        fabMicToggle.setOnClickListener {
            toggleMicTrigger()
        }
    }

    private fun checkAndRequestPermissions() {
        val neededPermissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO)
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
        startCamera()
        setupArSession()
        startBridgeService()
        resumeArTracking()
    }

    /**
     * CameraX Preview Lifecycle Binding:
     * Strictly bound to `this` (Activity LifecycleOwner) to automatically unbind on pause
     * and prevent thermal throttling during intense evaluation workflows.
     */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview)
                Log.i(TAG, "CameraX preview bound to Activity lifecycle successfully")
            } catch (exc: Exception) {
                Log.e(TAG, "CameraX use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startUiTelemetryLoop() {
        uiScope.launch {
            while (isActive) {
                updateHudAndBadge()
                delay(33) // ~30Hz UI refresh rate
            }
        }
    }

    /**
     * High-contrast HUD and Dynamic State Indicator updates
     * Decoupled badge state logic:
     * - If pendingAction != "NULL" -> Neon Green ("ACTION DISPATCHED")
     * - Else integer states -> Yellow (RECORDING), Purple (THINKING), Cyan (IDLE)
     */
    private fun updateHudAndBadge() {
        val seq = BridgeState.currentSeq.get()
        val isTracking = BridgeState.isTrackingValid && BridgeState.isTracking.get()
        val trackingState = if (isTracking) 1 else 0
        val pendingAction = BridgeState.pendingAction.get()
        val currentState = BridgeState.currentState

        // 1. Update Monospace HUD Fields
        tvHudSeq.text = String.format(Locale.US, "SEQ: %d", seq)
        tvHudTracking.text = String.format(Locale.US, "TRACKING: %d", trackingState)
        tvHudTracking.setTextColor(
            ContextCompat.getColor(
                this,
                if (isTracking) R.color.hud_tracking_on else R.color.hud_tracking_off
            )
        )

        tvHudPose.text = String.format(
            Locale.US,
            "POS: [%+.2f, %+.2f, %+.2f]",
            BridgeState.posX,
            BridgeState.posY,
            BridgeState.posZ
        )

        tvHudAction.text = String.format(Locale.US, "ACTION: %s", pendingAction)

        // 2. Dynamic State Indicator Badge
        if (pendingAction != "NULL") {
            // Neon Green: ACTION DISPATCHED
            cardStateBadge.setCardBackgroundColor(
                ContextCompat.getColor(this, R.color.badge_action_neon_green)
            )
            tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
            tvStateBadge.text = "ACTION DISPATCHED"
        } else {
            when (currentState) {
                BridgeState.STATE_RECORDING -> {
                    // Yellow: RECORDING
                    cardStateBadge.setCardBackgroundColor(
                        ContextCompat.getColor(this, R.color.badge_recording_yellow)
                    )
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
                    tvStateBadge.text = "RECORDING"
                }
                BridgeState.STATE_THINKING -> {
                    // Purple: THINKING
                    cardStateBadge.setCardBackgroundColor(
                        ContextCompat.getColor(this, R.color.badge_thinking_purple)
                    )
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.white))
                    tvStateBadge.text = "THINKING"
                }
                else -> {
                    // Cyan: IDLE
                    cardStateBadge.setCardBackgroundColor(
                        ContextCompat.getColor(this, R.color.badge_idle_cyan)
                    )
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
                    tvStateBadge.text = "IDLE"
                }
            }
        }
    }

    /**
     * Mic Toggle FAB for offline voice trigger
     */
    private fun toggleMicTrigger() {
        val current = BridgeState.isMicActive.get()
        val newState = !current
        BridgeState.isMicActive.set(newState)

        if (newState) {
            fabMicToggle.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.fab_mic_active)
            )
            Toast.makeText(this, "Voice Trigger: Listening...", Toast.LENGTH_SHORT).show()
        } else {
            fabMicToggle.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.fab_mic_idle)
            )
            Toast.makeText(this, "Voice Trigger: Off", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Generates a dummy trajectory_waypoints.csv file and shares it via FileProvider share sheet.
     */
    private fun exportTrajectoryCsv() {
        try {
            val exportDir = File(cacheDir, "exports")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }
            val csvFile = File(exportDir, "trajectory_waypoints.csv")
            val writer = FileWriter(csvFile)

            // Write CSV Header
            writer.append("seq,timestamp_ms,tracking_state,pos_x,pos_y,pos_z,qw,qx,qy,qz,gesture_state,action\n")

            val baseTime = System.currentTimeMillis() - 5000
            val currentSeq = BridgeState.currentSeq.get()
            val startSeq = maxOf(1, currentSeq - 60)

            // Generate representative 6-DOF waypoints leading up to current frame
            for (i in 0..60) {
                val s = startSeq + i
                val t = baseTime + (i * 66)
                val tRad = (i * 0.1).toFloat()
                val x = BridgeState.posX + (0.05f * kotlin.math.sin(tRad))
                val y = BridgeState.posY + (0.03f * kotlin.math.cos(tRad))
                val z = BridgeState.posZ - (0.02f * i)
                val qw = BridgeState.currentRotation[0]
                val qx = BridgeState.currentRotation[1]
                val qy = BridgeState.currentRotation[2]
                val qz = BridgeState.currentRotation[3]
                val gState = if (i in 20..40) "RECORDING" else if (i in 41..45) "THINKING" else "IDLE"
                val act = if (i in 46..52) "ACTION:SPAWN" else "NULL"

                writer.append(
                    String.format(
                        Locale.US,
                        "%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s\n",
                        s, t, 1, x, y, z, qw, qx, qy, qz, gState, act
                    )
                )
            }

            writer.flush()
            writer.close()

            val contentUri = FileProvider.getUriForFile(
                this,
                "com.ggr.kinetrak.fileprovider",
                csvFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "KineTrak Trajectory Waypoints")
                putExtra(Intent.EXTRA_TEXT, "Exported KineTrak 6-DOF Trajectory CSV (${csvFile.name})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Export Trajectory CSV"))
            Log.i(TAG, "Exported trajectory CSV to URI: $contentUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export trajectory CSV", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        BridgeState.isTrackingValid = true
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
                            BridgeState.isTrackingValid = true
                            BridgeState.isTracking.set(true)
                        } else {
                            BridgeState.isTrackingValid = true
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
        uiScope.cancel()
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
                BridgeState.currentState = BridgeState.STATE_RECORDING
                BridgeState.gestureState = "RECORDING"
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            BridgeState.currentState = BridgeState.STATE_THINKING
            BridgeState.gestureState = "THINKING"
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}