package com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.peatonal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.models.AccesoBitacora
import com.larangel.rondyaccesos.models.sockets.MessageType
import com.larangel.rondyaccesos.models.sockets.SocketMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class PeatonalUiState(
    val mensajeSuperior: String = "Ingrese datos de la visita",
    val calle: String = "",
    val numero: String = "",
    val nombre: String = "",
    val motivo: String = "",
    val esMoroso: Boolean = false,
    val subtitulosIA: String = "",
    val estatusMorosidadTxt: String = ""
)

class IngresoPeatonalViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(PeatonalUiState())
    val uiState: StateFlow<PeatonalUiState> = _uiState.asStateFlow()

    private val networkManager = getApplication<RondyApplication>().networkManager
    private val registroMutex = Mutex()

    fun onCalleChanged(valor: String) { _uiState.update { it.copy(calle = valor) } }
    fun onNombreChanged(valor: String) { _uiState.update { it.copy(nombre = valor) } }
    fun onMotivoChanged(valor: String) { _uiState.update { it.copy(motivo = valor) } }

    fun onNumeroChanged(valor: String) {
        _uiState.update { it.copy(numero = valor) }
        verificarRestriccionesDomicilio()
    }

    private fun verificarRestriccionesDomicilio() {
        val calle = _uiState.value.calle.trim()
        val numero = _uiState.value.numero.trim()
        if (calle.isEmpty() || numero.isEmpty()) return

        // Lógica de validación dura solicitada para peatonal
        val deudorDetectado = false // Conectar con la estructura DataRawRondin.kt -> helpers.esDeudor(calle, numero)

        if (deudorDetectado) {
            _uiState.update {
                it.copy(
                    esMoroso = true,
                    estatusMorosidadTxt = "DOMICILIO MOROSO: INGRESO NEGADO",
                    mensajeSuperior = "Acceso Denegado. Indique al visitante comunicarse con el residente."
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    esMoroso = false,
                    estatusMorosidadTxt = "Domicilio autorizado",
                    mensajeSuperior = "Datos válidos. Puede proceder."
                )
            }
        }
    }

    fun registrarIngresoPeatonal(fotoRostroPath: String) {
        viewModelScope.launch {
            if (registroMutex.isLocked) return@launch
            registroMutex.withLock {
                val state = _uiState.value
                if (state.calle.isEmpty() || state.numero.isEmpty() || state.nombre.isEmpty()) {
                    _uiState.update { it.copy(mensajeSuperior = "Error: Faltan datos obligatorios") }
                    return@launch
                }

                val registroPeatonal = AccesoBitacora(
                    fechaCreado = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(), // ID temporal offline
                    fechaIngreso = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(),
                    placa = "PEATONAL",
                    calle = state.calle,
                    numero = state.numero,
                    tipo = "Visitante Peatonal",
                    conductor = state.nombre,
                    descripcion = state.motivo,
                    foto1Url = "",
                    foto2Url = fotoRostroPath,
                    qrData = "",
                    fechaSalida = "",
                    status = if (state.esMoroso) "AUTORIZADO" else "DENEGADO"
                )

                _uiState.update { it.copy(mensajeSuperior = "Sincronizando registro con caseta principal...") }

                // Transmisión asíncrona local vía Ktor Sockets hacia la Caseta Central (Padre)
                networkManager.replicarIngreso(registroPeatonal)

                // Persistir localmente usando tu esquema DataRawRondin para subida diferida
                // dataRawRondin.sync(SheetTable.BITACORA_ACCESOS, Operation.APPEND, ...)

                if (!state.esMoroso) {
                    _uiState.update { it.copy(mensajeSuperior = "¡INGRESO AUTORIZADO!") }
                } else {
                    _uiState.update { it.copy(mensajeSuperior = "INGRESO RECHAZADO POR MOROSIDAD.") }
                }

                // TODO: Aquí se puede disparar el envío de la plantilla de notificación de WhatsApp
            }
        }
    }
}
