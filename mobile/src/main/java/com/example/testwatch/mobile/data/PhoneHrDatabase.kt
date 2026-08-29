package com.example.testwatch.mobile.data

/** Room database (hr_phone.db) for the phone-side HR and sensor mirror tables. */

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PhoneHrSample::class, PhoneSensorBatch::class], version = 2, exportSchema = false)
abstract class PhoneHrDatabase : RoomDatabase() {
    abstract fun phoneHrDao(): PhoneHrDao
    abstract fun phoneSensorDao(): PhoneSensorDao

    companion object {
        @Volatile private var instance: PhoneHrDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sensor_batches` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`participantId` TEXT NOT NULL, `sensor` TEXT NOT NULL, " +
                        "`timestampMs` INTEGER NOT NULL, `points` TEXT NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, `uploaded` INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): PhoneHrDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PhoneHrDatabase::class.java,
                "hr_phone.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
