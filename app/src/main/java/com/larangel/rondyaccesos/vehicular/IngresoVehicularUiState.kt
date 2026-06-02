package com.larangel.rondyaccesos.vehicular

import com.larangel.rondyaccesos.models.CaptureStep
import com.larangel.rondyaccesos.models.network.WhatsappAuthStatus
import kotlinx.serialization.Serializable

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
    val status: String = "",
    val extencionesTxt: String = "",
    val deudorBloqueo: Boolean = false,
    val mostrarDialogoPaqueteria: Boolean = false,
    val listaPaqueteria: List<Pair<String, String>> = emptyList(),
    val whatsappStatus: WhatsappAuthStatus = WhatsappAuthStatus.Idle,
    val subtitulosAsistente: String = "\uD83E\uDD16 Esperando instrucción...",
    val asistenteActivo: Boolean = false,
    val lectorQrActivo: Boolean = false,
    val qrData: String = "",
    val listaDomiciliosFiltrados: List<List<Any>> = emptyList(),
    val mostrarPanelResultadoDerecho: Boolean = false,
    val resultadoEsAutorizado: Boolean = false,
    val resultadoMotivoPrincipal: String = "",
    val resultadoMotivoDetalle: String = "",
    val tiempoTranscurrido: Int = 0, // ◄ Para ver el tiempo en el popup
    val direccionesPaqueteria: List<Pair<String, String>> = emptyList(), // ◄ Lista interna de (calle, numero)
    val ultimoMensajeHistorial: String = "" // ◄ Para ver de forma visual qué datos se ingresaron con anterioridad
)

@Serializable
data class ExcepcionRondin(
    var id: String,
    val calle: String,
    val numero: String,
    val placas: String,
    val conductor: String,
    var status: String,
    var descripcion: String,
    var resultadoEsAutorizado: Boolean = false,
)
