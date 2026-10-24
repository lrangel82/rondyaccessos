package com.larangel.rondyaccesos.models.sockets

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.AtomicReference
import com.larangel.rondyaccesos.models.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
    private val PORT = 54321

    // Lista de nodos conocidos (Punto 1 y 6)
    private val _nodosActivos = MutableStateFlow<Set<RondyNodo>>(emptySet())

    // Cache de registros recibidos de otros (Punto 3)
    val bitacoraExterna = mutableListOf<AccesoBitacora>()

    //ATOMICS para respuestas
    val RESPUESTA_SOLICITAR_AUTORIZACION = AtomicReference<String>("")

    private val _consoleLogs = MutableStateFlow<List<String>>(emptyList())
    val consoleLogs: StateFlow<List<String>> = _consoleLogs


    init {
        iniciarServidorTCP()
        realizarHandshakeInicial()
    }

    private fun logToConsole(tag: String, msg: String) {
        Log.d(tag, msg) // Seguir viéndolo en Android Studio

        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "[$timestamp] $msg"

        // Mantener solo las últimas 50 líneas para no saturar la memoria
        _consoleLogs.update { (it + newLog).takeLast(50) }
    }

    // PUNTO 1, 4 y 7: Handshake y Descubrimiento
    fun realizarHandshakeInicial(force: Boolean = false) = scope.launch {
        val _cacheIps = mySettings.getString("CACHE_NODOS_IPS","") // Guardar como "192.168.1.5,192.168.1.10"
        val cacheIps = _cacheIps.split(",")

        if (cacheIps.isNotEmpty() && !force) {
            // Intentar avisar a los que ya conocemos
            cacheIps.forEach { ip ->
                enviarMensaje(ip, MessageType.ANUNCIO_CONEXION)
            }
        } else {
            // PUNTO 7: Buscar en el segmento de red si no hay nada en cache
            descubrirNodosEnSegmento()
        }
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
            logToConsole("RondyNetwork", "Servidor TCP iniciado en puerto $PORT")
            while (isActive) {
                val socket = serverSocket.accept()
                val remoteAddress = socket.remoteAddress.toString()
                logToConsole("RondyNetwork", "📡 Nueva conexión entrante desde: $remoteAddress")
                launch {
                    try {
                        procesarSocketEntrante(socket)
                    } catch (e: Exception) {
                        logToConsole("RondyNetwork", "Error procesando socket de $remoteAddress: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            logToConsole("RondyNetwork", "Fallo crítico al iniciar servidor: ${e.message}")
        }
    }

    // PUNTO 7: Buscar en el segmento de red
    private suspend fun descubrirNodosEnSegmento() = withContext(Dispatchers.IO) {
        val miIp = getMiIp()
        if (miIp == "127.0.0.1") return@withContext

        // Extraer el prefijo (ej: de 192.168.1.50 a 192.168.1.)
        val prefix = if(esEmulador()) "10.0.2." else miIp.substringBeforeLast(".") + "."

        logToConsole("RondyNetwork", "Iniciando escaneo de red en segmento: ${prefix}0/24 myIP:${miIp}")

        // Lanzar 254 corrutinas en paralelo para un escaneo rápido
        (1..254).map { i ->
            launch {
                val targetIp = prefix + i
                if (targetIp == miIp) return@launch // Ignorarme a mí mismo
                //logToConsole("RondyNetwork", "testeando SOCKET en ip: $targetIp")
                try {
                    enviarMensaje(targetIp,MessageType.ANUNCIO_CONEXION, printError = false)

                } catch (e: Exception) {
                    val errorMsg = e.message ?: e.javaClass.simpleName
                    logToConsole("RondyNetwork", "No se pudo conectar a $targetIp: ${errorMsg}")
                }
            }
        }.joinAll() // Esperar a que termine el barrido completo
        logToConsole("RondyNetwork", "Finalizado escaneo de companieros en red: ${prefix}0/24")
    }

    //#########################################
    //##### ProcesarLineaJSon COMMANDOS #######
    private suspend fun procesarCOMMANDOS(linea: String,_output: io.ktor.utils.io.ByteWriteChannel? = null) {
        val msg = Json.decodeFromString<SocketMessage>(linea)
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
                if (_output != null)
                    _output.writeStringUtf8(Json.encodeToString(SocketMessage.serializer(),respuesta) + "\n")
                //output.flush()
            }
            MessageType.REGISTRO_INGRESO -> {
                // PUNTO 3: Guardar en cache externa
                msg.registro?.let { bitacoraExterna.add(it) }
            }
            MessageType.ACTUALIZACION_LISTA -> {
                // PUNTO 5 y 6: Sincronizar nodos
                logToConsole("RondyNetwork", "Recibido ACTUALIZACION_LISTA ${msg.senderRole}: ${msg.listaNodos}")
                //registrarNodo(remoteAddress, msg.senderRole)
                msg.listaNodos?.forEach { registrarNodo(it.ip, it.role) }
            }
            MessageType.AVISO_ESPECIFICO -> {
                // COMANDOS ENTRE MODULOS
                logToConsole("RondyNetwork", "Recibido aviso de ${msg.senderIp}: ${msg.mensajeExtra}")
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
    }
    //#########################################

    //##### ENTRADAS AL SOCKET
    private suspend fun procesarSocketEntrante(socket: Socket,
                                               _input: io.ktor.utils.io.ByteReadChannel? = null,
                                               _output: io.ktor.utils.io.ByteWriteChannel? = null) {
        val input = _input ?: socket.openReadChannel()
        val output = _output ?: socket.openWriteChannel(autoFlush = false)
        val remoteAddress = socket.remoteAddress.toString().substringAfter("/").substringBefore(":")
        try {
            logToConsole("RondyNetwork", "⏳ Suspendiendo corrutina, esperando bytes reales desde Python...")

            val line = withTimeoutOrNull(8000) {
                input.readUTF8Line()
            }
            logToConsole("RondyNetwork", "\uD83D\uDCF2 Recibiendo de $remoteAddress: LECTURA SOCKET: $line")
            //val line = input.readUTF8Line() ?: return
            if (line.isNullOrEmpty()) {
                logToConsole("RondyNetwork", "⚠️ Se recibió un string vacío o nulo (Timeout de lectura)")
                return
            }
            val msg = Json.decodeFromString<SocketMessage>(line)

            procesarCOMMANDOS(line,output)

//            when(msg.type) {
//                MessageType.ANUNCIO_CONEXION -> {
//                    // PUNTO 4: Alguien nuevo se conectó, le mandamos nuestra lista
//                    registrarNodo(msg.senderIp, msg.senderRole)
//                    val respuesta = SocketMessage(
//                        type = MessageType.ACTUALIZACION_LISTA,
//                        senderIp = getMiIp(),
//                        senderRole = miRol,
//                        listaNodos = _nodosActivos.value.toList()
//                    )
//                    output.writeStringUtf8(Json.encodeToString(SocketMessage.serializer(),respuesta) + "\n")
//                    //output.flush()
//                }
//                MessageType.REGISTRO_INGRESO -> {
//                    // PUNTO 3: Guardar en cache externa
//                    msg.registro?.let { bitacoraExterna.add(it) }
//                }
//                MessageType.ACTUALIZACION_LISTA -> {
//                    // PUNTO 5 y 6: Sincronizar nodos
//                    logToConsole("RondyNetwork", "Recibido ACTUALIZACION_LISTA ${msg.senderRole}: ${msg.listaNodos}")
//                    registrarNodo(remoteAddress, msg.senderRole)
//                    msg.listaNodos?.forEach { registrarNodo(it.ip, it.role) }
//                }
//                MessageType.AVISO_ESPECIFICO -> {
//                    // COMANDOS ENTRE MODULOS
//                    logToConsole("RondyNetwork", "Recibido aviso de ${msg.senderIp}: ${msg.mensajeExtra}")
//                    when (msg.mensajeExtra) {
//                        "SOLICITAR_AUTORIZACION" -> {
//                            //Inicializa con vacio
//                            RESPUESTA_SOLICITAR_AUTORIZACION.set("")
//                            //Aqui se debe hacer algo para decirle a caseta o preguntar al activity la respeusta
//                            // y despues enviar la respuesta
//                        }
//                        "RESPUESTA_AUTORIZACION" -> {
//                            RESPUESTA_SOLICITAR_AUTORIZACION.compareAndSet("",msg.respuestaCommando)
//                        }
//                    }
//                }
//                else -> {}
//            }
        } catch (e: Exception) {
            logToConsole("RondyNetwork", "Error procesando datos entrantes: ${e.message}")
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
            val ipDestino = if (esEmulador()) "10.0.2.2" else nodo.ip
            logToConsole("RondyNetwork", "Solicitando autorización a CASETA en $ipDestino...")
            enviarMensaje(ipDestino,MessageType.AVISO_ESPECIFICO, mensajeExtra = "SOLICITAR_AUTORIZACION", registro = registro)
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
    private suspend fun enviarMensaje( ip: String,type: MessageType, registro: AccesoBitacora? = null, mensajeExtra: String? = null, printError: Boolean? = true) {
        withContext(Dispatchers.IO) {
            var socket: io.ktor.network.sockets.Socket? = null
            try {
                //withTimeout(5000) { // Aumentamos un poco a 3 segundos para el barrido masivo
                    socket = aSocket(selectorManager).tcp().connect(ip, PORT){
                        keepAlive = true
                        socketTimeout = 10000
                    }
                    val input = socket.openReadChannel()
                    val output = socket.openWriteChannel(autoFlush = true) // Cambiar a false para controlar el flujo

                    val msg = SocketMessage(
                        type = type,
                        senderIp = getMiIp(),
                        senderRole = miRol,
                        registro = registro,
                        mensajeExtra = mensajeExtra.toString()
                    )

                    // Enviar mensaje con salto de línea
                    logToConsole("RondyNetwork", "Preparando mensaje ${type} para ${ip}...")
                    output.writeStringUtf8(Json.encodeToString(SocketMessage.serializer(), msg) + "\n")
                    //output.flush() // Forzar el envío real de los bytes a la red AHORA
                    logToConsole("RondyNetwork", "📤 Enviado ${type} a ${ip} esperando respuesta...")
                    //delay(4000)
                    val respuestaServidor = withTimeoutOrNull(6000) {
                        input.readUTF8Line()
                    }
                    //logToConsole("RondyNetwork", "...4 segundos esepera ...")
                    logToConsole("RondyNetwork", "📱 Respuesta final desde Python: $respuestaServidor")



                // Procesar la respuesta del servidor antes de dejar morir la corrutina
                    //procesarSocketEntrante(socket, input, output)
                    procesarCOMMANDOS(respuestaServidor.toString())
                //}
            } catch (e: Exception) {
                if (printError == true) {
                    logToConsole("RondyNetwork", "Error enviando $type a $ip: ${e.message}")
                }
            } finally {
                // Aseguramos el cierre formal y limpio del socket al terminar todo el proceso
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
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

    fun esEmulador(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic"))
                || Build.FINGERPRINT.contains("generic")
                || Build.FINGERPRINT.contains("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator")
    }
}