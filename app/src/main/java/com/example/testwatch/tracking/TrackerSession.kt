package com.example.testwatch.tracking

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint

/** Generic replacement for a per-sensor listener: attaches [spec]'s tracker and
 *  forwards each SDK callback as a list of {ts, ...fields} points. */
class TrackerSession(
    val spec: SensorSpec,
    private val service: HealthTrackingService,
    private val onBatch: (SensorSpec, List<Map<String, Number>>) -> Unit,
) {
    private var tracker: HealthTracker? = null
    private val handler = Handler(Looper.getMainLooper())

    private val listener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(list: List<DataPoint>) {
            val points = list.mapNotNull { dp ->
                try {
                    buildMap<String, Number> {
                        put("ts", dp.timestamp)
                        putAll(spec.extract(dp))
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "${spec.id} extract failed: ${t.message}")
                    null
                }
            }
            if (points.isNotEmpty()) onBatch(spec, points)
        }

        override fun onFlushCompleted() {}

        override fun onError(error: HealthTracker.TrackerError) {
            Log.w(TAG, "${spec.id} tracker error: $error")
        }
    }

    fun start(): Boolean {
        val t = try {
            when {
                spec.ppgTypes != null -> service.getHealthTracker(spec.trackerType, spec.ppgTypes)
                spec.needsProfile -> service.getHealthTracker(spec.trackerType, SensorConfig.userProfile)
                else -> service.getHealthTracker(spec.trackerType)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "${spec.id} getHealthTracker failed: ${t.message}")
            return false
        }
        tracker = t
        handler.post { t.setEventListener(listener) }
        return true
    }

    fun stop() {
        try {
            tracker?.unsetEventListener()
        } catch (t: Throwable) {
            Log.w(TAG, "${spec.id} unset failed: ${t.message}")
        }
        handler.removeCallbacksAndMessages(null)
        tracker = null
    }

    private companion object {
        const val TAG = "TrackerSession"
    }
}
