package com.example.testwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.testwatch.tracking.HrTrackingService

/** Restarts the tracking service after a reboot so a power-cycled watch resumes
 *  collection without anyone opening the app. BOOT_COMPLETED is exempt from the
 *  background foreground-service-start restriction for health-type services;
 *  if the OS still refuses, the service logs and stops, and the next app open
 *  or measurement alarm brings it back. */
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
