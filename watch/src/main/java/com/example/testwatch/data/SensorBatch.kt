package com.example.testwatch.data

/** Room entity for the sensor_batches table — one row per batch of non-HR sensor readings
 *  (ppg, accel, skin_temp, ecg, spo2, bia, mf_bia), stored as a JSON array in `points`. */

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sensor_batches")
data class SensorBatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sensor: String,
    val timestampMs: Long,
    val points: String,
    val synced: Boolean = false,
)
