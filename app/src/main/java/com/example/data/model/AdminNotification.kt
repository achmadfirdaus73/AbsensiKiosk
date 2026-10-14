package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "admin_notifications")
data class AdminNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val message: String,
    val type: String = "INFO", // INFO, ALERT, SUCCESS
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)
