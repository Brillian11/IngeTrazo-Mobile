package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC LIMIT 20")
    fun observeRecentFiles(): Flow<List<RecentFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentFileEntity)

    @Query("DELETE FROM recent_files WHERE documentKey = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM length_measurements WHERE documentKey = :docKey ORDER BY createdAt DESC")
    fun observeLengths(docKey: String): Flow<List<LengthMeasurementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLength(entity: LengthMeasurementEntity): Long

    @Update
    suspend fun updateLength(entity: LengthMeasurementEntity)

    @Query("DELETE FROM length_measurements WHERE id = :id")
    suspend fun deleteLengthById(id: Long)

    @Query("DELETE FROM length_measurements WHERE documentKey = :docKey")
    suspend fun clearLengthsForDoc(docKey: String)

    @Query("SELECT * FROM angle_measurements WHERE documentKey = :docKey ORDER BY createdAt DESC")
    fun observeAngles(docKey: String): Flow<List<AngleMeasurementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAngle(entity: AngleMeasurementEntity): Long

    @Update
    suspend fun updateAngle(entity: AngleMeasurementEntity)

    @Query("DELETE FROM angle_measurements WHERE id = :id")
    suspend fun deleteAngleById(id: Long)

    @Query("DELETE FROM angle_measurements WHERE documentKey = :docKey")
    suspend fun clearAnglesForDoc(docKey: String)

    @Query("SELECT * FROM counted_angles WHERE documentKey = :docKey ORDER BY angleDegrees ASC, createdAt DESC")
    fun observeCountedAngles(docKey: String): Flow<List<CountedAngleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCountedAngle(entity: CountedAngleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCountedAngles(entities: List<CountedAngleEntity>)

    @Update
    suspend fun updateCountedAngle(entity: CountedAngleEntity)

    @Query("DELETE FROM counted_angles WHERE id = :id")
    suspend fun deleteCountedAngleById(id: Long)

    @Query("DELETE FROM counted_angles WHERE documentKey = :docKey")
    suspend fun clearCountedAnglesForDoc(docKey: String)
}
