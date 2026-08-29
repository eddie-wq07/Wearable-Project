package com.example.testwatch.mobile.status

/** In-memory counters (batches received, uploads OK/failed, totals) read by StatusActivity. */

import kotlinx.coroutines.flow.MutableStateFlow

object PipelineStatus {
    // Batch reception
    val lastBatchReceivedMs = MutableStateFlow(0L)
    val lastBatchSize = MutableStateFlow(0)
    val lastBatchParticipant = MutableStateFlow("")
    val totalBatchesReceived = MutableStateFlow(0)

    // Upload state
    val uploading = MutableStateFlow(false)
    val lastUploadAttemptMs = MutableStateFlow(0L)
    val lastUploadOk = MutableStateFlow<Boolean?>(null)
    val lastUploadMessage = MutableStateFlow("")
    val lastUploadSampleCount = MutableStateFlow(0)
    val totalUploadsSucceeded = MutableStateFlow(0)
    val totalSamplesUploaded = MutableStateFlow(0L)
}
