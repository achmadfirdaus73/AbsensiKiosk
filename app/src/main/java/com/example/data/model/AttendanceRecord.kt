package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CheckType {
    CHECK_IN,
    CHECK_OUT
}

enum class AttendanceStatus {
    ON_TIME,          // Tepat Waktu
    LATE,             // Terlambat
    EARLY_DEPARTURE,  // Pulang Cepat
    OVERTIME          // Lembur
}

@Entity(tableName = "attendance_records")
data class AttendanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val employeeId: Long,
    val employeeName: String,
    val nik: String,
    val role: String,
    val shiftName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val checkType: CheckType,
    val status: AttendanceStatus,
    val confidenceScore: Float = 0.95f,
    val photoUri: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val note: String = ""
)

@Entity(tableName = "kiosk_settings")
data class KioskSettings(
    @PrimaryKey
    val id: Int = 1,
    val companyName: String = "Nama Perusahaan / Instansi",
    val address: String = "Alamat Kantor",
    val latitude: Double = -6.2088,
    val longitude: Double = 106.8456,
    val radiusMeters: Double = 150.0,
    val kioskPin: String = "8888",
    val faceConfidenceThreshold: Float = 0.70f,
    // Shift 1 (Pagi)
    val shift1Start: String = "08:00",
    val shift1End: String = "16:00",
    // Shift 2 (Siang/Sore)
    val shift2Start: String = "16:00",
    val shift2End: String = "00:00",
    // Shift 3 (Malam)
    val shift3Start: String = "00:00",
    val shift3End: String = "08:00",
    // Integrasi Cloud
    val fonnteApiKey: String = "",
    val fonnteAdminWa: String = "",
    val googleSheetsUrl: String = "",
    val lastSheetsSyncStatus: String = "Belum terhubung",
    val ttsVoiceEnabled: Boolean = true
)
