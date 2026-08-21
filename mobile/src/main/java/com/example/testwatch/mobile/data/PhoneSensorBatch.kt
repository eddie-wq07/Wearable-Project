package com.example.testwatch.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Mirror of the watch's sensor_batches rows; points is a JSON array of {ts, ...fields}. */
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
