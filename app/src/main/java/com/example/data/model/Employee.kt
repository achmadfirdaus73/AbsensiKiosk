package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ShiftAssignmentType(val label: String, val description: String) {
    AUTO_DETECT("Sesuai Shift Kantor (Auto-Detect)", "Otomatis mengikuti shift 1, 2, atau 3 kantor"),
    SHIFT_1("Shift 1 Tetap (Pagi)", "Terkunci pada jam Shift 1"),
    SHIFT_2("Shift 2 Tetap (Siang/Sore)", "Terkunci pada jam Shift 2"),
    SHIFT_3("Shift 3 Tetap (Malam)", "Terkunci pada jam Shift 3"),
    MANUAL_CUSTOM("Input Khusus / Manual", "Jam kerja khusus (bebas ditentukan)")
}

@Entity(tableName = "employees")
data class Employee(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val nik: String,
    val division: String,
    val role: String = "Staff", // Manager, Supervisor, Staff, Security, Cleaning Service, dll.
    val shiftType: String = ShiftAssignmentType.AUTO_DETECT.name,
    val customStartTime: String = "08:00",
    val customEndTime: String = "17:00",
    val pin: String = "1234",
    val photoUri: String? = null,
    val faceEmbedding: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)
