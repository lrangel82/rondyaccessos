package com.larangel.rondyaccesos.models.sockets

import android.content.Context
import android.util.Log
import androidx.lifecycle.AtomicReference
import com.larangel.rondyaccesos.models.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.net.NetworkInterface

class RondyNetworkManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val miRol: String,
    private val dataRaw: DataRawRondin,
    private val mySettings: MySettings
) {
    private val selectorManager = SelectorManager(Dispatchers.IO)
    private val PORT = 35420

    // Lista de nodos conocidos (Punto 1 y 6)
    private val _nodosActivos = MutableStateFlow<Set<RondyNodo>>(emptySet())

    // Cache de registros recibidos de otros (Punto 3)
    val bitacoraExterna = mutableListOf<AccesoBitacora>()

    //ATOMICS para respuestas
    val RESPUESTA_SOLICITAR_AUTORIZACION = AtomicReference<String>("")


    init {
        iniciarServidorTCP()
        realizarHandshakeInicial()
    }

    // PUNTO 1, 4 y 7: Handshake y Descubrimiento
    private fun realizarHandshakeInicial() = scope.launch {
        val _cacheIps = mySettings.getString("CACHE_NODOS_IPS","") // Guardar como "192.168.1.5,192.168.1.10"
        val cacheIps = _cacheIps.split(",")

//        if (cacheIps.isNotEmpty()) {
//            // Intentar avisar a los que ya conocemos
//            cacheIps.forEach { ip ->
//                enviarMensaje(ip, MessageType.ANUNCIO_CONEXION)
//            }
//        } else {
            // PUNTO 7: Buscar en el segmento de red si no hay nada en cache
            descubrirNodosEnSegmento()
        //}
    }

    // PUNTO 2: Replicar ingreso a todos
    fun replicarIngreso(acceso: AccesoBitacora) = scope.launch {
        _nodosActivos.value.forEach { nodo ->
            enviarMensaje(nodo.ip, MessageType.REGISTRO_INGRESO, acceso)
        }
    }

    // PUNTO 3: Integración para el ViewModel de Ingreso Vehicular
    fun getUltimoAccesoGlobal(placa: String): List<Any> {
        // Buscar primero en lo que nos ha llegado por red recientemente
        val matchRed = bitacoraExterna.find { it.placa.equals(placa, true) }
        if (matchRed != null) return matchRed.toSheetRow()

        // Si no, buscar en la DB local de siempre
        return dataRaw.getBitacoraUltimoAcceso(placa)
    }

    private fun iniciarServidorTCP() = scope.launch(Dispatchers.IO) {
        try {
            val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", PORT)
            Log.d("RondyNetwork", "Servidor TCP iniciado en puerto $PORT")
            while (isActive) {
                val socket = serverSocket.accept()
                val remoteAddress = socket.remoteAddress.toString()
                Log.d("RondyNetwork", "📡 Nueva conexión entrante desde: $remoteAddress")
                launch {
                    try {
                        procesarSocketEntrante(socket)
                    } catch (e: Exception) {
                        Log.e("RondyNetwork", "Error procesando socket de $remoteAddress: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RondyNetwork", "Fallo crítico al iniciar servidor: ${e.message}")
        }
    }

    // PUNTO 7: Buscar en el segmento de red
    private suspend fun descubrirNodosEnSegmento() = withContext(Dispatchers.IO) {
        val miIp = getMiIp()
        if (miIp == "127.0.0.1") return@withContext

        // Extraer el prefijo (ej: de 192.168.1.50 a 192.168.1.)
        val prefix = miIp.substringBeforeLast(".") + "."

        Log.d("RondyNetwork", "Iniciando escaneo de red en segmento: ${prefix}0/24")

        // Lanzar 254 corrutinas en paralelo para un escaneo rápido
        (1..254).map { i ->
            launch {
                val targetIp = prefix + i
                if (targetIp == miIp) return@launch // Ignorarme a mí mismo
                Log.d("RondyNetwork", "testeando SOCKET en ip: $targetIp")
                try {
                    // Intentar abrir un socket con un tiempo de espera de 1 segundo
                    withTimeout(2000) {
                        val socket = aSocket(selectorManager).tcp().connect(targetIp, PORT)
                        val output = socket.openWriteChannel(autoFlush = true)
                        val input = socket.openReadChannel()

                        // Si conecta, enviamos el Anuncio de Conexión (Punto 1 y 4)
                        try {
                            val anuncio = SocketMessage(
                                type = MessageType.ANUNCIO_CONEXION,
                                senderIp = miIp,
                                senderRole = miRol
                            )

                            val jsonMsg = Json.encodeToString(SocketMessage.serializer(), anuncio)
                            //val jsonMsg = "{\"type\":\"PING\"}"

                            // ESCRIBIMOS Y FORZAMOS FINALIZACIÓN DEL FLUJO DE SALIDA
                            output.writeStringUtf8(jsonMsg + "\n")
                            // No cerramos el socket aún, pero le decimos al canal que terminamos de enviar
                            // Esto ayuda a servidores que esperan el final del stream
                            delay(300)

                            // LEEMOS RESPUESTA
                            val responseLine = withTimeoutOrNull(2000) {
                                input.readUTF8Line()
                            }

                            if (responseLine != null) {
                                val resp = Json.decodeFromString<SocketMessage>(responseLine)
                                resp.listaNodos?.forEach { registrarNodo(it.ip, it.role) }
                                Log.d("RondyNetwork", "✅ Nodo verificado en: $targetIp")
                            } else {
                                Log.w("RondyNetwork", "⚠️ $targetIp aceptó conexión pero no respondió datos.")
                            }
                        } finally {
                            // Cierre ordenado
                            socket.close()
                        }
                    }
                } catch (e: Exception) {
                    val errorMsg = e.message ?: e.javaClass.simpleName
                    Log.w("RondyNetwork", "No se pudo conectar a $targetIp: ${errorMsg}")
                }
            }
        }.joinAll() // Esperar a que termine el barrido completo
        Log.d("RondyNetwork", "Finalizado escaneo de companieros en red: ${prefix}0/24")
    }

    private suspend fun procesarSocketEntrante(socket: Socket) {
        val input = socket.openReadChannel()
        val output = socket.openWriteChannel(autoFlush = true)
        try {
            val line = input.readUTF8Line() ?: return
            val msg = Json.decodeFromString<SocketMessage>(line)

            when(msg.type) {
                MessageType.ANUNCIO_CONEXION -> {
                    // PUNTO 4: Alguien nuevo se conectó, le mandamos nuestra lista
                    registrarNodo(msg.senderIp, msg.senderRole)
                    val respuesta = SocketMessage(
                        type = MessageType.ACTUALIZACION_LISTA,
                        senderIp = getMiIp(),
                        senderRole = miRol,
                        listaNodos = _nodosActivos.value.toList()
                    )
                    output.writeStringUtf8(Json.encodeToString(SocketMessage.serializer(),respuesta) + "\n")
                }
                MessageType.REGISTRO_INGRESO -> {
                    // PUNTO 3: Guardar en cache externa
                    msg.registro?.let { bitacoraExterna.add(it) }
                }
                MessageType.ACTUALIZACION_LISTA -> {
                    // PUNTO 5 y 6: Sincronizar nodos
                    msg.listaNodos?.forEach { registrarNodo(it.ip, it.role) }
                }
                MessageType.AVISO_ESPECIFICO -> {
                    // COMANDOS ENTRE MODULOS
                    Log.d("RondyNetwork", "Recibido aviso de ${msg.senderIp}: ${msg.mensajeExtra}")
                    when (msg.mensajeExtra) {
                        "SOLICITAR_AUTORIZACION" -> {
                            //Inicializa con vacio
                            RESPUESTA_SOLICITAR_AUTORIZACION.set("")
                            //Aqui se debe hacer algo para decirle a caseta o preguntar al activity la respeusta
                            // y despues enviar la respuesta
                        }
                        "RESPUESTA_AUTORIZACION" -> {
                            RESPUESTA_SOLICITAR_AUTORIZACION.compareAndSet("",msg.respuestaCommando)
                        }
                    }
                }
                else -> {}
            }
        } finally {
            socket.close()
        }
    }

    // PUNTO 8: Enviar avisos específicos
    fun enviarAvisoModulo(rolTarget: String, mensaje: String) = scope.launch {
        _nodosActivos.value.filter { it.role == rolTarget }.forEach { nodo ->
            enviarMensaje(nodo.ip,MessageType.AVISO_ESPECIFICO, mensajeExtra = mensaje)
        }
    }

    fun solicitarAutorizacionCaseta(registro: AccesoBitacora? = null)= scope.launch{
        _nodosActivos.value.filter { it.role == "CASETA" }.forEach { nodo ->
            enviarMensaje(nodo.ip,MessageType.AVISO_ESPECIFICO, mensajeExtra = "SOLICITAR_AUTORIZACION", registro = registro)
        }
    }

    // Punto 5: Validar que estén activos
    private fun iniciarMonitorValidacionNodos() = scope.launch {
        while (isActive) {
            delay(30000) // Cada 30 seg
            val listaActual = _nodosActivos.value
            listaActual.forEach { nodo ->
                val activo = pingTcp(nodo.ip)
                if (!activo) {
                    val nueva = _nodosActivos.value.toMutableSet()
                    nueva.remove(nodo)
                    _nodosActivos.value = nueva
                }
            }
        }
    }

    // Función para obtener la IP real de la tablet en la red Wifi
    fun getMiIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (intl in interfaces) {
                val addrs = intl.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr.indexOf(':') < 0) return sAddr // Retorna IPv4
                    }
                }
            }
            "127.0.0.1"
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    // Función genérica para enviar mensajes TCP (Punto 2 y 8)
    private suspend fun enviarMensaje( ip: String,type: MessageType, registro: AccesoBitacora? = null, mensajeExtra: String? = null) {
        try {
            withTimeout(2000) {
                val socket = aSocket(selectorManager).tcp().connect(ip, PORT)
                val output = socket.openWriteChannel(autoFlush = true)
                val msg = SocketMessage(
                    type = type,
                    senderIp = getMiIp(),
                    senderRole = miRol,
                    registro = registro,
                    mensajeExtra = mensajeExtra.toString()
                )
                output.writeStringUtf8(Json.encodeToString(SocketMessage.serializer(), msg) + "\n")
                socket.close()
            }
        } catch (e: Exception) {
            Log.e("RondyNetwork", "Error enviando $type a $ip: ${e.message}")
        }
    }

    // Persistencia en Disco (Punto 6)
    private fun registrarNodo(ip: String, role: String) {
        val nuevoNodo = RondyNodo(ip, role)
        val nuevaLista = _nodosActivos.value.toMutableSet()

        // Evitar duplicados por IP
        nuevaLista.removeAll { it.ip == ip }
        nuevaLista.add(nuevoNodo)

        _nodosActivos.value = nuevaLista

        // Guardar en MySettings para el Punto 1 del siguiente inicio
        val ipsString = nuevaLista.joinToString(",") { it.ip }
        mySettings.saveString("CACHE_NODOS_IPS", ipsString)
    }

    // Función para validar vida (Punto 5)
    private suspend fun pingTcp(ip: String): Boolean {
        return try {
            withTimeout(1500) {
                val socket = aSocket(selectorManager).tcp().connect(ip, PORT)
                socket.close()
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}