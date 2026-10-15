package com.example.util

import android.content.Context
import android.content.res.AssetManager
import android.graphics.*
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

/**
 * Enterprise On-Device Face Recognition & Biometric Engine powered by MobileFaceNet (TFLite).
 * Features:
 * 1. Native MobileFaceNet Deep Neural Network (192-D Embedding Vector)
 * 2. 2D Affine Transformation (Canonical 112x112 Eye Alignment & Normalization)
 * 3. Active Liveness Detection (Mandatory Dynamic Blink or Smile - Photo-Proof)
 * 4. Safe Mode: If TFLite model is not loaded, recognition is disabled to prevent false matches
 * 5. Hardware-Backed AES-256-GCM Encryption for stored biometrics
 */
object FaceBiometricHelper {
    private val gson = Gson()
    private const val TAG = "FaceBiometricHelper"

    // Standard MobileFaceNet input and output specifications
    const val EMBEDDING_DIM = 192
    const val CANONICAL_IMAGE_SIZE = 112

    private var tfliteInterpreter: Interpreter? = null
    private var isModelLoaded = false
    private val modelLock = Any()
    private var applicationContext: Context? = null

    data class FaceInfo(
        val boundingBox: Rect,
        val mlKitFace: Face? = null,
        val leftEye: PointF? = null,
        val rightEye: PointF? = null,
        val leftEyeOpenProb: Float? = mlKitFace?.leftEyeOpenProbability,
        val rightEyeOpenProb: Float? = mlKitFace?.rightEyeOpenProbability,
        val smileProb: Float? = mlKitFace?.smilingProbability,
        val headYaw: Float = mlKitFace?.headEulerAngleY ?: 0f,
        val headRoll: Float = mlKitFace?.headEulerAngleZ ?: 0f,
        val trackingId: Int? = mlKitFace?.trackingId
    ) {
        companion object {
            fun fromMlKit(face: Face): FaceInfo {
                return FaceInfo(
                    boundingBox = face.boundingBox,
                    mlKitFace = face,
                    leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                    rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
                    leftEyeOpenProb = face.leftEyeOpenProbability,
                    rightEyeOpenProb = face.rightEyeOpenProbability,
                    smileProb = face.smilingProbability,
                    headYaw = face.headEulerAngleY,
                    headRoll = face.headEulerAngleZ,
                    trackingId = face.trackingId
                )
            }
        }
    }

    val biometricDetectorOptions: FaceDetectorOptions by lazy {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.12f)
            .enableTracking()
            .build()
    }

    val fastDetectorOptions: FaceDetectorOptions by lazy {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.10f)
            .enableTracking()
            .build()
    }

    enum class LivenessStatus {
        WAITING_FOR_ACTION,       // Silakan Kedipkan Mata atau Tersenyum
        VERIFIED_REAL_PERSON,     // Manusia Asli Terverifikasi
        SUSPECTED_SPOOF_PHOTO     // Terdeteksi Foto Kertas / Layar Diam
    }

    /**
     * Active Anti-Spoofing Presentation Attack Detector.
     * Prevents printed paper photos, cardboard cutouts, and smartphone screens from spoofing attendance.
     * Requires genuine dynamic life signals (live blink sequence or natural neutral-to-smile transition).
     * Static photos can NEVER pass because they have static facial features without dynamic transitions.
     */
    class AntiSpoofTracker {
        private var wasEyesOpen = false
        private var wasEyesClosed = false
        private var wasNeutralFace = false
        private var firstSeenTime = 0L
        private var lastEyeOpenProb = -1f
        private var staticFrameCount = 0
        private var lastTrackingId: Int? = null
        private var verifiedExpiryTime = 0L

        var isLiveVerified = false
            private set

        fun reset() {
            wasEyesOpen = false
            wasEyesClosed = false
            wasNeutralFace = false
            firstSeenTime = 0L
            lastEyeOpenProb = -1f
            staticFrameCount = 0
            isLiveVerified = false
            verifiedExpiryTime = 0L
            lastTrackingId = null
        }

        fun evaluate(face: Face): Pair<LivenessStatus, String> {
            return evaluate(FaceInfo.fromMlKit(face))
        }

        fun evaluate(faceInfo: FaceInfo): Pair<LivenessStatus, String> {
            val now = System.currentTimeMillis()

            // If tracking ID changed (new face entered frame), reset immediately
            if (faceInfo.trackingId != null) {
                if (lastTrackingId != null && lastTrackingId != faceInfo.trackingId) {
                    reset()
                }
                lastTrackingId = faceInfo.trackingId
            }

            if (firstSeenTime == 0L) {
                firstSeenTime = now
            }

            // Extreme tilt warning
            if (abs(faceInfo.headYaw) > 24f) return Pair(LivenessStatus.WAITING_FOR_ACTION, "Tatap lurus ke arah kamera")
            if (abs(faceInfo.headRoll) > 20f) return Pair(LivenessStatus.WAITING_FOR_ACTION, "Tegakkan posisi kepala")

            val left = faceInfo.leftEyeOpenProb ?: -1f
            val right = faceInfo.rightEyeOpenProb ?: -1f
            val smile = faceInfo.smileProb ?: -1f

            // 1. Check if previously verified and still within the 4.0s valid window
            if (isLiveVerified) {
                if (now <= verifiedExpiryTime) {
                    return Pair(LivenessStatus.VERIFIED_REAL_PERSON, "✓ Wajah Asli Terverifikasi")
                } else {
                    isLiveVerified = false
                    wasEyesOpen = false
                    wasEyesClosed = false
                    wasNeutralFace = false
                }
            }

            // 2. Active Expression Verification: REQUIRES dynamic transition from neutral to smile
            // (A static photo with a printed smile never transitions dynamically, so static paper photos fail)
            if (smile >= 0f) {
                if (smile < 0.35f) {
                    wasNeutralFace = true
                } else if (smile > 0.60f && wasNeutralFace) {
                    isLiveVerified = true
                    verifiedExpiryTime = now + 4000L
                    return Pair(LivenessStatus.VERIFIED_REAL_PERSON, "✓ Senyuman Nyata Terverifikasi")
                }
            }

            // 3. Dynamic Human Blink Cycle (Open > 0.60 -> Closed < 0.28 -> Open > 0.60)
            if (left >= 0f && right >= 0f) {
                val avg = (left + right) / 2f

                // Track static frames (printed paper photos have near-zero natural eye variance)
                if (lastEyeOpenProb >= 0f && abs(avg - lastEyeOpenProb) < 0.018f) {
                    staticFrameCount++
                } else {
                    staticFrameCount = max(0, staticFrameCount - 1)
                }
                lastEyeOpenProb = avg

                if (avg > 0.60f) {
                    if (wasEyesClosed && wasEyesOpen) {
                        // Human completed full natural blink cycle!
                        isLiveVerified = true
                        verifiedExpiryTime = now + 4000L
                        return Pair(LivenessStatus.VERIFIED_REAL_PERSON, "✓ Kedipan Asli Terverifikasi")
                    }
                    wasEyesOpen = true
                } else if (avg < 0.28f && wasEyesOpen) {
                    wasEyesClosed = true
                }

                // If face has been shown for > 800ms with completely static features without blinking/smiling -> FLAG AS STATIC SPOOF
                if (now - firstSeenTime > 800 && staticFrameCount >= 5 && !isLiveVerified) {
                    return Pair(LivenessStatus.SUSPECTED_SPOOF_PHOTO, "⛔ Foto Diam Terdeteksi (Silakan kedip/senyum)")
                }
            }

            return Pair(LivenessStatus.WAITING_FOR_ACTION, "👁️ Silakan Kedipkan Mata atau Tersenyum")
        }
    }

    /**
     * Initializes the MobileFaceNet TFLite interpreter.
     */
    fun init(context: Context): Boolean {
        applicationContext = context.applicationContext
        synchronized(modelLock) {
            if (isModelLoaded && tfliteInterpreter != null) return true
            return try {
                val candidates = listOf("mobile_face_net.tflite", "mobilefacenet.tflite")
                var mappedBuffer: MappedByteBuffer? = null
                for (name in candidates) {
                    try {
                        mappedBuffer = loadModelFile(context.assets, name)
                        break
                    } catch (_: Exception) {}
                }

                if (mappedBuffer != null) {
                    val options = Interpreter.Options().apply {
                        setNumThreads(4)
                    }
                    tfliteInterpreter = Interpreter(mappedBuffer, options)
                    isModelLoaded = true
                    Log.i(TAG, "MobileFaceNet TFLite successfully loaded ($EMBEDDING_DIM-D).")
                    true
                } else {
                    Log.e(TAG, "MobileFaceNet model file not found in assets. Recognition disabled for security.")
                    isModelLoaded = false
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing MobileFaceNet: ${e.message}")
                isModelLoaded = false
                false
            }
        }
    }

    fun isModelReady(context: Context? = null): Boolean {
        synchronized(modelLock) {
            if (isModelLoaded && tfliteInterpreter != null) return true
            val ctx = context ?: applicationContext ?: return false
            return init(ctx)
        }
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, filePath: String): MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(filePath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Performs 2D Affine Transformation to align and crop face into canonical 112x112 RGB image.
     * Aligns eye centers horizontally, normalizes inter-ocular distance,
     * and equalizes lighting for illumination invariance.
     */
    fun alignAndCropFace(sourceBitmap: Bitmap, face: Face): Bitmap? {
        return alignAndCropFace(sourceBitmap, FaceInfo.fromMlKit(face))
    }

    fun alignAndCropFace(sourceBitmap: Bitmap, faceInfo: FaceInfo): Bitmap? {
        if (sourceBitmap.isRecycled || sourceBitmap.width <= 0 || sourceBitmap.height <= 0) return null

        val leftEye = faceInfo.leftEye ?: faceInfo.mlKitFace?.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = faceInfo.rightEye ?: faceInfo.mlKitFace?.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        return try {
            val rawCrop = if (leftEye != null && rightEye != null) {
                // 1. Calculate eye angle and midpoint
                val deltaX = rightEye.x - leftEye.x
                val deltaY = rightEye.y - leftEye.y
                val currentEyeDist = hypot(deltaX, deltaY)
                val eyeAngleDegrees = Math.toDegrees(atan2(deltaY.toDouble(), deltaX.toDouble())).toFloat()
                val eyeMidX = (leftEye.x + rightEye.x) / 2f
                val eyeMidY = (leftEye.y + rightEye.y) / 2f

                // 2. Canonical target parameters for 112x112 MobileFaceNet
                val targetEyeDist = 42.0f // 42px inter-ocular distance inside 112x112
                val targetEyeMidX = 56.0f // Center horizontally (112 / 2)
                val targetEyeMidY = 45.0f // Golden ratio eye level in 112x112 face crop

                val scale = targetEyeDist / max(10f, currentEyeDist)

                // 3. Transformation Matrix (Translate to origin -> Rotate -> Scale -> Translate to target)
                val matrix = Matrix().apply {
                    postTranslate(-eyeMidX, -eyeMidY)
                    postRotate(-eyeAngleDegrees)
                    postScale(scale, scale)
                    postTranslate(targetEyeMidX, targetEyeMidY)
                }

                // Render aligned 112x112 canvas
                val alignedBitmap = Bitmap.createBitmap(CANONICAL_IMAGE_SIZE, CANONICAL_IMAGE_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(alignedBitmap)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                canvas.drawBitmap(sourceBitmap, matrix, paint)
                alignedBitmap
            } else {
                // Bounding Box Crop with proportional margin
                val box = faceInfo.boundingBox
                val marginX = (box.width() * 0.15f).toInt()
                val marginY = (box.height() * 0.15f).toInt()
                val left = max(0, box.left - marginX)
                val top = max(0, box.top - marginY)
                val width = min(sourceBitmap.width - left, box.width() + marginX * 2)
                val height = min(sourceBitmap.height - top, box.height() + marginY * 2)

                if (width > 0 && height > 0) {
                    val rawBoxCrop = Bitmap.createBitmap(sourceBitmap, left, top, width, height)
                    val scaled = Bitmap.createScaledBitmap(rawBoxCrop, CANONICAL_IMAGE_SIZE, CANONICAL_IMAGE_SIZE, true)
                    if (scaled != rawBoxCrop) rawBoxCrop.recycle()
                    scaled
                } else {
                    Bitmap.createScaledBitmap(sourceBitmap, CANONICAL_IMAGE_SIZE, CANONICAL_IMAGE_SIZE, true)
                }
            }

            // Apply Adaptive Illumination Equalization
            val balancedBitmap = equalizeIllumination(rawCrop)
            if (balancedBitmap != rawCrop) rawCrop.recycle()
            balancedBitmap
        } catch (e: Exception) {
            Log.w(TAG, "Error aligning and cropping face: ${e.message}")
            null
        }
    }

    /**
     * Adaptive Illumination Equalization (Contrast Stretch & Dynamic Gamma Correction).
     */
    fun equalizeIllumination(alignedBitmap: Bitmap): Bitmap {
        val w = alignedBitmap.width
        val h = alignedBitmap.height
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        alignedBitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var minLuma = 255f
        var maxLuma = 0f
        var sumLuma = 0f

        for (p in pixels) {
            val r = (p shr 16 and 0xFF)
            val g = (p shr 8 and 0xFF)
            val b = (p and 0xFF)
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            if (luma < minLuma) minLuma = luma
            if (luma > maxLuma) maxLuma = luma
            sumLuma += luma
        }

        val range = max(20f, maxLuma - minLuma)
        val avgLuma = sumLuma / pixels.size
        val gamma = (log10(128.0 / 255.0) / log10(max(0.08, avgLuma.toDouble() / 255.0))).coerceIn(0.65, 1.55)

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24 and 0xFF)
            val r = (p shr 16 and 0xFF)
            val g = (p shr 8 and 0xFF)
            val b = (p and 0xFF)

            val rNorm = ((r - minLuma) / range).coerceIn(0f, 1f).toDouble()
            val gNorm = ((g - minLuma) / range).coerceIn(0f, 1f).toDouble()
            val bNorm = ((b - minLuma) / range).coerceIn(0f, 1f).toDouble()

            val rCorr = (rNorm.pow(gamma) * 255.0).toInt().coerceIn(0, 255)
            val gCorr = (gNorm.pow(gamma) * 255.0).toInt().coerceIn(0, 255)
            val bCorr = (bNorm.pow(gamma) * 255.0).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (rCorr shl 16) or (gCorr shl 8) or bCorr
        }

        output.setPixels(pixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Converts aligned 112x112 Bitmap to normalized ByteBuffer for MobileFaceNet.
     * Shape: [1, 112, 112, 3], Float32 normalized with (pixel - 127.5) / 128.0.
     */
    fun convertBitmapToByteBuffer(alignedBitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(1 * CANONICAL_IMAGE_SIZE * CANONICAL_IMAGE_SIZE * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }

        val intValues = IntArray(CANONICAL_IMAGE_SIZE * CANONICAL_IMAGE_SIZE)
        alignedBitmap.getPixels(intValues, 0, CANONICAL_IMAGE_SIZE, 0, 0, CANONICAL_IMAGE_SIZE, CANONICAL_IMAGE_SIZE)

        for (pixelValue in intValues) {
            val r = (pixelValue shr 16 and 0xFF)
            val g = (pixelValue shr 8 and 0xFF)
            val b = (pixelValue and 0xFF)

            // Standard MobileFaceNet normalization: (pixel - 127.5) / 128.0
            inputBuffer.putFloat((r - 127.5f) / 128.0f)
            inputBuffer.putFloat((g - 127.5f) / 128.0f)
            inputBuffer.putFloat((b - 127.5f) / 128.0f)
        }

        inputBuffer.rewind()
        return inputBuffer
    }

    /**
     * Extracts a 192-Dimensional Deep Feature Vector using the real MobileFaceNet TFLite model.
     * Safety guarantee: If model file is not loaded, returns null (or empty) so no false recognition occurs.
     */
    fun extractFeatureVector(face: Face, alignedBitmap: Bitmap? = null, context: Context? = null): FloatArray {
        return extractFeatureVector(FaceInfo.fromMlKit(face), alignedBitmap, context)
    }

    fun extractFeatureVector(faceInfo: FaceInfo, alignedBitmap: Bitmap? = null, context: Context? = null): FloatArray {
        val targetContext = context ?: applicationContext
        if (alignedBitmap == null || alignedBitmap.isRecycled) {
            return FloatArray(0)
        }

        if (!isModelReady(targetContext)) {
            Log.w(TAG, "MobileFaceNet model is not loaded. Recognition safely suspended.")
            return FloatArray(0)
        }

        return try {
            val inputBuffer = convertBitmapToByteBuffer(alignedBitmap)
            val outputArray = Array(1) { FloatArray(EMBEDDING_DIM) }

            synchronized(modelLock) {
                tfliteInterpreter?.run(inputBuffer, outputArray)
            }

            val embedding = outputArray[0]
            l2Normalize(embedding)
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "Inference error during MobileFaceNet execution: ${e.message}")
            FloatArray(0)
        }
    }

    fun l2Normalize(vector: FloatArray) {
        var sumSquares = 0f
        for (v in vector) {
            sumSquares += v * v
        }
        val norm = sqrt(max(1e-10f, sumSquares))
        for (i in vector.indices) {
            vector[i] /= norm
        }
    }

    /**
     * Extracts embedding from a photo file or Uri using ML Kit Face Detection + MobileFaceNet.
     * If model is not available or face cannot be detected, returns null.
     */
    suspend fun extractEmbeddingFromUri(context: Context, photoUriStr: String): FloatArray? {
        init(context)
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = loadAndCorrectBitmap(context, photoUriStr) ?: return@withContext null
                val image = InputImage.fromBitmap(bitmap, 0)
                val detector = FaceDetection.getClient(biometricDetectorOptions)
                val faces: List<Face> = try {
                    val task = detector.process(image)
                    Tasks.await(task)
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit photo processing notice: ${e.message}")
                    emptyList()
                } finally {
                    try { detector.close() } catch (_: Exception) {}
                }

                val vector = if (faces.isNotEmpty()) {
                    val primaryFace = faces.maxByOrNull { f -> f.boundingBox.width() * f.boundingBox.height() } ?: faces.first()
                    val alignedCrop = alignAndCropFace(bitmap, primaryFace)
                    val feat = if (alignedCrop != null) {
                        extractFeatureVector(primaryFace, alignedCrop, context)
                    } else FloatArray(0)
                    alignedCrop?.recycle()
                    if (feat.isNotEmpty() && feat.size == EMBEDDING_DIM) feat else null
                } else null

                bitmap.recycle()
                vector
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting embedding from uri $photoUriStr: ${e.message}")
                null
            }
        }
    }

    /**
     * Extracts embedding directly from a Bitmap.
     */
    suspend fun extractEmbeddingFromBitmap(bitmap: Bitmap): FloatArray? {
        val ctx = applicationContext
        return if (ctx != null) extractEmbeddingFromBitmap(ctx, bitmap) else null
    }

    suspend fun extractEmbeddingFromBitmap(context: Context, bitmap: Bitmap): FloatArray? {
        init(context)
        return withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val detector = FaceDetection.getClient(biometricDetectorOptions)
                val faces: List<Face> = try {
                    val task = detector.process(image)
                    Tasks.await(task)
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit bitmap processing error: ${e.message}")
                    emptyList()
                } finally {
                    try { detector.close() } catch (_: Exception) {}
                }

                if (faces.isNotEmpty()) {
                    val primaryFace = faces.maxByOrNull { f -> f.boundingBox.width() * f.boundingBox.height() } ?: faces.first()
                    val alignedCrop = alignAndCropFace(bitmap, primaryFace)
                    val feat = if (alignedCrop != null) {
                        extractFeatureVector(primaryFace, alignedCrop, context)
                    } else FloatArray(0)
                    alignedCrop?.recycle()
                    if (feat.isNotEmpty() && feat.size == EMBEDDING_DIM) feat else null
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting embedding from bitmap: ${e.message}")
                null
            }
        }
    }

    /**
     * Compares two biometric feature vectors using Cosine similarity on the unit hypersphere.
     * Returns similarity score (0.0 to 1.0).
     */
    fun calculateSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.isEmpty() || v2.isEmpty()) return 0f
        if (v1.size != EMBEDDING_DIM || v2.size != EMBEDDING_DIM) return 0f

        var dot = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in 0 until EMBEDDING_DIM) {
            val a = v1[i]
            val b = v2[i]
            dot += a * b
            norm1 += a * a
            norm2 += b * b
        }

        val cosineSim = if (norm1 > 0f && norm2 > 0f) {
            (dot / (sqrt(norm1) * sqrt(norm2))).coerceIn(-1f, 1f)
        } else {
            0f
        }

        return cosineSim.coerceIn(0f, 1.0f)
    }

    /**
     * Serializes and encrypts biometric embedding with AES-256-GCM.
     */
    fun serializeEmbedding(features: FloatArray): String {
        val plainJson = gson.toJson(features)
        return BiometricEncryptionHelper.encrypt(plainJson)
    }

    /**
     * Decrypts and deserializes biometric embedding from stored encrypted ciphertext or legacy JSON.
     */
    fun deserializeEmbedding(storedData: String?): FloatArray? {
        if (storedData.isNullOrBlank()) return null
        val decryptedJson = BiometricEncryptionHelper.decrypt(storedData) ?: return null
        return try {
            val type = object : TypeToken<FloatArray>() {}.type
            gson.fromJson<FloatArray>(decryptedJson, type)
        } catch (_: Exception) {
            null
        }
    }

    fun loadAndCorrectBitmap(context: Context, uriString: String): Bitmap? {
        return try {
            if (uriString.startsWith("/")) {
                val file = File(uriString)
                if (!file.exists()) return null
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
                val exif = ExifInterface(file.absolutePath)
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                rotateBitmap(bitmap, orientation)
            } else {
                val uri = Uri.parse(uriString)
                var inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) return null

                inputStream = context.contentResolver.openInputStream(uri)
                val exif = if (inputStream != null) ExifInterface(inputStream) else null
                val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
                inputStream?.close()

                rotateBitmap(bitmap, orientation)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from $uriString", e)
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }
}
