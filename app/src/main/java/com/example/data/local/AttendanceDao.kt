package com.example.data.local

import androidx.room.*
import com.example.data.model.AttendanceRecord
import com.example.data.model.KioskSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface AttendanceDao {
    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    fun getAllAttendanceRecords(): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance_records WHERE timestamp >= :startOfDay AND timestamp <= :endOfDay ORDER BY timestamp DESC")
    fun getAttendanceForDay(startOfDay: Long, endOfDay: Long): Flow<List<AttendanceRecord>>

    @Query("SELECT * FROM attendance_records WHERE employeeId = :empId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestAttendanceForEmployee(empId: Long): AttendanceRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttendance(record: AttendanceRecord): Long

    @Query("DELETE FROM attendance_records WHERE id = :id")
    suspend fun deleteAttendance(id: Long)

    @Query("SELECT * FROM kiosk_settings WHERE id = 1 LIMIT 1")
    fun getKioskSettingsFlow(): Flow<KioskSettings?>

    @Query("SELECT * FROM kiosk_settings WHERE id = 1 LIMIT 1")
    suspend fun getKioskSettings(): KioskSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveKioskSettings(settings: KioskSettings)
}
