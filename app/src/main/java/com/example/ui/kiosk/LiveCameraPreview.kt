package com.example.ui.kiosk

import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.util.FaceBiometricHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import java.util.concurrent.Executors
import kotlin.math.max

@OptIn(ExperimentalGetImage::class)
@Composable
fun LiveCameraPreview(
    modifier: Modifier = Modifier,
    useFrontCamera: Boolean = true,
    onFacesDetected: (List<Face>) -> Unit = {},
    onFaceBoundsTracked: (RectF?) -> Unit = {},
    onFaceBiometrics: (Face, FloatArray) -> Unit = { _, _ -> },
    onFaceBiometricsInfo: (FaceBiometricHelper.FaceInfo, FloatArray) -> Unit = { _, _ -> },
    onModelDownloadStatusChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraError by remember { mutableStateOf(false) }

    // Setup high-performance Accurate Real-Time ML Kit Face Detector
    val faceDetector: FaceDetector = remember {
        FaceDetection.getClient(FaceBiometricHelper.biometricDetectorOptions)
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var lastLogTime by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            faceDetector.close()
            cameraExecutor.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
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
                        
                        // 1. Camera Preview Stream
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        // 2. Real-Time Face Recognition & Analysis Stream
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val rotation = imageProxy.imageInfo.rotationDegrees
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    rotation
                                )
                                faceDetector.process(image)
                                    .addOnSuccessListener { faces ->
                                        onModelDownloadStatusChanged(false)
                                        onFacesDetected(faces)
                                        if (faces.isNotEmpty()) {
                                            val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces.first()
                                            
                                            // Compute real-time screen tracking coordinates mapped to PreviewView
                                            val previewWidth = previewView.width.toFloat()
                                            val previewHeight = previewView.height.toFloat()
                                            val uprightWidth = if (rotation == 90 || rotation == 270) mediaImage.height.toFloat() else mediaImage.width.toFloat()
                                            val uprightHeight = if (rotation == 90 || rotation == 270) mediaImage.width.toFloat() else mediaImage.height.toFloat()

                                            if (previewWidth > 0 && previewHeight > 0 && uprightWidth > 0 && uprightHeight > 0) {
                                                val scale = max(previewWidth / uprightWidth, previewHeight / uprightHeight)
                                                val offsetX = (previewWidth - uprightWidth * scale) / 2f
                                                val offsetY = (previewHeight - uprightHeight * scale) / 2f
                                                val box = primaryFace.boundingBox

                                                val tracked = if (useFrontCamera) {
                                                    val l = previewWidth - (box.right * scale + offsetX)
                                                    val r = previewWidth - (box.left * scale + offsetX)
                                                    val t = box.top * scale + offsetY
                                                    val b = box.bottom * scale + offsetY
                                                    RectF(l, t, r, b)
                                                } else {
                                                    val l = box.left * scale + offsetX
                                                    val r = box.right * scale + offsetX
                                                    val t = box.top * scale + offsetY
                                                    val b = box.bottom * scale + offsetY
                                                    RectF(l, t, r, b)
                                                }
                                                onFaceBoundsTracked(tracked)
                                            }

                                            // Pose Quality Filter: Only process biometrics when face orientation is within good range
                                            val yaw = Math.abs(primaryFace.headEulerAngleY)
                                            val roll = Math.abs(primaryFace.headEulerAngleZ)
                                            if (yaw <= 28f && roll <= 25f) {
                                                // Perform Face Alignment & Canonical 112x112 Cropping
                                                val frameBitmap = try {
                                                    val bmp = imageProxy.toBitmap()
                                                    if (rotation != 0) {
                                                        val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                                                        val rotBmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                                        if (rotBmp != bmp) bmp.recycle()
                                                        rotBmp
                                                    } else bmp
                                                } catch (_: Exception) {
                                                    null
                                                }

                                                val faceInfo = FaceBiometricHelper.FaceInfo.fromMlKit(primaryFace)
                                                val alignedCrop = if (frameBitmap != null) {
                                                    FaceBiometricHelper.alignAndCropFace(frameBitmap, faceInfo)
                                                } else null

                                                val vector = FaceBiometricHelper.extractFeatureVector(faceInfo, alignedCrop, ctx)
                                                alignedCrop?.recycle()
                                                frameBitmap?.recycle()

                                                onFaceBiometrics(primaryFace, vector)
                                                onFaceBiometricsInfo(faceInfo, vector)
                                            }
                                        } else {
                                            onFaceBoundsTracked(null)
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        val isDownloading = (e is com.google.mlkit.common.MlKitException && (
                                            e.errorCode == 14 ||
                                            e.message?.contains("face module", ignoreCase = true) == true ||
                                            e.message?.contains("download", ignoreCase = true) == true
                                        )) || (e.message?.contains("face module", ignoreCase = true) == true)

                                        if (isDownloading) {
                                            onModelDownloadStatusChanged(true)
                                        }
                                        val now = System.currentTimeMillis()
                                        if (now - lastLogTime > 6000) {
                                            Log.w("LiveCameraPreview", "Camera face analysis notice: ${e.message}")
                                            lastLogTime = now
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
                    } catch (exc: Exception) {
                        Log.e("LiveCameraPreview", "Camera bind failed", exc)
                        hasCameraError = true
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        if (hasCameraError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Mode Kamera Kiosk Aktif",
                    color = Color(0xFF00E5FF)
                )
            }
        }
    }
}
