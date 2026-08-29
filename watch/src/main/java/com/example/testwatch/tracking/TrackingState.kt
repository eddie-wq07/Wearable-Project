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

    /** Latest readings surfaced on the watch face; NaN/0 = nothing yet. */
    val latestSkinTemp = MutableStateFlow(Float.NaN)
    val latestSpo2 = MutableStateFlow(0)

    /** Store-and-forward buffer: estimated unsynced bytes held on the watch. */
    val bufferedBytes = MutableStateFlow(0L)

    /** True while the backlog is draining to the phone. */
    val draining = MutableStateFlow(false)

    /** Rows discarded by emergency back-pressure. Nonzero = a data gap the
     *  study needs to know about; surfaced, never silent. */
    val droppedRows = MutableStateFlow(0L)
}
