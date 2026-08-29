package com.example.testwatch

/** Restarts HrTrackingService after device reboot, so tracking resumes without manual relaunch. */

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.testwatch.tracking.HrTrackingService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startForegroundService(Intent(context, HrTrackingService::class.java))
            Log.i(TAG, "tracking service start requested after boot")
        } catch (t: Throwable) {
            Log.w(TAG, "could not start tracking service after boot: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
