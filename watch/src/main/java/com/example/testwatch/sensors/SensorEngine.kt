package com.example.testwatch.sensors

/** Orchestrates every sensor in the SensorSpec registry: runs continuous sensors permanently and
 *  on-demand sensors one at a time per alarm tick (they share hardware). */

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

class SensorEngine(
    private val service: HealthTrackingService,
    private val scope: CoroutineScope,
    private val store: (spec: SensorSpec, firstTs: Long, pointsJson: String) -> Unit,
    private val prompt: (text: String?) -> Unit,
    /** completed=true only when the round visited every sensor (timeouts included);
     *  false on cancellation, so a killed round never counts as today's measurement. */
    private val onRoundFinished: (completed: Boolean) -> Unit,
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

    /** Participant-initiated abort. The round's finally blocks stop the active session,
     *  resume PPG, clear the prompt, and report the round as not completed. */
    fun cancelRound() {
        roundJob?.cancel()
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
        var completed = false
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
            completed = true
        } finally {
            prompt(null)
            continuous.filter { it.spec.id == "ppg" }.forEach { it.start() }
            onRoundFinished(completed)
        }
    }

    private companion object {
        const val TAG = "SensorEngine"
    }
}
