package com.larangel.rondyaccesos.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import com.google.ai.client.generativeai.type.generationConfig

class GeminiVoiceAssistant(
    private val context: Context,
    private val apiKey: String,
    private val onDataExtracted: (calle: String, numero: String, nombre: String, tipo: String, placa: String) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var generativeModel: GenerativeModel? = null

    // Flujos reactivos para subtítulos e interfaz accesible
    private val _subtitulosState = MutableStateFlow("")
    val subtitulosState: StateFlow<String> get() = _subtitulosState

    init {
        if (apiKey.isNotEmpty()) {
            generativeModel = GenerativeModel(
                modelName = "gemini-2.5-flash-lite",
                apiKey = apiKey,
                systemInstruction = content {
                    text("Eres un asistente inteligente por voz para la caseta de vigilancia del condominio. " +
                            "Debes hablarle de manera clara y amable al visitante. " +
                            "Tu objetivo es preguntarle a qué calle, número de casa va, cuál es su nombre y el motivo de su visita. " +
                            "el motivo de la visita debe ser alguno de los siguientes [\"Visitante\", \"Uber/Taxi\", \"Residente sin tag\", \"Paqueteria\", \"Gas\", \"ComidaADomicilio\", \"Policia\", \"Camion Basura\", \"Grua\", \"Ambulancia\"]" +
                            "Siempre debes responder en formato JSON plano con dos llaves estrictas: " +
                            "1) 'speech': El texto corto y amigable que le dirás en voz alta al visitante. " +
                            "2) 'extracted_data': Un objeto con las llaves 'calle', 'numero', 'nombre', 'tipo', 'placa' extraídas de la conversación. " +
                            "Si el campo aún no se conoce, déjalo vacío ''. Ejemplo de respuesta: " +
                            "{\"speech\": \"Bienvenido, ¿a qué domicilio se dirige?\", \"extracted_data\": {\"calle\":\"\",\"numero\":\"\",\"nombre\":\"\",\"motivo\":\"\",\"placa\":\"\"}}")
                },
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                }
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "MX")
        }
    }
    fun setTtsProgressListener(listener: UtteranceProgressListener) {
        tts.setOnUtteranceProgressListener(listener)
    }

    suspend fun procesarEntradaVoz(transcripcionVigilanteOVisita: String, datosActualesJson: String) = withContext(Dispatchers.IO) {
        if (generativeModel == null) return@withContext

        val prompt = "Conversación actual del visitante: '$transcripcionVigilanteOVisita'. " +
                "Estructura de datos acumulados hasta ahora: $datosActualesJson. " +
                "Analiza la entrada de voz, actualiza el JSON y genera la siguiente locución corta para el visitante."

        try {
            val response = generativeModel?.generateContent(prompt)
            var jsonResultStr = response?.text ?: ""
            Log.d("GeminiAssistant", "Raw text from AI: $jsonResultStr")
            if (jsonResultStr.contains("```")) {
                jsonResultStr = jsonResultStr
                    .replace("```json", "")
                    .replace("```JSON", "")
                    .replace("```", "")
                    .trim()
            }

            val jsonObject = JSONObject(jsonResultStr)
            val textoParaHablar = jsonObject.optString("speech", "No te entendí bien, ¿puedes repetir?")
            val dataExtracted = jsonObject.optJSONObject("extracted_data")

            // Actualizar el canal de subtítulos para accesibilidad visual
            _subtitulosState.value = textoParaHablar

            // Hablar de forma fluida
            withContext(Dispatchers.Main) {
                val bundleParams = android.os.Bundle()
                tts.speak(textoParaHablar, TextToSpeech.QUEUE_FLUSH, bundleParams, "RondyAccesosSpeechID")
            }

            if (dataExtracted != null) {
                onDataExtracted(
                    dataExtracted.optString("calle", ""),
                    dataExtracted.optString("numero", ""),
                    dataExtracted.optString("nombre", ""),
                    dataExtracted.optString("motivo", ""),
                    dataExtracted.optString("placa", "")
                )
            }
        } catch (e: Exception) {
            Log.e("GeminiAssistant", "Error procesando IA por voz: ${e.message}")
        }
    }

    fun forzarLocucionPorAltavoz(textoFrase: String, mostrarTexto: Boolean = true) {
        try {
            if (mostrarTexto == true)
                _subtitulosState.value = textoFrase
            val bundleParams = android.os.Bundle()
            // QUEUE_FLUSH detiene cualquier sonido previo e inyecta la frase actual en el parlante
            tts.speak(textoFrase, TextToSpeech.QUEUE_FLUSH, bundleParams, "RondyAccesosSpeechID")
        } catch (e: Exception) {
            Log.e("GeminiAssistant", "Fallo al forzar TTS: ${e.message}")
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}