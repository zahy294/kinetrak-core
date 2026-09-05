package com.ggr.kinetrak.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ggr.kinetrak.R
import com.ggr.kinetrak.model.SessionRecord
import com.ggr.kinetrak.storage.SessionStorageManager
import com.ggr.kinetrak.ui.playback.TrajectoryPlaybackDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SessionHistoryBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "SessionHistoryBottomSheet"

        fun newInstance(): SessionHistoryBottomSheet {
            return SessionHistoryBottomSheet()
        }
    }

    private lateinit var rvSessions: RecyclerView
    private lateinit var adapter: SessionHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_sessions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvSessions = view.findViewById(R.id.rvSessions)
        rvSessions.layoutManager = LinearLayoutManager(requireContext())

        val storageManager = SessionStorageManager.getInstance(requireContext())
        val sessions = storageManager.getAllSessions()

        adapter = SessionHistoryAdapter(
            sessions = sessions,
            onShareClick = { record ->
                shareSessionToPc(record)
            },
            onCardClick = { record ->
                TrajectoryPlaybackDialog.newInstance(record)
                    .show(parentFragmentManager, TrajectoryPlaybackDialog.TAG)
            }
        )
        rvSessions.adapter = adapter
    }

    private fun shareSessionToPc(record: SessionRecord) {
        try {
            val storageManager = SessionStorageManager.getInstance(requireContext())
            val sessionFile = storageManager.getSessionFile(record)

            if (!sessionFile.exists()) {
                Toast.makeText(requireContext(), "Session file not found on disk", Toast.LENGTH_SHORT).show()
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
                putExtra(Intent.EXTRA_SUBJECT, "KineTrak Session: ${record.id}")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "KineTrak Session CSV: ${record.fileName} (${record.sampleCount} pts, Action: ${record.detectedAction})"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share Session to PC / Vivo Office Kit"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Share failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
