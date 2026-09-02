package com.example.testwatch.admin

/** ADB-broadcast → immediate upload attempt (still charge+WiFi gated). Exists because
 *  HrTrackingService is not exported, so the shell cannot deliver DRAIN_NOW to it directly. */

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.testwatch.upload.UploadWorker

class DrainNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "DRAIN_NOW broadcast received")
        UploadWorker.enqueueNow(context, "adb DRAIN_NOW broadcast")
    }

    companion object {
        private const val TAG = "DrainNow"
    }
}
