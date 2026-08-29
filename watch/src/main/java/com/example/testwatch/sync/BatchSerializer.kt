package com.example.testwatch.sync

/** Builds the upload-ready JSON payloads the watch sends straight to the server (same shape the
 *  phone relay used to upload, so nothing downstream changes), plus the point-list encoding the
 *  sensor engine stores in Room. */

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
    val timestamp_ms: Long,
    val time: String,
    val bpm: Int,
    val status: Int,
    val received_at: Long,
)

@Serializable
data class UploadPayload(
    val watch_serial: String,
    val uploaded_at: Long,
    val uploaded_at_readable: String,
    val is_test: Boolean,
    val samples: List<UploadSample>,
)

@Serializable
data class UploadSensorRow(
    val participant_id: String,
    val sensor: String,
    val timestamp_ms: Long,
    val time: String,
    val received_at: Long,
    val points: JsonElement,
)

@Serializable
data class SensorUploadPayload(
    val watch_serial: String,
    val uploaded_at: Long,
    val uploaded_at_readable: String,
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
            watch_serial = serial(),
            uploaded_at = now,
            uploaded_at_readable = readable(now),
            is_test = true, // flip to false (or pull from BuildConfig) for prod uploads
            samples = rows.map {
                UploadSample(
                    participant_id = participantId,
                    timestamp_ms = it.timestampMs,
                    time = readable(it.timestampMs),
                    bpm = it.bpm,
                    status = it.status,
                    // No phone hop anymore; field kept for ingest compatibility and now
                    // means "left the watch at".
                    received_at = now,
                )
            },
        )
        return json.encodeToString(payload).toByteArray(Charsets.UTF_8)
    }

    fun buildSensorPayload(participantId: String, rows: List<SensorBatch>): ByteArray {
        val now = System.currentTimeMillis()
        val payload = SensorUploadPayload(
            watch_serial = serial(),
            uploaded_at = now,
            uploaded_at_readable = readable(now),
            is_test = true, // flip together with UploadPayload.is_test for prod
            batches = rows.map {
                UploadSensorRow(
                    participant_id = participantId,
                    sensor = it.sensor,
                    timestamp_ms = it.timestampMs,
                    time = readable(it.timestampMs),
                    received_at = now,
                    points = json.parseToJsonElement(it.points),
                )
            },
        )
        return json.encodeToString(payload).toByteArray(Charsets.UTF_8)
    }

    @Suppress("DEPRECATION")
    private fun serial(): String = android.os.Build.SERIAL.orEmpty()

    // Human-readable mirror of timestamp_ms in the watch's local timezone,
    // e.g. "Jul 30 2026, 10:40:20 PM PDT". Display only — ingestion keys off timestamp_ms.
    private val readableFormat = java.text.SimpleDateFormat("MMM d yyyy, h:mm:ss a zzz", java.util.Locale.US)
    private fun readable(ms: Long): String = synchronized(readableFormat) { readableFormat.format(java.util.Date(ms)) }
}
