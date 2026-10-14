package com.example.ui.kiosk

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.CheckType
import com.example.ui.theme.*

@Composable
fun PinAttendanceDialog(
    onDismiss: () -> Unit,
    onSubmitPin: (nik: String, pin: String, checkType: CheckType) -> Unit
) {
    var nik by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var selectedCheckType by remember { mutableStateOf(CheckType.CHECK_IN) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                Text(
                    text = "Presensi Cadangan PIN",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimaryLight,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Gunakan jika kamera mengalami kendala",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryLight
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Check Type Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { selectedCheckType = CheckType.CHECK_IN },
                        modifier = Modifier.weight(1f).height(42.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedCheckType == CheckType.CHECK_IN) PrimaryNavy else InputBgLight
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Absen Masuk",
                            color = if (selectedCheckType == CheckType.CHECK_IN) PureWhite else TextSecondaryLight,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                    Button(
                        onClick = { selectedCheckType = CheckType.CHECK_OUT },
                        modifier = Modifier.weight(1f).height(42.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedCheckType == CheckType.CHECK_OUT) AccentGreen else InputBgLight
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Absen Pulang",
                            color = if (selectedCheckType == CheckType.CHECK_OUT) PureWhite else TextSecondaryLight,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = nik,
                    onValueChange = { nik = it },
                    label = { Text("Nomor Induk Karyawan (NIK)") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNavy,
                        unfocusedBorderColor = CardBorderLight,
                        focusedTextColor = TextPrimaryLight,
                        unfocusedTextColor = TextPrimaryLight
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 6) pin = it },
                    label = { Text("PIN Karyawan") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryNavy,
                        unfocusedBorderColor = CardBorderLight,
                        focusedTextColor = TextPrimaryLight,
                        unfocusedTextColor = TextPrimaryLight
                    )
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = AccentRed, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                    ) {
                        Text("Batal", color = TextSecondaryLight)
                    }
                    Button(
                        onClick = {
                            if (nik.isBlank() || pin.isBlank()) {
                                errorMessage = "Mohon isi NIK dan PIN"
                            } else {
                                onSubmitPin(nik.trim(), pin.trim(), selectedCheckType)
                            }
                        },
                        modifier = Modifier.weight(1f).height(44.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Kirim", color = PureWhite, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
