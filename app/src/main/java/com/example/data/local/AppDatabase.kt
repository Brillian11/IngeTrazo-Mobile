package com.example.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecentFileEntity::class,
        LengthMeasurementEntity::class,
        AngleMeasurementEntity::class,
        CountedAngleEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentFileDao(): RecentFileDao
    abstract fun measurementDao(): MeasurementDao
}
