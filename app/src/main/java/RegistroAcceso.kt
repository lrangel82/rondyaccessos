package com.larangel.rondyaccesos.models

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class AccesoBitacora(
    val fechaCreado: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    val fechaIngreso: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
    val placa: String,
    val calle: String,
    val numero: String,
    val tipo: String, // Ejemplo: "VISITA", "PROVEEDOR", "COLONOS"
    val conductor: String,
    val descripcion: String, // Motivo de ingreso extraído por la IA o manual
    val foto1Url: String = "",
    val foto2Url: String = "",
    val qrData: String = "",
    val fechaSalida: String = "",
    val status: String = "INGRESADO" // Control de flujo (INGRESADO, SALIDA, RECHAZADO)
) {
    // Convierte el objeto de negocio en una lista plana de Any/String compatible con Google Sheets API
    fun toSheetRow(): List<String> {
        return listOf(
            fechaCreado,
            fechaIngreso,
            placa.uppercase(Locale.getDefault()).trim(),
            calle.trim(),
            numero.trim(),
            tipo.trim(),
            conductor.trim(),
            descripcion.trim(),
            foto1Url,
            foto2Url,
            qrData,
            fechaSalida,
            status
        )
    }
}

//@Serializable
//data class RegistroAcceso(
//    val id: String, // ID temporal negativo si es offline, o timestamp largo
//    val fecha: String, // yyyy-MM-dd
//    val hora: String,  // HH:mm:ss
//    val placa: String,
//    val calle: String,
//    val numero: String,
//    val tipo: String, // Visitante, Paqueteria, Residente sin tag
//    val conductor: String,
//    val descripcion: String,
//    val fotoPlacaPath: String,
//    val fotoRostroPath: String,
//    val qrData: String,
//    val statusStr: String // acceso permitido / acceso denegado
//)
