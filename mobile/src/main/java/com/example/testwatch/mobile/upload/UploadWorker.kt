package com.example.testwatch.mobile.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.testwatch.mobile.data.PhoneHrDatabase
import com.example.testwatch.mobile.data.PhoneHrSample
import com.example.testwatch.mobile.status.PipelineStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.xfer.InMemorySourceFile
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.Security

@Serializable
private data class UploadSample(
    val participant_id: String,
    val timestamp_ms: Long,
    val time: String,
    val bpm: Int,
    val status: Int,
    val received_at: Long,
)

@Serializable
private data class UploadPayload(
    val watch_serial: String,
    val uploaded_at: Long,
    val uploaded_at_readable: String,
    val is_test: Boolean,
    val samples: List<UploadSample>,
)

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val json = Json { prettyPrint = false }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        PipelineStatus.uploading.value = true
        try {
            if (!ServerConfig.isConfigured()) {
                Log.w(TAG, "ServerConfig has TODO values; aborting")
                recordResult(ok = false, count = 0, message = "ServerConfig not filled in")
                return@withContext Result.failure()
            }
            val dao = PhoneHrDatabase.get(applicationContext).phoneHrDao()
            val rows = dao.unuploaded()
            if (rows.isEmpty()) {
                recordResult(ok = true, count = 0, message = "nothing to upload")
                return@withContext Result.success()
            }
            Log.i(TAG, "uploading ${rows.size} samples")

            val payload = json.encodeToString(buildPayload(rows)).toByteArray(Charsets.UTF_8)
            val filename = "hr_${rows.first().participantId}_${System.currentTimeMillis()}.json"

            try {
                uploadOverSftp(filename, payload)
                dao.markUploaded(rows.map { it.id })
                dao.pruneUploaded(rows.last().id - KEEP_RECENT)
                recordResult(ok = true, count = rows.size, message = "uploaded $filename")
                Result.success()
            } catch (t: Throwable) {
                Log.w(TAG, "upload failed: ${t.message}")
                recordResult(ok = false, count = rows.size, message = t.message ?: "unknown error")
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }
        } finally {
            PipelineStatus.uploading.value = false
        }
    }

    private fun recordResult(ok: Boolean, count: Int, message: String) {
        PipelineStatus.lastUploadAttemptMs.value = System.currentTimeMillis()
        PipelineStatus.lastUploadOk.value = ok
        PipelineStatus.lastUploadSampleCount.value = count
        PipelineStatus.lastUploadMessage.value = message
        if (ok && count > 0) {
            PipelineStatus.totalUploadsSucceeded.value = PipelineStatus.totalUploadsSucceeded.value + 1
            PipelineStatus.totalSamplesUploaded.value = PipelineStatus.totalSamplesUploaded.value + count
        }
    }

    private fun buildPayload(rows: List<PhoneHrSample>): UploadPayload =
        UploadPayload(
            watch_serial = android.os.Build.SERIAL.orEmpty(),
            uploaded_at = System.currentTimeMillis(),
            uploaded_at_readable = readable(System.currentTimeMillis()),
            is_test = true,  // flip to false (or pull from BuildConfig) for prod uploads
            samples = rows.map {
                UploadSample(
                    participant_id = it.participantId,
                    timestamp_ms = it.timestampMs,
                    time = readable(it.timestampMs),
                    bpm = it.bpm,
                    status = it.status,
                    received_at = it.receivedAt,
                )
            },
        )

    // Human-readable mirror of timestamp_ms in the phone's local timezone,
    // e.g. "Jul 30 2026, 10:40:20 PM PDT". Display only — ingestion keys off timestamp_ms.
    private val readableFormat = java.text.SimpleDateFormat("MMM d yyyy, h:mm:ss a zzz", java.util.Locale.US)
    private fun readable(ms: Long): String = readableFormat.format(java.util.Date(ms))

    private fun uploadOverSftp(filename: String, body: ByteArray) {
        ensureFullBouncyCastle()
        val ssh = SSHClient(androidConfig())
        try {
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(ServerConfig.HOST, ServerConfig.PORT)
            ssh.authPassword(ServerConfig.USER, ServerConfig.PASSWORD)
            val sftp: SFTPClient = ssh.newSFTPClient()
            try {
                val cwd = try { sftp.canonicalize(".") } catch (_: Throwable) { "?" }
                Log.i(TAG, "SFTP cwd: $cwd")
                val remoteDir = ServerConfig.REMOTE_DIR.trimEnd('/')
                // Ensure the inbox dir exists (idempotent — ignore "already exists").
                try {
                    sftp.mkdir(remoteDir)
                    Log.i(TAG, "mkdir $remoteDir OK")
                } catch (t: Throwable) {
                    Log.i(TAG, "mkdir $remoteDir skipped: ${t.message}")
                }
                Log.i(TAG, "SFTP put to $remoteDir/$filename (${body.size} bytes)")
                sftp.put(
                    object : InMemorySourceFile() {
                        override fun getName(): String = filename
                        override fun getLength(): Long = body.size.toLong()
                        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
                    },
                    "$remoteDir/$filename",
                )
                Log.i(TAG, "SFTP put OK")
            } finally {
                sftp.close()
            }
        } finally {
            try { ssh.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * sshj's DefaultConfig advertises X25519/Ed25519 algorithms that Android's
     * Bouncy Castle doesn't support. Filter them out so the handshake falls
     * back to NIST ECDH + RSA / ECDSA, which Android can handle.
     */
    /**
     * Android ships a stripped-down "BC" provider that's missing many algorithms
     * sshj asks for (SHA-256, X25519, etc.). sshj transitively pulls in the
     * full Bouncy Castle, but it isn't registered. Swap in the full BC at top
     * priority so JCA lookups for "BC" hit the real implementation.
     */
    private fun ensureFullBouncyCastle() {
        val existing = Security.getProvider("BC")
        if (existing == null || existing::class.java.name != BouncyCastleProvider::class.java.name) {
            Security.removeProvider("BC")
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private fun androidConfig(): DefaultConfig = DefaultConfig().apply {
        keyExchangeFactories = keyExchangeFactories.filterNot {
            it.name.contains("curve25519", ignoreCase = true)
        }
    }

    companion object {
        const val NAME = "hr_upload"
        private const val TAG = "UploadWorker"
        private const val MAX_RETRIES = 10
        private const val KEEP_RECENT = 5000L
    }
}
