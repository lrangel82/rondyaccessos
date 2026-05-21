package com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.salida

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.models.RegistroAcceso
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

data class SalidaUiState(
    val mensajeSuperior: String = "Escaneando entorno...",
    val vehiculoDetectado: RegistroAcceso? = null,
    val mostrarBotonForzar: Boolean = true
)

class SalidaVehicularViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SalidaUiState())
    val uiState: StateFlow<SalidaUiState> = _uiState.asStateFlow()

    private val socketClient = RondySocketClient()

    // Se ejecuta automáticamente cuando la cámara IP procesa un texto exitoso vía ML Kit OCR
    fun procesarPlacaDetectadaPorCamara(placaCamara: String) {
        val placaLimpia = placaCamara.replace(Regex("[^a-zA-Z0-9]"), "").uppercase().trim()
        if (placaLimpia.isEmpty()) return

        _uiState.update { it.copy(mensajeSuperior = "Buscando ingreso activo para: $placaLimpia") }

        viewModelScope.launch {
            // TODO: Consultar tu clase DataRawRondin.kt filtrando en la tabla local
            // val registroEntrada = dataRawRondin.buscarIngresoActivoPorPlaca(placaLimpia)
            val registroEntradaMock = RegistroAcceso(
                id = "99999",
                fecha = LocalDate.now().toString(),
                hora = "12:00:00",
                placa = placaLimpia,
                calle = "Circuito Olmos",
                numero = "12-B",
                tipo = "Visitante",
                conductor = "REPARTIDOR COCA COLA",
                descripcion = "Entrega de refrescos",
                fotoPlacaPath = "", fotoRostroPath = "", qrData = "",
                statusStr = "acceso permitido"
            )

            if (registroEntradaMock != null) {
                _uiState.update {
                    it.copy(
                        vehiculoDetectado = registroEntradaMock,
                        mensajeSuperior = "Vehículo identificado adentro del condominio.",
                        mostrarBotonForzar = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        vehiculoDetectado = null,
                        mensajeSuperior = "Placa sin registro de entrada activo.",
                        mostrarBotonForzar = true
                    )
                }
            }
        }
    }

    fun ejecutarSalidaVehicular() {
        val vehiculo = _uiState.value.vehiculoDetectado ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(mensajeSuperior = "Registrando egreso en la base central...") }

            // Generamos el registro de egreso modificado
            val registroEgreso = vehiculo.copy(
                id = (LocalTime.now().toSecondOfDay() * -1).toString(), // Llave negativa offline
                hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                statusStr = "salida registrada"
            )

            // 1. Transmitir JSON en tiempo real a la Caseta Central (Padre) vía TCP
            val msgSocket = SocketMessage(MessageType.REGISTRO_INGRESO, "client_ip", "SALIDA_VEHICULAR", registroEgreso)
            socketClient.enviarRegistroACaseta(msgSocket)

            // 2. Persistir en la cola diferida de Google Sheets de DataRawRondin
            // dataRawRondin.sync(SheetTable.BITACORA_ACCESOS, Operation.APPEND, listOf(...))

            _uiState.update {
                it.copy(
                    mensajeSuperior = "¡Salida Procesada! Abriendo barrera de egreso.",
                    vehiculoDetectado = null
                )
            }
        }
    }
}