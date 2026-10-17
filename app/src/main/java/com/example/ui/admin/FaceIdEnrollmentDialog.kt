package com.example.ui.admin

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.util.AudioFeedbackHelper
import com.example.util.FaceBiometricHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

private const val TARGET_ENROLLMENT_FRAMES = 10

@OptIn(ExperimentalGetImage::class)
@Composable
fun FaceIdEnrollmentDialog(
    employeeName: String,
    onDismiss: () -> Unit,
    onEnrollmentComplete: (photoPath: String, embeddingJson: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val audioHelper = remember { AudioFeedbackHelper(context) }

    LaunchedEffect(Unit) {
        FaceBiometricHelper.init(context)
    }

    var useFrontCamera by remember { mutableStateOf(true) }
    var enrollmentProgress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(
        targetValue = enrollmentProgress,
        animationSpec = tween(durationMillis = 100, easing = LinearOutSlowInEasing),
        label = "enrollmentProgressAnim"
    )
    var currentInstruction by remember { mutableStateOf("Posisikan wajah di dalam lingkaran") }
    var isFaceInsideCircle by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var isCapturingInstant by remember { mutableStateOf(false) }
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    var masterEmbeddingJson by remember { mutableStateOf<String?>(null) }

    val collectedVectors = remember { mutableListOf<FloatArray>() }
    var lastCollectionTimestamp = remember { 0L }
    var bestSnapshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var latestDetectedFace by remember { mutableStateOf<Face?>(null) }
    var latestFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var enrollDebug by remember { mutableStateOf<String?>(null) }
    val frameLock = remember { Any() }

    val faceDetector: FaceDetector = remember {
        FaceDetection.getClient(FaceBiometricHelper.fastDetectorOptions)
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    fun finishEnrollment(vectors: List<FloatArray>, photoBitmap: Bitmap?) {
        if (isComplete || vectors.isEmpty()) return
        isComplete = true
        coroutineScope.launch {
            audioHelper.playSuccessBeep()
            val dim = FaceBiometricHelper.EMBEDDING_DIM
            val masterVector = if (vectors.size == 1) {
                vectors[0].copyOf()
            } else {
                val preliminaryMean = FloatArray(dim)
                for (v in vectors) {
                    for (i in 0 until dim) preliminaryMean[i] += v[i]
                }
                FaceBiometricHelper.l2Normalize(preliminaryMean)

                val validVectors = vectors.filter { v ->
                    FaceBiometricHelper.calculateSimilarity(v, preliminaryMean) >= 0.40f
                }
                val finalPool = if (validVectors.isNotEmpty()) validVectors else vectors

                val mv = FloatArray(dim)
                for (v in finalPool) {
                    for (i in 0 until dim) mv[i] += v[i]
                }
                for (i in 0 until dim) {
                    mv[i] /= finalPool.size.toFloat()
                }
                FaceBiometricHelper.l2Normalize(mv)
                mv
            }

            val masterJson = FaceBiometricHelper.serializeEmbedding(masterVector)
            masterEmbeddingJson = masterJson

            val safeBitmap = if (photoBitmap != null && !photoBitmap.isRecycled) {
                try {
                    Bitmap.createBitmap(photoBitmap)
                } catch (_: Throwable) {
                    Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                }
            } else {
                Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            }

            val photosDir = File(context.filesDir, "employee_photos")
            if (!photosDir.exists()) photosDir.mkdirs()
            val photoFile = File(photosDir, "emp_${System.currentTimeMillis()}.jpg")
            FileOutputStream(photoFile).use { out ->
                safeBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            capturedPhotoPath = photoFile.absolutePath

            delay(500)
            onEnrollmentComplete(photoFile.absolutePath, masterJson)
        }
    }

    fun triggerInstantCapture() {
        val face = latestDetectedFace
        if (face == null || isComplete || isCapturingInstant) return
        val bmp: Bitmap? = synchronized(frameLock) {
            latestFrameBitmap?.takeIf { !it.isRecycled }?.let { src ->
                try { src.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }
            }
        }
        if (bmp == null) {
            currentInstruction = "Frame kamera belum siap, coba lagi"
            return
        }
        isCapturingInstant = true
        coroutineScope.launch {
            var failReason: String? = null
            val vector: FloatArray = withContext(Dispatchers.Default) {
                try {
                    val alignedCrop = FaceBiometricHelper.alignAndCropFace(bmp, face)
                    if (alignedCrop == null) {
                        failReason = "align gagal (landmark null?)"
                        FloatArray(0)
                    } else {
                        val v = FaceBiometricHelper.extractFeatureVector(face, alignedCrop, context)
                        alignedCrop.recycle()
                        if (v.size != FaceBiometricHelper.EMBEDDING_DIM) {
                            failReason = "ukuran vektor ${v.size}, harusnya ${FaceBiometricHelper.EMBEDDING_DIM}"
                        }
                        v
                    }
                } catch (t: Throwable) {
                    Log.e("FaceIdEnrollment", "instant extract error", t)
                    failReason = "${t.javaClass.simpleName}: ${t.message}"
                    FloatArray(0)
                }
            }

            if (vector.size == FaceBiometricHelper.EMBEDDING_DIM) {
                synchronized(collectedVectors) {
                    while (collectedVectors.size < TARGET_ENROLLMENT_FRAMES) {
                        collectedVectors.add(vector.copyOf())
                    }
                }
                enrollDebug = null
                enrollmentProgress = 1.0f
                currentInstruction = "Wajah berhasil diambil! Menyimpan 10 frame..."
                finishEnrollment(ArrayList(collectedVectors), bmp)
            } else {
                enrollDebug = failReason ?: "vektor tidak valid"
                currentInstruction = "Gagal ekstrak: $enrollDebug"
                bmp.recycle()
                isCapturingInstant = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            faceDetector.close()
            cameraExecutor.shutdown()
            audioHelper.release()
            latestFrameBitmap?.recycle()
            bestSnapshotBitmap?.recycle()
        }
    }

    // Dynamic rotation animation for the outer scan ticks
    val infiniteTransition = rememberInfiniteTransition(label = "face_id_scan")
    val scanAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scanAngle"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
        ) {
            // 1. Camera Preview
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    }

                    val mainExecutor = ContextCompat.getMainExecutor(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .setTargetResolution(Size(480, 640))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                                if (isComplete) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val uprightBmp = try {
                                    val rawBmp = imageProxy.toBitmap()
                                    if (rotation != 0) {
                                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                        val rotBmp = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                                        if (rotBmp != rawBmp) rawBmp.recycle()
                                        rotBmp
                                    } else rawBmp
                                } catch (t: Throwable) {
                                    Log.e("FaceIdEnrollment", "toBitmap error: ${t.message}")
                                    null
                                } finally {
                                    imageProxy.close()
                                }

                                if (uprightBmp == null) return@setAnalyzer

                                val inputImage = InputImage.fromBitmap(uprightBmp, 0)
                                faceDetector.process(inputImage)
                                    .addOnSuccessListener(cameraExecutor) { faces ->
                                        try {
                                            if (isComplete) return@addOnSuccessListener

                                            if (faces.isNotEmpty()) {
                                                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
                                                val box = face.boundingBox

                                                val isGoodSize = box.width() >= 45 && box.height() >= 45
                                                val yaw = Math.abs(face.headEulerAngleY)
                                                val roll = Math.abs(face.headEulerAngleZ)

                                                latestDetectedFace = face
                                                synchronized(frameLock) {
                                                    latestFrameBitmap?.recycle()
                                                    latestFrameBitmap = Bitmap.createBitmap(uprightBmp)
                                                }

                                                if (!isGoodSize) {
                                                    mainExecutor.execute {
                                                        if (!isComplete) {
                                                            isFaceInsideCircle = false
                                                            currentInstruction = "Dekatkan wajah sedikit ke kamera"
                                                        }
                                                    }
                                                } else if (roll > 50f) {
                                                    mainExecutor.execute {
                                                        if (!isComplete) {
                                                            isFaceInsideCircle = false
                                                            currentInstruction = "Tegakkan posisi kepala"
                                                        }
                                                    }
                                                } else if (yaw > 50f) {
                                                    mainExecutor.execute {
                                                        if (!isComplete) {
                                                            isFaceInsideCircle = false
                                                            currentInstruction = "Hadap lurus ke arah kamera"
                                                        }
                                                    }
                                                } else {
                                                    val now = System.currentTimeMillis()
                                                    val shouldCollect = (now - lastCollectionTimestamp >= 70L) &&
                                                            (collectedVectors.size < TARGET_ENROLLMENT_FRAMES) &&
                                                            !isComplete

                                                    if (shouldCollect) {
                                                        lastCollectionTimestamp = now

                                                        var failReason: String? = null
                                                        val vector: FloatArray = try {
                                                            val alignedCrop = FaceBiometricHelper.alignAndCropFace(uprightBmp, face)
                                                            if (alignedCrop == null) {
                                                                failReason = "align gagal (landmark null?)"
                                                                FloatArray(0)
                                                            } else {
                                                                val v = FaceBiometricHelper.extractFeatureVector(face, alignedCrop, ctx)
                                                                alignedCrop.recycle()
                                                                if (v.size != FaceBiometricHelper.EMBEDDING_DIM) failReason = "ukuran vektor ${v.size}, harusnya ${FaceBiometricHelper.EMBEDDING_DIM}"
                                                                v
                                                            }
                                                        } catch (t: Throwable) {
                                                            Log.e("FaceIdEnrollment", "extract error", t)
                                                            failReason = "${t.javaClass.simpleName}: ${t.message}"
                                                            FloatArray(0)
                                                        }

                                                        if (vector.size == FaceBiometricHelper.EMBEDDING_DIM) {
                                                            synchronized(collectedVectors) {
                                                                collectedVectors.add(vector)
                                                                enrollDebug = null

                                                                if (bestSnapshotBitmap == null || (yaw <= 18f && roll <= 18f)) {
                                                                    bestSnapshotBitmap?.recycle()
                                                                    bestSnapshotBitmap = Bitmap.createBitmap(uprightBmp)
                                                                }

                                                                val count = collectedVectors.size
                                                                val newProg = (count.toFloat() / TARGET_ENROLLMENT_FRAMES).coerceIn(0f, 1f)

                                                                mainExecutor.execute {
                                                                    if (!isComplete) {
                                                                        enrollmentProgress = newProg
                                                                        isFaceInsideCircle = true
                                                                        currentInstruction = "Merekam wajah: $count/$TARGET_ENROLLMENT_FRAMES frame..."
                                                                    }
                                                                }

                                                                if (count >= TARGET_ENROLLMENT_FRAMES && !isComplete) {
                                                                    val safePhoto = bestSnapshotBitmap ?: Bitmap.createBitmap(uprightBmp)
                                                                    finishEnrollment(ArrayList(collectedVectors), safePhoto)
                                                                }
                                                            }
                                                        } else {
                                                            Log.w("FaceIdEnrollment", "Ekstrak gagal: $failReason (vector size: ${vector.size})")
                                                            enrollDebug = failReason ?: "vektor tidak valid"
                                                        }
                                                    } else {
                                                        val count = collectedVectors.size
                                                        val newProg = (count.toFloat() / TARGET_ENROLLMENT_FRAMES).coerceIn(0f, 1f)
                                                        mainExecutor.execute {
                                                            if (!isComplete) {
                                                                enrollmentProgress = newProg
                                                                isFaceInsideCircle = true
                                                                currentInstruction = when {
                                                                    enrollDebug != null -> "Gagal ekstrak: $enrollDebug"
                                                                    count > 0 -> "Merekam wajah: $count/$TARGET_ENROLLMENT_FRAMES frame..."
                                                                    else -> "Wajah terdeteksi, merekam..."
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                mainExecutor.execute {
                                                    if (!isComplete) {
                                                        isFaceInsideCircle = false
                                                        currentInstruction = "Posisikan wajah di dalam lingkaran"
                                                    }
                                                }
                                            }
                                        } finally {
                                            uprightBmp.recycle()
                                        }
                                    }
                                    .addOnFailureListener(cameraExecutor) { e ->
                                        Log.w("FaceIdEnrollment", "Face detection failure: ${e.message}")
                                        uprightBmp.recycle()
                                    }
                            }

                            val cameraSelector = if (useFrontCamera && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                                CameraSelector.DEFAULT_FRONT_CAMERA
                            } else {
                                CameraSelector.DEFAULT_BACK_CAMERA
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            Log.e("FaceIdEnrollment", "Camera bind failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            // 2. Dark Overlay with Circular Cutout (Apple Face ID aesthetic)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height * 0.40f)
                val radius = size.width * 0.36f

                // Draw 48 surrounding radial tick segments
                val totalTicks = 48
                val activeTicks = (enrollmentProgress * totalTicks).toInt()

                for (i in 0 until totalTicks) {
                    val angleDeg = (i * (360f / totalTicks)) + scanAngle
                    val angleRad = Math.toRadians(angleDeg.toDouble())
                    val innerR = radius + 14f
                    val outerR = radius + 32f

                    val p1 = Offset(
                        center.x + (innerR * cos(angleRad)).toFloat(),
                        center.y + (innerR * sin(angleRad)).toFloat()
                    )
                    val p2 = Offset(
                        center.x + (outerR * cos(angleRad)).toFloat(),
                        center.y + (outerR * sin(angleRad)).toFloat()
                    )

                    val isTickActive = i <= activeTicks
                    val tickColor = if (isComplete) {
                        Color(0xFF10B981) // Emerald Green
                    } else if (isTickActive) {
                        Color(0xFF00E5FF) // Neon Cyan
                    } else {
                        Color.White.copy(alpha = 0.20f)
                    }

                    drawLine(
                        color = tickColor,
                        start = p1,
                        end = p2,
                        strokeWidth = if (isTickActive) 4.5f else 2.5f,
                        cap = StrokeCap.Round
                    )
                }

                // Inner Guide Ring
                drawCircle(
                    color = if (isComplete) Color(0xFF10B981) else if (isFaceInsideCircle) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.40f),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 3.5f)
                )
            }

            // 3. Top Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .testTag("close_face_enrollment_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White)
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.50f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                ) {
                    Text(
                        text = "Face ID Enrollment",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }

                IconButton(
                    onClick = { useFrontCamera = !useFrontCamera },
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .testTag("switch_camera_face_enrollment_button")
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Ganti Kamera", tint = Color.White)
                }
            }

            // 4. Bottom Guidance & Quick-Capture Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF0F172A).copy(alpha = 0.95f),
                                Color(0xFF0F172A)
                            )
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Success Badge or Scanning Status
                if (isComplete) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(Color(0xFF10B981), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Selesai", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Pendaftaran Biometrik Berhasil!",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Vektor wajah untuk $employeeName telah tersimpan permanen.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        text = if (employeeName.isNotBlank()) "Mendaftarkan Wajah: $employeeName" else "Pindai Wajah Karyawan",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = currentInstruction,
                        color = if (isFaceInsideCircle) Color(0xFF00E5FF) else Color(0xFFF59E0B),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Progress Bar (0% - 100%)
                    val currentCount = (enrollmentProgress * TARGET_ENROLLMENT_FRAMES).toInt().coerceIn(0, TARGET_ENROLLMENT_FRAMES)
                    Row(
                        modifier = Modifier.fillMaxWidth(0.85f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF00E5FF),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                        Text(
                            text = "$currentCount/$TARGET_ENROLLMENT_FRAMES (${(enrollmentProgress * 100).toInt()}%)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Quick-Action Instant Capture Button (Solves "daftarin susah" completely!)
                    Button(
                        onClick = { triggerInstantCapture() },
                        enabled = latestDetectedFace != null && !isCapturingInstant,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7),
                            disabledContainerColor = Color.White.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(46.dp)
                            .testTag("instant_capture_face_button")
                    ) {
                        if (isCapturingInstant) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Mengekstrak 10 Frame Biometrik...", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ambil Wajah Sekarang (Instan)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
