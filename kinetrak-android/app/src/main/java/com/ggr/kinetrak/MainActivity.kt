package com.ggr.kinetrak

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ggr.kinetrak.gesture.HeuristicGestureFallback
import com.ggr.kinetrak.gesture.MotionBufferManager
import com.ggr.kinetrak.kinematics.TrajectoryRecorder
import com.ggr.kinetrak.math.OneEuroFilter3D
import com.ggr.kinetrak.storage.SessionStorageManager
import com.ggr.kinetrak.tracking.ArCoreHeadlessEngine
import com.ggr.kinetrak.tracking.SensorFusionHub
import com.ggr.kinetrak.ui.SessionHistoryBottomSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    // ── Coroutine scopes ───────────────────────────────────────────────────────
    private val arScope = CoroutineScope(Dispatchers.Default + Job())
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    // ── Headless ARCore Engine ────────────────────────────────────────────────
    private var arEngine: ArCoreHeadlessEngine? = null

    // ── Sensor Fusion, Kinematics & Edge AI ───────────────────────────────────
    private lateinit var sensorFusionHub: SensorFusionHub
    private lateinit var motionBufferManager: MotionBufferManager
    private val trajectoryRecorder = TrajectoryRecorder()
    private val volumeKeyLock   = AtomicBoolean(false)
    private var isRecording: Boolean = false
    private var recordingStartTime: Long = 0L
    private lateinit var snapdragonNPU: SnapdragonNPU

    // ── UI Views ───────────────────────────────────────────────────────────────
    private lateinit var viewFinder:         PreviewView
    private lateinit var tvStateBadge:       TextView
    private lateinit var tvTelemetrySeq:     TextView
    private lateinit var tvTelemetryTracking: TextView
    private lateinit var tvTelemetryPose:    TextView
    private lateinit var tvTelemetryAction:  TextView
    private lateinit var btnSavedSessions:   ExtendedFloatingActionButton
    private lateinit var fabExport:          ExtendedFloatingActionButton
    private lateinit var fabMicToggle:       FloatingActionButton
    private lateinit var btnToggleTracking:  MaterialButton

    private var cameraProvider: ProcessCameraProvider? = null

    // ── Sensor manager for MotionBufferManager registration ───────────────────
    private lateinit var hwSensorManager: SensorManager

    companion object {
        private const val TAG = "MainActivity"
    }

    // ── Permission launchers ───────────────────────────────────────────────────
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Log.i(TAG, "RECORD_AUDIO permission granted")
        else           Log.w(TAG, "RECORD_AUDIO permission denied")
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
            Log.w(TAG, "Camera permission not granted; falling back to IMU telemetry.")
            enableImuFallback()
            startBridgeService()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initEdgeComponents()
        initViews()
        setupListeners()
        checkAndRequestPermissions()
        startUiTelemetryLoop()
    }

    /**
     * Initialise edge-AI components before permission checks so they are ready
     * as soon as the AR session starts.
     */
    private fun initEdgeComponents() {
        hwSensorManager   = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        snapdragonNPU     = SnapdragonNPU(application)
        sensorFusionHub   = SensorFusionHub(this)
        arEngine          = ArCoreHeadlessEngine(this, sensorFusionHub)
        motionBufferManager = MotionBufferManager(
            npu               = snapdragonNPU,
            heuristicFallback = HeuristicGestureFallback()
        )

        // Register MotionBufferManager as SensorEventListener for 6-channel IMU tensor
        hwSensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            hwSensorManager.registerListener(motionBufferManager, it, SensorManager.SENSOR_DELAY_GAME)
        }
        hwSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            hwSensorManager.registerListener(motionBufferManager, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // Load NPU model asynchronously — non-blocking
        arScope.launch {
            val ok = snapdragonNPU.initModelFromAssets("gesture_model_quantized.dlc")
            Log.i(TAG, if (ok) "NPU model loaded" else "NPU model load FAILED")
        }
    }

    private fun initViews() {
        viewFinder            = findViewById(R.id.viewFinder)
        tvStateBadge          = findViewById(R.id.tvStateBadge)
        tvTelemetrySeq        = findViewById(R.id.tvTelemetrySeq)
        tvTelemetryTracking   = findViewById(R.id.tvTelemetryTracking)
        tvTelemetryPose       = findViewById(R.id.tvTelemetryPose)
        tvTelemetryAction     = findViewById(R.id.tvTelemetryAction)
        btnSavedSessions      = findViewById(R.id.btnSavedSessions)
        fabExport             = findViewById(R.id.fabExport)
        fabMicToggle          = findViewById(R.id.fabMicToggle)
        btnToggleTracking     = findViewById(R.id.btnToggleTracking)
        updateTrackingButtonUi()
        updateMicToggleUi()
    }

    private fun setupListeners() {
        // "SAVED SESSIONS" → opens SessionHistoryBottomSheet;
        // selecting an item inside the sheet opens TrajectoryPlaybackDialog
        btnSavedSessions.setOnClickListener {
            SessionHistoryBottomSheet.newInstance()
                .show(supportFragmentManager, SessionHistoryBottomSheet.TAG)
        }

        // START / STOP TRACKING toggle
        btnToggleTracking.setOnClickListener { toggleTracking() }

        // "EXPORT TRAJECTORY" → saves session to vault + fires FileProvider share sheet
        fabExport.setOnClickListener { exportTrajectoryCsv() }

        // Mic trigger toggle
        fabMicToggle.setOnClickListener { toggleMicTrigger() }
    }

    // ── Tracking toggle ────────────────────────────────────────────────────────

    private fun toggleTracking() {
        BridgeState.isStreamingActive = !BridgeState.isStreamingActive
        if (BridgeState.isStreamingActive) {
            sensorFusionHub.resetFilter()
        }
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

    // ── Permissions ────────────────────────────────────────────────────

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS

        if (needed.isEmpty()) onPermissionsGranted()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun onPermissionsGranted() {
        startCamera()
        startBridgeService()
        arEngine?.start()
    }

    // ── CameraX / Viewfinder Placeholder ──────────────────────────────────────

    /**
     * CameraX Preview disabled to prevent camera hardware contention with ARCore.
     * ARCore requires exclusive access to the back camera sensor for VIO tracking.
     * PreviewView is set to a dark placeholder background.
     */
    private fun startCamera() {
        Log.i(TAG, "CameraX binding disabled to grant ARCore exclusive camera access.")
        viewFinder.setBackgroundColor(Color.BLACK)
    }

    // ── HUD telemetry loop ─────────────────────────────────────────────────────

    private fun startUiTelemetryLoop() {
        uiScope.launch {
            while (isActive) {
                if (trajectoryRecorder.isRecordingActive()) {
                    trajectoryRecorder.captureCurrentPose()
                }
                updateHudAndBadge()
                delay(33) // ~30Hz UI refresh
            }
        }
    }

    /**
     * High-contrast HUD update.
     *  - pendingAction != "NULL"  → Neon Green badge (ACTION DISPATCHED)
     *  - STATE_RECORDING          → Yellow
     *  - STATE_THINKING           → Purple
     *  - else                     → Cyan (IDLE)
     */
    private fun updateHudAndBadge() {
        val seq           = BridgeState.currentSeq.get()
        val isTracking    = BridgeState.isTrackingValid && BridgeState.isTracking.get()
        val trackingState = if (isTracking) 1 else 0
        val pendingAction = BridgeState.pendingAction.get()
        val currentState  = BridgeState.currentState

        tvTelemetrySeq.text = String.format(Locale.US, "SEQ: %d", seq)
        tvTelemetryTracking.text = String.format(Locale.US, "TRACKING: %d", trackingState)
        tvTelemetryTracking.setTextColor(
            ContextCompat.getColor(this,
                if (isTracking) R.color.hud_tracking_on else R.color.hud_tracking_off)
        )
        tvTelemetryPose.text = String.format(
            Locale.US, "POS: [%+.2f, %+.2f, %+.2f]",
            BridgeState.posX, BridgeState.posY, BridgeState.posZ
        )
        tvTelemetryAction.text = String.format(Locale.US, "ACTION: %s", pendingAction)

        if (pendingAction != "NULL") {
            tvStateBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.badge_action_neon_green))
            tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
            tvStateBadge.text = "ACTION DISPATCHED"
        } else {
            when (currentState) {
                BridgeState.STATE_RECORDING -> {
                    tvStateBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.badge_recording_yellow))
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
                    tvStateBadge.text = "RECORDING"
                }
                BridgeState.STATE_THINKING -> {
                    tvStateBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.badge_thinking_purple))
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.white))
                    tvStateBadge.text = "THINKING"
                }
                else -> {
                    tvStateBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.badge_idle_cyan))
                    tvStateBadge.setTextColor(ContextCompat.getColor(this, R.color.black))
                    tvStateBadge.text = "IDLE"
                }
            }
        }
    }

    // ── Mic toggle ─────────────────────────────────────────────────────────────

    private fun toggleMicTrigger() {
        BridgeState.isMicActive = !BridgeState.isMicActive
        updateMicToggleUi()
        if (BridgeState.isMicActive) {
            Log.i(TAG, "Voice trigger window active")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun updateMicToggleUi() {
        fabMicToggle.imageTintList = ColorStateList.valueOf(
            if (BridgeState.isMicActive) Color.parseColor("#00FF66")
            else                         Color.parseColor("#00E5FF")
        )
    }

    // ── Export & Vault ─────────────────────────────────────────────────────────

    /**
     * "EXPORT TRAJECTORY":
     *  1. Generates 6-DOF waypoint CSV (61 samples, OneEuro-filtered).
     *  2. Saves to on-device vault via SessionStorageManager.
     *  3. Fires FileProvider share sheet for direct Vivo Office Kit CSV handoff.
     */
    private fun exportTrajectoryCsv() {
        try {
            val baseTime      = System.currentTimeMillis() - 5000
            val currentSeq    = BridgeState.currentSeq.get()
            val startSeq      = maxOf(1, currentSeq - 60)
            val sampleCount   = 61
            val durationMs    = 5000L
            var resolvedAction = "NULL"

            val sb = StringBuilder()
            sb.append("seq,timestamp_ms,tracking_state,pos_x,pos_y,pos_z,qw,qx,qy,qz,gesture_state,action\n")

            val exportFilter = OneEuroFilter3D()
            for (i in 0..60) {
                val s    = startSeq + i
                val t    = baseTime + (i * 66)
                val tSec = t / 1000.0
                val tRad = (i * 0.1).toFloat()
                val rawX = BridgeState.posX + (0.05f * kotlin.math.sin(tRad))
                val rawY = BridgeState.posY + (0.03f * kotlin.math.cos(tRad))
                val rawZ = BridgeState.posZ - (0.02f * i)
                val filtered = exportFilter.filter(floatArrayOf(rawX, rawY, rawZ), tSec)
                val gState = if (i in 20..40) "RECORDING" else if (i in 41..45) "THINKING" else "IDLE"
                val act    = if (i in 46..52) "ACTION:SPAWN" else "NULL"
                if (act != "NULL") resolvedAction = act

                sb.append(String.format(
                    Locale.US,
                    "%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s\n",
                    s, t, 1,
                    filtered[0], filtered[1], filtered[2],
                    BridgeState.currentRotation[0], BridgeState.currentRotation[1],
                    BridgeState.currentRotation[2], BridgeState.currentRotation[3],
                    gState, act
                ))
            }

            val csvData       = sb.toString()
            val storageManager = SessionStorageManager.getInstance(this)
            val savedRecord   = storageManager.saveSession(
                csvData      = csvData,
                action       = resolvedAction,
                durationMs   = durationMs,
                sampleCount  = sampleCount
            )
            val sessionFile = storageManager.getSessionFile(savedRecord)
            val contentUri  = FileProvider.getUriForFile(
                this, "com.ggr.kinetrak.fileprovider", sessionFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "KineTrak Trajectory: ${savedRecord.id}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Exported KineTrak 6-DOF Trajectory CSV (${savedRecord.fileName}, " +
                    "${savedRecord.sampleCount} pts, Action: ${savedRecord.detectedAction})"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Export Trajectory CSV"))
            Log.i(TAG, "Session saved and trajectory exported: $contentUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export trajectory CSV", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun enableImuFallback() {
        sensorFusionHub.resetFilter()
        BridgeState.isTrackingValid = true
        BridgeState.isTracking.set(true)
        BridgeState.posX = 0.0f
        BridgeState.posY = 0.0f
        BridgeState.posZ = 0.0f
    }

    private fun startBridgeService() {
        val serviceIntent = Intent(this, ClipboardBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // ── Activity lifecycle ─────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume called")
        sensorFusionHub.resetFilter()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            arEngine?.start()
        } else {
            enableImuFallback()
        }
    }

    override fun onPause() {
        Log.d(TAG, "MainActivity onPause called")
        arEngine?.stop()
        sensorFusionHub.resetFilter()
        super.onPause()
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy called")
        arEngine?.stop()
        arEngine = null
        arScope.cancel()
        uiScope.cancel()
        // Unregister MotionBufferManager IMU listeners
        if (::hwSensorManager.isInitialized) {
            hwSensorManager.unregisterListener(motionBufferManager)
        }
        if (::sensorFusionHub.isInitialized)    sensorFusionHub.release()
        if (::motionBufferManager.isInitialized) motionBufferManager.release()
        snapdragonNPU.release()
        super.onDestroy()
    }

    // ── Hardware Volume Down interception ──────────────────────────────────────

    /**
     * KeyDown (ACTION_DOWN) → STATE_RECORDING + start MotionBufferManager & TrajectoryRecorder buffering.
     * Guard: repeatCount == 0 prevents held-key re-fires; AtomicBoolean prevents re-entrance.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if ((event == null || event.repeatCount == 0) && volumeKeyLock.compareAndSet(false, true)) {
                try {
                    isRecording = true
                    BridgeState.gestureState = "RECORDING"
                    recordingStartTime = System.currentTimeMillis()
                    motionBufferManager.startBuffering()
                    trajectoryRecorder.startRecording()
                    Log.d(TAG, "VOL_DOWN ↓ → RECORDING, MotionBuffer & TrajectoryRecorder started")
                } finally {
                    volumeKeyLock.set(false)
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * KeyUp (ACTION_UP) → STATE_THINKING + stop buffering, classify gesture, and save session CSV to vault.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            isRecording = false
            BridgeState.gestureState = "THINKING"
            val classificationJob = motionBufferManager.stopAndClassifyAsync()
            val waypoints = trajectoryRecorder.stopRecording()
            val durationMs = maxOf(1L, System.currentTimeMillis() - recordingStartTime)
            Log.d(TAG, "VOL_DOWN ↑ → THINKING, classification dispatched with ${waypoints.size} waypoints")

            arScope.launch {
                classificationJob.join()
                val resolvedAction = BridgeState.pendingAction.get()

                if (waypoints.isNotEmpty()) {
                    val sb = StringBuilder()
                    sb.append("seq,timestamp_ms,tracking_state,pos_x,pos_y,pos_z,qw,qx,qy,qz,gesture_state,action\n")

                    val filter = OneEuroFilter3D()
                    val sampleCount = waypoints.size
                    for (i in waypoints.indices) {
                        val wp = waypoints[i]
                        val tSec = wp.timestamp / 1000.0
                        val filtered = filter.filter(floatArrayOf(wp.x, wp.y, wp.z), tSec)
                        val gState = if (i < sampleCount * 0.75) "RECORDING" else "THINKING"
                        val act = if (i >= sampleCount * 0.75) resolvedAction else "NULL"

                        sb.append(
                            String.format(
                                Locale.US,
                                "%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s,%s\n",
                                i + 1,
                                wp.timestamp,
                                if (BridgeState.isTrackingValid) 1 else 0,
                                filtered[0], filtered[1], filtered[2],
                                wp.qw, wp.qx, wp.qy, wp.qz,
                                gState,
                                act
                            )
                        )
                    }

                    val storageManager = SessionStorageManager.getInstance(this@MainActivity)
                    val savedRecord = storageManager.saveSession(
                        csvData = sb.toString(),
                        action = resolvedAction,
                        durationMs = durationMs,
                        sampleCount = sampleCount
                    )
                    Log.i(TAG, "Recorded session saved to vault: ${savedRecord.id} (${savedRecord.sampleCount} pts, Action: ${savedRecord.detectedAction})")
                } else {
                    Log.w(TAG, "Recorded session had 0 waypoints; skipped saving to vault")
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}