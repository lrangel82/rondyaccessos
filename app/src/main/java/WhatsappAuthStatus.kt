package com.larangel.rondyaccesos.models

import kotlinx.serialization.Serializable

/**
 * Representa los estados del diálogo de sondeo paralelo (Polling)
 * contra la API de WhatsApp en la nube (Mapeado de Python Parte 8).
 */
@Serializable
sealed class WhatsappAuthStatus {

    @Serializable
    object Oculto : WhatsappAuthStatus()

    @Serializable
    object Solicitando : WhatsappAuthStatus()

    @Serializable
    data class Autorizado(val porQuien: String) : WhatsappAuthStatus()

    @Serializable
    data class Denegado(val motivo: String) : WhatsappAuthStatus()

    @Serializable
    object Timeout : WhatsappAuthStatus()

    @Serializable
    data class Error(val mensaje: String) : WhatsappAuthStatus()
}