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

    var registroCallbackActivo: ((calle: String, numero: String, nombre: String, tipo: String, placa: String) -> Unit)? = null

    // Instancias únicas inicializadas de forma perezosa (Lazy)
    val dataRawRondin: DataRawRondin by lazy {
        DataRawRondin(applicationContext, applicationScope)
    }

    val geminiVoiceAssistant: GeminiVoiceAssistant by lazy {
        val mySettings = MySettings(applicationContext)
        //val apiKey = mySettings.getString("GEMINI_API_KEY", "")
        val apiKey =""

        GeminiVoiceAssistant(applicationContext, apiKey) { calle, numero, nombre, tipo, placa ->
            /// 🚀 INTERCEPTION TRICK: Forward the extraction payload directly
            // to whichever ViewModel has its callback currently registered!
            registroCallbackActivo?.invoke(calle, numero, nombre, tipo, placa)
        }
    }
}