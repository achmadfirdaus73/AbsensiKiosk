package com.example.data.local

import androidx.room.*
import com.example.data.model.AdminNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Query("SELECT * FROM admin_notifications ORDER BY timestamp DESC LIMIT 50")
    fun getAllNotifications(): Flow<List<AdminNotification>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: AdminNotification): Long

    @Query("UPDATE admin_notifications SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAsRead()

    @Query("DELETE FROM admin_notifications")
    suspend fun clearAllNotifications()
}
