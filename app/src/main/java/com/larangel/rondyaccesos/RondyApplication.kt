package com.larangel.rondyaccesos

import android.app.Application
import com.larangel.rondyaccesos.models.DataRawRondin
import com.larangel.rondyaccesos.models.MySettings
import com.larangel.rondyaccesos.utils.GeminiVoiceAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RondyApplication : Application() {

    // Ámbito de corrutinas global que sobrevivirá a cualquier pantalla
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Instancias únicas inicializadas de forma perezosa (Lazy)
    val dataRawRondin: DataRawRondin by lazy {
        DataRawRondin(applicationContext, applicationScope)
    }

    val geminiVoiceAssistant: GeminiVoiceAssistant by lazy {
        val mySettings = MySettings(applicationContext)
        //val apiKey = mySettings.getString("GEMINI_API_KEY", "")
        val apiKey =""

        GeminiVoiceAssistant(applicationContext, apiKey) { calle, numero, nombre, tipo ->
            // Callback global o vacío, ya que el ViewModel escuchará su propio flujo reactivo
        }
    }
}