package com.example.testwatch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One SDK callback's worth of points for one sensor; points is a JSON array of {ts, ...fields}. */
@Entity(tableName = "sensor_batches")
data class SensorBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sensor: String,
    val timestampMs: Long,
    val points: String,
    val synced: Boolean = false,
)
