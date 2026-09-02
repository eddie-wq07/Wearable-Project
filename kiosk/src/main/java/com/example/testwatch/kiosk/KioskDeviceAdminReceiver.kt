package com.example.testwatch.kiosk

/** Device-admin entry point named in `dpm set-device-owner`; policies themselves are applied
 *  lazily by KioskManager on the next app resume. */

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KioskDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled; kiosk arms on next app launch")
    }

    private companion object {
        const val TAG = "KioskDeviceAdmin"
    }
}
