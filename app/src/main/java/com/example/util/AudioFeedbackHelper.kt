package com.example.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.*

class AudioFeedbackHelper(context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val indonesianLocale = Locale("id", "ID")
                val result = tts?.setLanguage(indonesianLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.US
                }
                isInitialized = true
            }
        }
    }

    fun speak(text: String) {
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AttendanceTTS")
        }
    }

    fun playSuccessBeep() {
        speak("Pendaftaran wajah berhasil")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    fun release() {
        shutdown()
    }
}
