package com.example.testwatch.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

data class SensorCount(val sensor: String, val n: Int, val lastTs: Long)

@Dao
interface PhoneSensorDao {
    @Insert
    suspend fun insertAll(batches: List<PhoneSensorBatch>)

    @Query("SELECT * FROM sensor_batches WHERE sensor = :sensor ORDER BY id DESC LIMIT 1")
    suspend fun latest(sensor: String): PhoneSensorBatch?

    @Query("SELECT sensor, COUNT(*) AS n, MAX(timestampMs) AS lastTs FROM sensor_batches GROUP BY sensor")
    suspend fun counts(): List<SensorCount>

    @Query("SELECT * FROM sensor_batches WHERE uploaded = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun unuploaded(limit: Int = 2000): List<PhoneSensorBatch>

    @Query("UPDATE sensor_batches SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("DELETE FROM sensor_batches WHERE uploaded = 1 AND id < :upToId")
    suspend fun pruneUploaded(upToId: Long)
}
