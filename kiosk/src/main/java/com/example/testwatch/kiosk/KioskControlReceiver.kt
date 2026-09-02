package com.example.testwatch.kiosk

/** ADB control channel for the kiosk (same pattern as admin/SetParticipantIdReceiver).
 *  Exit and release actions require the study passcode via the `passcode` string extra. */

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KioskControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATUS -> Log.i(TAG, KioskManager.statusLine(context))

            ACTION_ENABLE -> {
                KioskManager.setEnabled(context, true)
                KioskManager.relaunchMain(context)
                Log.i(TAG, "kiosk re-armed: ${KioskManager.statusLine(context)}")
            }

            ACTION_EXIT -> withPasscode(intent) {
                KioskManager.setEnabled(context, false)
                KioskManager.releaseHomePin(context)
                KioskManager.relaunchMain(context)
                Log.i(TAG, "kiosk exit accepted: ${KioskManager.statusLine(context)}")
            }

            ACTION_RELEASE_OWNER -> withPasscode(intent) {
                KioskManager.releaseDeviceOwner(context)
                Log.i(TAG, "device owner release attempted: ${KioskManager.statusLine(context)}")
            }
        }
    }

    private inline fun withPasscode(intent: Intent, block: () -> Unit) {
        val passcode = intent.getStringExtra(EXTRA_PASSCODE).orEmpty()
        if (KioskManager.verifyPasscode(passcode)) {
            block()
        } else {
            Log.w(TAG, "rejected ${intent.action}: wrong or missing passcode")
        }
    }

    companion object {
        const val ACTION_EXIT = "com.example.testwatch.KIOSK_EXIT"
        const val ACTION_ENABLE = "com.example.testwatch.KIOSK_ENABLE"
        const val ACTION_STATUS = "com.example.testwatch.KIOSK_STATUS"
        const val ACTION_RELEASE_OWNER = "com.example.testwatch.KIOSK_RELEASE_OWNER"
        const val EXTRA_PASSCODE = "passcode"
        private const val TAG = "KioskControl"
    }
}
