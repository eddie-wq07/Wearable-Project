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
import com.example.testwatch.R
import com.example.testwatch.config.SensorConfig
import com.example.testwatch.config.StorageConfig
import com.example.testwatch.data.HrDatabase
import com.example.testwatch.data.HrSample
import com.example.testwatch.data.ParticipantStore
import com.example.testwatch.data.SensorBatch
import com.example.testwatch.sensors.ConnectionManager
import com.example.testwatch.sensors.ConnectionObserver
import com.example.testwatch.sensors.HeartRateListener
import com.example.testwatch.sensors.OffBodyStatus
import com.example.testwatch.sensors.SensorEngine
import com.example.testwatch.sensors.TrackerDataSubject
import com.example.testwatch.sensors.TrackerObserver
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
    private val drainInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private var trackerDataSubject: TrackerDataSubject? = null
    private var connectionManager: ConnectionManager? = null
    private var heartRateListener: HeartRateListener? = null
    private var sensorEngine: SensorEngine? = null
    private var sensorManager: SensorManager? = null
    private var offBodySensor: Sensor? = null

    private val db by lazy { HrDatabase.get(this) }
    private val dao by lazy { db.hrDao() }
    private val sensorDao by lazy { db.sensorBatchDao() }
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
        try {
            startForegroundCompat()
        } catch (t: Throwable) {
            // Sticky restart from the background: Android 14+ forbids startForeground
            // here. Stop cleanly; MainActivity restarts us from the foreground.
            Log.w(TAG, "startForeground not allowed; stopping: ${t.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (syncJob == null || syncJob?.isActive != true) {
            syncJob = scope.launch { syncLoop() }
        }
        if (intent?.action == ACTION_RUN_ROUND) {
            // Hold the CPU for the ~3-minute round; times out on its own.
            (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "testwatch:round")
                .acquire(4 * 60_000L)
            sensorEngine?.runRoundNow()
            MeasureAlarmReceiver.scheduleNext(this, SensorConfig.ON_DEMAND_INTERVAL_MIN)
        }
        if (intent?.action == ACTION_DRAIN_NOW) {
            // End-of-study offload: multi-GB backlogs take a while, so run this
            // with the watch on its charger. Wakelock keeps BT sends flowing.
            (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "testwatch:drain")
                .acquire(60 * 60_000L)
            scope.launch { drainAll("manual DRAIN_NOW") }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        sensorEngine?.stop()
        sensorEngine = null
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
        if (!isConnected) {
            TrackingState.ready.value = false
            sensorEngine?.stop()
            sensorEngine = null
            return
        }
        startSensorEngine()
    }

    private fun startSensorEngine() {
        if (sensorEngine != null) return
        val trackingService = connectionManager?.trackingService ?: return
        try {
            sensorEngine = SensorEngine(
                service = trackingService,
                scope = scope,
                store = { spec, firstTs, pointsJson ->
                    scope.launch {
                        try {
                            sensorDao.insert(SensorBatch(sensor = spec.id, timestampMs = firstTs, points = pointsJson))
                        } catch (t: Throwable) {
                            Log.w(TAG, "sensor insert failed", t)
                        }
                    }
                },
                prompt = ::showMeasurementPrompt,
            ).also { it.start() }
            MeasureAlarmReceiver.scheduleNext(this, SensorConfig.FIRST_ROUND_DELAY_MIN)
        } catch (t: Throwable) {
            Log.e(TAG, "sensor engine start failed", t)
        }
    }

    private fun showMeasurementPrompt(text: String?) {
        TrackingState.currentMeasurement.value = text
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (text == null) {
            nm.cancel(MEASURE_NOTIF_ID)
            return
        }
        // Buzz explicitly rather than relying on notification haptics.
        getSystemService(android.os.Vibrator::class.java)?.vibrate(
            android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1),
        )
        if (nm.getNotificationChannel(MEASURE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(MEASURE_CHANNEL_ID, "Measurements", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val openApp = Intent(this, com.example.testwatch.presentation.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, openApp,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, MEASURE_CHANNEL_ID)
            .setSmallIcon(R.drawable.splash_icon)
            .setContentTitle(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            // Full-screen intent: turns the screen on and opens the app so the
            // participant sees the instructions without touching anything.
            .setFullScreenIntent(pi, true)
            .build()
        nm.notify(MEASURE_NOTIF_ID, notif)
        try {
            startActivity(openApp)
        } catch (t: Throwable) {
            Log.i(TAG, "direct activity launch blocked (${t.message}); full-screen intent will handle it")
        }
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

    /**
     * Store-and-forward (2026-08-26): data accumulates in hr.db and is pushed
     * to the phone only when the buffer crosses StorageConfig.HIGH_WATER_BYTES,
     * free space falls below MIN_FREE_BYTES, or a DRAIN_NOW broadcast arrives.
     */
    private suspend fun syncLoop() {
        while (true) {
            try {
                checkStorageAndMaybeDrain()
            } catch (t: Throwable) {
                Log.w(TAG, "storage check failed", t)
            }
            delay(StorageConfig.CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkStorageAndMaybeDrain() {
        val buffered = bufferedBytesEstimate()
        TrackingState.bufferedBytes.value = buffered
        val free = android.os.StatFs(filesDir.path).availableBytes

        if (free < StorageConfig.EMERGENCY_FREE_BYTES) {
            // Deleting rows lets SQLite reuse pages (the file stops growing) but
            // never shrinks the file — the point here is to halt growth, loudly.
            val dropped = sensorDao.dropOldestUnsynced(StorageConfig.EMERGENCY_DROP_ROWS) +
                dao.dropOldestUnsynced(StorageConfig.EMERGENCY_DROP_ROWS)
            TrackingState.droppedRows.value += dropped
            Log.e(TAG, "EMERGENCY: free=${free / MB} MB — dropped $dropped oldest unsynced rows (data gap)")
        }

        if (buffered >= StorageConfig.HIGH_WATER_BYTES || free < StorageConfig.MIN_FREE_BYTES) {
            drainAll("buffered=${buffered / MB} MB, free=${free / MB} MB")
        }
    }

    private suspend fun drainAll(reason: String) {
        if (!drainInFlight.compareAndSet(false, true)) return
        Log.i(TAG, "drain start: $reason")
        TrackingState.draining.value = true
        try {
            var rounds = 0
            while (syncOnce()) {
                rounds++
                delay(StorageConfig.DRAIN_PAUSE_MS)
            }
            Log.i(TAG, "drain stopped after $rounds rounds")
        } finally {
            TrackingState.draining.value = false
            TrackingState.bufferedBytes.value = bufferedBytesEstimate()
            drainInFlight.set(false)
        }
    }

    private suspend fun bufferedBytesEstimate(): Long {
        val logical = sensorDao.unsyncedBytes() + dao.unsyncedCount() * StorageConfig.HR_ROW_BYTES
        return logical * StorageConfig.OVERHEAD_PCT / 100
    }

    /** One bounded push to the phone. Returns true when something was sent and
     *  marked synced (drain should keep going); false when the buffer is empty
     *  or the phone is unreachable (buffer holds, retry next check). */
    private suspend fun syncOnce(): Boolean {
        val hrPending = dao.unsynced(BATCH_LIMIT)
        val sensorPending = sensorDao.unsynced(SENSOR_BATCH_LIMIT)
        if (hrPending.isEmpty() && sensorPending.isEmpty()) return false
        val participantId = participantStore.participantId
        val nodes = Wearable.getNodeClient(applicationContext).connectedNodes.await()
        if (nodes.isEmpty()) {
            Log.i(TAG, "no connected nodes; buffer holds")
            return false
        }
        val messageClient = Wearable.getMessageClient(applicationContext)
        var sentAny = false

        if (hrPending.isNotEmpty()) {
            val payload = BatchSerializer.encode(participantId, hrPending)
            if (sendToAnyNode(messageClient, nodes, MSG_PATH, payload, "${hrPending.size} HR samples")) {
                dao.markSynced(hrPending.map { it.id })
                dao.pruneSynced(hrPending.last().id - KEEP_RECENT_SYNCED)
                sentAny = true
            }
        }

        // MessageClient caps a message at ~100 KB; ship sensor rows in size-capped chunks.
        var i = 0
        while (i < sensorPending.size) {
            val chunk = mutableListOf<SensorBatch>()
            var bytes = 0
            while (i < sensorPending.size &&
                (chunk.isEmpty() || bytes + sensorPending[i].points.length < MAX_SENSOR_MSG_BYTES)
            ) {
                bytes += sensorPending[i].points.length
                chunk += sensorPending[i]
                i++
            }
            val payload = BatchSerializer.encodeSensorRows(participantId, chunk)
            if (!sendToAnyNode(messageClient, nodes, SENSOR_MSG_PATH, payload, "${chunk.size} sensor rows")) {
                // Link dropped mid-drain: stop; unsent rows stay buffered.
                return false
            }
            sensorDao.markSynced(chunk.map { it.id })
            sentAny = true
        }
        if (sensorPending.isNotEmpty()) {
            sensorDao.pruneSynced(sensorPending.last().id - KEEP_RECENT_SYNCED)
        }
        return sentAny
    }

    private suspend fun sendToAnyNode(
        messageClient: com.google.android.gms.wearable.MessageClient,
        nodes: List<com.google.android.gms.wearable.Node>,
        path: String,
        payload: ByteArray,
        what: String,
    ): Boolean {
        var anyOk = false
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, path, payload).await()
                anyOk = true
                Log.i(TAG, "sent $what to ${node.displayName}")
            } catch (t: Throwable) {
                Log.w(TAG, "sendMessage to ${node.id} failed: ${t.message}")
            }
        }
        return anyOk
    }

    companion object {
        const val MSG_PATH = "/hr_batch"
        const val SENSOR_MSG_PATH = "/sensor_batch"
        const val ACTION_RUN_ROUND = "com.example.testwatch.RUN_ROUND"
        const val ACTION_DRAIN_NOW = "com.example.testwatch.DRAIN_NOW"
        private const val CHANNEL_ID = "hr_tracking"
        private const val MEASURE_CHANNEL_ID = "measurements"
        private const val NOTIF_ID = 1001
        private const val MEASURE_NOTIF_ID = 1002
        private const val SENSOR_BATCH_LIMIT = 300
        private const val MAX_SENSOR_MSG_BYTES = 60_000
        private const val BATCH_LIMIT = 500
        private const val KEEP_RECENT_SYNCED = 1000L
        private const val MB = 1024L * 1024L
        private const val TAG = "HrTrackingService"
    }
}
