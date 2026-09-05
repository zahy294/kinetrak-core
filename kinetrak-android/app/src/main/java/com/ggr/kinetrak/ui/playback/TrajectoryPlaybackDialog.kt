package com.ggr.kinetrak.ui.playback

import android.content.Intent
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import com.ggr.kinetrak.R
import com.ggr.kinetrak.model.SessionRecord
import com.ggr.kinetrak.storage.SessionStorageManager
import com.google.android.material.button.MaterialButton
import java.util.Locale

class TrajectoryPlaybackDialog : DialogFragment() {

    companion object {
        const val TAG = "TrajectoryPlaybackDialog"
        private const val ARG_SESSION_ID = "arg_session_id"
        private const val ARG_SESSION_FILENAME = "arg_session_filename"
        private const val ARG_SESSION_DURATION = "arg_session_duration"
        private const val ARG_SESSION_SAMPLES = "arg_session_samples"
        private const val ARG_SESSION_ACTION = "arg_session_action"
        private const val ARG_SESSION_TIMESTAMP = "arg_session_timestamp"

        fun newInstance(record: SessionRecord): TrajectoryPlaybackDialog {
            val args = Bundle().apply {
                putString(ARG_SESSION_ID, record.id)
                putString(ARG_SESSION_FILENAME, record.fileName)
                putLong(ARG_SESSION_DURATION, record.durationMs)
                putInt(ARG_SESSION_SAMPLES, record.sampleCount)
                putString(ARG_SESSION_ACTION, record.detectedAction)
                putLong(ARG_SESSION_TIMESTAMP, record.timestamp)
            }
            return TrajectoryPlaybackDialog().apply {
                arguments = args
            }
        }
    }

    private lateinit var glTrajectoryView: GLSurfaceView
    private lateinit var renderer: TrajectoryRenderer
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private lateinit var tvPlaybackSessionId: TextView
    private lateinit var tvPlaybackLength: TextView
    private lateinit var tvPlaybackSamples: TextView
    private lateinit var tvPlaybackAction: TextView
    private lateinit var tvScrubProgress: TextView
    private lateinit var seekBarScrub: SeekBar
    private lateinit var btnClosePlayback: MaterialButton
    private lateinit var btnSharePlayback: MaterialButton

    private var previousX = 0f
    private var previousY = 0f
    private var isScaling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_trajectory_playback, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupOpenGL()
        loadSessionData()
        setupListeners()
    }

    private fun initViews(view: View) {
        glTrajectoryView = view.findViewById(R.id.glTrajectoryView)
        tvPlaybackSessionId = view.findViewById(R.id.tvPlaybackSessionId)
        tvPlaybackLength = view.findViewById(R.id.tvPlaybackLength)
        tvPlaybackSamples = view.findViewById(R.id.tvPlaybackSamples)
        tvPlaybackAction = view.findViewById(R.id.tvPlaybackAction)
        tvScrubProgress = view.findViewById(R.id.tvScrubProgress)
        seekBarScrub = view.findViewById(R.id.seekBarScrub)
        btnClosePlayback = view.findViewById(R.id.btnClosePlayback)
        btnSharePlayback = view.findViewById(R.id.btnSharePlayback)
    }

    private fun setupOpenGL() {
        renderer = TrajectoryRenderer()
        glTrajectoryView.setEGLContextClientVersion(2)
        glTrajectoryView.setRenderer(renderer)
        glTrajectoryView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        scaleGestureDetector = ScaleGestureDetector(
            requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isScaling = true
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val factor = detector.scaleFactor
                    if (factor > 0) {
                        renderer.zoomScale = (renderer.zoomScale / factor).coerceIn(0.2f, 5.0f)
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isScaling = false
                }
            }
        )

        glTrajectoryView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)

            if (!isScaling && event.pointerCount == 1) {
                val x = event.x
                val y = event.y

                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        val dx = x - previousX
                        val dy = y - previousY

                        // Drag sensitivity factor
                        renderer.touchAngleX += dx * 0.4f
                        renderer.touchAngleY = (renderer.touchAngleY + dy * 0.4f).coerceIn(-85f, 85f)
                    }
                }
                previousX = x
                previousY = y
            }
            true
        }
    }

    private fun loadSessionData() {
        val args = arguments ?: return
        val sessionId = args.getString(ARG_SESSION_ID) ?: "session_unknown"
        val fileName = args.getString(ARG_SESSION_FILENAME) ?: "session.csv"
        val initialSamples = args.getInt(ARG_SESSION_SAMPLES, 0)
        val initialAction = args.getString(ARG_SESSION_ACTION) ?: "IDLE"

        tvPlaybackSessionId.text = sessionId

        val storageManager = SessionStorageManager.getInstance(requireContext())
        val dummyRecord = SessionRecord(
            id = sessionId,
            timestamp = args.getLong(ARG_SESSION_TIMESTAMP, System.currentTimeMillis()),
            durationMs = args.getLong(ARG_SESSION_DURATION, 0L),
            sampleCount = initialSamples,
            detectedAction = initialAction,
            fileName = fileName
        )
        val sessionFile = storageManager.getSessionFile(dummyRecord)

        renderer.loadSessionCsv(sessionFile)

        val totalPts = renderer.getWaypointCount()
        val pathLen = renderer.totalPathLengthMeters
        val resolvedAction = if (renderer.detectedAction != "IDLE" && renderer.detectedAction != "NULL") {
            renderer.detectedAction
        } else {
            initialAction
        }

        tvPlaybackLength.text = String.format(
            Locale.US,
            "TAU: %.2f | LEN: %.2fm",
            renderer.tortuosity,
            renderer.totalPathLengthMeters
        )
        tvPlaybackSamples.text = "$totalPts pts"

        // Action badge styling
        val isActionPresent = resolvedAction.isNotBlank() &&
                resolvedAction != "NULL" &&
                resolvedAction != "IDLE" &&
                resolvedAction != "NONE"

        if (isActionPresent) {
            tvPlaybackAction.text = resolvedAction
            tvPlaybackAction.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.badge_action_neon_green)
            )
            tvPlaybackAction.setTextColor(Color.BLACK)
        } else {
            tvPlaybackAction.text = "IDLE"
            tvPlaybackAction.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.badge_idle_bg)
            )
            tvPlaybackAction.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.text_muted)
            )
        }

        tvScrubProgress.text = "$totalPts / $totalPts (100%)"
    }

    private fun setupListeners() {
        btnClosePlayback.setOnClickListener {
            dismiss()
        }

        btnSharePlayback.setOnClickListener {
            shareCurrentSession()
        }

        seekBarScrub.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val progressFrac = progress / 100.0f
                renderer.scrubProgress = progressFrac
                val totalPts = renderer.getWaypointCount()
                val currentPts = maxOf(1, (totalPts * progressFrac).toInt())
                tvScrubProgress.text = "$currentPts / $totalPts ($progress%)"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun shareCurrentSession() {
        try {
            val args = arguments ?: return
            val fileName = args.getString(ARG_SESSION_FILENAME) ?: "session.csv"
            val sessionId = args.getString(ARG_SESSION_ID) ?: "session"
            val action = args.getString(ARG_SESSION_ACTION) ?: "IDLE"
            val samples = args.getInt(ARG_SESSION_SAMPLES, 0)

            val dummyRecord = SessionRecord(
                id = sessionId,
                timestamp = args.getLong(ARG_SESSION_TIMESTAMP, System.currentTimeMillis()),
                durationMs = args.getLong(ARG_SESSION_DURATION, 0L),
                sampleCount = samples,
                detectedAction = action,
                fileName = fileName
            )

            val storageManager = SessionStorageManager.getInstance(requireContext())
            val sessionFile = storageManager.getSessionFile(dummyRecord)

            if (!sessionFile.exists()) {
                Toast.makeText(requireContext(), "File does not exist: $fileName", Toast.LENGTH_SHORT).show()
                return
            }

            val contentUri = FileProvider.getUriForFile(
                requireContext(),
                "com.ggr.kinetrak.fileprovider",
                sessionFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "KineTrak 3D Trajectory: $sessionId")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Exported KineTrak Trajectory CSV: $fileName ($samples pts, Action: $action)"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share Session to PC / Vivo Office Kit"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        glTrajectoryView.onResume()
    }

    override fun onPause() {
        super.onPause()
        glTrajectoryView.onPause()
    }
}
