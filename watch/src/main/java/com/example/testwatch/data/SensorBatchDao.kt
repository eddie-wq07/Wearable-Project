package com.example.testwatch.data

/** Room queries for the sensor_batches table — same pattern as HrDao, plus unsyncedBytes() to
 *  track buffer size against StorageConfig's GB thresholds. */

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

    @Query("SELECT COALESCE(SUM(LENGTH(points)), 0) FROM sensor_batches WHERE synced = 0")
    suspend fun unsyncedBytes(): Long

    @Query("SELECT COUNT(*) FROM sensor_batches WHERE synced = 0")
    suspend fun unsyncedCount(): Int

    // Emergency back-pressure only: creates a data gap. Callers must report the
    // returned count via TrackingState.droppedRows, never swallow it.
    @Query(
        "DELETE FROM sensor_batches WHERE id IN " +
            "(SELECT id FROM sensor_batches WHERE synced = 0 ORDER BY id ASC LIMIT :n)",
    )
    suspend fun dropOldestUnsynced(n: Int): Int
}
