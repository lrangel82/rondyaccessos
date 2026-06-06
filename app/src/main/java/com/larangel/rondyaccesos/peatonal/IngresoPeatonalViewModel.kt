package com.larangel.rondyaccesos.peatonal

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.models.*
import com.larangel.rondyaccesos.models.network.*
import com.larangel.rondyaccesos.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

class IngresoPeatonalViewModel(
    application: Application,
    private val dataRaw: DataRawRondin,
    private val geminiVoiceAssistant: GeminiVoiceAssistant,
    private val apiService: BotCasetaApiService,
    private val mySettings: MySettings
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(IngresoPeatonalUiState())
    val uiState: StateFlow<IngresoPeatonalUiState> = _uiState.asStateFlow()

    private val flujoResuelto = AtomicBoolean(false)
    private var orquestadorJob: Job? = null
    private var timerInactividadJob: Job? = null
    private val TIMEOUT_INACTIVIDAD = 60

    init {
        reiniciarAsistentePeatonal()
    }

    fun reiniciarAsistentePeatonal(porExito: Boolean = false) {
        orquestadorJob?.cancel()
        timerInactividadJob?.cancel()
        flujoResuelto.set(false)

        _uiState.update {
            IngresoPeatonalUiState(
                currentStep = CaptureStep.SELECCION_MOTIVO,
                //mensajeSuperior = "CONTROL PEATONAL ACTIVO",
                mencionarBienvenida = porExito
            )
        }

        if (porExito) {
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Acceso autorizado. Bienvenido.")
        }
        iniciarTimerInactividad()
    }

    private fun iniciarTimerInactividad() {
        timerInactividadJob?.cancel()
        timerInactividadJob = viewModelScope.launch {
            var segundos = TIMEOUT_INACTIVIDAD
            while (segundos > 0) {
                delay(1000)
                segundos--
                _uiState.update { it.copy(segundosRestantes = segundos) }
            }
            _uiState.update { it.copy(mostrarSplash = true) }
        }
    }

    fun procesarEntradaVoz(texto: String) {
        if (_uiState.value.mostrarSplash) {
            if (texto.lowercase().contains("hola")) {
                _uiState.update { it.copy(mostrarSplash = false) }
                reiniciarAsistentePeatonal()
            }
            return
        }
        iniciarTimerInactividad()
        // Aquí se invoca a Gemini para extraer Calle, Numero, Nombre, Motivo
        // similar a la lógica vehicular pero omitiendo Placas.
    }

    fun validarYProcesarAcceso(calle: String, numero: String, nombre: String, motivo: String) {
        viewModelScope.launch(Dispatchers.Default) {
            // 1. Validar Morosidad
            if (dataRaw.esDomicilioMoroso(calle, numero)) {
                manejarDenegacion("DOMICILIO MOROSO - ACCESO RESTRINGIDO")
                return@launch
            }

            // 2. Iniciar Orquestador (La misma lógica de tu VehicularViewModel)
            iniciarFlujoAutorizaciónPeatonal(calle, numero, nombre, motivo)
        }
    }

    private suspend fun iniciarFlujoAutorizaciónPeatonal(calle: String, numero: String, nombre: String, motivo: String) {
        val tokenApi = "Bearer " + mySettings.getString("TOKEN_API_BOTCASETA", "")
        val telefonos = dataRaw.getWhatsappTelefonosDomicilio(calle, numero)

        orquestadorJob = viewModelScope.launch(Dispatchers.IO) {
            // Hilo WhatsApp Polling
            launch {
               // ejecutarSondeoWhatsAppPeatonal(calle, numero, nombre, motivo, telefonos, tokenApi)
            }
            // Hilo IVR (Llamada) a los 30 segundos
            launch {
                delay(30000)
                //dispararIVRPeatonal(calle, numero, nombre, motivo, tokenApi)
            }
        }
    }

    private fun manejarDenegacion(razon: String) {
        //_uiState.update { it.copy(esMoroso = true, mensajeSuperior = razon) }
        geminiVoiceAssistant.forzarLocucionPorAltavoz(razon)
    }

    // ... (Implementación de ejecutarSondeoWhatsAppPeatonal y dispararIVRPeatonal
    // replicando la lógica de tu VehicularViewModel pero enviando "Peatonal" como tipo de registro)
}