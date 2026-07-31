package com.example.testwatch.tracking

import kotlinx.coroutines.flow.MutableStateFlow

object TrackingState {
    val latestBpm = MutableStateFlow(0)
    val latestStatus = MutableStateFlow(-99)
    val connected = MutableStateFlow(false)
    val ready = MutableStateFlow(false)
    val deviceWorn = MutableStateFlow(true)
}
