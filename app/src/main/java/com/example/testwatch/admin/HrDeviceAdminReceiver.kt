package com.example.testwatch.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

class HrDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.w(TAG, "Admin enabled but not Device Owner; lock-task whitelisting skipped")
            return
        }

        dpm.setLockTaskPackages(admin, arrayOf(context.packageName))

        runCatching {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
        }.onFailure { Log.w(TAG, "Could not apply user restrictions: ${it.message}") }

        Log.i(TAG, "Device Owner enabled; ${context.packageName} whitelisted for lock task")
    }

    companion object {
        private const val TAG = "HrDeviceAdminReceiver"

        fun adminComponent(context: Context): ComponentName =
            ComponentName(context, HrDeviceAdminReceiver::class.java)
    }
}
