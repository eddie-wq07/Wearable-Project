package com.example.testwatch.data

/** Room database definition (hr.db) tying together HrSample/SensorBatch entities and their DAOs.
 *  Holds the MIGRATION_1_2 step that added the sensor_batches table. */

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HrSample::class, SensorBatch::class], version = 2, exportSchema = false)
abstract class HrDatabase : RoomDatabase() {
    abstract fun hrDao(): HrDao
    abstract fun sensorBatchDao(): SensorBatchDao

    companion object {
        @Volatile private var instance: HrDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sensor_batches` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sensor` TEXT NOT NULL, `timestampMs` INTEGER NOT NULL, " +
                        "`points` TEXT NOT NULL, `synced` INTEGER NOT NULL)",
                )
            }
        }

        fun get(context: Context): HrDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HrDatabase::class.java,
                "hr.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
