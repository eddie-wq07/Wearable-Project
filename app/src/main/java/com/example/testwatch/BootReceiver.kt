package com.example.testwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val pending = goAsync()
        Thread {
            try {
                notifyServer(context)
            } catch (t: Throwable) {
                Log.e(TAG, "Boot notify failed: ${t.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notifyServer(context: Context) {
        val payload = JSONObject().apply {
            put("event", "boot_completed")
            put("package", context.packageName)
            put("device_model", Build.MODEL)
            put("device_manufacturer", Build.MANUFACTURER)
            put("android_sdk", Build.VERSION.SDK_INT)
            put("timestamp_ms", System.currentTimeMillis())
        }.toString()

        var attempt = 0
        while (attempt < KioskConfig.BOOT_NOTIFY_MAX_ATTEMPTS) {
            attempt++
            try {
                if (postOnce(payload)) {
                    Log.i(TAG, "Boot notify delivered on attempt $attempt")
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Boot notify attempt $attempt failed: ${t.message}")
            }
            try { Thread.sleep(KioskConfig.BOOT_NOTIFY_RETRY_DELAY_MS) } catch (_: InterruptedException) {}
        }
        Log.e(TAG, "Boot notify gave up after $attempt attempts")
    }

    private fun postOnce(payload: String): Boolean {
        val conn = (URL(KioskConfig.BOOT_NOTIFY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = KioskConfig.BOOT_NOTIFY_TIMEOUT_MS
            readTimeout = KioskConfig.BOOT_NOTIFY_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            return code in 200..299
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
