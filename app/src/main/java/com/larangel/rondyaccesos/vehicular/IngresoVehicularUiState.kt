package com.larangel.rondyaccesos.vehicular

import com.larangel.rondyaccesos.models.WhatsappAuthStatus
import com.larangel.rondyaccesos.models.CaptureStep

data class IngresoVehicularUiState(
    val lblTopMensaje: String = "Esperando vehículo...",
    val currentStep: CaptureStep = CaptureStep.SELECCION_MOTIVO,
    val segundosRestantes: Int = 30,
    val placaInput: String = "",
    val calleInput: String = "",
    val numeroInput: String = "",
    val tipoInput: String = "",
    val conductorInput: String = "",
    val descripcionInput: String = "",
    val extencionesTxt: String = "",
    val deudorBloqueo: Boolean = false,
    val mostrarDialogoPaqueteria: Boolean = false,
    val listaPaqueteria: List<Pair<String, String>> = emptyList(),
    val whatsappStatus: WhatsappAuthStatus = WhatsappAuthStatus.Oculto,
    val subtitulosAsistente: String = "\uD83E\uDD16 Esperando instrucción...",
    val asistenteActivo: Boolean = false,
    val lectorQrActivo: Boolean = false,
    val qrData: String = "",
    val listaDomiciliosFiltrados: List<List<Any>> = emptyList()
)

