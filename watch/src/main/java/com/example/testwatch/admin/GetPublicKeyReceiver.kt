package com.example.testwatch.admin

/** ADB broadcast (GET_PUBKEY) that returns this watch's SSH public key in authorized_keys
 *  format, for one-time provisioning onto the server:
 *  `adb shell am broadcast -a com.example.testwatch.GET_PUBKEY` — the key line appears in the
 *  broadcast result data (and in logcat). */

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.testwatch.config.ServerConfig
import com.example.testwatch.data.ParticipantStore

class GetPublicKeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val comment = "watch-" + ParticipantStore(context).participantId
            val line = com.example.testwatch.upload.WatchKeys
                .authorizedKeysLine(ServerConfig.KEYSTORE_ALIAS, comment)
            resultData = line
            Log.i(TAG, "public key: $line")
        } catch (t: Throwable) {
            resultData = "ERROR: ${t.message}"
            Log.e(TAG, "could not produce public key", t)
        }
    }

    companion object {
        private const val TAG = "GetPublicKey"
    }
}
