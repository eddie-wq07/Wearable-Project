package com.example.testwatch.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.testwatch.data.ParticipantStore

class SetParticipantIdReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID)?.trim().orEmpty()
        if (id.isEmpty()) {
            Log.w(TAG, "Empty participant_id; ignored")
            return
        }
        ParticipantStore(context).participantId = id
        Log.i(TAG, "participant_id set to '$id'")
    }

    companion object {
        const val EXTRA_ID = "participant_id"
        private const val TAG = "SetParticipantId"
    }
}
