package com.example.testwatch.mobile.wear

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.testwatch.mobile.data.PhoneHrDatabase
import com.example.testwatch.mobile.data.PhoneHrSample
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

class HrBatchListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH) return
        val payload = String(event.data, Charsets.UTF_8)
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
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(UploadWorker.NAME, ExistingWorkPolicy.REPLACE, req)
    }

    companion object {
        private const val PATH = "/hr_batch"
        private const val TAG = "HrBatchListener"
    }
}
