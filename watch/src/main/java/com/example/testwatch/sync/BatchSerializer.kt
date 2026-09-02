package com.example.testwatch.sync

/** Builds the upload-ready JSON payloads the watch sends straight to the server (the standard
 *  format in docs/upload-format.md: every timestamp human-readable, no epoch values), plus the
 *  point-list encoding the sensor engine stores in Room. */

import com.example.testwatch.data.HrSample
import com.example.testwatch.data.SensorBatch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class UploadSample(
    val participant_id: String,
    val time: String,
    val bpm: Int,
    val status: Int,
    val received_at: String,
)

@Serializable
data class UploadPayload(
    val uploaded_at: String,
    val is_test: Boolean,
    val samples: List<UploadSample>,
)

@Serializable
data class UploadSensorRow(
    val participant_id: String,
    val sensor: String,
    val time: String,
    val received_at: String,
    val points: JsonElement,
)

@Serializable
data class SensorUploadPayload(
    val uploaded_at: String,
    val is_test: Boolean,
    val batches: List<UploadSensorRow>,
)

object BatchSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodePoints(points: List<Map<String, Number>>): String =
        JsonArray(points.map { p -> JsonObject(p.mapValues { JsonPrimitive(it.value) }) }).toString()

    fun buildHrPayload(participantId: String, rows: List<HrSample>): ByteArray {
        val now = System.currentTimeMillis()
        val payload = UploadPayload(
            uploaded_at = readable(now),
            is_test = true, // flip to false (or pull from BuildConfig) for prod uploads
            samples = rows.map {
                UploadSample(
                    participant_id = participantId,
                    time = readable(it.timestampMs),
                    bpm = it.bpm,
                    status = it.status,
                    // No phone hop anymore; field kept for ingest compatibility and now
                    // means "left the watch at".
                    received_at = readable(now),
                )
            },
        )
        return json.encodeToString(payload).toByteArray(Charsets.UTF_8)
    }

    fun buildSensorPayload(participantId: String, rows: List<SensorBatch>): ByteArray {
        val now = System.currentTimeMillis()
        val payload = SensorUploadPayload(
            uploaded_at = readable(now),
            is_test = true, // flip together with UploadPayload.is_test for prod
            batches = rows.map {
                UploadSensorRow(
                    participant_id = participantId,
                    sensor = it.sensor,
                    time = readable(it.timestampMs),
                    received_at = readable(now),
                    points = json.parseToJsonElement(it.points),
                )
            },
        )
        return json.encodeToString(payload).toByteArray(Charsets.UTF_8)
    }

    // The one timestamp format in every upload (docs/upload-format.md): watch-local timezone,
    // e.g. "Jul 30 2026, 10:40:20 PM PDT". Server ingestion parses `time` back to epoch-ms.
    private val readableFormat = java.text.SimpleDateFormat("MMM d yyyy, h:mm:ss a zzz", java.util.Locale.US)
    private fun readable(ms: Long): String = synchronized(readableFormat) { readableFormat.format(java.util.Date(ms)) }
}
