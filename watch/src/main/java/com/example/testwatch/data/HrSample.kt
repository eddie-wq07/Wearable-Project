package com.example.testwatch.data

/** Room entity for the hr_samples table — one row per heart-rate reading (legacy HR lane). */

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hr_samples")
data class HrSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val bpm: Int,
    val status: Int,
    val synced: Boolean = false,
)
