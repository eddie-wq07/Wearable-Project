package com.example.testwatch.data

/** SharedPreferences wrapper for the participant ID. Auto-generates a random ID on first read;
 *  overridable via the SET_PARTICIPANT_ID ADB broadcast. */

import android.content.Context
import java.util.UUID

class ParticipantStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * On first read, generates a stable random ID and persists it.
     * The ADB broadcast (SetParticipantIdReceiver) can override it later.
     */
    var participantId: String
        get() {
            val existing = prefs.getString(KEY_ID, null)
            if (existing != null) return existing
            val generated = "P-" + UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString(KEY_ID, generated).apply()
            return generated
        }
        set(value) { prefs.edit().putString(KEY_ID, value).apply() }

    companion object {
        private const val PREFS = "participant"
        private const val KEY_ID = "id"
    }
}
