package com.larangel.rondyaccesos.vehicular

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.larangel.rondyaccesos.RondyApplication

class IngresoVehicularViewModelFactory(private val application: RondyApplication) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IngresoVehicularViewModel::class.java)) {
            return IngresoVehicularViewModel(
                application = application,
                dataRaw = application.dataRawRondin,
                geminiVoiceAssistant = application.geminiVoiceAssistant
            ) as T
        }
        throw IllegalArgumentException("Clase ViewModel desconocida")
    }
}