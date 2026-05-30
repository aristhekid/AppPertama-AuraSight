package com.example.ui

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.api.AuraSightResult
import com.example.data.api.Content
import com.example.data.api.GenerateContentRequest
import com.example.data.api.GenerationConfig
import com.example.data.api.InlineData
import com.example.data.api.Part
import com.example.data.api.ResponseSchema
import com.example.data.api.RetrofitClient
import com.example.data.api.SchemaProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed interface AuraSightState {
    object Idle : AuraSightState
    object Loading : AuraSightState
    data class Success(val result: AuraSightResult) : AuraSightState
    data class Error(val message: String) : AuraSightState
}

class AuraSightViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<AuraSightState>(AuraSightState.Idle)
    val uiState: StateFlow<AuraSightState> = _uiState.asStateFlow()

    // Callback to trigger TTS speech from ViewModel in UI
    var onSpeechTrigger: ((String) -> Unit)? = null

    fun analyzeImage(bitmap: Bitmap) {
        _uiState.value = AuraSightState.Loading
        
        // Speak initial loading alert immediately
        onSpeechTrigger?.invoke("Sedang mengambil foto dan menganalisis lingkungan sekitar, mohon tunggu sebentar...")

        viewModelScope.launch {
            try {
                // Perform compression and base64 encoding in IO dispatchers
                val compressedBytes = withContext(Dispatchers.IO) {
                    val outputStream = ByteArrayOutputStream()
                    // Minimum quality (quality: 0.1) as requested.
                    // Scale parameter of compress is 0-100, where 10 equals 10% (0.1 quality)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 10, outputStream)
                    outputStream.toByteArray()
                }

                val base64Image = withContext(Dispatchers.Default) {
                    Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
                }

                val promptText = "Analisis gambar yang dikirimkan oleh pengguna secara objektif, singkat, padat, dan mengutamakan keselamatan. Gunakan bahasa Indonesia yang natural dan ramah untuk Text-to-Speech (TTS)."

                val requestBody = GenerateContentRequest(
                    contents = listOf(
                        Content(
                            parts = listOf(
                                Part(text = promptText),
                                Part(inlineData = InlineData(mimeType = "image/jpeg", data = base64Image))
                            )
                        )
                    ),
                    generationConfig = GenerationConfig(
                        responseMimeType = "application/json",
                        responseSchema = ResponseSchema(
                            type = "OBJECT",
                            properties = mapOf(
                                "scene_description" to SchemaProperty(
                                    type = "STRING",
                                    description = "Deskripsi singkat 1 kalimat tentang lingkungan di depan pengguna."
                                ),
                                "spatial_guidance" to SchemaProperty(
                                    type = "STRING",
                                    description = "Informasi posisi objek penting relatif terhadap pengguna (kiri/kanan/depan)."
                                ),
                                "smart_warning" to SchemaProperty(
                                    type = "STRING",
                                    description = "Peringatan darurat jika ada rintangan berbahaya atau sangat dekat."
                                ),
                                "quick_text_reader" to SchemaProperty(
                                    type = "STRING",
                                    description = "Teks pendek yang terbaca dari papan petunjuk, nomor ruangan, atau tanda peringatan."
                                )
                            ),
                            required = listOf("scene_description", "spatial_guidance", "smart_warning", "quick_text_reader")
                        ),
                        temperature = 0.4
                    ),
                    systemInstruction = Content(
                        parts = listOf(
                            Part(text = "Anda adalah core engine dari AuraSight, asisten visual berbasis AI untuk tuna netra.\nTugas Anda adalah menganalisis gambar yang dikirimkan pengguna secara objektif, singkat, padat, dan mengutamakan keselamatan.\n\nGunakan bahasa Indonesia yang natural dan ramah untuk Text-to-Speech (TTS).")
                        )
                    )
                )

                val apiKey = BuildConfig.GEMINI_API_KEY
                if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
                    throw Exception("Kunci API Gemini (GEMINI_API_KEY) belum dikonfigurasi. Silakan masukkannya melalui panel Secrets di AI Studio.")
                }

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(apiKey, requestBody)
                }

                val responseText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (responseText != null) {
                    val result = withContext(Dispatchers.Default) {
                        try {
                            val adapter = RetrofitClient.moshiInstance.adapter(AuraSightResult::class.java)
                            adapter.fromJson(responseText)
                        } catch (e: Exception) {
                            Log.e("AuraSightVM", "JSON parsing failed", e)
                            null
                        }
                    }

                    if (result != null) {
                        _uiState.value = AuraSightState.Success(result)
                        // Speak outcomes
                        val voiceMessage = buildSpeakableResult(result)
                        onSpeechTrigger?.invoke(voiceMessage)
                    } else {
                        throw Exception("Gagal menguraikan data respons dari satelit AI. Silakan coba lagi.")
                    }
                } else {
                    throw Exception("Tidak ada hasil yang diterima dari penglihatan AI.")
                }

            } catch (e: Exception) {
                Log.e("AuraSightVM", "Error analyzing snapshot", e)
                val errorMessage = if (e.message?.contains("503") == true || e.message?.contains("Unavailable") == true) {
                    "Layanan padat (Error 503). Gagal menganalisis, silakan ketuk layar untuk mencoba lagi."
                } else {
                    e.message ?: "Koneksi terganggu. Gagal menganalisis, silakan ketuk layar untuk mencoba lagi."
                }
                _uiState.value = AuraSightState.Error(errorMessage)
                // Speak error voice announcement
                onSpeechTrigger?.invoke("Gagal menganalisis, silakan ketuk layar untuk mencoba lagi.")
            }
        }
    }

    private fun buildSpeakableResult(result: AuraSightResult): String {
        val builder = java.lang.StringBuilder()
        // Langsung informasikan isinya secara mengalir ramah kawan dekat, tanpa label judul kaku
        builder.append("${result.sceneDescription} ")
        if (result.spatialGuidance.isNotBlank()) {
            builder.append("${result.spatialGuidance} ")
        }
        if (result.smartWarning.isNotBlank()) {
            builder.append("Oh iya, ${result.smartWarning} ")
        }
        if (result.quickTextReader.isNotBlank()) {
            builder.append("Ada tulisan kebaca: ${result.quickTextReader}. ")
        }
        return builder.toString().trim()
    }
}
