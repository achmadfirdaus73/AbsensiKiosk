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

class MainActivity : ComponentActivity() {
    private lateinit var repository: AttendanceRepository
    private lateinit var audioHelper: AudioFeedbackHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = AttendanceRepository(this)
        audioHelper = AudioFeedbackHelper(this)

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
