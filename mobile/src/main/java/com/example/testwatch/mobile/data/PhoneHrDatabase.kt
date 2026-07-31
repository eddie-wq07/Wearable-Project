package com.example.testwatch.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PhoneHrSample::class], version = 1, exportSchema = false)
abstract class PhoneHrDatabase : RoomDatabase() {
    abstract fun phoneHrDao(): PhoneHrDao

    companion object {
        @Volatile private var instance: PhoneHrDatabase? = null

        fun get(context: Context): PhoneHrDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PhoneHrDatabase::class.java,
                "hr_phone.db",
            ).build().also { instance = it }
        }
    }
}
