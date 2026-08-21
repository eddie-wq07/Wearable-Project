package com.example.testwatch.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whoop-style live dashboard: hero HR figure + one stat tile per sensor.
 * Values wear text tokens; the colored dot carries sensor identity
 * (dark-mode categorical palette from the dataviz reference).
 */
class StatusActivity : Activity() {

    private object Ui {
        const val BG = 0xFF101010.toInt()
        const val CARD = 0xFF1A1A19.toInt()
        const val TEXT = 0xFFFFFFFF.toInt()
        const val TEXT_2 = 0xFFC3C2B7.toInt()
        const val TEXT_3 = 0xFF7A796F.toInt()
    }

    /** Fixed slot order — color follows the sensor, never its position on screen. */
    private val sensorDot = mapOf(
        "hr" to 0xFF3987E5.toInt(),
        "ppg" to 0xFFD95926.toInt(),
        "accel" to 0xFF199E70.toInt(),
        "skin_temp" to 0xFFC98500.toInt(),
        "spo2" to 0xFFD55181.toInt(),
        "ecg" to 0xFF008300.toInt(),
        "bia" to 0xFF9085E9.toInt(),
        "mf_bia" to 0xFFE66767.toInt(),
    )

    private class Tile(val value: TextView, val caption: TextView)

    private var scope: CoroutineScope? = null
    private lateinit var tvHero: TextView
    private lateinit var tvHeroCaption: TextView
    private val tiles = mutableMapOf<String, Tile>()
    private lateinit var tvPipeline: TextView

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
        scope.launch {
            val db = PhoneHrDatabase.get(applicationContext)
            val hrDao = db.phoneHrDao()
            val sensorDao = db.phoneSensorDao()
            while (true) {
                runCatching {
                    val hr = hrDao.latest()
                    tvHero.text = if (hr != null && hr.bpm > 0) "${hr.bpm}" else "--"
                    tvHeroCaption.text = if (hr == null) "bpm · waiting for watch"
                    else "bpm · ${relativeNow(hr.timestampMs)}"

                    val counts = sensorDao.counts().associateBy { it.sensor }
                    for ((sensor, tile) in tiles) {
                        val c = counts[sensor]
                        if (c == null) {
                            tile.value.text = "--"
                            tile.caption.text = "no data"
                            continue
                        }
                        val last = sensorDao.latest(sensor)
                        val point = last?.let { lastPoint(it.points) }
                        tile.value.text = when (sensor) {
                            "skin_temp" -> point?.optDouble("object_c")?.takeIf { !it.isNaN() }?.let { "%.1f °C".format(it) } ?: "--"
                            "spo2" -> point?.optInt("spo2")?.takeIf { it > 0 }?.let { "$it%" } ?: "--"
                            "bia", "mf_bia" -> point?.optDouble("progress")?.takeIf { !it.isNaN() }?.let { "%.0f%%".format(it) } ?: "--"
                            else -> "${c.n} rows"
                        }
                        tile.caption.text = relativeNow(c.lastTs).toString()
                    }
                }
                delay(1500)
            }
        }

        scope.launch {
            combine(
                PipelineStatus.lastBatchReceivedMs,
                PipelineStatus.uploading,
                PipelineStatus.lastUploadOk,
                PipelineStatus.lastUploadAttemptMs,
                PipelineStatus.totalSamplesUploaded,
            ) { batchTs, uploading, ok, upTs, total ->
                val batch = if (batchTs == 0L) "no batches yet" else "batch ${relativeNow(batchTs)}"
                val upload = when {
                    uploading -> "uploading…"
                    upTs == 0L -> "no uploads yet"
                    ok == true -> "upload ok ${relativeNow(upTs)}"
                    else -> "UPLOAD FAILED ${relativeNow(upTs)}"
                }
                "$batch\n$upload · $total samples sent"
            }.collect { tvPipeline.text = it }
        }
    }

    private fun lastPoint(points: String): JSONObject? =
        runCatching {
            val arr = JSONArray(points)
            if (arr.length() == 0) null else arr.getJSONObject(arr.length() - 1)
        }.getOrNull()

    private fun forceUpload() {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(UploadWorker.NAME, ExistingWorkPolicy.KEEP, req)
    }

    private fun buildLayout(): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }

        column.addView(TextView(this).apply {
            text = "WEARABLE LAB"
            textSize = 12f
            letterSpacing = 0.2f
            setTextColor(Ui.TEXT_3)
            gravity = Gravity.CENTER
        })

        // Hero: heart rate
        column.addView(TextView(this).apply {
            text = "--"
            textSize = 72f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }.also { tvHero = it })
        column.addView(TextView(this).apply {
            text = "bpm"
            textSize = 13f
            setTextColor(Ui.TEXT_2)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }.also { tvHeroCaption = it })

        // Sensor tiles, two per row
        val tileSpecs = listOf(
            "ppg" to "PPG",
            "accel" to "ACCEL",
            "skin_temp" to "SKIN TEMP",
            "spo2" to "SPO2",
            "ecg" to "ECG",
            "bia" to "BIA",
            "mf_bia" to "MF-BIA",
        )
        tileSpecs.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (sensor, label) ->
                row.addView(makeTile(sensor, label, dp(12)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            column.addView(row)
        }

        // Pipeline footer
        column.addView(TextView(this).apply {
            text = "—"
            textSize = 12f
            setTextColor(Ui.TEXT_3)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
        }.also { tvPipeline = it })

        column.addView(Button(this).apply {
            text = "Force upload"
            setTextColor(Ui.TEXT_2)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Ui.CARD)
            }
            setOnClickListener { forceUpload() }
        })

        return ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(column)
        }
    }

    private fun makeTile(sensor: String, label: String, margin: Int): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Ui.CARD)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(sensorDot[sensor] ?: Ui.TEXT_2)
            }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
        header.addView(TextView(this).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.1f
            setTextColor(Ui.TEXT_2)
        })
        tile.addView(header)

        val value = TextView(this).apply {
            text = "--"
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Ui.TEXT)
            setPadding(0, dp(4), 0, 0)
        }
        tile.addView(value)
        val caption = TextView(this).apply {
            text = ""
            textSize = 11f
            setTextColor(Ui.TEXT_3)
        }
        tile.addView(caption)
        tiles[sensor] = Tile(value, caption)

        val wrapper = LinearLayout(this).apply { setPadding(dp(3), dp(3), dp(3), dp(3)) }
        wrapper.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrapper
    }

    private fun relativeNow(ts: Long): CharSequence =
        DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS)
}
