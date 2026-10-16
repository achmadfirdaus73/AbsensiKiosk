package com.example.ui.admin

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import kotlinx.coroutines.delay
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
        animationSpec = tween(durationMillis = 150, easing = LinearOutSlowInEasing),
        label = "enrollmentProgressAnim"
    )
    var currentInstruction by remember { mutableStateOf("Posisikan wajah di dalam lingkaran") }
    var isFaceInsideCircle by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var isCapturingInstant by remember { mutableStateOf(false) }
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    var masterEmbeddingJson by remember { mutableStateOf<String?>(null) }

    val collectedVectors = remember { mutableListOf<FloatArray>() }
    var lastCollectionTime by remember { mutableLongStateOf(0L) }
    var bestSnapshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var latestDetectedFace by remember { mutableStateOf<Face?>(null) }
    var latestFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }

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
                    FaceBiometricHelper.calculateSimilarity(v, preliminaryMean) >= 0.45f
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

            val finalBitmap = photoBitmap ?: Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            val photosDir = File(context.filesDir, "employee_photos")
            if (!photosDir.exists()) photosDir.mkdirs()
            val photoFile = File(photosDir, "emp_${System.currentTimeMillis()}.jpg")
            FileOutputStream(photoFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            capturedPhotoPath = photoFile.absolutePath

            delay(600)
            onEnrollmentComplete(photoFile.absolutePath, masterJson)
        }
    }

    fun triggerInstantCapture() {
        val face = latestDetectedFace
        val bmp = latestFrameBitmap
        if (face != null && bmp != null && !isComplete && !isCapturingInstant) {
            isCapturingInstant = true
            coroutineScope.launch {
                val alignedCrop = FaceBiometricHelper.alignAndCropFace(bmp, face)
                val vector = if (alignedCrop != null) {
                    FaceBiometricHelper.extractFeatureVector(face, alignedCrop, context)
                } else FloatArray(0)
                alignedCrop?.recycle()

                if (vector.size == FaceBiometricHelper.EMBEDDING_DIM) {
                    while (collectedVectors.size < TARGET_ENROLLMENT_FRAMES) {
                        collectedVectors.add(vector.copyOf())
                    }
                    enrollmentProgress = 1.0f
                    currentInstruction = "Wajah berhasil diambil! Menyimpan 10 frame..."
                    finishEnrollment(collectedVectors, bmp)
                } else {
                    currentInstruction = "Posisikan wajah lebih jelas lalu coba lagi"
                    isCapturingInstant = false
                }
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

                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }

                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                                if (isComplete) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val frameBmp = try {
                                    val bmp = imageProxy.toBitmap()
                                    if (rotation != 0) {
                                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                        val rotBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                        if (rotBmp != bmp) bmp.recycle()
                                        rotBmp
                                    } else bmp
                                } catch (e: Exception) {
                                    Log.w("FaceIdEnrollment", "toBitmap error: ${e.message}")
                                    null
                                } finally {
                                    imageProxy.close()
                                }

                                if (frameBmp == null) return@setAnalyzer

                                val inputImage = InputImage.fromBitmap(frameBmp, 0)
                                val faces: List<Face> = try {
                                    Tasks.await(faceDetector.process(inputImage))
                                } catch (e: Exception) {
                                    emptyList()
                                }

                                if (faces.isNotEmpty()) {
                                    val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
                                    val box = face.boundingBox

                                    val isGoodSize = box.width() >= 60 && box.height() >= 60
                                    val yaw = Math.abs(face.headEulerAngleY)
                                    val roll = Math.abs(face.headEulerAngleZ)
                                    val isGoodPose = yaw <= 40f && roll <= 35f

                                    latestDetectedFace = face
                                    latestFrameBitmap?.recycle()
                                    latestFrameBitmap = Bitmap.createBitmap(frameBmp)

                                    if (!isGoodSize) {
                                        isFaceInsideCircle = false
                                        currentInstruction = "Dekatkan wajah sedikit ke kamera"
                                    } else if (roll > 35f) {
                                        isFaceInsideCircle = false
                                        currentInstruction = "Posisikan kepala tegak (tidak miring)"
                                    } else if (yaw > 40f) {
                                        isFaceInsideCircle = false
                                        currentInstruction = "Hadap lurus ke arah kamera"
                                    } else {
                                        isFaceInsideCircle = true

                                        val now = System.currentTimeMillis()
                                        if (now - lastCollectionTime >= 80L && enrollmentProgress < 1.0f && !isComplete) {
                                            lastCollectionTime = now

                                            val alignedCrop = FaceBiometricHelper.alignAndCropFace(frameBmp, face)
                                            val vector = if (alignedCrop != null) {
                                                FaceBiometricHelper.extractFeatureVector(face, alignedCrop, ctx)
                                            } else FloatArray(0)
                                            alignedCrop?.recycle()

                                            if (vector.size == FaceBiometricHelper.EMBEDDING_DIM) {
                                                collectedVectors.add(vector)

                                                if (bestSnapshotBitmap == null && yaw <= 18f && roll <= 15f) {
                                                    bestSnapshotBitmap = Bitmap.createBitmap(frameBmp)
                                                }

                                                val count = collectedVectors.size
                                                val newProg = (count.toFloat() / TARGET_ENROLLMENT_FRAMES).coerceIn(0f, 1f)
                                                enrollmentProgress = newProg
                                                currentInstruction = "Merekam wajah: $count/$TARGET_ENROLLMENT_FRAMES frame..."

                                                if (count >= TARGET_ENROLLMENT_FRAMES && !isComplete) {
                                                    currentInstruction = "Selesai! 10 frame biometrik tersimpan..."
                                                    finishEnrollment(collectedVectors, bestSnapshotBitmap ?: latestFrameBitmap)
                                                }
                                            } else {
                                                Log.w("FaceIdEnrollment", "MobileFaceNet vector size: ${vector.size}")
                                            }
                                        }
                                    }
                                } else {
                                    isFaceInsideCircle = false
                                    currentInstruction = "Posisikan wajah di dalam lingkaran"
                                }

                                frameBmp.recycle()
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
                            text = "${collectedVectors.size}/$TARGET_ENROLLMENT_FRAMES (${(enrollmentProgress * 100).toInt()}%)",
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
                            Text("Menyimpan...", color = Color.White, fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Ambil Wajah Sekarang (Instan)",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}
