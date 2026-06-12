package com.larangel.rondyaccesos.models.sockets

import com.larangel.rondyaccesos.models.AccesoBitacora
import kotlinx.serialization.Serializable

@Serializable
enum class MessageType {
    DISCOVERY_PING,      // Busco nodos en la red
    DISCOVERY_PONG,      // Respondo a quien busca
    ANUNCIO_CONEXION,    // Me conecto y digo quién soy
    ACTUALIZACION_LISTA, // Envío mi lista de nodos conocidos
    REGISTRO_INGRESO,    // Comparto un nuevo ingreso (Vehicular/Peatonal)
    AVISO_ESPECIFICO,    // Comandos entre módulos
    KEEP_ALIVE           // Validar si el nodo sigue activo
}

@Serializable
data class SocketMessage(
    val type: MessageType,
    val senderIp: String,
    val senderRole: String, // CASETA, INGRESO_VEHICULAR, etc.
    val registro: AccesoBitacora? = null,
    val listaNodos: List<RondyNodo>? = null, // Para el punto 4 y 6
    val mensajeExtra: String? = "",
    val respuestaCommando: String? = ""
)

@Serializable
data class RondyNodo(
    val ip: String,
    val role: String,
    var lastSeen: Long = System.currentTimeMillis()
){
    override fun equals(other: Any?): Boolean = (other as? RondyNodo)?.ip == ip
    override fun hashCode(): Int = ip.hashCode()
}