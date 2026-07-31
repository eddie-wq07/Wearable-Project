package com.example.testwatch.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.testwatch.ConnectionManager
import com.example.testwatch.ConnectionObserver
import com.example.testwatch.HeartRateListener
import com.example.testwatch.OffBodyStatus
import com.example.testwatch.R
import com.example.testwatch.TrackerDataSubject
import com.example.testwatch.TrackerObserver
import com.example.testwatch.data.HrDatabase
import com.example.testwatch.data.HrSample
import com.example.testwatch.data.ParticipantStore
import com.example.testwatch.sync.BatchSerializer
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class HrTrackingService : Service(), TrackerObserver, ConnectionObserver, SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    private var trackerDataSubject: TrackerDataSubject? = null
    private var connectionManager: ConnectionManager? = null
    private var heartRateListener: HeartRateListener? = null
    private var sensorManager: SensorManager? = null
    private var offBodySensor: Sensor? = null

    private val db by lazy { HrDatabase.get(this) }
    private val dao by lazy { db.hrDao() }
    private val participantStore by lazy { ParticipantStore(this) }

    override fun onCreate() {
        super.onCreate()
        trackerDataSubject = TrackerDataSubject().also { it.addObserver(this) }
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        offBodySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        offBodySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        connectionManager = ConnectionManager(this).also {
            try {
                it.connect(null, applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "connect failed", t)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (syncJob == null || syncJob?.isActive != true) {
            syncJob = scope.launch { syncLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        heartRateListener?.stopTracker()
        connectionManager?.disconnect()
        trackerDataSubject?.removeObserver(this)
        sensorManager?.unregisterListener(this)
        scope.cancel()
        TrackingState.connected.value = false
        TrackingState.ready.value = false
    }

    // ConnectionObserver
    override fun onConnectionResult(isConnected: Boolean) {
        Log.i(TAG, "onConnectionResult=$isConnected")
        TrackingState.connected.value = isConnected
        if (!isConnected) TrackingState.ready.value = false
    }

    override fun onHeartRateAvailability(isAvailable: Boolean) {
        Log.i(TAG, "onHeartRateAvailability=$isAvailable")
        if (!isAvailable) {
            TrackingState.ready.value = false
            return
        }
        val listener = HeartRateListener().also {
            it.setTrackerDataSubject(trackerDataSubject)
        }
        heartRateListener = listener
        connectionManager?.initHeartRate(listener)
        TrackingState.ready.value = true
        listener.startTracker()
    }

    // TrackerObserver
    override fun onHeartRateChanged(timestampMs: Long, status: Int, heartRateValue: Int) {
        TrackingState.latestBpm.value = heartRateValue
        TrackingState.latestStatus.value = status
        val sample = HrSample(
            // SDK timestamp, not arrival time: doze-buffered bursts arrive minutes late
            timestampMs = timestampMs,
            bpm = heartRateValue,
            status = status,
        )
        scope.launch {
            try {
                dao.insert(sample)
            } catch (t: Throwable) {
                Log.w(TAG, "insert failed", t)
            }
        }
    }

    override fun notifyTrackerError(errorResourceId: Int) {
        Log.w(TAG, "tracker error res=$errorResourceId")
    }

    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values[0].roundToInt()
        when (value) {
            OffBodyStatus.DEVICE_ON_BODY -> TrackingState.deviceWorn.value = true
            OffBodyStatus.DEVICE_OFF_BODY -> TrackingState.deviceWorn.value = false
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HR tracking", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle("HR tracking active")
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private suspend fun syncLoop() {
        while (true) {
            delay(SYNC_INTERVAL_MS)
            try {
                syncOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "sync failed", t)
            }
        }
    }

    private suspend fun syncOnce() {
        val pending = dao.unsynced(BATCH_LIMIT)
        if (pending.isEmpty()) return
        val participantId = participantStore.participantId
        val payload = BatchSerializer.encode(participantId, pending)
        val nodes = Wearable.getNodeClient(applicationContext).connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.i(TAG, "no connected nodes; skipping")
            return
        }
        val messageClient = Wearable.getMessageClient(applicationContext)
        var anyOk = false
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, MSG_PATH, payload).await()
                anyOk = true
                Log.i(TAG, "sent ${pending.size} samples to ${node.displayName}")
            } catch (t: Throwable) {
                Log.w(TAG, "sendMessage to ${node.id} failed: ${t.message}")
            }
        }
        if (anyOk) {
            val ids = pending.map { it.id }
            dao.markSynced(ids)
            dao.pruneSynced(pending.last().id - KEEP_RECENT_SYNCED)
        }
    }

    companion object {
        const val MSG_PATH = "/hr_batch"
        private const val CHANNEL_ID = "hr_tracking"
        private const val NOTIF_ID = 1001
        private const val SYNC_INTERVAL_MS = 30L * 1000L  // TESTING: lowered from 5 min
        private const val BATCH_LIMIT = 500
        private const val KEEP_RECENT_SYNCED = 1000L
        private const val TAG = "HrTrackingService"
    }
}
