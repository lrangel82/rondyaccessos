package com.larangel.rondyaccesos.vehicular

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.models.*
import com.larangel.rondyaccesos.models.network.BotCasetaApiService
import com.larangel.rondyaccesos.models.network.ValidarVisitaRequest
import com.larangel.rondyaccesos.models.network.WhatsappAuthStatus
import com.larangel.rondyaccesos.models.sockets.MessageType
import com.larangel.rondyaccesos.models.sockets.RondySocketClient
import com.larangel.rondyaccesos.models.sockets.SocketMessage
import com.larangel.rondyaccesos.models.sync.SyncManager
import com.larangel.rondyaccesos.utils.GeminiVoiceAssistant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

class IngresoVehicularViewModel(
                                application: Application,
                                private val dataRaw: DataRawRondin,
                                private val geminiVoiceAssistant: GeminiVoiceAssistant,
                                private val apiService: BotCasetaApiService,
                                private val mySettings: MySettings
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(IngresoVehicularUiState())
    val uiState: StateFlow<IngresoVehicularUiState> = _uiState.asStateFlow()

    private val socketClient = RondySocketClient()
    private val guardarMutex = Mutex()
    private var timerJob: Job? = null
    private val TIMEOUT_SEGUNDOS = 30

    private val _whatsappStatus = MutableStateFlow<WhatsappAuthStatus>(WhatsappAuthStatus.Idle)
    val whatsappStatus: StateFlow<WhatsappAuthStatus> = _whatsappStatus.asStateFlow()
    private var whatsappPollingJob: Job? = null


    var listadoMotivosPredefinidos: List<String> = emptyList()
    var todosLosDomiciliosCache: List<List<Any>> = emptyList() // calle, numero, clave

    // Control parameters loaded dynamically from your S3 configuration
    var urlCamaraPlacasRtsp: String = "rtsp://luisrangel:mevale14@172.16.1.67:554/stream2"
    var urlCamaraQrRtspFallback: String = ""
    var usarCamaraLocalParaQr: Boolean = true

    // State flows to toggle the hardware reconfiguration screens on connection drops
    private val _camaraPlacaFalla = MutableStateFlow(false)
    val camaraPlacaFalla: StateFlow<Boolean> = _camaraPlacaFalla.asStateFlow()

    private val _camaraQrFalla = MutableStateFlow(false)
    val camaraQrFalla: StateFlow<Boolean> = _camaraQrFalla.asStateFlow()

    // Flag controlling the activation of the OCR processing task loop
    private val _vlcStreamActive = MutableStateFlow(false)
    val vlcStreamActive: StateFlow<Boolean> = _vlcStreamActive.asStateFlow()

    private var qrCooldownActivo = false


    init {
        cargarConfiguracionesIniciales()
        reiniciarAsistenteCompleto()
    }

    private fun cargarConfiguracionesIniciales() {
        listadoMotivosPredefinidos = listOf("Visitante", "Uber/Taxi", "Residente sin tag", "Paqueteria", "Gas", "ComidaADomicilio", "Policia", "Camion Basura", "Grua", "Ambulancia")
//        todosLosDomiciliosCache = listOf(
//            listOf("Circuito Olmos", "10", "OLM10"),
//            listOf("Circuito Olmos", "24", "OLM24"),
//            listOf("Circuito Olmos", "35", "OLM35"),
//            listOf("Paseo Bugambilias", "5", "BUG5"),
//            listOf("Paseo Bugambilias", "12", "BUG12")
//        )
        todosLosDomiciliosCache = dataRaw.getDomiciliosUbicacion()
    }

    fun reiniciarAsistenteCompleto() {
        timerJob?.cancel()
        whatsappPollingJob?.cancel()
        _uiState.update {
            IngresoVehicularUiState(
                currentStep = CaptureStep.SELECCION_MOTIVO,
                lblTopMensaje = "Asistente iniciado. Seleccione motivo.",
                segundosRestantes = TIMEOUT_SEGUNDOS
            )
        }
        viewModelScope.launch {
            geminiVoiceAssistant.subtitulosState.collect { speechText ->
                _uiState.update { it.copy(subtitulosAsistente = speechText) }
            }
        }
        //Register this ViewModel's pipeline processor to the Application scope
        val app = getApplication<RondyApplication>()
        app.registroCallbackActivo = { calle, numero, nombre, tipo, placa ->
            procesarEntidadesExtraidasPorGemini(calle, numero, nombre, tipo, placa)
        }
        controlarCicloDeVidaDeStreaming()
        geminiVoiceAssistant.forzarLocucionPorAltavoz("Bienvenido al condominio. Indique el motivo de su visita.")
        iniciarTimerInactividad()
    }

    fun iniciarTimerInactividad() {
        timerJob?.cancel()
        _uiState.update { it.copy(segundosRestantes = TIMEOUT_SEGUNDOS) }
        timerJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                while (isActive && _uiState.value.segundosRestantes > 0) {
                    delay(1000)
                    _uiState.update { current ->
                        current.copy(segundosRestantes = current.segundosRestantes - 1)
                    }
                }
                if (_uiState.value.segundosRestantes == 0) {
                    withContext(Dispatchers.Main) {
                        reiniciarAsistenteCompleto()
                    }
                }
            } catch (e: CancellationException) {
                // Cancelación controlada
            }
        }
    }

    // -- CAMARAS
    fun actualizarUrlPlacasRtsp(nuevaUrl: String) {
        urlCamaraPlacasRtsp = nuevaUrl
        _camaraPlacaFalla.value = false
        controlarCicloDeVidaDeStreaming()
    }
    fun cambiarOrigenQrAHardwareIp(url: String) {
        usarCamaraLocalParaQr = false
        urlCamaraQrRtspFallback = url
        _camaraQrFalla.value = false
    }
    private fun controlarCicloDeVidaDeStreaming() {
        val paso = _uiState.value.currentStep
        val placaVacia = _uiState.value.placaInput.trim().isEmpty()

        if (paso == CaptureStep.SELECCION_MOTIVO || (paso == CaptureStep.CAPTURA_PLACA && placaVacia)) {
            _vlcStreamActive.value = true
            Log.d("CameraManager", "Encendiendo Stream RTSP para lectura OCR de placas.")
        } else {
            _vlcStreamActive.value = false
            Log.d("CameraManager", "Apagando Stream RTSP para liberar memoria y ancho de banda.")
        }
    }
    fun registrarPlacaDetectadaPorOcr(placaOcr: String) {
        val limpia = placaOcr.replace(Regex("[^a-zA-Z0-9]"), "").uppercase().trim()
        if (limpia.length >= 3) {
            _uiState.update { it.copy(placaInput = limpia) }
            // Apagar la cámara de inmediato para liberar recursos (Principio de UI Optimista)
            controlarCicloDeVidaDeStreaming()
        }
    }
    fun reportarFallaConexionPlacas() {
        _camaraPlacaFalla.value = true
        _vlcStreamActive.value = false
    }
    fun reportarFallaConexionQr() {
        _camaraQrFalla.value = true
    }
    fun procesarContenidoQrDetectado(rawText: String) {
        if (qrCooldownActivo) return

        // Validar el prefijo estricto de tu condominio (Mapeado de Python Parte 7)
        if (rawText.startsWith("ginn")) {
            val payloadLimpio = rawText.substring(4) // Eliminar el prefijo "ginn"
            qrCooldownActivo = true

            _uiState.update { current ->
                current.copy(
                    qrData = payloadLimpio,
                    lblTopMensaje = "¡Código QR Válido Detectado! Procesando autorización...",
                    currentStep = CaptureStep.PROCESANDO_AUTORIZACION
                )
            }

            // TODO: Aquí puedes evaluar el payload si contiene "TERRAZA" o "VISITANTE"
            // y saltar automáticamente los pasos del formulario.

            // Bloqueo de bucle por 10 segundos (Equivalente al after(10000) de tu script Python)
            viewModelScope.launch {
                delay(10000)
                qrCooldownActivo = false
            }
        }
    }

    // --- ACCIONES SECUENCIALES DEL FLUJO DE CAPTURA ---

    fun seleccionarMotivo(motivo: String) {
        iniciarTimerInactividad()
        _uiState.update { it.copy(tipoInput = motivo) }

        val mUpper = motivo.uppercase()
        if (mUpper.contains("BASURA") || mUpper.contains("POLICIA") || mUpper.contains("AMBULANCIA")) {
            // Caso Especial: Excepción Inmediata sin Dirección
            _uiState.update {
                it.copy(
                    calleInput = "Administracion",
                    numeroInput = "1",
                    conductorInput = "SERVICIO PÚBLICO / EMERGENCIA",
                    descripcionInput = "Ingreso Exprés: $motivo",
                    currentStep = CaptureStep.CAPTURA_PLACA,
                    lblTopMensaje = "Pase de contingencia. Teclee o valide placas:"
                )
            }
        } else {
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme la calle de destino, porfavor.")
            _uiState.update {
                it.copy(
                    currentStep = CaptureStep.SELECCION_CALLE,
                    lblTopMensaje = "Indique la calle de destino:"
                )
            }
        }
        controlarCicloDeVidaDeStreaming()
    }

    fun seleccionarCalle(calle: String) {
        iniciarTimerInactividad()
        geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme el numero del domicilio.")
        _uiState.update {
            it.copy(
                calleInput = calle,
                currentStep = CaptureStep.SELECCION_NUMERO,
                lblTopMensaje = "Seleccione el número de casa para la calle $calle:"
            )
        }
        controlarCicloDeVidaDeStreaming()
    }

    fun seleccionarNumero(numero: String) {
        iniciarTimerInactividad()
        geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme el nombre del conductor.")
        _uiState.update {
            it.copy(
                numeroInput = numero,
                currentStep = CaptureStep.CAPTURA_NOMBRE,
                lblTopMensaje = "Ingrese el nombre del conductor:"
            )
        }
        controlarCicloDeVidaDeStreaming()
    }

    fun guardarNombreYPasarAPlacas(nombre: String) {
        iniciarTimerInactividad()
        geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme su placa.")
        _uiState.update {
            it.copy(
                conductorInput = nombre.uppercase(),
                currentStep = CaptureStep.CAPTURA_PLACA,
                lblTopMensaje = "Valide la placa tecleada o leída por la cámara:"
            )
        }
        controlarCicloDeVidaDeStreaming()
    }

    fun guardarPlacaYSolicitarAutorizacion(placa: String){
        geminiVoiceAssistant.forzarLocucionPorAltavoz("Estamos solicitando Autorizacion al Residente, espere un poco.")
        _uiState.update { it.copy(
            placaInput = placa ,
            lblTopMensaje = "SOLICITANDO AUTORIZACION... espere un poco",
            currentStep = CaptureStep.PROCESANDO_AUTORIZACION
        ) }
        controlarCicloDeVidaDeStreaming()
        iniciarFlujoAutorizacionWhatsapp()

    }

    // --- PROCESAMIENTO CON WHATSAPP Y ENLACE DE RENDERING API ---
    fun iniciarFlujoAutorizacionWhatsapp() {
        val state = _uiState.value.copy()
        // 1. Obtener números válidos del catálogo offline-first de DataRawRondin
        val telefonosArray = dataRaw.getWhatsappTelefonosDomicilio(state.calleInput, state.numeroInput)

        if (telefonosArray.isEmpty()) {
            _whatsappStatus.value = WhatsappAuthStatus.Error("No se encontraron números registrados para este domicilio.")
            return
        }


        val tokenApi = "Bearer " + mySettings.getString("TOKEN_API_BOTCASETA", "")
        val startTime = System.currentTimeMillis()

        // Cancelar flujos de consultas previas por seguridad
        whatsappPollingJob?.cancel()
        timerJob?.cancel()

        // 2. Temporizador visual nativo (Equivalente a dialog.after(1000) de Tkinter)
        timerJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                _whatsappStatus.update {
                    WhatsappAuthStatus.Solicitando(elapsed, state.conductorInput, state.calleInput, state.numeroInput)
                }
                _uiState.update { it.copy(currentStep = CaptureStep.PROCESANDO_AUTORIZACION) }
                delay(1000)
            }
        }

        // 3. Orquestador del ciclo de peticiones asíncronas (Polling Loop)
        whatsappPollingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var finalStatusFound = false
                var segundosRestantes = 300

                while (isActive && !finalStatusFound && segundosRestantes > 0) {
                    // Actualizar contador en la UI de forma atómica
                    _uiState.update { state ->
                        if (state.whatsappStatus is WhatsappAuthStatus.Solicitando) {
                            state.copy(whatsappStatus = (state.whatsappStatus as WhatsappAuthStatus.Solicitando).copy(segundos = segundosRestantes))
                        } else state
                    }

                    // Ejecutar sondeo de estatus cada 3 segundos en paralelo
                    if (segundosRestantes % 3 == 0) {
                        for (row in telefonosArray) {
                            val telefono = row[2].toString()
                            if (!isActive) return@launch

                            val requestPayload = ValidarVisitaRequest(
                                telefono = telefono,
                                calle = state.calleInput,
                                numero = state.numeroInput,
                                placas = state.placaInput,
                                nombre = state.conductorInput,
                                tiporegistro = state.tipoInput
                            )

                            val response = apiService.validarVisita(tokenApi, requestPayload)

                            if (!response.isSuccessful) {
                                Log.e(
                                    "WhatsappPolling",
                                    "Error en API validar_visita: ${response.code()}"
                                )
                                withContext(Dispatchers.Main) {
                                    _whatsappStatus.value =
                                        WhatsappAuthStatus.Error("Error de comunicación: ${response.code()}")
                                }
                                cancelarFlujoPorError()
                                return@launch
                            }

                            val body = response.body() ?: continue
                            val lastAct = body.last_actividad
                            val fechaLastActividad = body.fecha_ultima_actualizacion ?: ""

                            // 4. Validación de vigencia temporal estricta de 5 minutos en formato UTC
                            if (fechaLastActividad.length > 10) {
                                try {
                                    val dateUtcLast =
                                        Instant.parse(fechaLastActividad) // Parsea directo formatos ISO-8601 con Z / UTC
                                    val ahoraUtc = Instant.now()

                                    if (dateUtcLast.plus(5, ChronoUnit.MINUTES)
                                            .isBefore(ahoraUtc)
                                    ) {
                                        Log.i(
                                            "WhatsappPolling",
                                            "Respuesta descartada por antigüedad de +5 minutos: $fechaLastActividad"
                                        )
                                        continue // Salta al siguiente teléfono sin procesar estados viejos
                                    }
                                } catch (e: DateTimeParseException) {
                                    Log.e(
                                        "WhatsappPolling",
                                        "Fallo al procesar estampa de tiempo UTC de la API: $fechaLastActividad",
                                        e
                                    )
                                }
                            }

                            // 5. Evaluación de acciones condicionales según el estado de respuesta
                            when (lastAct) {
                                "visita_acceso_permitido" -> {
                                    val toInform =
                                        telefonosArray.filter { it[2].toString() != telefono }
                                    informarOtrosCopropietarios(
                                        tokenApi,
                                        toInform,
                                        telefono,
                                        "Acceso permitido",
                                        requestPayload
                                    )

                                    withContext(Dispatchers.Main) {
                                        _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                                    }
                                    procesarEjecucionGuardadoFinal(state, telefono, "visita_acceso_permitido")
                                    finalStatusFound = true
                                    break
                                }

                                "visita_acceso_denegado" -> {
                                    val toInform =
                                        telefonosArray.filter { it[2].toString() != telefono }
                                    informarOtrosCopropietarios(
                                        tokenApi,
                                        toInform,
                                        telefono,
                                        "Acceso denegado",
                                        requestPayload
                                    )

                                    withContext(Dispatchers.Main) {
                                        _whatsappStatus.value = WhatsappAuthStatus.Denegado
                                    }
                                    procesarEjecucionGuardadoFinal(state, telefono, "visita_acceso_denegado")
                                    finalStatusFound = true
                                    break
                                }

                                "timeout_visita" -> {
                                    withContext(Dispatchers.Main) {
                                        _whatsappStatus.value = WhatsappAuthStatus.Timeout
                                    }
                                    finalStatusFound = true
                                    break
                                }
                            }
                        }
                    }
                    if (finalStatusFound) break

                    // Intervalo de espera táctico de 3 segundos antes del siguiente ciclo de sondeo
                    delay(1000)
                    segundosRestantes--
                }
                // 5. Si el bucle termina sin respuesta, cae en Timeout
                _uiState.update { it.copy(whatsappStatus = WhatsappAuthStatus.Timeout) }
                geminiVoiceAssistant.forzarLocucionPorAltavoz("Tiempo de espera agotado. Por favor, contacte a administración manualmente.")

            } catch (e: Exception) {
                Log.e("WhatsappPolling", "Fallo crítico en el hilo del bucle de diálogo de WhatsApp", e)
                //withContext(Dispatchers.Main) { _whatsappStatus.value = WhatsappAuthStatus.Error(e.localizedMessage ?: "Error desconocido") }
                _uiState.update { it.copy(whatsappStatus = WhatsappAuthStatus.Error(e.localizedMessage ?: "Fallo desconocido")) }
            } finally {
                timerJob?.cancel()
            }
        }
    }
    private fun informarOtrosCopropietarios(token: String, telQuienes: List<List<Any>>, quienValido: String, respuesta: String, basePayload: ValidarVisitaRequest) {
        viewModelScope.launch(Dispatchers.IO) {
            for (row in telQuienes) {
                val telefono = row[2].toString()
                try {
                    val infoPayload = basePayload.copy(
                        telefono = telefono,
                        quien_valido = quienValido,
                        respuesta = respuesta
                    )
                    apiService.informarRespuestaVisita(token, infoPayload)
                } catch (e: Exception) {
                    Log.e("WhatsappPolling", "Fallo al despachar notificación de cierre a: $telefono", e)
                }
            }
        }
    }
    fun cancelarSolicitudManual() {
        whatsappPollingJob?.cancel()
        timerJob?.cancel()
        _whatsappStatus.value = WhatsappAuthStatus.Idle
    }
    private fun cancelarFlujoPorError() {
        whatsappPollingJob?.cancel()
        timerJob?.cancel()
    }
    private fun procesarEjecucionGuardadoFinal(state: IngresoVehicularUiState, quienValido: String, estatusFinal: String) {
        viewModelScope.launch(Dispatchers.Main) {
            delay(2000) // Sostiene el banner visual de éxito/negado en pantalla por 2 segundos antes de resetear
            _whatsappStatus.value = WhatsappAuthStatus.Idle

            // Invocamos la función transaccional pasándole el estatus mapeado
            val stateModificado = state.copy(
                descripcionInput = "Whatsapp respondio:xx...(${quienValido.takeLast(3)})",
                status = estatusFinal
            )
            ejecutarGuardadoTransaccionalFinal(stateModificado)
        }
    }

    // --- GUARDADO DE REGISTROS Y PERSISTENCIA OFF_LINE COMPLETA ---
    private fun ejecutarGuardadoTransaccionalFinal(state: IngresoVehicularUiState) {
        viewModelScope.launch {
            // Validación de reentrada atómica para evitar duplicados por dobles clics táctiles
            if (guardarMutex.isLocked) return@launch

            guardarMutex.withLock {
                _uiState.update { it.copy(lblTopMensaje = "Procesando ingreso...") }

                // 1. Generar estampas de tiempo unificadas para consistencia de datos
                val fechaActualStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                val horaActualStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val timestampCombinado = "$fechaActualStr $horaActualStr"

                // 2. Construir entidad DTO para la Red Local Distribuida (Ktor Sockets)
                val registroSocket = RegistroAcceso(
                    id = (java.time.LocalTime.now().toSecondOfDay() * -1).toString(), // ID offline temporal
                    fecha = fechaActualStr,
                    hora = horaActualStr,
                    placa = state.placaInput,
                    calle = state.calleInput,
                    numero = state.numeroInput,
                    tipo = state.tipoInput,
                    conductor = state.conductorInput,
                    descripcion = state.descripcionInput,
                    fotoPlacaPath = "internal/placa.jpg",
                    fotoRostroPath = "internal/rostro.jpg",
                    qrData = "",
                    statusStr = "acceso permitido"
                )

                // 3. Construir la Entidad Estructurada para DataRawRondin (Alineada a las 13 columnas de Sheets)
                val accesoBitacora = AccesoBitacora(
                    fechaCreado = timestampCombinado,
                    fechaIngreso = timestampCombinado,
                    placa = state.placaInput,
                    calle = state.calleInput,
                    numero = state.numeroInput,
                    tipo = state.tipoInput,
                    conductor = state.conductorInput,
                    descripcion = state.descripcionInput,
                    foto1Url = "internal/placa.jpg",
                    foto2Url = "internal/rostro.jpg",
                    qrData = "",
                    fechaSalida = "",
                    status = state.status
                )

                // 4. Envío Distribuido al Nodo Central (Caseta Padre) protegido contra fallas de red
                try {
                    _uiState.update { it.copy(lblTopMensaje = "Notificando a Caseta Principal...") }
                    val msg = SocketMessage(MessageType.REGISTRO_INGRESO, "client_ip", "INGRESO_VEHICULAR", registroSocket)

                    // Se ejecuta de manera asíncrona dedicada para no colgar el flujo si el servidor TCP tarda en responder
                    withContext(Dispatchers.IO) {
                        socketClient.enviarRegistroACaseta(msg)
                    }
                } catch (e: Exception) {
                    Log.e("IngresoVehicularVM", "Fallo de red local en Ktor Socket (Caseta remota inaccesible). Continuando en modo Offline-First.", e)
                }

                // 5. Persistir de forma inmediata en la capa de datos unificada de la app (RAM + MySettings + Queue)
                _uiState.update { it.copy(lblTopMensaje = "Guardando en bitácora local...") }
                val guardadoLocalExitoso = withContext(Dispatchers.IO) {
                    dataRaw.addBitacoraAccesos(accesoBitacora)
                }

                if (!guardadoLocalExitoso) {
                    Log.e("IngresoVehicularVM", "Error crítico al intentar indexar el acceso en las estructuras de DataRawRondin.")
                }

                // 6. Sincronización diferida nativa de Android al recuperar enlace de datos
                SyncManager.programarSincronizacionAlRecuperarInternet(getApplication())

                // 7. Notificación visual al Guardia y liberación del hardware
                _uiState.update { it.copy(lblTopMensaje = "¡ACCESO CONCEDIDO! Abriendo Barrera.") }

                // Retraso controlado para permitir que el operario visualice el estatus en la pantalla antes del reset
                delay(3000)

                // Retorna el flujo guiado al paso 1, apaga el Timer de Inactividad y reestablece parámetros de IA
                reiniciarAsistenteCompleto()
            }
        }
    }

    // --- PROCESADOR DE ENTRADAS DE VOZ DE GEMINI (Match forzado de Catálogo) ---
    fun procesarEntradaVozAsistenteGemini(textoEscuchado: String) {
        iniciarTimerInactividad()
        val query = textoEscuchado.trim().lowercase()
        if (query.isEmpty()) return

        // Actualizamos el estado para que el guardia vea en pantalla lo que el usuario dictó
        _uiState.update { it.copy(subtitulosAsistente = "🎤 Escuchado: \"$textoEscuchado\"") }
        val pasoActual = _uiState.value.currentStep

        val listaPalabras = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val numeroDePalabras = listaPalabras.size

        // ESTRATEGIA EN LÍNEA: Si hay red, delegamos la extracción estructurada a Gemini
        viewModelScope.launch(Dispatchers.Default) {
            if (numeroDePalabras == 1) {
                Log.d("ViewModelIA", "Comando de una sola palabra detectado ('$query'). Evitando Gemini.")
                procesarEntradaVozLocalFallback(query.uppercase(), pasoActual)
                return@launch
            }
            if (dataRaw.isNetworkAvailable()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        // Estructuramos el estado actual en un JSON para dárselo como contexto a Gemini
                        val datosAcumuladosJson = """
                    {
                      "paso_actual": "${pasoActual.name}",
                      "motivo_actual": "${_uiState.value.tipoInput}",
                      "calle_actual": "${_uiState.value.calleInput}",
                      "numero_actual": "${_uiState.value.numeroInput}",
                      "nombre_actual": "${_uiState.value.conductorInput}",
                      "placa_actual": "${_uiState.value.placaInput}"
                    }
                """.trimIndent()

                        // Llamamos a la infraestructura que creamos en la Fase 1 (GeminiVoiceAssistant)
                        // El SDK procesará la locución libre del usuario y rellenará los campos correspondientes
                        timerJob?.cancel()
                        geminiVoiceAssistant.procesarEntradaVoz(textoEscuchado, datosAcumuladosJson)
                        //iniciarTimerInactividad()

                    } catch (e: Exception) {
                        Log.e("ViewModelIA", "Error en el pipeline de Gemini: ${e.message}")
                        // Si la llamada a la nube truena, caemos de forma segura en el emparejador local
                        withContext(Dispatchers.Main) {
                            procesarEntradaVozLocalFallback(query, pasoActual)
                        }
                    }
                }
            } else {
                // ESTRATEGIA FUERA DE LÍNEA: Procesamiento determinista mediante comandos directos
                procesarEntradaVozLocalFallback(query, pasoActual)
            }
        }
    }
    /** Validar output IA */
    private fun procesarEntidadesExtraidasPorGemini(calle: String, numero: String, nombre: String, tipo: String, placa: String) {
        viewModelScope.launch(Dispatchers.Default) {
            var stepActual = _uiState.value.currentStep

            // --- PASO 0: MOTIVO ---
            if (tipo.isNotEmpty() && _uiState.value.tipoInput != tipo) {
                withContext(Dispatchers.Main) {
                    // Match forzado contra la lista del archivo .ini (tiporegistro)
                    val matchMotivo = listadoMotivosPredefinidos.find { it.uppercase() == tipo.uppercase() }
                    if (matchMotivo != null) {
                        _uiState.update { it.copy(tipoInput = matchMotivo) }
                    } else {
                        // Si no es un comando exacto, buscamos si la frase contiene la palabra clave
                        val coincidenciaParcial =
                            listadoMotivosPredefinidos.find { tipo.uppercase().contains(it.uppercase()) }
                        if (coincidenciaParcial != null) {
                            _uiState.update { it.copy(tipoInput = coincidenciaParcial) }
                        } else {
                            _uiState.update {
                                it.copy(
                                    subtitulosAsistente = "🤖 No reconozco ese motivo. Elija uno de la lista en pantalla.",
                                    currentStep = CaptureStep.SELECCION_MOTIVO,
                                    tipoInput = "",
                                )
                            }
                        }
                    }
                }

            }
            // --- PASO 1 & 2: VALIDACIÓN DE CALLE Y NÚMERO (DIRECCIÓN SIMILAR) ---
            if (calle.isNotEmpty() && numero.isNotEmpty() && (_uiState.value.calleInput != calle || _uiState.value.numeroInput != numero)) {
                // Execute your pre-compiled fuzzy matching utility method on background storage threads
                val posiblesDomiciliosSimilares: List<List<Any>> = dataRaw.getDomiciliosSimilares(calle, numero)

                withContext(Dispatchers.Main) {
                    when {
                        posiblesDomiciliosSimilares.isEmpty() -> {
                            // No matches found: Notify user over TTS and keep grid available for selection
                            geminiVoiceAssistant.forzarLocucionPorAltavoz("No me fue posible reconocer la direccion, Por favor seleccione con la direccion con los botones en pantalla.")
                            _uiState.update { it.copy(subtitulosAsistente = "Dirección no localizada. Por favor use los botones.") }
                        }
                        posiblesDomiciliosSimilares.size == 1 -> {
                            // Exact unique match verified: Extract accurate row cells cleanly
                            val matchExacto = posiblesDomiciliosSimilares[0]
                            val calleVerificada = matchExacto[0].toString()
                            val numeroVerificado = matchExacto[1].toString()

                            _uiState.update {
                                it.copy(
                                    calleInput = calleVerificada,
                                    numeroInput = numeroVerificado,
                                    //currentStep = CaptureStep.CAPTURA_NOMBRE, // Advance step automatically
                                    subtitulosAsistente = "Domicilio validado: $calleVerificada #$numeroVerificado. Ingrese Nombre."
                                )
                            }
                            iniciarTimerInactividad()
                        }
                        posiblesDomiciliosSimilares.size > 1 -> {
                            // Multiple partial matches: Update local list cache to force activity to render selection chips
                            // Mapped from your python implementation (open_multiple_domicilios_dialog criteria)
                            val textoDomiciliosEncontrados = posiblesDomiciliosSimilares.joinToString(separator = ", ") { fila ->
                                val calleFila = fila.getOrNull(0)?.toString() ?: ""
                                val numeroFila = fila.getOrNull(1)?.toString() ?: ""
                                "$calleFila:$numeroFila"
                            }
                            geminiVoiceAssistant.forzarLocucionPorAltavoz("Múltiples opciones encontradas.")
                            val mensajeCompleto = "Múltiples opciones encontradas ($textoDomiciliosEncontrados). Seleccione con los botones el correcto:"
                            withContext(Dispatchers.Main) {
                                _uiState.update { current ->
                                    current.copy(
                                        subtitulosAsistente = mensajeCompleto,
                                        currentStep = CaptureStep.SELECCION_CALLE, // 🔄 Forzar el paso a captura de calle
                                        listaDomiciliosFiltrados = posiblesDomiciliosSimilares // Guardar las coincidencias para la Activity
                                    )
                                }
                                iniciarTimerInactividad()
                            }
                            // In real deployment, you re-map 'todosLosDomiciliosCache' with 'posiblesDomiciliosSimilares'
                            // to automatically mutate your grid layer to display only the matched rows.
                        }
                    }
                }
            }

            // --- PASO 3: CAPTURA DE NOMBRE VIA VOICE ASYNC ---
            if (nombre.isNotEmpty() && _uiState.value.conductorInput != nombre) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        conductorInput = nombre,
                        //currentStep = CaptureStep.CAPTURA_PLACA, // Advance step automatically
                        //subtitulosAsistente = "Nombre: $nombre. Ingrese su placa."
                        )
                    }
                }
            }

            // --- PASO 4: CAPTURA Y VALIDACIÓN DE PLACAS ---
            if (placa.isNotEmpty() && _uiState.value.placaInput != placa) {
                val placaSanitizada = tipo.replace(Regex("[^a-zA-Z0-9]"), "").uppercase().trim()
                if (placaSanitizada.length >= 3) {
                    _uiState.update { it.copy(placaInput = placaSanitizada) }
                }
            }

            // --- VERIFICAR SI REQUIRE IR A UN PASO ESPECIFICO
            if (stepActual == _uiState.value.currentStep) {
                var msgAsitente=""
                if (_uiState.value.tipoInput.isEmpty()) {
                    stepActual = CaptureStep.SELECCION_MOTIVO
                    msgAsitente = "🤖 No reconozco ese motivo. Elija uno de la lista en pantalla."
                }else if (_uiState.value.calleInput.isEmpty()) {
                    stepActual = CaptureStep.SELECCION_CALLE
                    msgAsitente = "🤖 A que calle desea ingresar."
                }else if (_uiState.value.numeroInput.isEmpty()) {
                    stepActual = CaptureStep.SELECCION_NUMERO
                    msgAsitente = "🤖 A que numero."
                }else if (_uiState.value.conductorInput.isEmpty()) {
                    stepActual = CaptureStep.CAPTURA_NOMBRE
                    msgAsitente = "🤖 Indique su nombre."
                }else if (_uiState.value.placaInput.isEmpty()) {
                    stepActual = CaptureStep.CAPTURA_PLACA
                    msgAsitente = "🤖 Indique la Placa."
                }else {
                    //Datos completos solicitar acceso
                    withContext(Dispatchers.Main) {
                        iniciarFlujoAutorizacionWhatsapp()
//                        dispararProtocoloDeSeguridadYWhatsApp(
//                            _uiState.value.placaInput,
//                            "Autorizado por Voz Inteligente"
//                        )
                    }
                }
                _uiState.update { it.copy(currentStep = stepActual, subtitulosAsistente = msgAsitente) }
            }

        }
    }
    /** Validar palabra contra opciones */
    private fun procesarEntradaVozLocalFallback(query: String, pasoActual: CaptureStep) {
        when (pasoActual) {
            CaptureStep.SELECCION_MOTIVO -> {
                // Match forzado contra la lista del archivo .ini (tiporegistro)
                val matchMotivo = listadoMotivosPredefinidos.find { it.uppercase() == query }
                if (matchMotivo != null) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Motivo: $matchMotivo") }
                    seleccionarMotivo(matchMotivo)
                } else {
                    // Si no es un comando exacto, buscamos si la frase contiene la palabra clave
                    val coincidenciaParcial = listadoMotivosPredefinidos.find { query.contains(it.uppercase()) }
                    if (coincidenciaParcial != null) {
                        _uiState.update { it.copy(subtitulosAsistente = "🤖 Motivo: $coincidenciaParcial") }
                        seleccionarMotivo(coincidenciaParcial)
                    } else {
                        geminiVoiceAssistant.forzarLocucionPorAltavoz("Lo siento no reconozco ese motivo. Elija uno de la lista en pantalla.", false)
                        _uiState.update { it.copy(subtitulosAsistente = "🤖 Escucue: \"$query\"\n No reconozco ese motivo. Elija uno de la lista en pantalla.") }
                    }
                }
            }

            CaptureStep.SELECCION_CALLE -> {
                // Extraer las calles únicas dadas de alta en el condominio
                val listaCallesUnicas = todosLosDomiciliosCache.map { it[0].toString() }.distinct()
                val matchCalle = listaCallesUnicas.find { it.uppercase() == query || query.contains(it.uppercase()) }

                if (matchCalle != null) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Calle selecionada: $matchCalle") }
                    seleccionarCalle(matchCalle)
                } else {
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("Calle no localizada. Presione un botón de la rejilla.", false)
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Escucue: \"$query\"\n Calle no localizada. Presione un botón de la rejilla.") }
                }
            }

            CaptureStep.SELECCION_NUMERO -> {
                // Filtrar los números válidos en RAM que pertenecen a la calle que ya se seleccionó
                val numerosValidosParaCalle = todosLosDomiciliosCache
                    .filter { it[0].toString().equalsIgnoreCase(_uiState.value.calleInput) }
                    .map { it[1].toString() }

                // Buscamos si el dictado numérico coincide con alguna de las casas existentes
                val matchNumero = numerosValidosParaCalle.find { query.contains(it) || it == query }

                if (matchNumero != null) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Numero seleccionado: $matchNumero") }
                    seleccionarNumero(matchNumero)
                } else {
                    // Invocamos la función analizada de similitud para proponer aproximaciones
                    // DataRawRondin.getDomiclioSimilar(calle, numero)
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("Numero inexistente. Seleccione el correcto de lista.", false)
                    _uiState.update { it.copy(subtitulosAsistente = "🤖Escucue: \"$query\"\n  Número inválido para la calle ${_uiState.value.calleInput}.") }
                }
            }

            CaptureStep.CAPTURA_NOMBRE -> {
                // En el paso del nombre no podemos forzar un catálogo.
                // Absorber el texto transcrito directamente de la voz limpia como el nombre del conductor
                val nombreLimpio = query.replace(Regex("[^a-zA-Z\\s]"), "").trim()
                if (nombreLimpio.length > 2) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Nombre: $nombreLimpio") }
                    guardarNombreYPasarAPlacas(nombreLimpio)
                } else {
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("El nombre parece muy corto. Repítalo o use el teclado.", false)
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 El nombre parece muy corto. Repítalo o use el teclado.") }
                }
            }

            CaptureStep.CAPTURA_PLACA -> {
                // Sanitizar la matrícula eliminando guiones o espacios dictados por error
                val placaLimpia = query.replace(Regex("[^a-zA-Z0-9]"), "").trim().uppercase()

                // Expresión de nomenclatura nacional: Validar que contenga estructura alfanumérica mínima
                if (placaLimpia.length >= 3) {
                    _uiState.update { it.copy(
                        subtitulosAsistente = "🤖 Placa validada correctamente por voz.",
                        placaInput = placaLimpia ,
                        lblTopMensaje = "SOLICITANDO AUTORIZACION... espere un poco"
                    ) }
                    iniciarFlujoAutorizacionWhatsapp()
                    //dispararProtocoloDeSeguridadYWhatsApp(placaLimpia, "Captura por Voz Exitosa")
                } else {
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("Formato de placa inválido. Digítela manualmente.", false)
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Escucue: \"$query\"\n Formato de placa inválido. Digítela manualmente.") }
                }
            }

            else -> {}
        }
    }

    // Función utilitaria de extensión rápida para comparar strings ignorando mayúsculas
    private fun String.equalsIgnoreCase(other: String): Boolean {
        return this.lowercase() == other.lowercase()
    }

    override fun onCleared() {
        super.onCleared()
        // Anti-memory leak rule: Clear callback reference when the current view session is destroyed
        val app = getApplication<Application>() as RondyApplication
        app.registroCallbackActivo = null
    }
}