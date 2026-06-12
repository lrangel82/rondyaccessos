package com.larangel.rondyaccesos.peatonal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.larangel.rondyaccesos.RondyApplication

class IngresoPeatonalViewModelFactory (private val application: RondyApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IngresoPeatonalViewModel::class.java)) {
            return IngresoPeatonalViewModel(
                application = application,
                dataRaw = application.dataRawRondin,
                geminiVoiceAssistant = application.geminiVoiceAssistant,
                apiService = application.botCasetaApiService,
                mySettings = application.mySettings
            ) as T
        }
        throw IllegalArgumentException("Clase ViewModel desconocida")
    }
}