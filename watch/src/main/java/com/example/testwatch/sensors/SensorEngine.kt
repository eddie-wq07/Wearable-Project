package com.example.testwatch.sensors

import android.util.Log
import com.example.testwatch.config.SensorConfig
import com.example.testwatch.sync.BatchSerializer
import com.example.testwatch.tracking.TrackingState
import com.samsung.android.service.health.tracking.HealthTrackingService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs every supported continuous tracker permanently and, every
 * [SensorConfig.ON_DEMAND_INTERVAL_MIN] minutes, works through the on-demand
 * trackers one at a time (they share sensor hardware and cannot overlap).
 */
class SensorEngine(
    private val service: HealthTrackingService,
    private val scope: CoroutineScope,
    private val store: (spec: SensorSpec, firstTs: Long, pointsJson: String) -> Unit,
    private val prompt: (text: String?) -> Unit,
) {
    private val supported = service.getTrackingCapability().supportHealthTrackerTypes.toSet()
    private val continuous = mutableListOf<TrackerSession>()
    private var roundJob: Job? = null
    private var measurementDone: CompletableDeferred<Unit>? = null

    fun start() {
        SENSORS.filter { it.mode == SensorMode.CONTINUOUS && it.trackerType in supported }
            .forEach { spec ->
                val session = TrackerSession(spec, service, ::handleBatch)
                if (session.start()) continuous += session
            }
        Log.i(TAG, "continuous started: ${continuous.map { it.spec.id }}")
    }

    /** Called on each MeasureAlarmReceiver tick; no-op if a round is already running. */
    fun runRoundNow() {
        if (roundJob?.isActive == true) return
        roundJob = scope.launch {
            try {
                runOnDemandRound()
            } catch (t: Throwable) {
                Log.w(TAG, "on-demand round failed: ${t.message}")
            }
        }
    }

    fun stop() {
        roundJob?.cancel()
        continuous.forEach { it.stop() }
        continuous.clear()
    }

    private fun handleBatch(spec: SensorSpec, points: List<Map<String, Number>>) {
        store(spec, points.first()["ts"]!!.toLong(), BatchSerializer.encodePoints(points))
        when (spec.id) {
            "skin_temp" -> points.last()["object_c"]?.let { TrackingState.latestSkinTemp.value = it.toFloat() }
            "spo2" -> points.lastOrNull { it["status"]?.toInt() == 2 }?.get("spo2")
                ?.let { TrackingState.latestSpo2.value = it.toInt() }
        }
        if (spec.mode == SensorMode.ON_DEMAND && points.any { isFinal(spec, it) }) {
            measurementDone?.complete(Unit)
        }
    }

    /** Measurements that report completion finish early; the rest run out the timeout. */
    private fun isFinal(spec: SensorSpec, point: Map<String, Number>): Boolean = when (spec.id) {
        "spo2" -> point["status"]?.toInt() == 2
        "bia", "mf_bia" -> (point["progress"]?.toFloat() ?: 0f) >= 100f
        else -> false
    }

    private suspend fun runOnDemandRound() {
        // ECG/BIA use the same optical/electrode stack as PPG; pause it for the round.
        continuous.filter { it.spec.id == "ppg" }.forEach { it.stop() }
        try {
            for (spec in SENSORS.filter { it.mode == SensorMode.ON_DEMAND && it.trackerType in supported }) {
                prompt(spec.prompt)
                Log.i(TAG, "on-demand ${spec.id} starting")
                val done = CompletableDeferred<Unit>()
                measurementDone = done
                val session = TrackerSession(spec, service, ::handleBatch)
                try {
                    if (session.start()) {
                        withTimeoutOrNull(SensorConfig.ON_DEMAND_TIMEOUT_SEC * 1000L) { done.await() }
                    }
                } finally {
                    session.stop()
                    measurementDone = null
                }
            }
        } finally {
            prompt(null)
            continuous.filter { it.spec.id == "ppg" }.forEach { it.start() }
        }
    }

    private companion object {
        const val TAG = "SensorEngine"
    }
}
