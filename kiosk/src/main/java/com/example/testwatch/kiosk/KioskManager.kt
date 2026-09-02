package com.example.testwatch.kiosk

/** Central kiosk API: applies device-owner lockdown policies, keeps the activity pinned in
 *  lock-task mode, and verifies the study passcode for every exit path. */

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import java.security.MessageDigest

object KioskManager {

    private const val TAG = "KioskManager"
    private const val PREFS = "kiosk_prefs"
    private const val KEY_ENABLED = "kiosk_enabled"

    /** SHA-256 of the study passcode; the plaintext is never stored on the watch. */
    private const val PASSCODE_SHA256 =
        "a6e02383841d8b722794b070593c2893d928307542e229f6daabae73bfabb992"

    private const val MAIN_ACTIVITY = "com.example.testwatch.presentation.MainActivity"
    private const val HOME_ALIAS = "com.example.testwatch.kiosk.KioskHomeAlias"

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, KioskDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    /** Whether the kiosk should currently hold the app pinned. Defaults to true so a freshly
     *  provisioned watch locks down on first launch with no extra step. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "kiosk enabled=$enabled")
    }

    fun verifyPasscode(input: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(hex.toByteArray(), PASSCODE_SHA256.toByteArray())
    }

    /** Single hook for MainActivity.onResume: enters or leaves lock-task mode so the pinned
     *  state always matches the persisted kiosk flag. */
    fun sync(activity: Activity) {
        val locked = lockTaskState(activity) != ActivityManager.LOCK_TASK_MODE_NONE
        when {
            isEnabled(activity) && isDeviceOwner(activity) -> {
                applyDeviceOwnerPolicies(activity)
                if (!locked) {
                    try {
                        activity.startLockTask()
                        Log.i(TAG, "entered lock task mode")
                    } catch (t: Throwable) {
                        Log.w(TAG, "startLockTask failed: ${t.message}")
                    }
                }
            }
            !isEnabled(activity) && locked -> {
                try {
                    activity.stopLockTask()
                    Log.i(TAG, "left lock task mode")
                } catch (t: Throwable) {
                    Log.w(TAG, "stopLockTask failed: ${t.message}")
                }
            }
            isEnabled(activity) && !isDeviceOwner(activity) ->
                Log.w(TAG, "kiosk enabled but app is not device owner; watch is NOT protected")
        }
    }

    /** Idempotent; re-applied on every resume so policies survive app updates. */
    private fun applyDeviceOwnerPolicies(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            dpm.setKeyguardDisabled(admin, true)
            dpm.setStatusBarDisabled(admin, true)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            pinAsHome(context, dpm, admin)
        } catch (t: Throwable) {
            Log.w(TAG, "policy application failed: ${t.message}")
        }
    }

    /** Makes the study app the persistent HOME handler so crashes or stray intents always
     *  land back in it. The alias ships disabled so dev watches never see a chooser. */
    private fun pinAsHome(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val alias = ComponentName(context.packageName, HOME_ALIAS)
        context.packageManager.setComponentEnabledSetting(
            alias,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, alias)
    }

    /** Restores normal navigation (system launcher reachable again). Called after a verified
     *  passcode exit; device-owner status itself is untouched. */
    fun releaseHomePin(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try {
            dpm.clearPackagePersistentPreferredActivities(adminComponent(context), context.packageName)
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, HOME_ALIAS),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "releaseHomePin failed: ${t.message}")
        }
    }

    /** Full un-provisioning for study end: lifts every restriction, then drops device-owner
     *  status. After this the watch behaves like a normal consumer device. */
    fun releaseDeviceOwner(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        try {
            releaseHomePin(context)
            dpm.setKeyguardDisabled(admin, false)
            dpm.setStatusBarDisabled(admin, false)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            setEnabled(context, false)
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "device owner released")
        } catch (t: Throwable) {
            Log.w(TAG, "releaseDeviceOwner failed: ${t.message}")
        }
    }

    fun lockTaskState(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState
    }

    fun statusLine(context: Context): String {
        val lockState = when (lockTaskState(context)) {
            ActivityManager.LOCK_TASK_MODE_LOCKED -> "locked"
            ActivityManager.LOCK_TASK_MODE_PINNED -> "pinned"
            else -> "none"
        }
        return "deviceOwner=${isDeviceOwner(context)} enabled=${isEnabled(context)} lockTask=$lockState"
    }

    /** Relaunches MainActivity (singleTop) so its onResume re-runs [sync] after a broadcast
     *  flipped the kiosk flag. */
    fun relaunchMain(context: Context) {
        val intent = Intent().apply {
            setClassName(context.packageName, MAIN_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(intent)
    }
}
