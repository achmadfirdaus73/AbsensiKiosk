package com.example.ui.admin

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.data.repository.AttendanceRepository
import com.example.ui.theme.*
import com.example.util.ExportReportHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    repository: AttendanceRepository,
    onNavigateBack: () -> Unit
) {
    BackHandler {
        onNavigateBack()
    }

    val coroutineScope = rememberCoroutineScope()
    val employees by repository.allActiveEmployees.collectAsState(initial = emptyList())
    val attendanceRecords by repository.allAttendanceRecords.collectAsState(initial = emptyList())
    val kioskSettingsState by repository.kioskSettingsFlow.collectAsState(initial = null)
    val notifications by repository.allNotifications.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Karyawan, 1: Log Presensi, 2: Pengaturan, 3: Notifikasi
    var showRegisterDialog by remember { mutableStateOf(false) }
    var employeeToEdit by remember { mutableStateOf<Employee?>(null) }
    var employeeToDelete by remember { mutableStateOf<Employee?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }

    val currentSettings = kioskSettingsState ?: KioskSettings()

    Scaffold(
        containerColor = LightBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Admin Dashboard",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryLight,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${currentSettings.companyName} • ${currentSettings.address}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryLight,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali ke Kiosk", tint = PrimaryNavy)
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Buka Kiosk", color = PrimaryNavy, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PureWhite)
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = {
                        employeeToEdit = null
                        showRegisterDialog = true
                    },
                    containerColor = PrimaryNavy,
                    contentColor = PureWhite,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = PureWhite)
                        Text("Tambah Karyawan", fontWeight = FontWeight.SemiBold, color = PureWhite)
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Navigation Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = PureWhite,
                contentColor = PrimaryNavy,
                divider = { HorizontalDivider(color = CardBorderLight) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Karyawan (${employees.size})", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Log Presensi (${attendanceRecords.size})", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Pengaturan", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Notifikasi (${notifications.size})", fontWeight = FontWeight.SemiBold) }
                )
            }

            // Tab Contents
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                when (selectedTab) {
                    0 -> EmployeesTab(
                        employees = employees,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onEditEmployee = { emp ->
                            employeeToEdit = emp
                            showRegisterDialog = true
                        },
                        onDeleteEmployee = { emp ->
                            employeeToDelete = emp
                        }
                    )
                    1 -> AttendanceLogsTab(
                        records = attendanceRecords,
                        companyName = currentSettings.companyName,
                        onDeleteRecord = { id ->
                            coroutineScope.launch {
                                repository.deleteAttendance(id)
                                snackbarMessage = "Log presensi dihapus"
                            }
                        }
                    )
                    2 -> KioskSettingsTab(
                        settings = currentSettings,
                        repository = repository,
                        onSaveSettings = { updated ->
                            coroutineScope.launch {
                                repository.saveKioskSettings(updated)
                                snackbarMessage = "Pengaturan berhasil disimpan!"
                            }
                        }
                    )
                    3 -> NotificationsTab(notifications = notifications)
                }
            }
        }
    }

    // Register / Edit Employee Dialog
    if (showRegisterDialog) {
        RegisterEmployeeDialog(
            employeeToEdit = employeeToEdit,
            kioskSettings = currentSettings,
            onDismiss = { showRegisterDialog = false },
            onSaveEmployee = { employee ->
                coroutineScope.launch {
                    if (employee.id == 0L) {
                        repository.insertEmployee(employee)
                    } else {
                        repository.updateEmployee(employee)
                    }
                    showRegisterDialog = false
                    snackbarMessage = if (employeeToEdit == null) "Karyawan baru berhasil ditambahkan!" else "Data karyawan diperbarui!"
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (employeeToDelete != null) {
        AlertDialog(
            onDismissRequest = { employeeToDelete = null },
            title = { Text("Hapus Karyawan", color = TextPrimaryLight, fontWeight = FontWeight.Bold) },
            text = { Text("Apakah Anda yakin ingin menghapus data karyawan ${employeeToDelete!!.name}?", color = TextSecondaryLight) },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            repository.deleteEmployee(employeeToDelete!!)
                            employeeToDelete = null
                            snackbarMessage = "Karyawan telah dihapus"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Hapus", color = PureWhite)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { employeeToDelete = null },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                ) {
                    Text("Batal", color = TextSecondaryLight)
                }
            },
            containerColor = PureWhite
        )
    }
}

@Composable
fun EmployeesTab(
    employees: List<Employee>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onEditEmployee: (Employee) -> Unit,
    onDeleteEmployee: (Employee) -> Unit
) {
    val filtered = employees.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        it.nik.contains(searchQuery, ignoreCase = true) ||
        it.role.contains(searchQuery, ignoreCase = true) ||
        it.division.contains(searchQuery, ignoreCase = true)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Cari nama, NIK, role, atau divisi...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondaryLight) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMutedLight)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryNavy,
                unfocusedBorderColor = CardBorderLight,
                focusedTextColor = TextPrimaryLight,
                unfocusedTextColor = TextPrimaryLight
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (employees.isEmpty()) "Belum ada data karyawan" else "Tidak ada karyawan yang cocok",
                        color = TextSecondaryLight,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (employees.isEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tekan tombol Tambah Karyawan di bawah untuk mendaftarkan.",
                            color = TextMutedLight,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filtered, key = { it.id }) { emp ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(2.dp, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Photo / Avatar
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(InputBgLight)
                                        .border(1.dp, CardBorderLight, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val employeeImageModel = remember(emp.photoUri) {
                                        val path = emp.photoUri
                                        if (path.isNullOrBlank()) null
                                        else if (path.startsWith("/")) {
                                            val f = java.io.File(path)
                                            if (f.exists() && f.length() > 0) f else null
                                        } else android.net.Uri.parse(path)
                                    }

                                    if (employeeImageModel != null) {
                                        AsyncImage(
                                            model = employeeImageModel,
                                            contentDescription = emp.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            text = emp.name.take(2).uppercase(),
                                            color = PrimaryNavy,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                }

                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = emp.name,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimaryLight,
                                            fontSize = 15.sp
                                        )
                                        Surface(
                                            color = InputBgLight,
                                            shape = RoundedCornerShape(4.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                                        ) {
                                            Text(
                                                text = emp.role.uppercase(),
                                                color = PrimaryNavy,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "NIK: ${emp.nik} • ${emp.division}",
                                        color = TextSecondaryLight,
                                        fontSize = 12.sp
                                    )

                                    Spacer(modifier = Modifier.height(3.dp))

                                    val shiftBadgeText = when (emp.shiftType) {
                                        ShiftAssignmentType.SHIFT_1.name -> "Shift 1 (Pagi)"
                                        ShiftAssignmentType.SHIFT_2.name -> "Shift 2 (Siang)"
                                        ShiftAssignmentType.SHIFT_3.name -> "Shift 3 (Malam)"
                                        ShiftAssignmentType.MANUAL_CUSTOM.name -> "Khusus (${emp.customStartTime} - ${emp.customEndTime})"
                                        else -> "Shift Kantor (Auto-Detect)"
                                    }

                                    Text(
                                        text = shiftBadgeText,
                                        color = PrimaryBlue,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                IconButton(onClick = { onEditEmployee(emp) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit", tint = PrimaryNavy, modifier = Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onDeleteEmployee(emp) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = AccentRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AttendanceLogsTab(
    records: List<AttendanceRecord>,
    companyName: String,
    onDeleteRecord: (Long) -> Unit
) {
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val monthFormat = SimpleDateFormat("yyyyMM", Locale.getDefault())
    val now = Date()
    val todayKey = dayFormat.format(now)
    val thisMonthKey = monthFormat.format(now)

    var filterType by remember { mutableIntStateOf(0) } // 0: Semua, 1: Hari Ini, 2: Bulan Ini
    var logSearchQuery by remember { mutableStateOf("") }

    val filteredRecords = records.filter { record ->
        val recordDate = Date(record.timestamp)
        val matchesFilter = when (filterType) {
            1 -> dayFormat.format(recordDate) == todayKey
            2 -> monthFormat.format(recordDate) == thisMonthKey
            else -> true
        }
        val matchesQuery = if (logSearchQuery.isBlank()) true else {
            record.employeeName.contains(logSearchQuery, ignoreCase = true) ||
            record.nik.contains(logSearchQuery, ignoreCase = true) ||
            record.role.contains(logSearchQuery, ignoreCase = true) ||
            record.shiftName.contains(logSearchQuery, ignoreCase = true)
        }
        matchesFilter && matchesQuery
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Action Bar: Search & Export CSV/Excel
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Total Log: ${filteredRecords.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryLight
            )

            Button(
                onClick = {
                    ExportReportHelper.exportAndShareCsv(
                        context = context,
                        companyName = companyName,
                        records = filteredRecords
                    )
                },
                enabled = filteredRecords.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = "Ekspor CSV/Excel", modifier = Modifier.size(16.dp), tint = PureWhite)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Ekspor CSV / Excel", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PureWhite)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterType == 0,
                onClick = { filterType = 0 },
                label = { Text("Semua (${records.size})") }
            )
            FilterChip(
                selected = filterType == 1,
                onClick = { filterType = 1 },
                label = { Text("Hari Ini") }
            )
            FilterChip(
                selected = filterType == 2,
                onClick = { filterType = 2 },
                label = { Text("Bulan Ini") }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = logSearchQuery,
            onValueChange = { logSearchQuery = it },
            placeholder = { Text("Cari log karyawan, NIK, shift...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondaryLight) },
            trailingIcon = {
                if (logSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { logSearchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMutedLight)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryNavy,
                unfocusedBorderColor = CardBorderLight,
                focusedTextColor = TextPrimaryLight,
                unfocusedTextColor = TextPrimaryLight
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredRecords.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (records.isEmpty()) "Belum ada riwayat presensi tersimpan" else "Tidak ada log presensi yang cocok",
                        color = TextSecondaryLight,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredRecords, key = { it.id }) { record ->
                    val (statusText, statusBg, statusColor) = when (record.status) {
                        AttendanceStatus.ON_TIME -> Triple("Tepat Waktu", AccentGreen.copy(alpha = 0.10f), AccentGreen)
                        AttendanceStatus.LATE -> Triple("Terlambat", AccentRed.copy(alpha = 0.10f), AccentRed)
                        AttendanceStatus.EARLY_DEPARTURE -> Triple("Pulang Awal", AccentAmber.copy(alpha = 0.10f), AccentAmber)
                        AttendanceStatus.OVERTIME -> Triple("Lembur", AccentPurple.copy(alpha = 0.10f), AccentPurple)
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(2.dp, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = PureWhite),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = record.employeeName,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimaryLight,
                                        fontSize = 15.sp
                                    )
                                    Surface(
                                        color = InputBgLight,
                                        shape = RoundedCornerShape(4.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                                    ) {
                                        Text(
                                            text = record.role,
                                            color = PrimaryNavy,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = "NIK: ${record.nik} • ${record.shiftName}",
                                    color = TextSecondaryLight,
                                    fontSize = 12.sp
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        color = if (record.checkType == CheckType.CHECK_IN) PrimaryNavy else AccentGreen,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = if (record.checkType == CheckType.CHECK_IN) "MASUK" else "PULANG",
                                            color = PureWhite,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    Surface(
                                        color = statusBg,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = statusText,
                                            color = statusColor,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    Text(
                                        text = "Akurasi: ${(record.confidenceScore * 100).toInt()}%",
                                        color = TextMutedLight,
                                        fontSize = 10.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = dateFormat.format(Date(record.timestamp)),
                                    color = TextMutedLight,
                                    fontSize = 11.sp
                                )
                            }

                            IconButton(onClick = { onDeleteRecord(record.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Hapus Log", tint = TextMutedLight, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun KioskSettingsTab(
    settings: KioskSettings,
    repository: AttendanceRepository,
    onSaveSettings: (KioskSettings) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var companyName by remember { mutableStateOf(settings.companyName) }
    var address by remember { mutableStateOf(settings.address) }
    var latitude by remember { mutableDoubleStateOf(settings.latitude) }
    var longitude by remember { mutableDoubleStateOf(settings.longitude) }
    var radiusMeters by remember { mutableDoubleStateOf(settings.radiusMeters) }
    var kioskPin by remember { mutableStateOf(settings.kioskPin) }
    var confidenceThreshold by remember { mutableFloatStateOf(settings.faceConfidenceThreshold) }

    // Master Shifts
    var shift1Start by remember { mutableStateOf(settings.shift1Start) }
    var shift1End by remember { mutableStateOf(settings.shift1End) }
    var shift2Start by remember { mutableStateOf(settings.shift2Start) }
    var shift2End by remember { mutableStateOf(settings.shift2End) }
    var shift3Start by remember { mutableStateOf(settings.shift3Start) }
    var shift3End by remember { mutableStateOf(settings.shift3End) }

    // Integrations
    var fonnteApiKey by remember { mutableStateOf(settings.fonnteApiKey) }
    var fonnteAdminWa by remember { mutableStateOf(settings.fonnteAdminWa) }
    var googleSheetsUrl by remember { mutableStateOf(settings.googleSheetsUrl) }
    var gpsStatusMessage by remember { mutableStateOf<String?>(null) }

    fun processLocation(loc: Location) {
        latitude = loc.latitude
        longitude = loc.longitude
        try {
            val geocoder = Geocoder(context, Locale("id", "ID"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(loc.latitude, loc.longitude, 1) { addresses ->
                    if (addresses.isNotEmpty()) {
                        val addr = addresses[0]
                        val thoroughfare = addr.thoroughfare ?: ""
                        val subLoc = addr.subLocality ?: ""
                        val locality = addr.locality ?: addr.subAdminArea ?: ""
                        val parts = listOf(thoroughfare, subLoc, locality).filter { it.isNotBlank() }
                        if (parts.isNotEmpty()) {
                            address = parts.joinToString(", ")
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val thoroughfare = addr.thoroughfare ?: ""
                    val subLoc = addr.subLocality ?: ""
                    val locality = addr.locality ?: addr.subAdminArea ?: ""
                    val parts = listOf(thoroughfare, subLoc, locality).filter { it.isNotBlank() }
                    if (parts.isNotEmpty()) {
                        address = parts.joinToString(", ")
                    }
                }
            }
            gpsStatusMessage = "Lokasi GPS berhasil diperbarui: $address"
        } catch (_: Exception) {
            gpsStatusMessage = "Koordinat GPS: ${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)}"
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (loc != null) {
                    processLocation(loc)
                } else {
                    gpsStatusMessage = "GPS aktif. Pastikan sinyal lokasi tersedia."
                }
            } catch (e: Exception) {
                gpsStatusMessage = "Gagal membaca GPS: ${e.message}"
            }
        } else {
            gpsStatusMessage = "Izin lokasi tidak diberikan."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Identitas & Lokasi Kantor
        Card(
            modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Identitas & Lokasi Kantor", color = TextPrimaryLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                OutlinedTextField(
                    value = companyName,
                    onValueChange = { companyName = it },
                    label = { Text("Nama Perusahaan / Instansi") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Alamat Kantor (Diperbarui Otomatis via GPS)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = InputBgLight,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Titik GPS Kantor", color = TextPrimaryLight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text("Lat: ${String.format("%.4f", latitude)}, Long: ${String.format("%.4f", longitude)}", color = TextSecondaryLight, fontSize = 11.sp)
                            }

                            Button(
                                onClick = {
                                    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                    if (fine) {
                                        try {
                                            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                                            val loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                                            if (loc != null) {
                                                processLocation(loc)
                                            } else {
                                                gpsStatusMessage = "Menunggu sinyal GPS..."
                                            }
                                        } catch (_: Exception) {}
                                    } else {
                                        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Ambil Alamat GPS", color = PureWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (gpsStatusMessage != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = gpsStatusMessage!!, color = AccentGreen, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Pengaturan Biometrik & Face Recognition (MobileFaceNet)
        Card(
            modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = PrimaryNavy, modifier = Modifier.size(20.dp))
                    Text("Pengenalan Wajah & Ambang Biometrik", color = TextPrimaryLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }

                Text(
                    text = "Batas skor pencocokan MobileFaceNet (Default: 0.70, Minimal: 0.60). Sistem mewajibkan selisih skor minimal 0.08 dari kandidat kedua demi presisi 100%.",
                    color = TextSecondaryLight,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ambang Kecocokan:", color = TextPrimaryLight, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${(confidenceThreshold.coerceIn(0.60f, 0.95f) * 100).toInt()}%", color = PrimaryNavy, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                Slider(
                    value = confidenceThreshold.coerceIn(0.60f, 0.95f),
                    onValueChange = { confidenceThreshold = it.coerceIn(0.60f, 0.95f) },
                    valueRange = 0.60f..0.95f,
                    steps = 6,
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryNavy,
                        activeTrackColor = PrimaryNavy,
                        inactiveTrackColor = CardBorderLight
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.60 (Min)", color = TextSecondaryLight, fontSize = 11.sp)
                    Text("0.70 (Standar)", color = TextSecondaryLight, fontSize = 11.sp)
                    Text("0.95 (Ketat)", color = TextSecondaryLight, fontSize = 11.sp)
                }
            }
        }

        // 2. Master Shift
        Card(
            modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Master Konfigurasi Shift Kantor", color = TextPrimaryLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                // Shift 1
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Shift 1:", color = TextPrimaryLight, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp), fontSize = 13.sp)
                    OutlinedTextField(
                        value = shift1Start,
                        onValueChange = { shift1Start = it },
                        label = { Text("Mulai") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                    )
                    OutlinedTextField(
                        value = shift1End,
                        onValueChange = { shift1End = it },
                        label = { Text("Selesai") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                    )
                }

                // Shift 2
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Shift 2:", color = TextPrimaryLight, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp), fontSize = 13.sp)
                    OutlinedTextField(
                        value = shift2Start,
                        onValueChange = { shift2Start = it },
                        label = { Text("Mulai") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                    )
                    OutlinedTextField(
                        value = shift2End,
                        onValueChange = { shift2End = it },
                        label = { Text("Selesai") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                    )
                }

                // Shift 3
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Shift 3:", color = TextPrimaryLight, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp), fontSize = 13.sp)
                    OutlinedTextField(
                        value = shift3Start,
                        onValueChange = { shift3Start = it },
                        label = { Text("Mulai") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                    )
                    OutlinedTextField(
                        value = shift3End,
                        onValueChange = { shift3End = it },
                        label = { Text("Selesai") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                    )
                }
            }
        }

        // 3. Integrasi WhatsApp & Google Sheets
        Card(
            modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Integrasi WhatsApp & Google Sheets", color = TextPrimaryLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                OutlinedTextField(
                    value = fonnteApiKey,
                    onValueChange = { fonnteApiKey = it },
                    label = { Text("API Token Fonnte (WhatsApp)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                )

                OutlinedTextField(
                    value = fonnteAdminWa,
                    onValueChange = { fonnteAdminWa = it },
                    label = { Text("Nomor WhatsApp Admin HRD (cth: 08123456789)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                )

                OutlinedTextField(
                    value = googleSheetsUrl,
                    onValueChange = { googleSheetsUrl = it },
                    label = { Text("URL Webhook Google Sheets") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                )
            }
        }

        // 4. Ekspor & Backup Data Presensi (CSV / Excel)
        val allRecords by repository.allAttendanceRecords.collectAsState(initial = emptyList())
        Card(
            modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Share, contentDescription = null, tint = PrimaryNavy, modifier = Modifier.size(20.dp))
                    Text("Ekspor & Backup Laporan Presensi", color = TextPrimaryLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Text(
                    text = "Unduh atau bagikan seluruh arsip riwayat presensi karyawan (${allRecords.size} catatan) dalam format file .CSV yang kompatibel langsung dengan Microsoft Excel, Google Sheets, atau aplikasi Spreadsheet lainnya.",
                    color = TextSecondaryLight,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Button(
                    onClick = {
                        ExportReportHelper.exportAndShareCsv(
                            context = context,
                            companyName = companyName,
                            records = allRecords
                        )
                    },
                    enabled = allRecords.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = PureWhite)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Ekspor Seluruh Laporan (CSV / Excel)", color = PureWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        // 5. Keamanan PIN Admin
        Card(
            modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PureWhite),
            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Keamanan Akses Kiosk", color = TextPrimaryLight, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                OutlinedTextField(
                    value = kioskPin,
                    onValueChange = { kioskPin = it },
                    label = { Text("PIN Akses Admin (Untuk Buka Dashboard)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PrimaryNavy, unfocusedBorderColor = CardBorderLight, focusedTextColor = TextPrimaryLight, unfocusedTextColor = TextPrimaryLight)
                )
            }
        }

        Button(
            onClick = {
                val updated = settings.copy(
                    companyName = companyName.trim(),
                    address = address.trim(),
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = radiusMeters,
                    kioskPin = kioskPin.trim(),
                    faceConfidenceThreshold = confidenceThreshold.coerceIn(0.60f, 0.95f),
                    shift1Start = shift1Start.trim(),
                    shift1End = shift1End.trim(),
                    shift2Start = shift2Start.trim(),
                    shift2End = shift2End.trim(),
                    shift3Start = shift3Start.trim(),
                    shift3End = shift3End.trim(),
                    fonnteApiKey = fonnteApiKey.trim(),
                    fonnteAdminWa = fonnteAdminWa.trim(),
                    googleSheetsUrl = googleSheetsUrl.trim()
                )
                onSaveSettings(updated)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
        ) {
            Text("Simpan Pengaturan", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun NotificationsTab(notifications: List<AdminNotification>) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    if (notifications.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Belum ada notifikasi sistem", color = TextSecondaryLight, fontSize = 14.sp)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(notifications, key = { it.id }) { notif ->
                Card(
                    modifier = Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = PureWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = notif.title, fontWeight = FontWeight.Bold, color = if (notif.type == "ALERT") AccentRed else AccentGreen, fontSize = 14.sp)
                            Text(text = dateFormat.format(Date(notif.timestamp)), color = TextMutedLight, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = notif.message, color = TextPrimaryLight, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
