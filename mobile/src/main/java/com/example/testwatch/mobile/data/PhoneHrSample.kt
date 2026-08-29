package com.example.testwatch.mobile.data

/** Room entity mirroring watch HR samples on the phone, plus participantId/receivedAt/uploaded
 *  bookkeeping. */

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hr_samples")
data class PhoneHrSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: String,
    val timestampMs: Long,
    val bpm: Int,
    val status: Int,
    val receivedAt: Long,
    val uploaded: Boolean = false,
)
