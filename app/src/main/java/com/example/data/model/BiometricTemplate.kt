package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Stores biometric face embedding templates mapped strictly to unique Employee ID.
 * Prevents index-based mismatch and supports multi-angle biometric template fusion.
 */
@Entity(
    tableName = "biometric_templates",
    foreignKeys = [
        ForeignKey(
            entity = Employee::class,
            parentColumns = ["id"],
            childColumns = ["employeeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["employeeId"]),
        Index(value = ["employeeId", "angleType"], unique = true)
    ]
)
data class BiometricTemplate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val employeeId: Long,
    val embeddingJson: String,
    val qualityScore: Float = 1.0f,
    val angleType: String = "MASTER_FUSION", // e.g., "MASTER_FUSION", "FRONT", "LEFT", "RIGHT"
    val updatedAt: Long = System.currentTimeMillis()
)
