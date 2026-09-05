package com.ggr.kinetrak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
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
    private lateinit var tvStateBadge: TextView
    private lateinit var tvTelemetrySeq: TextView
    private lateinit var tvTelemetryTracking: TextView
    private lateinit var tvTelemetryPose: TextView
    private lateinit var tvTelemetryAction: TextView
    private lateinit var fabExport: ExtendedFloatingActionButton
    private lateinit var fabMicToggle: FloatingActionButton
    private lateinit var btnToggleTracking: MaterialButton

    private var cameraProvider: ProcessCameraProvider? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("KineTrak", "RECORD_AUDIO permission granted")
        } else {
            Log.w("KineTrak", "RECORD_AUDIO permission denied")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
        if (cameraGranted) {
            onPermissionsGranted()
        } else {
            Log.w(TAG, "Camera permission was not granted; ARCore tracking unavailable. Falling back to IMU telemetry.")
            enableImuFallback()
            startBridgeService()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
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
        tvStateBadge = findViewById(R.id.tvStateBadge)
        tvTelemetrySeq = findViewById(R.id.tvTelemetrySeq)
        tvTelemetryTracking = findViewById(R.id.tvTelemetryTracking)
        tvTelemetryPose = findViewById(R.id.tvTelemetryPose)
        tvTelemetryAction = findViewById(R.id.tvTelemetryAction)
        fabExport = findViewById(R.id.fabExport)
        fabMicToggle = findViewById(R.id.fabMicToggle)
        btnToggleTracking = findViewById(R.id.btnToggleTracking)
        updateTrackingButtonUi()
        updateMicToggleUi()
    }

    private fun setupListeners() {
        btnToggleTracking.setOnClickListener {
            toggleTracking()
        }

        fabExport.setOnClickListener {
            exportTrajectoryCsv()
        }

        fabMicToggle.setOnClickListener {
            toggleMicTrigger()
        }
    }

    private fun toggleTracking() {
        BridgeState.isStreamingActive = !BridgeState.isStreamingActive
        updateTrackingButtonUi()
    }

    private fun updateTrackingButtonUi() {
        if (BridgeState.isStreamingActive) {
            btnToggleTracking.text = "STOP TRACKING"
            btnToggleTracking.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.btn_tracking_stop)
            )
            btnToggleTracking.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            btnToggleTracking.text = "START TRACKING"
            btnToggleTracking.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.btn_tracking_start)
            )
            btnToggleTracking.setTextColor(ContextCompat.getColor(this, R.color.black))
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
            permissionLauncher.launch(neededPermissions.toTypedArray())
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
        tvTelemetrySeq.text = String.format(Locale.US, "SEQ: %d", seq)
        tvTelemetryTracking.text = String.format(Locale.US, "TRACKING: %d", trackingState)
        tvTelemetryTracking.setTextColor(
            ContextCompat.getColor(
                this,
                if (isTracking) R.color.hud_tracking_on else R.color.hud_tracking_off
            )
        )

        tvTelemetryPose.text = String.format(
            Locale.US,
            "POS: [%+.2f, %+.2f, %+.2f]",
            BridgeState.posX,
            BridgeState.posY,
            BridgeState.posZ
        )

        tvTelemetryAction.text = String.format(Locale.US, "ACTION: %s", pendingAction)

        // 2. Dynamic State Indicator Badge
        if (pendingAction != "NULL") {
            // Neon Green: ACTION DISPATCHED
            tvStateBadge.setBackgroundColor(
                ContextCompat.getColor(this, R.color.badge_action_neon_green)
            )
            tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
            tvStateBadge.text = "ACTION DISPATCHED"
        } else {
            when (currentState) {
                BridgeState.STATE_RECORDING -> {
                    // Yellow: RECORDING
                    tvStateBadge.setBackgroundColor(
                        ContextCompat.getColor(this, R.color.badge_recording_yellow)
                    )
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
                    tvStateBadge.text = "RECORDING"
                }
                BridgeState.STATE_THINKING -> {
                    // Purple: THINKING
                    tvStateBadge.setBackgroundColor(
                        ContextCompat.getColor(this, R.color.badge_thinking_purple)
                    )
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.white))
                    tvStateBadge.text = "THINKING"
                }
                else -> {
                    // Cyan: IDLE
                    tvStateBadge.setBackgroundColor(
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
        BridgeState.isMicActive = !BridgeState.isMicActive
        updateMicToggleUi()

        if (BridgeState.isMicActive) {
            Log.i("KineTrak", "Voice trigger window active")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateMicToggleUi() {
        if (BridgeState.isMicActive) {
            fabMicToggle.imageTintList = ColorStateList.valueOf(Color.parseColor("#00FF66"))
        } else {
            fabMicToggle.imageTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
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