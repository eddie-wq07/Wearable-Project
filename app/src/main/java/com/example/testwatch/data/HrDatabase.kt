package com.example.testwatch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HrSample::class], version = 1, exportSchema = false)
abstract class HrDatabase : RoomDatabase() {
    abstract fun hrDao(): HrDao

    companion object {
        @Volatile private var instance: HrDatabase? = null

        fun get(context: Context): HrDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HrDatabase::class.java,
                "hr.db",
            ).build().also { instance = it }
        }
    }
}
