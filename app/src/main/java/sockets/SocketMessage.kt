package com.larangel.rondyaccesos.models.sockets

import com.larangel.rondyaccesos.models.AccesoBitacora
import kotlinx.serialization.Serializable

@Serializable
enum class MessageType { DISCOVERY_PING, DISCOVERY_PONG, REGISTRO_INGRESO, RESPUESTA_VALIDACION }

@Serializable
data class SocketMessage(
    val type: MessageType,
    val senderIp: String,
    val senderRole: String, // CASETA, INGRESO_VEHICULAR, etc.
    val registro: AccesoBitacora? = null,
    val autorizado: Boolean = false,
    val motivoDenegacion: String = ""
)
