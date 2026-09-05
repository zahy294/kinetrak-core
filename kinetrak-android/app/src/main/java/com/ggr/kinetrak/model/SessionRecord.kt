package com.ggr.kinetrak.model

import org.json.JSONObject

data class SessionRecord(
    val id: String,
    val timestamp: Long,
    val durationMs: Long,
    val sampleCount: Int,
    val detectedAction: String,
    val fileName: String
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("timestamp", timestamp)
        json.put("durationMs", durationMs)
        json.put("sampleCount", sampleCount)
        json.put("detectedAction", detectedAction)
        json.put("fileName", fileName)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): SessionRecord {
            return SessionRecord(
                id = json.getString("id"),
                timestamp = json.getLong("timestamp"),
                durationMs = json.getLong("durationMs"),
                sampleCount = json.getInt("sampleCount"),
                detectedAction = json.optString("detectedAction", "NULL"),
                fileName = json.getString("fileName")
            )
        }
    }
}
