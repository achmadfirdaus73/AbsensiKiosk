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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.*
import com.example.util.AudioFeedbackHelper
import com.example.util.FaceBiometricHelper
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

    var useFrontCamera by remember { mutableStateOf(true) }
    var enrollmentProgress by remember { mutableFloatStateOf(0f) }
    var currentInstruction by remember { mutableStateOf("Posisikan wajah di dalam lingkaran") }
    var isFaceInsideCircle by remember { mutableStateOf(false) }
    var isComplete by remember { mutableStateOf(false) }
    var capturedPhotoPath by remember { mutableStateOf<String?>(null) }
    var masterEmbeddingJson by remember { mutableStateOf<String?>(null) }

    val collectedVectors = remember { mutableListOf<FloatArray>() }
    var bestSnapshotBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val faceDetector: FaceDetector = remember {
        FaceDetection.getClient(FaceBiometricHelper.biometricDetectorOptions)
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            faceDetector.close()
            cameraExecutor.shutdown()
            audioHelper.release()
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
                .background(Color(0xFF0F172A)) // Dark immersive Apple Face ID backdrop
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
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !isComplete) {
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                                    faceDetector.process(inputImage)
                                        .addOnSuccessListener { faces ->
                                            if (faces.isNotEmpty()) {
                                                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
                                                val box = face.boundingBox

                                                // Check if face is well-centered and of good size
                                                val isGoodSize = box.width() > 180 && box.height() > 180
                                                isFaceInsideCircle = isGoodSize

                                                if (isGoodSize && enrollmentProgress < 1.0f) {
                                                    // Extract aligned canonical crop for higher-accuracy master embedding
                                                    val frameBmp = try {
                                                        val bmp = imageProxy.toBitmap()
                                                        if (rotation != 0) {
                                                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                                            val rotBmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                                            if (rotBmp != bmp) bmp.recycle()
                                                            rotBmp
                                                        } else bmp
                                                    } catch (_: Exception) {
                                                        null
                                                    }

                                                    val alignedCrop = if (frameBmp != null) {
                                                        FaceBiometricHelper.alignAndCropFace(frameBmp, face)
                                                    } else null

                                                    val vector = FaceBiometricHelper.extractFeatureVector(face, alignedCrop)
                                                    collectedVectors.add(vector)

                                                    // Grab a clean frame snapshot for profile avatar
                                                    if (bestSnapshotBitmap == null && enrollmentProgress > 0.4f && frameBmp != null) {
                                                        bestSnapshotBitmap = Bitmap.createBitmap(frameBmp)
                                                    }

                                                    alignedCrop?.recycle()
                                                    frameBmp?.recycle()

                                                    val newProg = (collectedVectors.size / 12f).coerceIn(0f, 1f)
                                                    enrollmentProgress = newProg

                                                    currentInstruction = when {
                                                        newProg < 0.35f -> "Tahan dan tatap kamera lurus..."
                                                        newProg < 0.70f -> "Bagus! Tetap stabil..."
                                                        newProg < 0.95f -> "Hampir selesai, memproses biometrik..."
                                                        else -> "Selesai!"
                                                    }

                                                    if (newProg >= 1.0f && !isComplete) {
                                                        isComplete = true
                                                        coroutineScope.launch {
                                                            audioHelper.playSuccessBeep()

                                                            // Fuse collected multi-frame vectors into pristine master embedding
                                                            val masterVector = FloatArray(128)
                                                            for (v in collectedVectors) {
                                                                for (i in 0 until 128) {
                                                                    masterVector[i] += v[i]
                                                                }
                                                            }
                                                            for (i in 0 until 128) {
                                                                masterVector[i] /= collectedVectors.size.toFloat()
                                                            }
                                                            // Normalize master vector
                                                            var sumSq = 0f
                                                            for (valX in masterVector) sumSq += valX * valX
                                                            val norm = kotlin.math.sqrt(maxOf(1e-8f, sumSq))
                                                            for (i in 0 until 128) masterVector[i] /= norm

                                                            val masterJson = FaceBiometricHelper.serializeEmbedding(masterVector)
                                                            masterEmbeddingJson = masterJson

                                                            // Save the snapshot bitmap
                                                            val finalBitmap = bestSnapshotBitmap ?: Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                                                            val photosDir = File(context.filesDir, "employee_photos")
                                                            if (!photosDir.exists()) photosDir.mkdirs()
                                                            val photoFile = File(photosDir, "emp_${System.currentTimeMillis()}.jpg")
                                                            FileOutputStream(photoFile).use { out ->
                                                                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                                                            }
                                                            capturedPhotoPath = photoFile.absolutePath

                                                            delay(800)
                                                            onEnrollmentComplete(photoFile.absolutePath, masterJson)
                                                        }
                                                    }
                                                } else if (!isGoodSize) {
                                                    currentInstruction = "Dekatkan wajah ke dalam lingkaran"
                                                }
                                            } else {
                                                isFaceInsideCircle = false
                                                currentInstruction = "Posisikan wajah di dalam lingkaran"
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
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
                val center = Offset(size.width / 2f, size.height * 0.42f)
                val radius = size.width * 0.36f

                // Draw 60 surrounding radial tick segments
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
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = Color.White)
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.50f),
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.20f))
                ) {
                    Text(
                        text = "Apple Face ID Enrollment",
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
                ) {
                    Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Ganti Kamera", tint = Color.White)
                }
            }

            // 4. Bottom Guidance & Progress Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xFF0F172A).copy(alpha = 0.95f), Color(0xFF0F172A))
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 32.dp),
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
                        fontSize = 18.sp,
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress Bar (0% - 100%)
                    Row(
                        modifier = Modifier.fillMaxWidth(0.85f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { enrollmentProgress },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF00E5FF),
                            trackColor = Color.White.copy(alpha = 0.15f),
                        )
                        Text(
                            text = "${(enrollmentProgress * 100).toInt()}%",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
