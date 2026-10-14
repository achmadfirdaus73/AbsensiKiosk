package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AdminNotification
import com.example.data.model.AttendanceRecord
import com.example.data.model.BiometricTemplate
import com.example.data.model.Employee
import com.example.data.model.KioskSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Employee::class, BiometricTemplate::class, AttendanceRecord::class, KioskSettings::class, AdminNotification::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun biometricTemplateDao(): BiometricTemplateDao
    abstract fun attendanceDao(): AttendanceDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_presensi_kiosk.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getDatabase(context)
                                // Only initialize default clean system settings (No dummy employees/records)
                                database.attendanceDao().saveKioskSettings(
                                    KioskSettings(
                                        companyName = "Nama Perusahaan / Instansi",
                                        address = "Alamat Kantor",
                                        kioskPin = "8888"
                                    )
                                )
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
