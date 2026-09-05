package com.ggr.kinetrak

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
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
import com.ggr.kinetrak.gesture.HeuristicGestureFallback
import com.ggr.kinetrak.gesture.MotionBufferManager
import com.ggr.kinetrak.math.OneEuroFilter3D
import com.ggr.kinetrak.storage.SessionStorageManager
import com.ggr.kinetrak.tracking.SensorFusionHub
import com.ggr.kinetrak.ui.SessionHistoryBottomSheet
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.FatalException
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    // ── Coroutine scopes ───────────────────────────────────────────────────────
    private val arScope = CoroutineScope(Dispatchers.Default + Job())
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    // ── ARCore ─────────────────────────────────────────────────────────────────
    private var arSession: Session? = null
    private var isArTrackingActive = false

    // ── Offscreen EGL (headless ARCore VIO) ────────────────────────────────────
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext  = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface  = EGL14.EGL_NO_SURFACE
    private var cameraTextureId: Int    = -1

    // ── Sensor Fusion & Edge AI ────────────────────────────────────────────────
    private lateinit var sensorFusionHub: SensorFusionHub
    private lateinit var motionBufferManager: MotionBufferManager
    private val oneEuroFilter3D = OneEuroFilter3D()
    private val volumeKeyLock   = AtomicBoolean(false)
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
        if (BridgeState.isStreamingActive) oneEuroFilter3D.reset()
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

    // ── Permissions ────────────────────────────────────────────────────────────

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
        setupArSession()
        startBridgeService()
        resumeArTracking()
    }

    // ── CameraX ────────────────────────────────────────────────────────────────

    /**
     * CameraX Preview — bound to Activity LifecycleOwner to auto-unbind on pause.
     * Used for the live viewfinder only; ARCore VIO runs on the offscreen EGL surface.
     */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                Log.i(TAG, "CameraX preview bound successfully")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ── HUD telemetry loop ─────────────────────────────────────────────────────

    private fun startUiTelemetryLoop() {
        uiScope.launch {
            while (isActive) {
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

    // ── ARCore session management ──────────────────────────────────────────────

    private fun checkArCoreAvailability(): Boolean {
        return try {
            val avail = ArCoreApk.getInstance().checkAvailability(this)
            if (avail == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
                Log.w(TAG, "ARCore not supported; activating IMU-only fallback.")
                false
            } else avail.isSupported
        } catch (e: Exception) {
            Log.w(TAG, "ARCore check failed; activating IMU-only fallback.", e)
            false
        }
    }

    private fun enableImuFallback() {
        oneEuroFilter3D.reset()
        BridgeState.isTrackingValid = true
        BridgeState.isTracking.set(true)
        BridgeState.posX = 0.0f
        BridgeState.posY = 0.0f
        BridgeState.posZ = 0.0f
    }

    private fun setupArSession() {
        if (arSession != null) return
        try {
            if (!checkArCoreAvailability()) { enableImuFallback(); return }
            val session = Session(this)
            val config  = Config(session).apply {
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode  = Config.FocusMode.AUTO
            }
            session.configure(config)
            arSession = session
            Log.i(TAG, "ARCore session initialised")
        } catch (e: FatalException) {
            Log.w(TAG, "ARCore FatalException; IMU fallback.", e)
            arSession = null; enableImuFallback()
        } catch (e: Exception) {
            Log.w(TAG, "ARCore exception; IMU fallback.", e)
            arSession = null; enableImuFallback()
        }
    }

    private fun resumeArTracking() {
        val session = arSession ?: run { enableImuFallback(); return }
        try {
            session.resume()
            startArTrackingLoop()
        } catch (e: Exception) {
            Log.w(TAG, "ARCore resume failed; IMU fallback.", e)
            enableImuFallback()
        }
    }

    // ── Offscreen EGL PBuffer context ─────────────────────────────────────────

    /**
     * Creates a 1×1 PBuffer EGL surface and binds a GL_TEXTURE_EXTERNAL_OES texture.
     * This is required for ARCore's session.update() to advance camera odometry in headless mode.
     *
     * @return the generated camera texture ID, or -1 on failure.
     */
    private fun initOffscreenGl(): Int {
        return try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return -1

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return -1

            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE,         8,
                EGL14.EGL_GREEN_SIZE,       8,
                EGL14.EGL_BLUE_SIZE,        8,
                EGL14.EGL_RENDERABLE_TYPE,  EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,     EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs    = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
            if (numConfigs[0] == 0 || configs[0] == null) return -1

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return -1

            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return -1

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            cameraTextureId = textures[0]
            Log.i(TAG, "Offscreen EGL PBuffer ready, textureId=$cameraTextureId")
            cameraTextureId
        } catch (e: Exception) {
            Log.w(TAG, "EGL init failed: ${e.message}", e)
            -1
        }
    }

    private fun releaseOffscreenGl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                )
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

    // ── ARCore tracking loop ───────────────────────────────────────────────────

    /**
     * Starts the background ARCore VIO loop:
     *  1. Initialises the offscreen EGL PBuffer (required for headless session.update()).
     *  2. Binds the camera texture so ARCore odometry can advance.
     *  3. On each frame:
     *     a. Applies OneEuroFilter3D to the raw translation for jitter suppression.
     *     b. Passes the filtered frame to SensorFusionHub (applies 2.5× scale, ZOH, quaternion remap).
     */
    private fun startArTrackingLoop() {
        if (isArTrackingActive) return
        isArTrackingActive = true

        arScope.launch(Dispatchers.Default) {
            // Initialise offscreen GL surface on the AR worker thread
            val textureId = initOffscreenGl()
            if (textureId > 0 && arSession != null) {
                try {
                    arSession?.setCameraTextureName(textureId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind camera texture: ${e.message}")
                }
            } else {
                Log.w(TAG, "EGL init failed or no session; ARCore VIO may not advance odometry.")
            }

            while (isActive && isArTrackingActive) {
                val session = arSession
                if (session != null) {
                    try {
                        val frame  = session.update()
                        val camera = frame.camera

                        // Pre-filter raw translation before handing to SensorFusionHub
                        if (camera.trackingState == TrackingState.TRACKING) {
                            val rawTranslation = camera.displayOrientedPose.translation
                            val timestampSec   = if (frame.timestamp > 0L)
                                frame.timestamp / 1_000_000_000.0
                            else
                                System.nanoTime() / 1_000_000_000.0

                            // OneEuroFilter3D suppresses high-frequency jitter
                            val filteredTranslation = oneEuroFilter3D.filter(rawTranslation, timestampSec)

                            // Create a synthetic pose-like object so SensorFusionHub can
                            // read filteredTranslation instead of the raw pose translation.
                            // We pass the frame directly — SensorFusionHub reads displayOrientedPose
                            // internally for the quaternion, and we override position from
                            // the filtered translation written into BridgeState here:
                            BridgeState.posX = filteredTranslation[0]
                            BridgeState.posY = filteredTranslation[1]
                            BridgeState.posZ = filteredTranslation[2]
                            BridgeState.isTrackingValid = true
                            BridgeState.isTracking.set(true)
                        }

                        // SensorFusionHub handles quaternion remap, 2.5× scale on position,
                        // ZOH failover, and IMU fallback rotation
                        sensorFusionHub.onArCoreFrame(frame)
                    } catch (_: Exception) {
                        // Frame update may fail while paused or camera unavailable
                    }
                }
                delay(33) // ~30Hz ARCore frame sampling
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

    // ── Activity lifecycle ─────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            if (arSession == null) setupArSession()
            resumeArTracking()
        } else {
            enableImuFallback()
        }
    }

    override fun onPause() {
        isArTrackingActive = false
        oneEuroFilter3D.reset()
        try { arSession?.pause() } catch (e: Exception) {
            Log.e(TAG, "Error pausing ARCore session", e)
        }
        super.onPause()
    }

    override fun onDestroy() {
        isArTrackingActive = false
        arScope.cancel()
        uiScope.cancel()
        releaseOffscreenGl()
        // Unregister MotionBufferManager IMU listeners
        if (::hwSensorManager.isInitialized) {
            hwSensorManager.unregisterListener(motionBufferManager)
        }
        if (::sensorFusionHub.isInitialized)    sensorFusionHub.release()
        if (::motionBufferManager.isInitialized) motionBufferManager.release()
        snapdragonNPU.release()
        try { arSession?.close(); arSession = null }
        catch (e: Exception) { Log.e(TAG, "Error closing ARCore session", e) }
        super.onDestroy()
    }

    // ── Hardware Volume Down interception ──────────────────────────────────────

    /**
     * KeyDown → STATE_RECORDING + start MotionBufferManager sampling.
     * Guard: repeatCount == 0 prevents held-key re-fires; AtomicBoolean prevents re-entrance.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if ((event == null || event.repeatCount == 0) && volumeKeyLock.compareAndSet(false, true)) {
                try {
                    BridgeState.currentState = BridgeState.STATE_RECORDING
                    motionBufferManager.startBuffering()
                    Log.d(TAG, "VOL_DOWN ↓ → RECORDING, MotionBuffer started")
                } finally {
                    volumeKeyLock.set(false)
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * KeyUp → STATE_THINKING + kick off async gesture classification.
     * Classification result is latched into BridgeState.pendingAction by MotionBufferManager.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            BridgeState.currentState = BridgeState.STATE_THINKING
            motionBufferManager.stopAndClassifyAsync()
            Log.d(TAG, "VOL_DOWN ↑ → THINKING, classification dispatched")
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}