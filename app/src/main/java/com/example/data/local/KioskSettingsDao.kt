package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.KioskSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface KioskSettingsDao {
    @Query("SELECT * FROM kiosk_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<KioskSettings?>

    @Query("SELECT * FROM kiosk_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): KioskSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settings: KioskSettings)

    @Update
    suspend fun update(settings: KioskSettings)
}
