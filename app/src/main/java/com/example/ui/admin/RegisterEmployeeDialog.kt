package com.example.ui.admin

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.model.Employee
import com.example.data.model.KioskSettings
import com.example.data.model.ShiftAssignmentType
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterEmployeeDialog(
    employeeToEdit: Employee? = null,
    kioskSettings: KioskSettings,
    onDismiss: () -> Unit,
    onSaveEmployee: (Employee) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var name by remember { mutableStateOf(employeeToEdit?.name ?: "") }
    var nik by remember { mutableStateOf(employeeToEdit?.nik ?: "") }
    var division by remember { mutableStateOf(employeeToEdit?.division ?: "Operasional") }
    var role by remember { mutableStateOf(employeeToEdit?.role ?: "Staff") }
    var customRoleText by remember { mutableStateOf("") }
    
    // Photo State
    var photoUriString by remember { mutableStateOf(employeeToEdit?.photoUri) }
    var enrolledFaceEmbedding by remember { mutableStateOf(employeeToEdit?.faceEmbedding) }
    var showFaceIdEnrollment by remember { mutableStateOf(false) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    fun saveBitmapToInternal(bitmap: Bitmap): String? {
        return try {
            val dir = File(context.filesDir, "employee_photos")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "emp_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveUriToInternal(uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "employee_photos")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "emp_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Camera Capture Launcher
    val takePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            capturedBitmap = bitmap
            val savedPath = saveBitmapToInternal(bitmap)
            if (savedPath != null) {
                photoUriString = savedPath
            }
        }
    }

    // Gallery Picker Launcher
    val pickPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            capturedBitmap = null
            val savedPath = saveUriToInternal(uri)
            if (savedPath != null) {
                photoUriString = savedPath
            } else {
                photoUriString = uri.toString()
            }
        }
    }

    // Shift selection
    var selectedShiftType by remember {
        mutableStateOf(
            try {
                ShiftAssignmentType.valueOf(employeeToEdit?.shiftType ?: ShiftAssignmentType.AUTO_DETECT.name)
            } catch (_: Exception) {
                ShiftAssignmentType.AUTO_DETECT
            }
        )
    }
    var customStartTime by remember { mutableStateOf(employeeToEdit?.customStartTime ?: "09:00") }
    var customEndTime by remember { mutableStateOf(employeeToEdit?.customEndTime ?: "18:00") }
    var pin by remember { mutableStateOf(employeeToEdit?.pin ?: "1234") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var isShiftDropdownExpanded by remember { mutableStateOf(false) }
    var isRoleDropdownExpanded by remember { mutableStateOf(false) }

    val standardRoles = listOf("Manager", "Supervisor", "Staff", "Security", "Cleaning Service", "Admin", "Lainnya...")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .shadow(8.dp, RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (employeeToEdit == null) "Daftarkan Karyawan" else "Edit Data Karyawan",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimaryLight,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Profil, foto biometrik, dan jadwal kerja",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryLight
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondaryLight)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = CardBorderLight)
                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Form
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Photo Capture Section
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = InputBgLight,
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Avatar Display
                            Box(
                                modifier = Modifier
                                    .size(70.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(InputBgLight)
                                    .border(1.dp, CardBorderLight, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                val currentModel = remember(photoUriString) {
                                    val path = photoUriString
                                    if (path.isNullOrBlank()) null
                                    else if (path.startsWith("/")) {
                                        val f = File(path)
                                        if (f.exists()) f else null
                                    } else Uri.parse(path)
                                }

                                if (capturedBitmap != null) {
                                    Image(
                                        bitmap = capturedBitmap!!.asImageBitmap(),
                                        contentDescription = "Foto Karyawan",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else if (currentModel != null) {
                                    AsyncImage(
                                        model = currentModel,
                                        contentDescription = "Foto Karyawan",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else if (name.isNotBlank()) {
                                    Text(
                                        text = name.trim().take(2).uppercase(),
                                        color = PrimaryNavy,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 22.sp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Face,
                                        contentDescription = null,
                                        tint = TextMutedLight,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "Biometrik Wajah",
                                        color = TextPrimaryLight,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    if (enrolledFaceEmbedding != null) {
                                        Surface(
                                            color = AccentGreen.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = "✓ Terkalibrasi",
                                                color = AccentGreen,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (enrolledFaceEmbedding != null) "Vektor biometrik aktif & siap untuk Kiosk" else "Daftarkan wajah dengan pemindai Face ID",
                                    color = TextSecondaryLight,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { showFaceIdEnrollment = true },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(16.dp), tint = PureWhite)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pindai Face ID", color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }

                                    OutlinedButton(
                                        onClick = { pickPhotoLauncher.launch("image/*") },
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("Galeri", color = TextSecondaryLight, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 2. Nama Lengkap
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nama Lengkap Karyawan") },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryNavy,
                            unfocusedBorderColor = CardBorderLight,
                            focusedTextColor = TextPrimaryLight,
                            unfocusedTextColor = TextPrimaryLight
                        )
                    )

                    // 3. NIK & Divisi
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = nik,
                            onValueChange = { nik = it },
                            label = { Text("NIK / ID") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNavy,
                                unfocusedBorderColor = CardBorderLight,
                                focusedTextColor = TextPrimaryLight,
                                unfocusedTextColor = TextPrimaryLight
                            )
                        )

                        OutlinedTextField(
                            value = division,
                            onValueChange = { division = it },
                            label = { Text("Divisi / Departemen") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PrimaryNavy,
                                unfocusedBorderColor = CardBorderLight,
                                focusedTextColor = TextPrimaryLight,
                                unfocusedTextColor = TextPrimaryLight
                            )
                        )
                    }

                    // 4. Role / Jabatan
                    Column {
                        ExposedDropdownMenuBox(
                            expanded = isRoleDropdownExpanded,
                            onExpandedChange = { isRoleDropdownExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = role,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Role / Jabatan") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isRoleDropdownExpanded) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryNavy,
                                    unfocusedBorderColor = CardBorderLight,
                                    focusedTextColor = TextPrimaryLight,
                                    unfocusedTextColor = TextPrimaryLight
                                )
                            )

                            ExposedDropdownMenu(
                                expanded = isRoleDropdownExpanded,
                                onDismissRequest = { isRoleDropdownExpanded = false },
                                modifier = Modifier.background(PureWhite)
                            ) {
                                standardRoles.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r, color = TextPrimaryLight) },
                                        onClick = {
                                            if (r == "Lainnya...") {
                                                role = "Lainnya"
                                            } else {
                                                role = r
                                            }
                                            isRoleDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (role == "Lainnya") {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customRoleText,
                                onValueChange = { customRoleText = it },
                                label = { Text("Ketik Nama Jabatan Khusus") },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryNavy,
                                    unfocusedBorderColor = CardBorderLight,
                                    focusedTextColor = TextPrimaryLight,
                                    unfocusedTextColor = TextPrimaryLight
                                )
                            )
                        }
                    }

                    // 5. Shift / Waktu Kerja
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = InputBgLight),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "Konfigurasi Jadwal Kerja",
                                color = TextPrimaryLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            ExposedDropdownMenuBox(
                                expanded = isShiftDropdownExpanded,
                                onExpandedChange = { isShiftDropdownExpanded = it }
                            ) {
                                val currentShiftLabel = when (selectedShiftType) {
                                    ShiftAssignmentType.AUTO_DETECT -> "Sesuai Shift Kantor (Auto-Detect)"
                                    ShiftAssignmentType.SHIFT_1 -> "Shift 1 Tetap (${kioskSettings.shift1Start} - ${kioskSettings.shift1End})"
                                    ShiftAssignmentType.SHIFT_2 -> "Shift 2 Tetap (${kioskSettings.shift2Start} - ${kioskSettings.shift2End})"
                                    ShiftAssignmentType.SHIFT_3 -> "Shift 3 Tetap (${kioskSettings.shift3Start} - ${kioskSettings.shift3End})"
                                    ShiftAssignmentType.MANUAL_CUSTOM -> "Masukkan Jam Kerja Khusus (Manual)"
                                }

                                OutlinedTextField(
                                    value = currentShiftLabel,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Mode Waktu Kerja") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isShiftDropdownExpanded) },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = PrimaryNavy,
                                        unfocusedBorderColor = CardBorderLight,
                                        focusedTextColor = TextPrimaryLight,
                                        unfocusedTextColor = TextPrimaryLight
                                    )
                                )

                                ExposedDropdownMenu(
                                    expanded = isShiftDropdownExpanded,
                                    onDismissRequest = { isShiftDropdownExpanded = false },
                                    modifier = Modifier.background(PureWhite)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Sesuai Shift Kantor (Auto-Detect)", color = TextPrimaryLight, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            selectedShiftType = ShiftAssignmentType.AUTO_DETECT
                                            isShiftDropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Shift 1 Tetap (Pagi)", color = TextPrimaryLight) },
                                        onClick = {
                                            selectedShiftType = ShiftAssignmentType.SHIFT_1
                                            isShiftDropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Shift 2 Tetap (Siang)", color = TextPrimaryLight) },
                                        onClick = {
                                            selectedShiftType = ShiftAssignmentType.SHIFT_2
                                            isShiftDropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Shift 3 Tetap (Malam)", color = TextPrimaryLight) },
                                        onClick = {
                                            selectedShiftType = ShiftAssignmentType.SHIFT_3
                                            isShiftDropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Masukkan Jam Khusus (Manual)", color = PrimaryNavy, fontWeight = FontWeight.Bold) },
                                        onClick = {
                                            selectedShiftType = ShiftAssignmentType.MANUAL_CUSTOM
                                            isShiftDropdownExpanded = false
                                        }
                                    )
                                }
                            }

                            if (selectedShiftType == ShiftAssignmentType.MANUAL_CUSTOM) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customStartTime,
                                        onValueChange = { customStartTime = it },
                                        label = { Text("Jam Masuk (HH:mm)") },
                                        placeholder = { Text("09:00") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = PrimaryNavy,
                                            unfocusedBorderColor = CardBorderLight,
                                            focusedTextColor = TextPrimaryLight,
                                            unfocusedTextColor = TextPrimaryLight
                                        )
                                    )

                                    OutlinedTextField(
                                        value = customEndTime,
                                        onValueChange = { customEndTime = it },
                                        label = { Text("Jam Pulang (HH:mm)") },
                                        placeholder = { Text("18:00") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = PrimaryNavy,
                                            unfocusedBorderColor = CardBorderLight,
                                            focusedTextColor = TextPrimaryLight,
                                            unfocusedTextColor = TextPrimaryLight
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 6. PIN Karyawan
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6) pin = it },
                        label = { Text("PIN Karyawan (Untuk Cadangan Manual)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryNavy,
                            unfocusedBorderColor = CardBorderLight,
                            focusedTextColor = TextPrimaryLight,
                            unfocusedTextColor = TextPrimaryLight
                        )
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = AccentRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = CardBorderLight)
                Spacer(modifier = Modifier.height(14.dp))

                // Actions Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                    ) {
                        Text("Batal", color = TextSecondaryLight)
                    }

                    Button(
                        onClick = {
                            if (isSaving) return@Button
                            val finalRole = if (role == "Lainnya") {
                                if (customRoleText.isNotBlank()) customRoleText.trim() else "Karyawan"
                            } else {
                                role
                            }

                            if (name.isBlank() || nik.isBlank()) {
                                errorMessage = "Mohon isi Nama Lengkap dan NIK"
                            } else {
                                isSaving = true
                                errorMessage = null
                                coroutineScope.launch {
                                    val embeddingJson = try {
                                        val vector = if (capturedBitmap != null) {
                                            com.example.util.FaceBiometricHelper.extractEmbeddingFromBitmap(capturedBitmap!!)
                                        } else if (!photoUriString.isNullOrBlank()) {
                                            com.example.util.FaceBiometricHelper.extractEmbeddingFromUri(context, photoUriString!!)
                                        } else null
                                        if (vector != null) com.example.util.FaceBiometricHelper.serializeEmbedding(vector) else null
                                    } catch (_: Exception) {
                                        null
                                    }

                                    val employee = (employeeToEdit ?: Employee(name = "", nik = "", division = "")).copy(
                                        name = name.trim(),
                                        nik = nik.trim(),
                                        division = division.trim(),
                                        role = finalRole,
                                        shiftType = selectedShiftType.name,
                                        customStartTime = customStartTime.trim(),
                                        customEndTime = customEndTime.trim(),
                                        pin = pin.trim(),
                                        photoUri = photoUriString,
                                        faceEmbedding = enrolledFaceEmbedding ?: embeddingJson ?: employeeToEdit?.faceEmbedding
                                    )
                                    onSaveEmployee(employee)
                                    isSaving = false
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = PureWhite,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Simpan Karyawan", color = PureWhite, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // Interactive Apple-Style Face ID Scanner Dialog
    if (showFaceIdEnrollment) {
        FaceIdEnrollmentDialog(
            employeeName = name,
            onDismiss = { showFaceIdEnrollment = false },
            onEnrollmentComplete = { photoPath, masterEmbeddingJson ->
                photoUriString = photoPath
                enrolledFaceEmbedding = masterEmbeddingJson
                showFaceIdEnrollment = false
            }
        )
    }
}
