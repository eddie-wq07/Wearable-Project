package com.example.testwatch.upload

/** Direct watch-to-server SFTP upload via sshj: key auth with this watch's Keystore key, pinned
 *  host key, chunked drain of the Room backlog. Runs only while charging on unmetered WiFi. */

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.testwatch.config.ServerConfig
import com.example.testwatch.config.StorageConfig
import com.example.testwatch.data.HrDatabase
import com.example.testwatch.data.ParticipantStore
import com.example.testwatch.data.SensorBatch
import com.example.testwatch.sync.BatchSerializer
import com.example.testwatch.tracking.TrackingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.xfer.InMemorySourceFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.PublicKey
import java.util.concurrent.TimeUnit

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val db by lazy { HrDatabase.get(applicationContext) }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = db.hrDao()
        val sensorDao = db.sensorBatchDao()
        if (dao.unsyncedCount() == 0 && sensorDao.unsyncedCount() == 0) {
            return@withContext Result.success()
        }
        val participantId = ParticipantStore(applicationContext).participantId
        val remoteDir = "${ServerConfig.REMOTE_BASE_DIR}/$participantId"

        TrackingState.draining.value = true
        try {
            val ssh = connect()
            try {
                val sftp = ssh.newSFTPClient()
                try {
                    // Idempotent; the base dir is group-writable so this watch can
                    // create its own subfolder (shared-account model, no chroot).
                    try { sftp.mkdirs(remoteDir) } catch (t: Throwable) {
                        Log.i(TAG, "mkdirs $remoteDir skipped: ${t.message}")
                    }
                    drain(sftp, remoteDir, participantId)
                } finally {
                    sftp.close()
                }
            } finally {
                try { ssh.disconnect() } catch (_: Throwable) {}
            }
            Log.i(TAG, "upload pass done; remaining hr=${dao.unsyncedCount()} sensor=${sensorDao.unsyncedCount()}")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "upload failed: ${t.message}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        } finally {
            TrackingState.draining.value = false
            TrackingState.bufferedBytes.value =
                (sensorDao.unsyncedBytes() + dao.unsyncedCount() * StorageConfig.HR_ROW_BYTES) *
                StorageConfig.OVERHEAD_PCT / 100
        }
    }

    /** Uploads the backlog in bounded slices, marking rows synced after each file so progress
     *  survives interruption (JobScheduler stops long-running workers around the 10-minute
     *  mark; the next constrained window simply resumes where this one stopped).
     *
     *  Layout follows docs/upload-format.md and is keyed to when the data was RECORDED,
     *  not when it uploads: each file holds rows from a single recording day, lands in
     *  `<remoteDir>/<yyyy-MM-dd>/` for that day, and is named by the first row's recording
     *  time — `<kind>_<participantId>_<HHmmss>_<NNN>.json`, with a per-drain-pass slice
     *  counter shared across both file kinds so same-second slices never overwrite each
     *  other. A multi-day backlog draining in one pass fans out into one folder per day. */
    private suspend fun drain(sftp: SFTPClient, remoteDir: String, participantId: String) {
        val dao = db.hrDao()
        val sensorDao = db.sensorBatchDao()
        var slice = 0
        var lastDayDir: String? = null
        // Slices land in the day's hidden parts/ staging dir; the server-side consolidator
        // (server/consolidate.py, cron on MISR) merges them into the two researcher-facing
        // files — sensors_<pid>_continuous.json and sensors_<pid>_ondemand.json — and
        // deletes the consumed parts.
        fun ensureDayDir(firstTs: Long): String {
            val dir = "$remoteDir/${dayOf(firstTs)}/parts"
            if (dir != lastDayDir) {
                try { sftp.mkdirs(dir) } catch (t: Throwable) {
                    Log.i(TAG, "mkdirs $dir skipped: ${t.message}")
                }
                lastDayDir = dir
            }
            return dir
        }
        while (!isStopped) {
            val hrSlice = firstDay(dao.unsynced(HR_SLICE_ROWS)) { it.timestampMs }
            val sensorSlice = sensorSlice(firstDay(sensorDao.unsynced(SENSOR_SLICE_ROWS)) { it.timestampMs })
            if (hrSlice.isEmpty() && sensorSlice.isEmpty()) return

            if (hrSlice.isNotEmpty()) {
                val firstTs = hrSlice.first().timestampMs
                val body = BatchSerializer.buildHrPayload(participantId, hrSlice)
                put(sftp, "${ensureDayDir(firstTs)}/hr_${participantId}_${timeOf(firstTs)}_${pad(++slice)}.json", body)
                dao.markSynced(hrSlice.map { it.id })
                dao.pruneSynced(hrSlice.last().id - KEEP_RECENT_SYNCED)
            }
            if (sensorSlice.isNotEmpty()) {
                val firstTs = sensorSlice.first().timestampMs
                val body = BatchSerializer.buildSensorPayload(participantId, sensorSlice)
                put(sftp, "${ensureDayDir(firstTs)}/sensors_${participantId}_${timeOf(firstTs)}_${pad(++slice)}.json", body)
                sensorDao.markSynced(sensorSlice.map { it.id })
                sensorDao.pruneSynced(sensorSlice.last().id - KEEP_RECENT_SYNCED)
            }
        }
    }

    /** Head-of-backlog rows sharing the first row's recording day, so every upload file
     *  (and therefore its folder) is single-day. */
    private fun <T> firstDay(rows: List<T>, ts: (T) -> Long): List<T> {
        if (rows.isEmpty()) return rows
        val day = dayOf(ts(rows.first()))
        return rows.takeWhile { dayOf(ts(it)) == day }
    }

    // Watch-local recording-time stamps (filesystem-safe, sort chronologically): day folder + file time.
    private val dayStampFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    private val timeStampFormat = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US)
    private fun dayOf(ms: Long): String = synchronized(dayStampFormat) { dayStampFormat.format(java.util.Date(ms)) }
    private fun timeOf(ms: Long): String = synchronized(timeStampFormat) { timeStampFormat.format(java.util.Date(ms)) }
    private fun pad(n: Int): String = n.toString().padStart(3, '0')

    /** Caps a sensor slice by accumulated JSON bytes so one upload file stays bounded. */
    private fun sensorSlice(rows: List<SensorBatch>): List<SensorBatch> {
        val slice = mutableListOf<SensorBatch>()
        var bytes = 0
        for (row in rows) {
            if (slice.isNotEmpty() && bytes + row.points.length > MAX_FILE_BYTES) break
            bytes += row.points.length
            slice += row
        }
        return slice
    }

    private fun put(sftp: SFTPClient, remotePath: String, body: ByteArray) {
        Log.i(TAG, "SFTP put $remotePath (${body.size} bytes)")
        sftp.put(
            object : InMemorySourceFile() {
                override fun getName(): String = remotePath.substringAfterLast('/')
                override fun getLength(): Long = body.size.toLong()
                override fun getInputStream(): InputStream = ByteArrayInputStream(body)
            },
            remotePath,
        )
    }

    private fun connect(): SSHClient {
        // Do NOT pin sshj's JCA lookups to Bouncy Castle: the Keystore-backed private key can
        // only be used via delayed provider selection (Signature.getInstance without a provider,
        // resolved to the AndroidKeyStore provider at initSign). Android's own providers cover
        // the NIST-curve KEX, ECDSA, and AES the negotiated connection needs.
        SecurityUtils.setRegisterBouncyCastle(false)
        val config = DefaultConfig().apply {
            // Android lacks the X25519/Ed25519 primitives sshj's DefaultConfig advertises;
            // drop them so negotiation lands on ECDH-over-NIST + ECDSA/RSA host keys.
            keyExchangeFactories = keyExchangeFactories.filterNot {
                it.name.contains("curve25519", ignoreCase = true) || it.name.contains("ed25519", ignoreCase = true)
            }
        }
        val ssh = SSHClient(config)
        ssh.addHostKeyVerifier(pinnedVerifier())
        ssh.connect(ServerConfig.HOST, ServerConfig.PORT)
        ssh.authPublickey(ServerConfig.USER, WatchKeys.keyProvider(ServerConfig.KEYSTORE_ALIAS))
        return ssh
    }

    /** Accepts the host key iff it matches one of the pinned SHA256 fingerprints. */
    private fun pinnedVerifier(): HostKeyVerifier {
        val pinned = ServerConfig.HOST_KEY_FINGERPRINTS.map { FingerprintVerifier.getInstance(it) }
        return object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                pinned.any { it.verify(hostname, port, key) }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val PERIODIC_NAME = "watch_upload_periodic"
        private const val NOW_NAME = "watch_upload_now"
        private const val MAX_RETRIES = 10
        private const val HR_SLICE_ROWS = 5000
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val SENSOR_SLICE_ROWS = 2000
        private const val KEEP_RECENT_SYNCED = 1000L

        /** Charging + unmetered WiFi: the upload runs only in this state, everything else
         *  buffers locally. Both conditions are plain WorkManager Constraints, so no manual
         *  BatteryManager polling is needed. */
        private fun constraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .build()

        /** Standing daily schedule: uploads run at most once a day, whenever the charging +
         *  unmetered-WiFi constraints are met within the 24 h window. Idempotent. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UploadWorker>(24, TimeUnit.HOURS)
                    .setConstraints(constraints())
                    .build(),
            )
        }

        /** Immediate attempt (high-water mark, DRAIN_NOW). Still constraint-gated: it runs the
         *  moment the watch is on its charger with WiFi, which is the drain procedure anyway. */
        fun enqueueNow(context: Context, reason: String) {
            Log.i(TAG, "upload requested: $reason")
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>()
                    .setConstraints(constraints())
                    .build(),
            )
        }
    }
}
