package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "recent_files")
data class RecentFileEntity(
    @PrimaryKey val documentKey: String,
    val displayName: String,
    val uriString: String,
    val isSampleAsset: Boolean,
    val assetPath: String?,
    val formatName: String,
    val fileSizeBytes: Long,
    val faceCount: Int,
    val edgeCount: Int,
    val lastOpenedAt: Long
)

@Entity(
    tableName = "length_measurements",
    indices = [Index("documentKey")]
)
data class LengthMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentKey: String,
    val label: String,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val bx: Double,
    val by: Double,
    val bz: Double,
    val lengthMeters: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "angle_measurements",
    indices = [Index("documentKey")]
)
data class AngleMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentKey: String,
    val label: String,
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val bx: Double,
    val by: Double,
    val bz: Double,
    val angleDegrees: Double,
    val mode: String, // "3-Point", "2-Edge", "2-Face"
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "counted_angles",
    indices = [Index("documentKey")]
)
data class CountedAngleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentKey: String,
    val angleDegrees: Double,
    val locationLabel: String,
    val source: String, // "Manual" or "Auto-Selection"
    val entityRef: String,
    val vx: Double,
    val vy: Double,
    val vz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val bx: Double,
    val by: Double,
    val bz: Double,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
