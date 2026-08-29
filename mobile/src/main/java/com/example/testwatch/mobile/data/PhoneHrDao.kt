package com.example.testwatch.mobile.data

/** Room queries for the phone-side hr_samples mirror table. */

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PhoneHrDao {
    @Insert
    suspend fun insertAll(samples: List<PhoneHrSample>)

    @Query("SELECT * FROM hr_samples WHERE uploaded = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun unuploaded(limit: Int = 5000): List<PhoneHrSample>

    @Query("UPDATE hr_samples SET uploaded = 1 WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>)

    @Query("DELETE FROM hr_samples WHERE uploaded = 1 AND id < :upToId")
    suspend fun pruneUploaded(upToId: Long)

    @Query("SELECT COUNT(*) FROM hr_samples")
    suspend fun allCount(): Int

    @Query("SELECT COUNT(*) FROM hr_samples WHERE uploaded = 0")
    suspend fun unuploadedCount(): Int

    // By timestamp, not insert order: backlog drains oldest-first, so the newest
    // insert can be an old sample.
    @Query("SELECT * FROM hr_samples ORDER BY timestampMs DESC LIMIT 1")
    suspend fun latest(): PhoneHrSample?
}
