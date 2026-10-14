package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.AdminNotification
import kotlinx.coroutines.flow.Flow

@Dao
interface AdminNotificationDao {
    @Query("SELECT * FROM admin_notifications ORDER BY timestamp DESC")
    fun getAllNotifications(): Flow<List<AdminNotification>>

    @Query("SELECT * FROM admin_notifications ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentNotifications(limit: Int = 20): Flow<List<AdminNotification>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNotification(notification: AdminNotification): Long

    @Query("UPDATE admin_notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("UPDATE admin_notifications SET isRead = 1")
    suspend fun markAllAsRead()

    @Query("SELECT COUNT(*) FROM admin_notifications WHERE isRead = 0")
    fun getUnreadCount(): Flow<Int>

    @Query("DELETE FROM admin_notifications")
    suspend fun clearAllNotifications()
}
