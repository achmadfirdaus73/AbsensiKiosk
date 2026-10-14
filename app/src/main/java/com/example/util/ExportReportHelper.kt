package com.example.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.model.AttendanceRecord
import com.example.data.model.AttendanceStatus
import com.example.data.model.CheckType
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object ExportReportHelper {

    fun exportAndShareCsv(
        context: Context,
        companyName: String,
        records: List<AttendanceRecord>
    ): Boolean {
        if (records.isEmpty()) return false

        try {
            val reportsDir = File(context.cacheDir, "reports")
            if (!reportsDir.exists()) {
                reportsDir.mkdirs()
            }

            val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val displayDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            val fileName = "Laporan_Presensi_${fileDateFormat.format(Date())}.csv"
            val file = File(reportsDir, fileName)

            val writer = FileWriter(file)
            // UTF-8 BOM so Excel opens it with correct Indonesian characters and accents
            writer.write("\uFEFF")
            
            // CSV Header
            writer.append("No,Waktu,NIK,Nama Karyawan,Jabatan/Role,Shift,Tipe Absen,Status,Akurasi Biometrik,Catatan\n")

            // Rows
            records.forEachIndexed { index, record ->
                val timeStr = displayDateFormat.format(Date(record.timestamp))
                val typeStr = if (record.checkType == CheckType.CHECK_IN) "Masuk" else "Pulang"
                val statusStr = when (record.status) {
                    AttendanceStatus.ON_TIME -> "Tepat Waktu"
                    AttendanceStatus.LATE -> "Terlambat"
                    AttendanceStatus.EARLY_DEPARTURE -> "Pulang Awal"
                    AttendanceStatus.OVERTIME -> "Lembur"
                }
                val confidenceStr = "${(record.confidenceScore * 100).toInt()}%"
                val safeName = escapeCsv(record.employeeName)
                val safeNik = escapeCsv(record.nik)
                val safeRole = escapeCsv(record.role)
                val safeShift = escapeCsv(record.shiftName)
                val safeNote = escapeCsv(record.note)

                writer.append("${index + 1},\"$timeStr\",\"$safeNik\",\"$safeName\",\"$safeRole\",\"$safeShift\",\"$typeStr\",\"$statusStr\",\"$confidenceStr\",\"$safeNote\"\n")
            }

            writer.flush()
            writer.close()

            // Generate FileProvider Uri
            val authority = "${context.packageName}.fileprovider"
            val fileUri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Laporan Rekap Presensi - $companyName")
                putExtra(Intent.EXTRA_TEXT, "Berikut terlampir Laporan Rekap Presensi Kiosk untuk $companyName (Total: ${records.size} log).")
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Bagikan / Buka Laporan CSV & Excel")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun escapeCsv(value: String): String {
        return value.replace("\"", "\"\"")
    }
}
