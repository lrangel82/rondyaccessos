package com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.caseta

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.models.RegistroAcceso
import com.larangel.rondyaccesos.models.SateliteMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CasetaCentralViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CasetaCentralUiState())
    val uiState: StateFlow<CasetaCentralUiState> = _uiState.asStateFlow()

    init {
        // Simular inicio del monitor de red local
        _uiState.update { it.copy(statusServidorTxt = "Servidor activo en puerto 35420. Escuchando...") }
        inicializarMosaicoSatelitesPorDefecto()
    }

    private fun inicializarMosaicoSatelitesPorDefecto() {
        // Inicializa la cuadrícula base con los 4 satélites esclavos esperados en el condominio
        val satelitesIniciales = listOf(
            SateliteNodoState("192.168.1.50", SateliteMode.INGRESO_VEHICULAR),
            SateliteNodoState("192.168.1.51", SateliteMode.INGRESO_PEATONAL),
            SateliteNodoState("192.168.1.52", SateliteMode.SALIDA_VEHICULAR),
            SateliteNodoState("192.168.1.53", SateliteMode.SALIDA_PEATONAL)
        )
        _uiState.update { it.copy(nodosSatelites = satelitesIniciales) }
    }

    // Esta función es llamada de forma asíncrona cuando RondySocketService detecta un JSON entrante
    fun procesarPaqueteEntranteDeSatelite(ip: String, role: SateliteMode, registro: RegistroAcceso) {
        viewModelScope.launch {
            _uiState.update { current ->
                val listaModificada = current.nodosSatelites.map { nodo ->
                    if (nodo.role == role) {
                        // Actualiza el satélite coincidente con el último registro tomado en campo
                        nodo.copy(
                            ipAddress = ip,
                            estaEnLinea = true,
                            ultimoRegistroRecibido = registro,
                            requiereAsistencia = registro.statusStr == "acceso denegado" // Alerta visual si se le deniega el paso
                        )
                    } else {
                        nodo
                    }
                }

                // Si el satélite modificado es el que el guardia está auditando a pantalla completa, actualiza el detalle
                val seleccionadoActualizado = listaModificada.find { it.role == current.sateliteSeleccionado?.role }

                current.copy(
                    nodosSatelites = listaModificada,
                    sateliteSeleccionado = seleccionadoActualizado,
                    totalIngresosHoy = if (role == SateliteMode.INGRESO_VEHICULAR || role == SateliteMode.INGRESO_PEATONAL) current.totalIngresosHoy + 1 else current.totalIngresosHoy,
                    totalSalidasHoy = if (role == SateliteMode.SALIDA_VEHICULAR || role == SateliteMode.SALIDA_PEATONAL) current.totalSalidasHoy + 1 else current.totalSalidasHoy
                )
            }
        }
    }

    fun seleccionarSateliteParaDetalle(satelite: SateliteNodoState) {
        _uiState.update { it.copy(sateliteSeleccionado = satelite) }
    }

    fun cerrarDetalle() {
        _uiState.update { it.copy(sateliteSeleccionado = null) }
    }

    fun forzarAperturaRemotaDesdeCaseta(role: SateliteMode) {
        viewModelScope.launch {
            // Lógica para enviar paquete TCP de control de hardware al satélite hijo
            // Mapeado del control remoto de Python: enviar_comando_satelite(role, "ABRIR_BARRERA")
            _uiState.update { current ->
                val listaModificada = current.nodosSatelites.map { nodo ->
                    if (nodo.role == role) nodo.copy(requiereAsistencia = false) else nodo
                }
                current.copy(nodosSatelites = listaModificada, sateliteSeleccionado = null)
            }
        }
    }
}