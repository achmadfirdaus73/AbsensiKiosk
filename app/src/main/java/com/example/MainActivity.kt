package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.data.repository.AttendanceRepository
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.kiosk.KioskScreen
import com.example.ui.theme.DarkBg
import com.example.ui.theme.SmartPresensiKioskTheme
import com.example.util.AudioFeedbackHelper
import com.example.util.FaceBiometricHelper

class MainActivity : ComponentActivity() {
    private lateinit var repository: AttendanceRepository
    private lateinit var audioHelper: AudioFeedbackHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = AttendanceRepository(this)
        audioHelper = AudioFeedbackHelper(this)

        // Proactively ensure ML Kit Face Detection module is requested and downloaded via Google Play Services
        try {
            val moduleInstallClient = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(this)
            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(FaceBiometricHelper.biometricDetectorOptions)
            val request = com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder()
                .addApi(detector)
                .build()

            moduleInstallClient.areModulesAvailable(detector)
                .addOnSuccessListener { response ->
                    if (!response.areModulesAvailable()) {
                        moduleInstallClient.installModules(request)
                            .addOnSuccessListener {
                                android.util.Log.i("MainActivity", "Proactively initiated ML Kit Face module download via ModuleInstallClient")
                            }
                            .addOnFailureListener { e ->
                                android.util.Log.w("MainActivity", "ModuleInstallClient notice: ${e.message}")
                            }
                    }
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("MainActivity", "areModulesAvailable notice: ${e.message}")
                }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "ModuleInstall init notice: ${e.message}")
        }

        setContent {
            SmartPresensiKioskTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBg
                ) {
                    var currentScreen by remember { mutableStateOf("KIOSK") }

                    when (currentScreen) {
                        "KIOSK" -> {
                            KioskScreen(
                                repository = repository,
                                audioHelper = audioHelper,
                                onNavigateToAdmin = { currentScreen = "ADMIN" }
                            )
                        }
                        "ADMIN" -> {
                            AdminDashboardScreen(
                                repository = repository,
                                onNavigateBack = { currentScreen = "KIOSK" }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioHelper.shutdown()
    }
}
