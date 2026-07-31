package com.example.testwatch.mobile.status

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live in-memory pipeline state. Updated by HrBatchListenerService when a batch
 * arrives, and by UploadWorker on each upload attempt. Observed by StatusActivity
 * to render a live dashboard.
 */
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
