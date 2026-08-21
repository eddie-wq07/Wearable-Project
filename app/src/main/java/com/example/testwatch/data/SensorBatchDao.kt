package com.example.testwatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SensorBatchDao {
    @Insert
    suspend fun insert(batch: SensorBatch): Long

    @Query("SELECT * FROM sensor_batches WHERE synced = 0 ORDER BY id DESC LIMIT :limit")
    suspend fun unsynced(limit: Int = 300): List<SensorBatch>

    @Query("UPDATE sensor_batches SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM sensor_batches WHERE synced = 1 AND id < :upToId")
    suspend fun pruneSynced(upToId: Long)
}
