package com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.salida

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.models.AccesoBitacora
import com.larangel.rondyaccesos.models.sockets.MessageType
import com.larangel.rondyaccesos.models.sockets.RondySocketClient
import com.larangel.rondyaccesos.models.sockets.SocketMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class SalidaPeatonalUiState(
    val mensajeSuperior: String = "Ingrese criterio de búsqueda",
    val peatonEncontrado: AccesoBitacora? = null
)

class SalidaPeatonalViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SalidaPeatonalUiState())
    val uiState: StateFlow<SalidaPeatonalUiState> = _uiState.asStateFlow()

    private val socketClient = RondySocketClient()

    fun buscarRegistroEntradaPeatonal(criterio: String) {
        val query = criterio.trim().lowercase()
        if (query.isEmpty()) return

        _uiState.update { it.copy(mensajeSuperior = "Buscando en registros del día...") }

        viewModelScope.launch {
            // TODO: Consultar tu base de datos unificada DataRawRondin.kt filtrando peatones sin salida
            // val coincidencia = dataRawRondin.buscarPeatonAdentro(query)

            // Simulación de coincidencia encontrada en el coto
            val mockPeaton = AccesoBitacora(
                fechaCreado = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(), // ID temporal offline
                fechaIngreso = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(),
                placa = "PEATONAL",
                calle = "Circuito Olmos",
                numero = "34",
                tipo = "Visitante Peatonal",
                conductor = "CARLOS GÓMEZ", // Nombre del peatón
                descripcion = "Visita familiar",
                foto1Url = "local/express_placa.jpg",
                foto2Url = "",
                qrData = "",
                status = "acceso permitido"
            )

            // Filtro por similitud o coincidencia de texto (Usa tu función sonCadenasSimilares si es necesario)
            if (mockPeaton.conductor.lowercase().contains(query) || "${mockPeaton.calle}:${mockPeaton.numero}".lowercase().contains(query)) {
                _uiState.update {
                    it.copy(
                        peatonEncontrado = mockPeaton,
                        mensajeSuperior = "Registro localizado."
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        peatonEncontrado = null,
                        mensajeSuperior = "No se encontraron peatones activos con ese criterio."
                    )
                }
            }
        }
    }

    fun registrarEgresoPeatonal(rutaFotoSalida: String) {
        val visita = _uiState.value.peatonEncontrado ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(mensajeSuperior = "Procesando salida peatonal...") }

            // Creamos la estructura de egreso final
            val registroEgreso = visita.copy(
                fechaCreado = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(), // ID temporal offline
                fechaIngreso = LocalTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).toString(),
                foto1Url = rutaFotoSalida, // Reutilizamos el campo para guardar la foto de salida física
                status = "salida peatonal registrada"
            )

            // 1. Envío asíncrono TCP síncrono al Módulo Central de Caseta (Nodo Padre)
            val msgSocket = SocketMessage(MessageType.REGISTRO_INGRESO, "client_ip", "SALIDA_PEATONAL", registroEgreso)
            socketClient.enviarRegistroACaseta(msgSocket)

            // 2. Insertar en la cola resiliente de Google Sheets en DataRawRondin
            // dataRawRondin.sync(SheetTable.BITACORA_ACCESOS, Operation.APPEND, listOf(...))

            _uiState.update {
                it.copy(
                    mensajeSuperior = "Salida guardada con éxito. Vuelva a escanear.",
                    peatonEncontrado = null
                )
            }
        }
    }
}