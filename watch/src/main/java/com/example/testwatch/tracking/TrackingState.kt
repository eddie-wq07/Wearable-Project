package com.example.testwatch.tracking

/** Live state (BPM, connection status, buffered bytes, dropped rows) read by MainActivity to
 *  display on the watch face. Not logic — a one-way readout of HrTrackingService's state. */

import kotlinx.coroutines.flow.MutableStateFlow

object TrackingState {
    val latestBpm = MutableStateFlow(0)
    val latestStatus = MutableStateFlow(-99)
    val connected = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val deviceWorn = MutableStateFlow(true)

    /** Non-null while an on-demand measurement wants the participant's attention. */
    val currentMeasurement = MutableStateFlow<String?>(null)

    /** Epoch ms when the current measurement times out (worst case; most finish
     *  early). 0 = no measurement. Drives the hold-still countdown in the UI. */
    val measurementDeadline = MutableStateFlow(0L)

    /** Latest readings surfaced on the watch face; NaN/0 = nothing yet. */
    val latestSkinTemp = MutableStateFlow(Float.NaN)
    val latestSpo2 = MutableStateFlow(0)

    /** Store-and-forward buffer: estimated unsynced bytes held on the watch. */
    val bufferedBytes = MutableStateFlow(0L)

    /** True while UploadWorker is pushing the backlog to the server. */
    val draining = MutableStateFlow(false)

    /** Rows discarded by emergency back-pressure. Nonzero = a data gap the
     *  study needs to know about; surfaced, never silent. */
    val droppedRows = MutableStateFlow(0L)
}
