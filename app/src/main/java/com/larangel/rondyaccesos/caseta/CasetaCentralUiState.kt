package com.larangel.rondyaccesos.models.com.larangel.rondyaccesos.caseta

import com.larangel.rondyaccesos.models.AccesoBitacora
import com.larangel.rondyaccesos.models.SateliteMode

data class SateliteNodoState(
    val ipAddress: String,
    val role: SateliteMode,
    val estaEnLinea: Boolean = true,
    val ultimoRegistroRecibido: AccesoBitacora? = null,
    val requiereAsistencia: Boolean = false
)

data class CasetaCentralUiState(
    val nodosSatelites: List<SateliteNodoState> = emptyList(),
    val sateliteSeleccionado: SateliteNodoState? = null,
    val totalIngresosHoy: Int = 0,
    val totalSalidasHoy: Int = 0,
    val statusServidorTxt: String = "Iniciando servidor de red..."
)
