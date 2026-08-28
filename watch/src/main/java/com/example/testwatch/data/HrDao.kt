package com.example.testwatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface HrDao {
    @Insert
    suspend fun insert(sample: HrSample): Long

    // Newest first: live data reaches the phone within one cycle even while a
    // large backlog drains behind it.
    @Query("SELECT * FROM hr_samples WHERE synced = 0 ORDER BY id DESC LIMIT :limit")
    suspend fun unsynced(limit: Int = 1000): List<HrSample>

    @Query("UPDATE hr_samples SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM hr_samples WHERE synced = 1 AND id < :upToId")
    suspend fun pruneSynced(upToId: Long)

    @Query("SELECT COUNT(*) FROM hr_samples WHERE synced = 0")
    suspend fun unsyncedCount(): Int

    // Emergency back-pressure only: creates a data gap. Callers must report the
    // returned count via TrackingState.droppedRows, never swallow it.
    @Query(
        "DELETE FROM hr_samples WHERE id IN " +
            "(SELECT id FROM hr_samples WHERE synced = 0 ORDER BY id ASC LIMIT :n)",
    )
    suspend fun dropOldestUnsynced(n: Int): Int
}
