package com.ggr.kinetrak.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.ggr.kinetrak.R
import com.ggr.kinetrak.model.SessionRecord
import com.ggr.kinetrak.ui.playback.TrajectoryPlaybackDialog
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionHistoryAdapter(
    private var sessions: List<SessionRecord>,
    private val onShareClick: (SessionRecord) -> Unit,
    private val onCardClick: ((SessionRecord) -> Unit)? = null
) : RecyclerView.Adapter<SessionHistoryAdapter.SessionViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    inner class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvSessionId: TextView = view.findViewById(R.id.tvSessionId)
        val tvSessionDate: TextView = view.findViewById(R.id.tvSessionDate)
        val tvSessionDuration: TextView = view.findViewById(R.id.tvSessionDuration)
        val tvSessionSamples: TextView = view.findViewById(R.id.tvSessionSamples)
        val tvSessionAction: TextView = view.findViewById(R.id.tvSessionAction)
        val tvSessionFileName: TextView = view.findViewById(R.id.tvSessionFileName)
        val btnShareSession: MaterialButton = view.findViewById(R.id.btnShareSession)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_card, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        val record = sessions[position]
        val context = holder.itemView.context

        holder.tvSessionId.text = record.id
        holder.tvSessionDate.text = timeFormat.format(Date(record.timestamp))
        holder.tvSessionDuration.text = String.format(Locale.US, "%.1fs", record.durationMs / 1000f)
        holder.tvSessionSamples.text = "${record.sampleCount} pts"
        holder.tvSessionFileName.text = record.fileName

        // Dynamic Action Badge
        val action = record.detectedAction
        val isActionPresent = action.isNotBlank() &&
                action != "NULL" &&
                action != "IDLE" &&
                action != "NONE"

        if (isActionPresent) {
            holder.tvSessionAction.text = action
            holder.tvSessionAction.setBackgroundColor(
                ContextCompat.getColor(context, R.color.badge_action_neon_green)
            )
            holder.tvSessionAction.setTextColor(Color.BLACK)
        } else {
            holder.tvSessionAction.text = "IDLE"
            holder.tvSessionAction.setBackgroundColor(
                ContextCompat.getColor(context, R.color.badge_idle_bg)
            )
            holder.tvSessionAction.setTextColor(
                ContextCompat.getColor(context, R.color.text_muted)
            )
        }

        // Tap on card opens 3D Trajectory Playback Dialog
        holder.itemView.setOnClickListener {
            if (onCardClick != null) {
                onCardClick.invoke(record)
            } else {
                val activity = context as? FragmentActivity
                activity?.let { act ->
                    TrajectoryPlaybackDialog.newInstance(record)
                        .show(act.supportFragmentManager, TrajectoryPlaybackDialog.TAG)
                }
            }
        }

        // Share to PC button
        holder.btnShareSession.setOnClickListener {
            onShareClick(record)
        }
    }

    override fun getItemCount(): Int = sessions.size

    fun updateData(newSessions: List<SessionRecord>) {
        this.sessions = newSessions
        notifyDataSetChanged()
    }
}
