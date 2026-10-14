package com.example.ui.kiosk

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.AttendanceRecord
import com.example.data.model.AttendanceStatus
import com.example.data.model.CheckType
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AttendanceSuccessDialog(
    record: AttendanceRecord,
    onDismiss: () -> Unit
) {
    val isCheckIn = record.checkType == CheckType.CHECK_IN
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID"))
    val recordDate = Date(record.timestamp)

    val (statusText, statusBg, statusTextColor) = when (record.status) {
        AttendanceStatus.ON_TIME -> Triple("TEPAT WAKTU", AccentGreen.copy(alpha = 0.12f), AccentGreen)
        AttendanceStatus.LATE -> Triple("TERLAMBAT", AccentRed.copy(alpha = 0.12f), AccentRed)
        AttendanceStatus.EARLY_DEPARTURE -> Triple("PULANG AWAL", AccentAmber.copy(alpha = 0.12f), AccentAmber)
        AttendanceStatus.OVERTIME -> Triple("LEMBUR", AccentPurple.copy(alpha = 0.12f), AccentPurple)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .shadow(8.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(AccentGreen.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Success",
                        tint = AccentGreen,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = if (isCheckIn) "Presensi Masuk Berhasil" else "Presensi Pulang Berhasil",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimaryLight,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = dateFormat.format(recordDate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryLight
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Employee Info Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = InputBgLight,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = record.employeeName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = PureWhite,
                                shape = RoundedCornerShape(4.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                            ) {
                                Text(
                                    text = record.role,
                                    color = PrimaryNavy,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "NIK: ${record.nik}",
                                color = TextSecondaryLight,
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = CardBorderLight)
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(text = "Jam Presensi", color = TextSecondaryLight, fontSize = 12.sp)
                                Text(
                                    text = timeFormat.format(recordDate),
                                    color = TextPrimaryLight,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(text = "Status", color = TextSecondaryLight, fontSize = 12.sp)
                                Surface(
                                    color = statusBg,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = statusText,
                                        color = statusTextColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Jadwal Shift", color = TextSecondaryLight, fontSize = 12.sp)
                            Text(
                                text = record.shiftName,
                                color = TextPrimaryLight,
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                ) {
                    Text(text = "Tutup", color = PureWhite, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
