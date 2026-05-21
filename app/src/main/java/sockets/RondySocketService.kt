package com.larangel.rondyaccesos.models.sockets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import com.larangel.rondyaccesos.models.RegistroAcceso
import io.ktor.utils.io.core.ByteReadPacket

class RondySocketService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tcpServerJob: Job? = null
    private var udpDiscoveryJob: Job? = null

    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val PORT = 35420
    private val TAG = "RondySocketService"

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceChannel()
        iniciarServidorTCP()
        iniciarDescubrimientoUDP()
    }

    private fun iniciarServidorTCP() {
        tcpServerJob = serviceScope.launch {
            try {
                val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", PORT)
                Log.d(TAG, "Servidor TCP a la escucha en el puerto $PORT")

                while (isActive) {
                    val socket = serverSocket.accept()
                    launch { // Hilo ligero por cada satélite conectado
                        procesarConexionSatelite(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en el servidor TCP: ${e.message}")
            }
        }
    }

    private suspend fun procesarConexionSatelite(socket: Socket) {
        val remoteAddress = socket.remoteAddress.toString()
        Log.d(TAG, "Satélite conectado desde: $remoteAddress")
        val receiveChannel = socket.openReadChannel()
        val sendChannel = socket.openWriteChannel(autoFlush = true)

        try {
            while (!receiveChannel.isClosedForRead) {
                val line = receiveChannel.readUTF8Line() ?: break
                val message = Json.decodeFromString<SocketMessage>(line)

                when (message.type) {
                    MessageType.REGISTRO_INGRESO -> {
                        Log.d(TAG, "Registro recibido de ${message.senderRole}: ${message.registro?.placa}")

                        // TODO: Aquí se conecta con el ViewModel central de Caseta para validar
                        // Morosidad, Placas Prohibidas, etc.
                        val respuesta = evaluarAccesoLocal(message.registro)

                        // Enviar respuesta inmediata al Satélite hijo
                        val respuestaJson = Json.encodeToString(SocketMessage.serializer(), respuesta)
                        sendChannel.writeStringUtf8(respuestaJson + "\n")
                    }
                    else -> { /* Ignorar otros tipos en TCP */ }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conexión abrupta con el satélite $remoteAddress: ${e.message}")
        } finally {
            socket.close()
        }
    }

    private fun iniciarDescubrimientoUDP() {
        udpDiscoveryJob = serviceScope.launch {
            try {
                // Escucha de paquetes broadcast locales
                val udpSocket = aSocket(selectorManager).udp().bind(
                    io.ktor.network.sockets.InetSocketAddress("0.0.0.0", PORT)
                )
                Log.d(TAG, "Escucha UDP Broadcast activa en puerto $PORT")

                while (isActive) {
                    val packet = udpSocket.receive()
                    val packetText = packet.packet.readText()

                    val message = Json.decodeFromString<SocketMessage>(packetText)
                    if (message.type == MessageType.DISCOVERY_PING) {
                        Log.d(TAG, "Ping de descubrimiento recibido de: ${packet.address}")

                        // Responder Pong directo al satélite con la IP de la caseta
                        val response = SocketMessage(
                            type = MessageType.DISCOVERY_PONG,
                            senderIp = "LOCAL_IP", // Se sustituye dinámicamente por la IP local del dispositivo
                            senderRole = "CASETA"
                        )
                        val responseText = Json.encodeToString(SocketMessage.serializer(), response)

                        udpSocket.send(
                            Datagram(
                                ByteReadPacket(responseText.toByteArray()),
                                packet.address
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en socket UDP Discovery: ${e.message}")
            }
        }
    }

    private fun evaluarAccesoLocal(registro: RegistroAcceso?): SocketMessage {
        if (registro == null) return SocketMessage(MessageType.RESPUESTA_VALIDACION, "", "CASETA", autorizado = false, motivoDenegacion = "Datos nulos")

        // Simulación lógica de validación dura (Este bloque interactúa con DataRawRondin)
        // Ej: if (helpers.esDeudor(registro.calle, registro.numero)) { ... }

        return SocketMessage(
            type = MessageType.RESPUESTA_VALIDACION,
            senderIp = "",
            senderRole = "CASETA",
            autorizado = true
        )
    }

    private fun startForegroundServiceChannel() {
        val channelId = "RondySockets"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Servidor Rondy Local", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Rondy Central Activo")
            .setContentText("Escuchando satélites en puerto $PORT...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        tcpServerJob?.cancel()
        udpDiscoveryJob?.cancel()
        selectorManager.close()
        serviceScope.cancel()
        Log.d(TAG, "Servidor de Sockets Rondy cerrado de manera segura.")
    }
}