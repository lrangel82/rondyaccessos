package com.larangel.rondyaccesos.models.sockets

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.ByteReadPacket
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.util.network.address

class RondySocketClient {

    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val PORT = 35420
    private var casetaIp: String? = null

    // 1. Descubrimiento Automático por UDP Broadcast
    suspend fun descubrirIpCaseta(): String? = withContext(Dispatchers.IO) {
        val udpSocket = aSocket(selectorManager).udp().bind()
        val pingMessage = SocketMessage(MessageType.DISCOVERY_PING, "", "SATELITE_CLIENTE")
        val pingText = Json.encodeToString(SocketMessage.serializer(), pingMessage)

        // Enviar a la dirección broadcast global de la subred local
        val broadcastAddress = io.ktor.network.sockets.InetSocketAddress("255.255.255.255", PORT)

        var intentos = 0
        while (casetaIp == null && intentos < 3) {
            try {
                udpSocket.send(Datagram(ByteReadPacket(pingText.toByteArray()), broadcastAddress))

                // Esperar respuesta con timeout de 3 segundos
                withTimeout(3000) {
                    val packet = udpSocket.receive()
                    val responseText = packet.packet.readText()
                    val response = Json.decodeFromString<SocketMessage>(responseText)
                    if (response.type == MessageType.DISCOVERY_PONG) {
                        // Extraemos la IP del remitente
                        casetaIp = (packet.address as? io.ktor.network.sockets.InetSocketAddress)?.hostname
                    }
                }
            } catch (e: TimeoutCancellationException) {
                intentos++
            } catch (e: Exception) {
                break
            }
        }
        udpSocket.close()
        return@withContext casetaIp
    }

    // 2. Transmisión TCP Síncrona del Registro a Caseta
    suspend fun enviarRegistroACaseta(message: SocketMessage): SocketMessage? = withContext(Dispatchers.IO) {
        val targetIp = casetaIp ?: descubrirIpCaseta() ?: return@withContext null

        try {
            val socket = aSocket(selectorManager).tcp().connect(targetIp, PORT)
            val receiveChannel = socket.openReadChannel()
            val sendChannel = socket.openWriteChannel(autoFlush = true)

            val jsonStr = Json.encodeToString(SocketMessage.serializer(), message)
            sendChannel.writeStringUtf8(jsonStr + "\n")

            // Esperar veredicto en tiempo real del guardia de Caseta
            val responseLine = receiveChannel.readUTF8Line()
            socket.close()

            if (responseLine != null) {
                return@withContext Json.decodeFromString<SocketMessage>(responseLine)
            }
        } catch (e: Exception) {
            // Manejo de desconexión: Al fallar el Socket, los datos se preservan en DataRawRondin para push diferido
        }
        return@withContext null
    }
}