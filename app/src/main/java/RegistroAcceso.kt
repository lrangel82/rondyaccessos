package com.larangel.rondyaccesos.models

import kotlinx.serialization.Serializable

@Serializable
data class RegistroAcceso(
    val id: String, // ID temporal negativo si es offline, o timestamp largo
    val fecha: String, // yyyy-MM-dd
    val hora: String,  // HH:mm:ss
    val placa: String,
    val calle: String,
    val numero: String,
    val tipo: String, // Visitante, Paqueteria, Residente sin tag
    val conductor: String,
    val descripcion: String,
    val fotoPlacaPath: String,
    val fotoRostroPath: String,
    val qrData: String,
    val statusStr: String // acceso permitido / acceso denegado
)
