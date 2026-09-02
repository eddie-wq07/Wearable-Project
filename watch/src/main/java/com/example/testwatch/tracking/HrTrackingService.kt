package com.example.testwatch.tracking

/** The hub. Foreground service tying together the SDK connection, both sensor paths, Room writes,
 *  and the storage monitor that hands the buffered backlog to UploadWorker (direct SFTP). */

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
import com.example.testwatch.config.StorageConfig
import com.example.testwatch.data.HrDatabase
import com.example.testwatch.data.HrSample
import com.example.testwatch.data.SensorBatch
import com.example.testwatch.ondemand.OnDemandController
import com.example.testwatch.sensors.ConnectionManager
import com.example.testwatch.sensors.ConnectionObserver
import com.example.testwatch.sensors.HeartRateListener
import com.example.testwatch.sensors.OffBodyStatus
import com.example.testwatch.sensors.SensorEngine
import com.example.testwatch.sensors.TrackerDataSubject
import com.example.testwatch.sensors.TrackerObserver
import com.example.testwatch.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class HrTrackingService : Service(), TrackerObserver, ConnectionObserver, SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    private var trackerDataSubject: TrackerDataSubject? = null
    private var connectionManager: ConnectionManager? = null
    private var heartRateListener: HeartRateListener? = null
    private var sensorEngine: SensorEngine? = null
    private var sensorManager: SensorManager? = null
    private var offBodySensor: Sensor? = null

    private val db by lazy { HrDatabase.get(this) }
    private val dao by lazy { db.hrDao() }
    private val sensorDao by lazy { db.sensorBatchDao() }

    override fun onCreate() {
        super.onCreate()
        OnDemandController.init(this)
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
        // Standing upload schedule: fires whenever the watch is charging on WiFi.
        UploadWorker.schedulePeriodic(this)
        if (intent?.action == ACTION_RUN_ROUND) {
            val engine = sensorEngine
            if (engine == null) {
                // SDK not connected yet; report failure so the Measure button re-enables.
                OnDemandController.onRoundFinished(this, false)
            } else {
                // Hold the CPU for the ~3-minute round; times out on its own.
                (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                    .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "testwatch:round")
                    .acquire(4 * 60_000L)
                scope.launch {
                    // Re-run replaces today's earlier round before new rows land.
                    try {
                        sensorDao.deleteForSensorsSince(
                            com.example.testwatch.sensors.ON_DEMAND_SENSOR_IDS,
                            OnDemandController.startOfTodayMs(),
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "replace-delete failed", t)
                    }
                    engine.runRoundNow()
                }
            }
        }
        if (intent?.action == ACTION_DRAIN_NOW) {
            // End-of-study offload. The worker is constraint-gated on charger + WiFi,
            // which is the offload procedure anyway; WorkManager holds its own wakelock.
            UploadWorker.enqueueNow(this, "manual DRAIN_NOW")
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
                onRoundFinished = { completed ->
                    OnDemandController.onRoundFinished(applicationContext, completed)
                },
            ).also { it.start() }
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
     * Storage monitor. Data accumulates in hr.db; the actual upload runs in
     * UploadWorker whenever the watch is charging on WiFi. This loop only keeps
     * the dashboard estimate fresh, applies emergency back-pressure, and asks
     * for an immediate upload attempt when the budget is blown.
     */
    private suspend fun syncLoop() {
        while (true) {
            try {
                checkStorage()
            } catch (t: Throwable) {
                Log.w(TAG, "storage check failed", t)
            }
            delay(StorageConfig.CHECK_INTERVAL_MS)
        }
    }

    private suspend fun checkStorage() {
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
            UploadWorker.enqueueNow(this, "buffered=${buffered / MB} MB, free=${free / MB} MB")
        }
    }

    private suspend fun bufferedBytesEstimate(): Long {
        val logical = sensorDao.unsyncedBytes() + dao.unsyncedCount() * StorageConfig.HR_ROW_BYTES
        return logical * StorageConfig.OVERHEAD_PCT / 100
    }

    companion object {
        const val ACTION_RUN_ROUND = "com.example.testwatch.RUN_ROUND"
        const val ACTION_DRAIN_NOW = "com.example.testwatch.DRAIN_NOW"
        private const val CHANNEL_ID = "hr_tracking"
        private const val MEASURE_CHANNEL_ID = "measurements"
        private const val NOTIF_ID = 1001
        private const val MEASURE_NOTIF_ID = 1002
        private const val MB = 1024L * 1024L
        private const val TAG = "HrTrackingService"
    }
}
