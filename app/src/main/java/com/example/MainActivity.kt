package com.example

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ui.AuraSightScreen
import com.example.ui.theme.MyApplicationTheme
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            // Initialize Android Text-To-Speech engine safely
            textToSpeech = TextToSpeech(this, this)
        } catch (e: Throwable) {
            Log.e("MainActivity", "Failed to create TextToSpeech engine", e)
            textToSpeech = null
        }

        setContent {
            MyApplicationTheme(darkTheme = true, dynamicColor = false) {
                AuraSightScreen(
                    onSpeechTrigger = { speechText ->
                        speak(speechText)
                    }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        try {
            if (status == TextToSpeech.SUCCESS) {
                val indonesianLocale = java.util.Locale("id", "ID")
                val result = textToSpeech?.setLanguage(indonesianLocale)

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("MainActivity", "Indonesian language is not supported or missing data, falling back to default.")
                    textToSpeech?.language = java.util.Locale.getDefault()
                }
                isTtsInitialized = true
                // Initial welcoming voice guidance safely
                speak("Selamat datang di AuraSight. Ketuk layar di mana saja untuk mengambil foto dan menganalisis lingkungan sekitar.")
            } else {
                Log.e("MainActivity", "Initialization of Text-To-Speech failed! Status: $status")
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "Error during TextToSpeech onInit implementation", e)
        }
    }

    private fun speak(text: String) {
        try {
            if (isTtsInitialized && textToSpeech != null) {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AuraSightSpeechId")
            } else {
                Log.w("MainActivity", "Speech request dropped - TTS is not ready or is null")
            }
        } catch (e: Throwable) {
            Log.e("MainActivity", "Speech failed", e)
        }
    }

    override fun onDestroy() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Throwable) {
            Log.e("MainActivity", "Error during TextToSpeech shutdown", e)
        }
        super.onDestroy()
    }
}

