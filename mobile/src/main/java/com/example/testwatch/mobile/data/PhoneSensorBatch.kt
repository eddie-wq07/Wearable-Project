package com.example.testwatch.mobile.data

/** Room entity mirroring watch sensor batches on the phone, plus upload bookkeeping. */

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_batches")
data class PhoneSensorBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val participantId: String,
    val sensor: String,
    val timestampMs: Long,
    val points: String,
    val receivedAt: Long,
    val uploaded: Boolean = false,
)
