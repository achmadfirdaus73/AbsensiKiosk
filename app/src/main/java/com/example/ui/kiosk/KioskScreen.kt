package com.example.ui.kiosk

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.data.repository.AttendanceRepository
import com.example.ui.theme.*
import com.example.util.AudioFeedbackHelper
import com.example.util.FaceBiometricHelper
import com.google.mlkit.vision.face.Face
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun KioskScreen(
    repository: AttendanceRepository,
    audioHelper: AudioFeedbackHelper,
    onNavigateToAdmin: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val employees by repository.allActiveEmployees.collectAsState(initial = emptyList())
    val biometricTemplates by repository.allBiometricTemplates.collectAsState(initial = emptyList())
    val kioskSettingsState by repository.kioskSettingsFlow.collectAsState(initial = null)
    val settings = kioskSettingsState ?: KioskSettings()

    // Camera Permission State
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var useFrontCamera by remember { mutableStateOf(true) }
    var currentTimeStr by remember { mutableStateOf("") }
    var currentDateStr by remember { mutableStateOf("") }

    var lastSuccessRecord by remember { mutableStateOf<AttendanceRecord?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showAdminPinDialog by remember { mutableStateOf(false) }
    var adminPinInput by remember { mutableStateOf("") }
    var adminPinError by remember { mutableStateOf(false) }

    // Live Face Recognition State
    var isLiveFaceDetected by remember { mutableStateOf(false) }
    var isUnrecognizedFace by remember { mutableStateOf(false) }
    var selectedEmployee by remember { mutableStateOf<Employee?>(null) }
    var matchConfidenceScore by remember { mutableFloatStateOf(0.98f) }
    var lastFaceDetectionTime by remember { mutableStateOf(0L) }
    var isProcessingAttendance by remember { mutableStateOf(false) }
    var livenessHint by remember { mutableStateOf<String?>(null) }
    var trackedFaceRect by remember { mutableStateOf<RectF?>(null) }
    var isSpoofDetected by remember { mutableStateOf(false) }
    val antiSpoofTracker = remember { FaceBiometricHelper.AntiSpoofTracker() }
    val consensusBuffer = remember { mutableListOf<Long>() }

    var isModelDownloading by remember { mutableStateOf(false) }

    var scanMatchCount by remember { mutableIntStateOf(0) }
    var scanCandidateId by remember { mutableStateOf<Long?>(null) }
    var scanCandidateName by remember { mutableStateOf("") }
    var scanProgress by remember { mutableFloatStateOf(0f) }

    // Auto-calculate biometric embeddings for existing registered employees if not yet cached
    LaunchedEffect(employees) {
        if (employees.isNotEmpty()) {
            repository.ensureEmployeeEmbeddings(context, employees)
        }
    }

    // Auto-clear badge when person walks away (no face detected for 2.2 seconds)
    LaunchedEffect(lastFaceDetectionTime) {
        if (lastFaceDetectionTime > 0) {
            delay(2200)
            if (System.currentTimeMillis() - lastFaceDetectionTime >= 2000) {
                isLiveFaceDetected = false
                selectedEmployee = null
                isUnrecognizedFace = false
                livenessHint = null
                trackedFaceRect = null
                isSpoofDetected = false
                scanMatchCount = 0
                scanCandidateId = null
                scanCandidateName = ""
                scanProgress = 0f
                antiSpoofTracker.reset()
            }
        }
    }

    // Biometric Scanner Pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scanPulseScale by infiniteTransition.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanPulse"
    )

    // Live clock ticker
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID"))
        while (true) {
            val now = Date()
            currentTimeStr = timeFormat.format(now)
            currentDateStr = dateFormat.format(now)
            delay(1000)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBg)
    ) {
        val density = LocalDensity.current
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenHeightPx = with(density) { maxHeight.toPx() }

        // Dynamic Face Bounding Box Tracking Coordinates
        val targetLeft: Float
        val targetTop: Float
        val targetWidth: Float
        val targetHeight: Float

        if (trackedFaceRect != null && isLiveFaceDetected) {
            val padX = trackedFaceRect!!.width() * 0.12f
            val padY = trackedFaceRect!!.height() * 0.16f
            targetLeft = (trackedFaceRect!!.left - padX).coerceIn(16f, screenWidthPx - 120f)
            targetTop = (trackedFaceRect!!.top - padY).coerceIn(70f, screenHeightPx - 140f)
            targetWidth = (trackedFaceRect!!.width() + padX * 2).coerceIn(160f, screenWidthPx * 0.88f)
            targetHeight = (trackedFaceRect!!.height() + padY * 2).coerceIn(160f, screenHeightPx * 0.85f)
        } else {
            val defaultSize = with(density) { 230.dp.toPx() }
            targetLeft = (screenWidthPx - defaultSize) / 2f
            targetTop = (screenHeightPx - defaultSize) / 2f - with(density) { 40.dp.toPx() }
            targetWidth = defaultSize
            targetHeight = defaultSize
        }

        val animLeft by animateFloatAsState(targetValue = targetLeft, animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy), label = "animLeft")
        val animTop by animateFloatAsState(targetValue = targetTop, animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy), label = "animTop")
        val animWidth by animateFloatAsState(targetValue = targetWidth, animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy), label = "animWidth")
        val animHeight by animateFloatAsState(targetValue = targetHeight, animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy), label = "animHeight")

        // 1. FULLSCREEN LIVE CAMERA
        if (hasCameraPermission) {
            LiveCameraPreview(
                modifier = Modifier.fillMaxSize(),
                useFrontCamera = useFrontCamera,
                onFacesDetected = { faces: List<Face> ->
                    if (faces.isNotEmpty()) {
                        isLiveFaceDetected = true
                        lastFaceDetectionTime = System.currentTimeMillis()
                    } else {
                        trackedFaceRect = null
                    }
                },
                onFaceBoundsTracked = { rect ->
                    trackedFaceRect = rect
                },
                onFaceBiometricsInfo = { faceInfo, liveVector ->
                    isLiveFaceDetected = true
                    lastFaceDetectionTime = System.currentTimeMillis()
                    isSpoofDetected = false

                    if (employees.isNotEmpty() && selectedEmployee == null) {
                        val match = repository.findBestMatchingEmployee(
                            liveBiometricVector = liveVector,
                            employees = employees,
                            threshold = settings.faceConfidenceThreshold,
                            templates = biometricTemplates
                        )

                        if (match != null) {
                            val emp = match.first
                            val conf = match.second

                            if (scanCandidateId == emp.id) {
                                scanMatchCount++
                            } else {
                                scanCandidateId = emp.id
                                scanCandidateName = emp.name
                                scanMatchCount = 1
                            }

                            scanProgress = (scanMatchCount / 10f).coerceIn(0f, 1f)

                            // Require 10 consistent frames (~1.5 seconds) to thoroughly scan and recognize facial contours
                            if (scanMatchCount >= 10 || conf >= 0.95f) {
                                selectedEmployee = emp
                                matchConfidenceScore = conf
                                isUnrecognizedFace = false
                                scanProgress = 1f
                            }
                        } else {
                            scanMatchCount = maxOf(0, scanMatchCount - 1)
                            if (scanMatchCount == 0) {
                                scanCandidateId = null
                                scanCandidateName = ""
                                scanProgress = 0f
                                isUnrecognizedFace = true
                            }
                        }
                    } else if (employees.isEmpty()) {
                        isUnrecognizedFace = false
                    }
                },
                onModelDownloadStatusChanged = { downloading ->
                    isModelDownloading = downloading
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightBg),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideocamOff, contentDescription = null, tint = TextSecondaryLight, modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Izin Kamera Diperlukan", color = TextPrimaryLight, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Izinkan Kamera", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }



        // 2. 🔲 DYNAMIC AI FACE TRACKING RETICLE (Follows Face in Real-Time)
        Box(
            modifier = Modifier
                .offset { IntOffset(animLeft.roundToInt(), animTop.roundToInt()) }
                .size(
                    width = with(density) { animWidth.toDp() },
                    height = with(density) { animHeight.toDp() }
                )
                .scale(scanPulseScale)
        ) {
            val bracketColor = when {
                selectedEmployee != null -> AccentGreen
                isUnrecognizedFace && isLiveFaceDetected -> AccentAmber
                isLiveFaceDetected -> PrimaryNavy
                else -> PrimaryNavy.copy(alpha = 0.6f)
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val cornerLength = 32.dp.toPx().coerceAtMost(size.width * 0.28f)
                val strokeWidth = 3.5.dp.toPx()

                // Top-Left Corner
                drawLine(color = bracketColor, start = Offset(0f, cornerLength), end = Offset(0f, 0f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = bracketColor, start = Offset(0f, 0f), end = Offset(cornerLength, 0f), strokeWidth = strokeWidth, cap = StrokeCap.Round)

                // Top-Right Corner
                drawLine(color = bracketColor, start = Offset(size.width - cornerLength, 0f), end = Offset(size.width, 0f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = bracketColor, start = Offset(size.width, 0f), end = Offset(size.width, cornerLength), strokeWidth = strokeWidth, cap = StrokeCap.Round)

                // Bottom-Left Corner
                drawLine(color = bracketColor, start = Offset(0f, size.height - cornerLength), end = Offset(0f, size.height), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = bracketColor, start = Offset(0f, size.height), end = Offset(cornerLength, size.height), strokeWidth = strokeWidth, cap = StrokeCap.Round)

                // Bottom-Right Corner
                drawLine(color = bracketColor, start = Offset(size.width - cornerLength, size.height), end = Offset(size.width, size.height), strokeWidth = strokeWidth, cap = StrokeCap.Round)
                drawLine(color = bracketColor, start = Offset(size.width, size.height - cornerLength), end = Offset(size.width, size.height), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }

            // Real-Time Floating Identity Badge Attached Directly to Tracked Face
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = PureWhite.copy(alpha = 0.95f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, bracketColor.copy(alpha = 0.85f)),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (selectedEmployee?.photoUri != null) {
                            AsyncImage(
                                model = selectedEmployee!!.photoUri,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp).clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            val dotColor = if (selectedEmployee != null) AccentGreen else if (isUnrecognizedFace) AccentAmber else PrimaryNavy
                            Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
                        }

                        val statusLabel = when {
                            selectedEmployee != null -> "Wajah Cocok: ${selectedEmployee!!.name} (${(matchConfidenceScore * 100).toInt()}%)"
                            scanProgress > 0f && scanCandidateName.isNotEmpty() -> "Memindai Kontur: $scanCandidateName (${(scanProgress * 100).toInt()}%)..."
                            isUnrecognizedFace && isLiveFaceDetected -> "Wajah Belum Terdaftar"
                            isLiveFaceDetected -> "Mengenali Kontur Wajah..."
                            else -> "Posisikan Wajah ke Kamera"
                        }

                        Text(
                            text = statusLabel,
                            color = bracketColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 3. 🏢 CLEAN EXECUTIVE WHITE TOP HEADER
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Company Name & Dynamic Address from GPS
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PureWhite.copy(alpha = 0.96f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight),
                    shadowElevation = 2.dp,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (isLiveFaceDetected) AccentGreen else PrimaryBlue, CircleShape)
                        )
                        Column {
                            Text(
                                text = settings.companyName,
                                color = TextPrimaryLight,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${settings.address} • ${currentTimeStr.ifEmpty { "08:00:00" }}",
                                color = TextSecondaryLight,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Right: Clean Functional Actions
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Switch Camera
                    IconButton(
                        onClick = { useFrontCamera = !useFrontCamera },
                        modifier = Modifier
                            .size(38.dp)
                            .background(PureWhite, CircleShape)
                            .border(1.dp, CardBorderLight, CircleShape)
                            .shadow(1.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Ganti Kamera", tint = PrimaryNavy, modifier = Modifier.size(18.dp))
                    }

                    // Absen Manual PIN
                    IconButton(
                        onClick = { showPinDialog = true },
                        modifier = Modifier
                            .size(38.dp)
                            .background(PureWhite, CircleShape)
                            .border(1.dp, CardBorderLight, CircleShape)
                            .shadow(1.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.Pin, contentDescription = "Absen PIN", tint = PrimaryNavy, modifier = Modifier.size(18.dp))
                    }

                    // Admin Access
                    Button(
                        onClick = {
                            adminPinInput = ""
                            adminPinError = false
                            showAdminPinDialog = true
                        },
                        modifier = Modifier.height(38.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("Admin", color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // High-Res AI Module Download Indicator
            if (isModelDownloading) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PrimaryNavy.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = PrimaryBlue,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Menyiapkan modul AI offline presisi tinggi... (Mode Instan Aktif)",
                            color = PureWhite,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Top Alert if no employees yet
            if (employees.isEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = InputBgLight,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Belum ada data karyawan. Buka menu Admin di atas untuk mendaftarkan.",
                            color = TextSecondaryLight,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 4. 🪪 REFINED WHITE COMPACT BADGE CARD (When face matched or unrecognized)
        AnimatedVisibility(
            visible = isLiveFaceDetected && (selectedEmployee != null || isUnrecognizedFace),
            enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut(tween(200)) + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (selectedEmployee != null) {
                val emp = selectedEmployee!!
                val shiftResolution = repository.resolveShiftForEmployee(emp, settings)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(6.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = PureWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        // Top Identity Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Photo / Initials
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(InputBgLight)
                                        .border(1.dp, CardBorderLight, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!emp.photoUri.isNullOrEmpty()) {
                                        AsyncImage(
                                            model = emp.photoUri,
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

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = emp.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = TextPrimaryLight,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        Surface(
                                            color = InputBgLight,
                                            shape = RoundedCornerShape(4.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                                        ) {
                                            Text(
                                                text = emp.role.uppercase(),
                                                color = PrimaryNavy,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 9.sp,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = "NIK: ${emp.nik} • ${emp.division} • ${shiftResolution.shiftName}",
                                        color = TextSecondaryLight,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // Match Confidence Badge
                            Surface(
                                color = AccentGreen.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = "✓ ${(matchConfidenceScore * 100).toInt()}% Cocok",
                                    color = AccentGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Compact Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // ABSEN MASUK
                            Button(
                                onClick = {
                                    if (!isProcessingAttendance) {
                                        isProcessingAttendance = true
                                        coroutineScope.launch {
                                            val record = repository.processAttendance(
                                                employee = emp,
                                                checkType = CheckType.CHECK_IN,
                                                confidenceScore = matchConfidenceScore
                                            )
                                            val statusText = if (record.status == AttendanceStatus.ON_TIME) "Tepat Waktu" else "Terlambat"
                                            audioHelper.speak("Terima kasih ${emp.name}, berhasil Absen Masuk $statusText!")
                                            lastSuccessRecord = record
                                            // IMMEDIATELY RESET SO BADGE DISAPPEARS FOR NEXT PERSON
                                            selectedEmployee = null
                                            isLiveFaceDetected = false
                                            isProcessingAttendance = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy)
                            ) {
                                Text("ABSEN MASUK", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            // ABSEN PULANG
                            Button(
                                onClick = {
                                    if (!isProcessingAttendance) {
                                        isProcessingAttendance = true
                                        coroutineScope.launch {
                                            val record = repository.processAttendance(
                                                employee = emp,
                                                checkType = CheckType.CHECK_OUT,
                                                confidenceScore = matchConfidenceScore
                                            )
                                            val statusText = if (record.status == AttendanceStatus.OVERTIME) "Lembur" else "Berhasil"
                                            audioHelper.speak("Terima kasih ${emp.name}, berhasil Absen Pulang $statusText!")
                                            lastSuccessRecord = record
                                            // IMMEDIATELY RESET SO BADGE DISAPPEARS FOR NEXT PERSON
                                            selectedEmployee = null
                                            isLiveFaceDetected = false
                                            isProcessingAttendance = false
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                            ) {
                                Text("ABSEN PULANG", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else if (isUnrecognizedFace) {
                // Unrecognized Face Alert Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = PureWhite),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentAmber.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(AccentAmber.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.PersonSearch, contentDescription = null, tint = AccentAmber, modifier = Modifier.size(22.dp))
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Wajah Belum Terdaftar",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryLight,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Wajah tidak cocok dengan data karyawan terdaftar. Gunakan tombol Absen PIN di atas atau hubungi Admin.",
                                color = TextSecondaryLight,
                                fontSize = 11.sp
                            )
                        }

                        OutlinedButton(
                            onClick = { showPinDialog = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                        ) {
                            Text("Absen PIN", color = PrimaryNavy, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    // Success Dialog
    if (lastSuccessRecord != null) {
        AttendanceSuccessDialog(
            record = lastSuccessRecord!!,
            onDismiss = {
                lastSuccessRecord = null
                // Ensure fresh clean state when dialog closes
                selectedEmployee = null
                isLiveFaceDetected = false
            }
        )
    }

    // PIN Attendance Fallback Dialog
    if (showPinDialog) {
        PinAttendanceDialog(
            onDismiss = { showPinDialog = false },
            onSubmitPin = { nik, pin, checkType ->
                coroutineScope.launch {
                    val employee = repository.getEmployeeByNikAndPin(nik, pin)
                    if (employee != null) {
                        val record = repository.processAttendance(
                            employee = employee,
                            checkType = checkType,
                            confidenceScore = 1.0f
                        )
                        val actionName = if (checkType == CheckType.CHECK_IN) "Masuk" else "Pulang"
                        audioHelper.speak("Terima kasih ${employee.name}, berhasil Absen $actionName!")
                        lastSuccessRecord = record
                        showPinDialog = false
                    } else {
                        audioHelper.speak("NIK atau PIN tidak cocok!")
                    }
                }
            }
        )
    }

    // Admin Verification PIN Modal
    if (showAdminPinDialog) {
        Dialog(onDismissRequest = { showAdminPinDialog = false }) {
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
                        text = "Verifikasi Admin",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimaryLight,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Masukkan PIN Kiosk Admin untuk membuka Dashboard",
                        color = TextSecondaryLight,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    OutlinedTextField(
                        value = adminPinInput,
                        onValueChange = {
                            if (it.length <= 6) {
                                adminPinInput = it
                                adminPinError = false
                            }
                        },
                        label = { Text("PIN Admin") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        isError = adminPinError,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryNavy,
                            unfocusedBorderColor = CardBorderLight,
                            focusedTextColor = TextPrimaryLight,
                            unfocusedTextColor = TextPrimaryLight
                        )
                    )

                    if (adminPinError) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "PIN Admin salah!", color = AccentRed, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAdminPinDialog = false },
                            modifier = Modifier.weight(1f).height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CardBorderLight)
                        ) {
                            Text("Batal", color = TextSecondaryLight)
                        }

                        Button(
                            onClick = {
                                if (adminPinInput == settings.kioskPin) {
                                    showAdminPinDialog = false
                                    onNavigateToAdmin()
                                } else {
                                    adminPinError = true
                                }
                            },
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNavy),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Masuk", color = PureWhite, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
