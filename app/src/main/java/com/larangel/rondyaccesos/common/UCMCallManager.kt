package com.larangel.rondyaccesos.common

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocketFactory
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class UCMCallManager(
    val ip: String,
    val port: Int,
    val user: String,
    val pass: String,
    val ivrNumber: String
) {
    private val TAG = "UCM6202"

    // Variables de control de estado de la llamada actual
    var resultadoDtmf: String? = null
    var estadoLlamada: String? = null

    // En Kotlin/Android usamos Job y Corrutinas en lugar de threading.Event
    private var jobEscucha: Job? = null
    private var sockSeguro: SSLSocket? = null
    var escuchando: Boolean = false
    var numeroDestino: String? = null
    var actionIdActual: String? = null

    // Lógica para finalizar la espera de forma segura
    private val _eventoFinalizado = java.util.concurrent.atomic.AtomicBoolean(false)

    fun isEventoFinalizado(): Boolean = _eventoFinalizado.get()

    fun setEventoFinalizado(value: Boolean) {
        _eventoFinalizado.set(value)
        if (value) {
            escuchando = false
        }
    }

    /**
     * Inicia el procesamiento de tráfico en un hilo secundario mediante Corrutinas.
     * Reemplaza al método _procesar_trafico y el manejo del Thread en Python.
     */
    fun iniciarProcesamientoTrafico(scope: CoroutineScope) {
        escuchando = true
        setEventoFinalizado(false)

        jobEscucha = scope.launch(Dispatchers.IO) {
            try {
                // Configurar el timeout (None en Python equivale a 0 en Java/Kotlin, es decir, infinito)
                sockSeguro?.soTimeout = 0

                val reader = BufferedReader(InputStreamReader(sockSeguro?.inputStream, "UTF-8"))
                val buffer = StringBuilder()
                val charBuffer = CharArray(4096)
                var bytesRead: Int

                while (escuchando && !isEventoFinalizado()) {
                    bytesRead = reader.read(charBuffer)
                    if (bytesRead == -1) break // Socket cerrado por el servidor

                    buffer.append(charBuffer, 0, bytesRead)

                    // El protocolo AMI separa paquetes por una línea en blanco doble "\r\n\r\n"
                    while (buffer.contains("\r\n\r\n")) {
                        val index = buffer.indexOf("\r\n\r\n")
                        val paquete = buffer.substring(0, index)
                        buffer.delete(0, index + 4) // +4 para remover el "\r\n\r\n"

                        _analizarEvento(paquete)
                    }
                }
            } catch (e: SocketTimeoutException) {
                // Equivalente a socket.timeout en Python. No rompemos el ciclo, mantenemos vivo.
                Log.d(TAG, "UCM6202 -> Timeout de lectura (Inactividad), continuando...")
            } catch (e: SocketException) {
                // Equivalente a OSError / EBADF (Bad file descriptor)
                if (e.message?.contains("Socket closed", ignoreCase = true) == true) {
                    Log.w(TAG, "UCM6202 -> El socket fue cerrado externamente. Finalizando de forma limpia.")
                } else {
                    Log.e(TAG, "UCM6202 ->[-] Error de socket: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "UCM6202 ->[-] Error leyendo tráfico cifrado: ${e.message}")
            } finally {
                // Manejo del cierre del ciclo y desconexión física
                if (!isEventoFinalizado()) {
                    if (estadoLlamada == "LLAMANDO" || estadoLlamada == "EN_IVR") {
                        delay(500) // Margen de espera por si cuelga primero
                        if (!isEventoFinalizado()) {
                            estadoLlamada = "ERROR_CONEXION_PERDIDA [LLAMANDO, EN_IVR]"
                            setEventoFinalizado(true)
                        }
                    } else {
                        Log.e(TAG, "UCM6202 -> ERROR_CONEXION_PERDIDA.")
                        estadoLlamada = "ERROR_CONEXION_PERDIDA"
                        setEventoFinalizado(true)
                    }
                }
                Log.i(TAG, "UCM6202 -> Fin de hilo _procesar_trafico")
            }
        }
    }

    /**
     * Filtra e interpreta los eventos críticos del conmutador (AMI Asterisk)
     */
    private fun _analizarEvento(paquete: String) {
        Log.i(TAG, "UCM6202........ respuesta:$paquete")

        val destino = numeroDestino ?: ""
        if (isEventoFinalizado() || !paquete.contains(destino)) {
            return
        }

        // 1. Monitorear Estados de la llamada (Respuestas de red)
        if (paquete.contains("Event: Newstate")) {
            val match = Regex("""ChannelStateDesc:\s*(.+)""").find(paquete)
            if (match != null && match.groupValues[1].trim() == "Up") {
                Log.i(TAG, "UCM6202 -> La llamada ha sido contestada en el extremo remoto")
                if (estadoLlamada == "LLAMANDO") {
                    estadoLlamada = "EN_IVR"
                }
            }
        }
        // 2. Monitorear Fallos de Enlace o Fin de Llamada
        else if (paquete.contains("Event: Hangup")) {
            val match = Regex("""Cause-txt:\s*(.+)""").find(paquete)
            if (match != null) {
                val causa = match.groupValues[1].trim()
                when (causa) {
                    "No Answer" -> {
                        Log.i(TAG, "UCM6202 -> NO_CONTESTADA")
                        estadoLlamada = "NO_CONTESTADA"
                        setEventoFinalizado(true)
                    }
                    "User signaling", "Busy" -> {
                        Log.i(TAG, "UCM6202 -> RECHAZADA_OCUPADO")
                        estadoLlamada = "RECHAZADA_OCUPADO"
                        setEventoFinalizado(true)
                    }
                    "Normal Clearing" -> {
                        Log.i(TAG, "UCM6202 -> LLAMADA INCOMPLETA COLOGO SIN ACCION DETECTADA")
                        if (resultadoDtmf == null) {
                            estadoLlamada = "COLGO_SIN_ACCION"
                        }
                        setEventoFinalizado(true)
                    }
                    else -> {
                        Log.i(TAG, "UCM6202 -> LLAMADA COLGADA, Cause-txt:$causa")
                        if (resultadoDtmf == null) {
                            estadoLlamada = "COLGO_SIN_ACCION"
                        }
                        setEventoFinalizado(true)
                    }
                }
            }
        }

        // 3. Capturar si el comando Originate falló directamente
        if (paquete.contains("Event: OriginateResponse")) {
            val actionIdMatch = Regex("""ActionID:\s*(.+)""").find(paquete)
            val responseMatch = Regex("""Response:\s*(.+)""").find(paquete)
            val reasonMatch = Regex("""Reason:\s*(.+)""").find(paquete)

            if (actionIdMatch != null && actionIdMatch.groupValues[1].trim() == actionIdActual) {
                if (responseMatch != null && responseMatch.groupValues[1].trim() == "Failure") {
                    val razonTxt = reasonMatch?.groupValues[1]?.trim() ?: "Desconocida"
                    Log.w(TAG, "UCM6202 -> El conmutador no pudo originar la llamada. Razón: $razonTxt")
                    estadoLlamada = "FALLO_LINEA_TRONCAL"
                    setEventoFinalizado(true)
                    return
                }
            }
        }
        // 4. Monitorear captura de Teclas DTMF
        else if (paquete.contains("Event: DTMFEnd") || paquete.contains("Event: DTMFBegin")) {
            val digitMatch = Regex("""Digit:\s*(.+)""").find(paquete)
            val directionMatch = Regex("""Direction:\s*(.+)""").find(paquete)

            if (digitMatch != null && directionMatch != null && directionMatch.groupValues[1].trim() == "Received") {
                val tecla = digitMatch.groupValues[1].trim()
                when (tecla) {
                    "1" -> {
                        Log.i(TAG, "UCM6202 -> USUARIO contesto: ACEPTADO (1)")
                        resultadoDtmf = "ACEPTADO"
                        estadoLlamada = "EXITOSO"
                        setEventoFinalizado(true)
                    }
                    "3" -> {
                        Log.i(TAG, "UCM6202 -> USUARIO contesto: DENEGADO (3)")
                        resultadoDtmf = "DENEGADO"
                        estadoLlamada = "EXITOSO"
                        setEventoFinalizado(true)
                    }
                    else -> {
                        Log.i(TAG, "UCM6202 -> valor digitado erroneo tecla: '$tecla'")
                    }
                }
            }
        }
    }

    /**
     * Instancia la llamada hacia un número externo de manera suspendida (segura para hilos de Android).
     * Reemplaza al método enviar_solicitud_acceso de Python.
     */
    suspend fun enviarSolicitudAcceso(
        numeroDestino: String,
        nombre: String,
        tipo: String,
        timeoutEsperaSegundos: Long = 45
    ): ResponseUCM = withContext(Dispatchers.IO) {

        Log.i(TAG, "UCM6202 -> Realizar llamada solicitud acceso a $numeroDestino nombre: $nombre tipo: $tipo")

        // Reiniciar banderas de control
        resultadoDtmf = null
        estadoLlamada = "INICIANDO"
        setEventoFinalizado(false)
        this@UCMCallManager.numeroDestino = numeroDestino

        // Generar un ActionID único para rastrear la llamada (puedes usar un timestamp)
        val actionIdIdActual = "ACT_${System.currentTimeMillis()}"
        this@UCMCallManager.actionIdActual = actionIdIdActual

        // 1. Establecer conexión física segura (TLS) ignorando certificados (Igual que contexto_tls.verify_mode = ssl.CERT_NONE)
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            val factory = sslContext.socketFactory

            val socketBase = Socket()
            socketBase.connect(InetSocketAddress(ip, port), 5000) // Timeout de conexión física de 5 seg.

            sockSeguro = factory.createSocket(socketBase, ip, port, true) as SSLSocket
            sockSeguro?.startHandshake()

        } catch (e: Exception) {
            Log.e(TAG, "UCM6202 -> ERROR_CONMUTADOR data : No se pudo conectar al puerto AMI-TLS. e: ${e.message}")
            return@withContext ResponseUCM("ERROR_CONMUTADOR", detalles = "No se pudo conectar al puerto AMI-TLS.")
        }

        val outputStream: OutputStream? = sockSeguro?.outputStream
        val reader = BufferedReader(InputStreamReader(sockSeguro?.inputStream, "UTF-8"))

        // 2. Limpiar Banner de bienvenida (Equivalente a recv(1024))
        try {
            val bannerBuffer = CharArray(1024)
            reader.read(bannerBuffer)
        } catch (e: Exception) {
            sockSeguro?.close()
            Log.e(TAG, "UCM6202 -> ERROR_CONMUTADOR data: Error al recibir saludo inicial.")
            return@withContext ResponseUCM("ERROR_CONMUTADOR", detalles = "Error al recibir saludo inicial.")
        }

        // 3. Autenticación en la interfaz AMI
        try {
            val loginCmd = "Action: Login\r\nUsername: $user\r\nSecret: $pass\r\n\r\n"
            outputStream?.write(loginCmd.toByteArray(Charsets.UTF_8))
            outputStream?.flush()

            delay(500) // time.sleep(0.5)

            val loginRespBuffer = CharArray(2048)
            val bytesRead = reader.read(loginRespBuffer)
            val respLogin = String(loginRespBuffer, 0, bytesRead)

            if (!respLogin.contains("Response: Success")) {
                sockSeguro?.close()
                Log.e(TAG, "UCM6202 -> ERROR_CONMUTADOR data: Credenciales AMI rechazadas o IP bloqueada.")
                return@withContext ResponseUCM("ERROR_CONMUTADOR", detalles = "Credenciales AMI rechazadas o IP bloqueada.")
            }
        } catch (e: Exception) {
            sockSeguro?.close()
            return@withContext ResponseUCM("ERROR_CONMUTADOR", detalles = "Error durante el protocolo de autenticación.")
        }

        // 4. Iniciar hilo de escucha en tiempo real (Usando la función de la Parte 1)
        // Pasamos el scope actual de la corrutina para mantener la asincronía controlada
        iniciarProcesamientoTrafico(this)
        delay(1000) // time.sleep(1)

        // 5. Lanzar Acción de Marcado (Originate) usando Canal Local
        estadoLlamada = "LLAMANDO"
        val originateCmd = (
                "Action: Originate\r\n" +
                        "Channel: Local/$numeroDestino@from-internal/n\r\n" +
                        "Exten: $ivrNumber\r\n" +
                        "Context: from-internal\r\n" +
                        "Priority: 1\r\n" +
                        "Timeout: 15000\r\n" +
                        "ActionID: $actionIdIdActual\r\n" + // Agregado para que haga match en el parseo de la Parte 1
                        "CallerID: Acceso $nombre <$ivrNumber>\r\n\r\n"
                )

        try {
            outputStream?.write(originateCmd.toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) {
            escuchando = false
            sockSeguro?.close()
            Log.e(TAG, "UCM6202 -> ERROR_CONMUTADOR data: Fallo al enviar comando de marcación Originate e: ${e.message}")
            return@withContext ResponseUCM("ERROR_CONMUTADOR", detalles = "Fallo al enviar comando de marcación Originate.")
        }

        // 6. Monitoreo y Espera bloqueante controlada (Reemplaza a self.evento_finalizado.wait)
        var completadoATiempo = false
        val tiempoInicio = System.currentTimeMillis()
        val timeoutMaximoMs = timeoutEsperaSegundos * 1000

        while (System.currentTimeMillis() - tiempoInicio < timeoutMaximoMs) {
            if (isEventoFinalizado()) {
                completadoATiempo = true
                break
            }
            delay(200) // Pequeñas pausas de verificación para no saturar el procesador
        }

        // 7. Cierre formal de conexiones limpiando la memoria
        escuchando = false
        try {
            setEventoFinalizado(true)
            // Esperamos brevemente a que el job de escucha finalice por la bandera 'escuchando = false'
            jobEscucha?.cancelAndJoin()
            sockSeguro?.close()
            this@UCMCallManager.numeroDestino = null
            Log.i(TAG, "UCM6202 -> Socket cerrado ciclo terminado de llamada a $numeroDestino")
        } catch (e: Exception) {
            Log.e(TAG, "UCM6202 -> Error al finalizar el hilo y/o socket de llamada e: ${e.message}")
        }

        // 8. Mapeo y retorno del resultado final estructurado al bloque llamante
        if (!completadoATiempo) {
            return@withContext ResponseUCM(
                status = "TIMEOUT",
                detalles = "La llamada excedió el tiempo máximo de espera establecido."
            )
        }

        if (estadoLlamada == "EXITOSO") {
            return@withContext ResponseUCM(
                status = "EXITOSO",
                resultado = resultadoDtmf,
                detalles = "El usuario respondió e ingresó la opción: $resultadoDtmf"
            )
        }

        return@withContext ResponseUCM(
            status = estadoLlamada ?: "DESCONOCIDO",
            detalles = "La llamada no prosperó debido al estado: $estadoLlamada"
        )
    }
}

data class ResponseUCM(
    val status: String,
    val resultado: String? = null,
    val detalles: String
)