package com.larangel.rondyaccesos.models.sockets

import kotlinx.serialization.Serializable
import com.larangel.rondyaccesos.models.RegistroAcceso

@Serializable
enum class MessageType { DISCOVERY_PING, DISCOVERY_PONG, REGISTRO_INGRESO, RESPUESTA_VALIDACION }

@Serializable
data class SocketMessage(
    val type: MessageType,
    val senderIp: String,
    val senderRole: String, // CASETA, INGRESO_VEHICULAR, etc.
    val registro: RegistroAcceso? = null,
    val autorizado: Boolean = false,
    val motivoDenegacion: String = ""
)
