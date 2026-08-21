package com.example.testwatch.tracking

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
}
