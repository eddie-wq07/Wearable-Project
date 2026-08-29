package com.example.testwatch.mobile.wear

/** Receives /hr_batch and /sensor_batch messages from the watch over the Wear Data Layer, decodes
 *  them via BatchSerializer, writes into phone Room, and enqueues UploadWorker. */

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.testwatch.mobile.data.PhoneHrDatabase
import com.example.testwatch.mobile.data.PhoneHrSample
import com.example.testwatch.mobile.data.PhoneSensorBatch
import com.example.testwatch.mobile.status.PipelineStatus
import com.example.testwatch.mobile.upload.UploadWorker
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class WireSample(val ts: Long, val bpm: Int, val status: Int)

@Serializable
private data class WireBatch(val participantId: String, val samples: List<WireSample>)

@Serializable
private data class WireSensorRow(val sensor: String, val ts: Long, val points: kotlinx.serialization.json.JsonElement)

@Serializable
private data class WireSensorBatch(val participantId: String, val rows: List<WireSensorRow>)

class HrBatchListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH -> handleHrBatch(String(event.data, Charsets.UTF_8))
            SENSOR_PATH -> handleSensorBatch(String(event.data, Charsets.UTF_8))
        }
    }

    private fun handleSensorBatch(payload: String) {
        scope.launch {
            try {
                val batch = json.decodeFromString(WireSensorBatch.serializer(), payload)
                val now = System.currentTimeMillis()
                val rows = batch.rows.map { r ->
                    PhoneSensorBatch(
                        participantId = batch.participantId,
                        sensor = r.sensor,
                        timestampMs = r.ts,
                        points = r.points.toString(),
                        receivedAt = now,
                    )
                }
                PhoneHrDatabase.get(applicationContext).phoneSensorDao().insertAll(rows)
                Log.i(TAG, "stored ${rows.size} sensor rows from ${batch.participantId}")
                PipelineStatus.lastBatchReceivedMs.value = now
                PipelineStatus.lastBatchSize.value = rows.size
                PipelineStatus.lastBatchParticipant.value = batch.participantId
                PipelineStatus.totalBatchesReceived.value = PipelineStatus.totalBatchesReceived.value + 1
                enqueueUpload()
            } catch (t: Throwable) {
                Log.w(TAG, "sensor decode failed: ${t.message}")
            }
        }
    }

    private fun handleHrBatch(payload: String) {
        scope.launch {
            try {
                val batch = json.decodeFromString(WireBatch.serializer(), payload)
                val now = System.currentTimeMillis()
                val rows = batch.samples.map { s ->
                    PhoneHrSample(
                        participantId = batch.participantId,
                        timestampMs = s.ts,
                        bpm = s.bpm,
                        status = s.status,
                        receivedAt = now,
                    )
                }
                PhoneHrDatabase.get(applicationContext).phoneHrDao().insertAll(rows)
                Log.i(TAG, "stored ${rows.size} samples from ${batch.participantId}")
                PipelineStatus.lastBatchReceivedMs.value = now
                PipelineStatus.lastBatchSize.value = rows.size
                PipelineStatus.lastBatchParticipant.value = batch.participantId
                PipelineStatus.totalBatchesReceived.value = PipelineStatus.totalBatchesReceived.value + 1
                enqueueUpload()
            } catch (t: Throwable) {
                Log.w(TAG, "decode failed: ${t.message}")
            }
        }
    }

    private fun enqueueUpload() {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        // KEEP, not REPLACE: batches arrive every second or two now, and REPLACE
        // cancels the in-flight SFTP transfer each time — uploads never finish.
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(UploadWorker.NAME, ExistingWorkPolicy.KEEP, req)
    }

    companion object {
        private const val PATH = "/hr_batch"
        private const val SENSOR_PATH = "/sensor_batch"
        private const val TAG = "HrBatchListener"
    }
}
