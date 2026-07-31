package com.example.testwatch.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.testwatch.mobile.data.PhoneHrDatabase
import com.example.testwatch.mobile.status.PipelineStatus
import com.example.testwatch.mobile.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class StatusActivity : Activity() {

    private var scope: CoroutineScope? = null
    private lateinit var tvDb: TextView
    private lateinit var tvBatch: TextView
    private lateinit var tvUpload: TextView
    private lateinit var tvTotals: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    override fun onStart() {
        super.onStart()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        startObservers(s)
    }

    override fun onStop() {
        super.onStop()
        scope?.cancel()
        scope = null
    }

    private fun startObservers(scope: CoroutineScope) {
        // Poll Room every 1.5s for DB counts (works even after first arrival)
        scope.launch {
            val dao = PhoneHrDatabase.get(applicationContext).phoneHrDao()
            while (true) {
                val total = runCatching { dao.allCount() }.getOrDefault(0)
                val pending = runCatching { dao.unuploadedCount() }.getOrDefault(0)
                val uploaded = total - pending
                tvDb.text = "$total total · $pending pending · $uploaded uploaded"
                delay(1500)
            }
        }

        // Last batch from watch
        scope.launch {
            combine(
                PipelineStatus.lastBatchReceivedMs,
                PipelineStatus.lastBatchSize,
                PipelineStatus.lastBatchParticipant,
            ) { ts, size, pid -> Triple(ts, size, pid) }.collect { (ts, size, pid) ->
                tvBatch.text = if (ts == 0L) "—"
                else "$size samples · $pid\n${relativeNow(ts)}"
            }
        }

        // Upload state (compose-flow-style: uploading + last attempt + result)
        scope.launch {
            combine(
                PipelineStatus.uploading,
                PipelineStatus.lastUploadOk,
                PipelineStatus.lastUploadMessage,
                PipelineStatus.lastUploadAttemptMs,
                PipelineStatus.lastUploadSampleCount,
            ) { uploading, ok, msg, ts, count -> UploadView(uploading, ok, msg, ts, count) }
                .collect { v ->
                    tvUpload.text = when {
                        v.uploading -> "Uploading…"
                        v.ts == 0L -> "—"
                        v.ok == true -> "OK · ${v.count} samples\n${relativeNow(v.ts)}"
                        else -> "FAILED · ${v.count} samples\n${relativeNow(v.ts)}\n${v.msg}"
                    }
                }
        }

        // Session totals
        scope.launch {
            combine(
                PipelineStatus.totalBatchesReceived,
                PipelineStatus.totalUploadsSucceeded,
                PipelineStatus.totalSamplesUploaded,
            ) { b, u, s -> "$b batches received · $u uploads ok · $s samples sent" }
                .collect { tvTotals.text = it }
        }
    }

    private fun forceUpload() {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(UploadWorker.NAME, ExistingWorkPolicy.REPLACE, req)
    }

    private fun buildLayout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        column.addView(makeTitle(getString(R.string.companion_name)))
        column.addView(makeSubtitle("Pipeline status — live"))
        column.addView(makeSectionHeader("Phone database"))
        tvDb = makeValue().also(column::addView)
        column.addView(makeSectionHeader("Last batch from watch"))
        tvBatch = makeValue().also(column::addView)
        column.addView(makeSectionHeader("Last upload"))
        tvUpload = makeValue().also(column::addView)
        column.addView(makeSectionHeader("Session totals"))
        tvTotals = makeValue().also(column::addView)
        column.addView(Button(this).apply {
            text = "Force upload now"
            setOnClickListener { forceUpload() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = 32
            layoutParams = lp
        })
        return ScrollView(this).apply {
            addView(column)
        }
    }

    private fun makeTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 24f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 8)
    }

    private fun makeSubtitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#888888"))
        setPadding(0, 0, 0, 16)
    }

    private fun makeSectionHeader(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#888888"))
        setPadding(0, 24, 0, 4)
    }

    private fun makeValue() = TextView(this).apply {
        textSize = 16f
        setPadding(0, 0, 0, 4)
    }

    private fun relativeNow(ts: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)

    private data class UploadView(
        val uploading: Boolean,
        val ok: Boolean?,
        val msg: String,
        val ts: Long,
        val count: Int,
    )
}
