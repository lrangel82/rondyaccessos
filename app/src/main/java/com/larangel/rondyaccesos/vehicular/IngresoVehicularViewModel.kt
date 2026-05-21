package com.larangel.rondyaccesos.vehicular

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.models.*
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class IngresoVehicularViewModel(
                                application: Application,
                                private val dataRaw: DataRawRondin,
                                private val geminiVoiceAssistant: GeminiVoiceAssistant
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(IngresoVehicularUiState())
    val uiState: StateFlow<IngresoVehicularUiState> = _uiState.asStateFlow()

    private val socketClient = RondySocketClient()
    private val guardarMutex = Mutex()
    private var timerJob: Job? = null
    private var whatsappPollingJob: Job? = null
    private val TIMEOUT_SEGUNDOS = 30

    var listadoMotivosPredefinidos: List<String> = emptyList()
    var todosLosDomiciliosCache: List<List<Any>> = emptyList() // calle, numero, clave

    // Control parameters loaded dynamically from your S3 configuration
    var urlCamaraPlacasRtsp: String = "rtsp://192.168.1.150:554/live/ch1"
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
        todosLosDomiciliosCache = listOf(
            listOf("Circuito Olmos", "10", "OLM10"),
            listOf("Circuito Olmos", "24", "OLM24"),
            listOf("Circuito Olmos", "35", "OLM35"),
            listOf("Paseo Bugambilias", "5", "BUG5"),
            listOf("Paseo Bugambilias", "12", "BUG12")
        )
        //todosLosDomiciliosCache = dataRaw.getDomiciliosUbicacion()
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
        _uiState.update {
            it.copy(
                conductorInput = nombre.uppercase(),
                currentStep = CaptureStep.CAPTURA_PLACA,
                lblTopMensaje = "Valide la placa tecleada o leída por la cámara:"
            )
        }
        controlarCicloDeVidaDeStreaming()
    }

    // --- PROCESAMIENTO CON WHATSAPP Y ENLACE DE RENDERING API ---

    fun dispararProtocoloDeSeguridadYWhatsApp(placaFinal: String, descripcion: String) {
        timerJob?.cancel()
        val state = _uiState.value.copy(
            placaInput = placaFinal.replace(Regex("[^a-zA-Z0-9]"), "").uppercase(),
            descripcionInput = descripcion
        )
        _uiState.update { state }

        // Si fue un camión de basura o policía, salta la validación de WhatsApp de inmediato
        if (state.calleInput == "Administracion") {
            ejecutarGuardadoTransaccionalFinal(state)
            return
        }

        whatsappPollingJob?.cancel()
        _uiState.update { it.copy(whatsappStatus = WhatsappAuthStatus.Solicitando, currentStep = CaptureStep.PROCESANDO_AUTORIZACION) }

        whatsappPollingJob = viewModelScope.launch(Dispatchers.IO) {
            val telefonos = listOf("5213331234567") // Simulado del catálogo DataRawRondin para esa Calle:Número
            var tiempo = 0

            while (tiempo < 45 && isActive) {
                val consultas = telefonos.map { tel ->
                    async { consultarRenderApiMock(tel, state.placaInput) }
                }.awaitAll()

                if (consultas.contains("visita_acceso_permitido")) {
                    _uiState.update { it.copy(whatsappStatus = WhatsappAuthStatus.Autorizado("Residente Local")) }
                    withContext(Dispatchers.Main) {
                        ejecutarGuardadoTransaccionalFinal(state)
                    }
                    return@launch
                }
                delay(3000)
                tiempo += 3
            }
            _uiState.update { it.copy(whatsappStatus = WhatsappAuthStatus.Timeout, lblTopMensaje = "Timeout sin respuesta de WhatsApp. Contacte a caseta.") }
        }
    }

    private suspend fun consultarRenderApiMock(tel: String, placa: String): String {
        delay(400) // Simulación de Red
        return "pendiente"
    }

    // --- GUARDADO DE REGISTROS Y PERSISTENCIA OFF_LINE COMPLETA ---

    private fun ejecutarGuardadoTransaccionalFinal(state: IngresoVehicularUiState) {
        viewModelScope.launch {
            if (guardarMutex.isLocked) return@launch
            guardarMutex.withLock {
                val registro = RegistroAcceso(
                    id = (LocalTime.now().toSecondOfDay() * -1).toString(), // ID offline
                    fecha = LocalDate.now().toString(),
                    hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
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

                // 1. Envío distribuido síncrono al Nodo Central (Padre) vía TCP Ktor Sockets
                _uiState.update { it.copy(lblTopMensaje = "Notificando a Caseta Principal...") }
                val msg = SocketMessage(MessageType.REGISTRO_INGRESO, "client_ip", "INGRESO_VEHICULAR", registro)
                socketClient.enviarRegistroACaseta(msg)

                // 2. Persistir localmente en tu cola JSON de DataRawRondin
                // dataRawRondin.sync(SheetTable.BITACORA_ACCESOS, Operation.APPEND, ...)

                // 3. Forzar al WorkManager a activarse al recuperar internet de forma nativa
                SyncManager.programarSincronizacionAlRecuperarInternet(getApplication())

                _uiState.update { it.copy(lblTopMensaje = "¡ACCESO CONCEDIDO! Abriendo Barrera.") }
                delay(3000)
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
                        geminiVoiceAssistant.procesarEntradaVoz(textoEscuchado, datosAcumuladosJson)


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
    /**
     * Core router that intercepts extracted entities from the AI voice engine,
     * performs hard/fuzzy address validation, updates steps, and handles UI refresh.
     */
    private fun procesarEntidadesExtraidasPorGemini(calle: String, numero: String, nombre: String, tipo: String, placa: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val stepActual = _uiState.value.currentStep

            // --- PASO 0: MOTIVO ---
            if (tipo.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    // Match forzado contra la lista del archivo .ini (tiporegistro)
                    val matchMotivo = listadoMotivosPredefinidos.find { it.uppercase() == tipo }
                    if (matchMotivo != null) {
                        seleccionarMotivo(matchMotivo)
                    } else {
                        // Si no es un comando exacto, buscamos si la frase contiene la palabra clave
                        val coincidenciaParcial =
                            listadoMotivosPredefinidos.find { tipo.contains(it.uppercase()) }
                        if (coincidenciaParcial != null) {
                            seleccionarMotivo(coincidenciaParcial)
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
            if (calle.isNotEmpty() && numero.isNotEmpty()) {
                // Execute your pre-compiled fuzzy matching utility method on background storage threads
                val posiblesDomiciliosSimilares: List<List<Any>> = dataRaw.getDomiciliosSimilares(calle, numero)

                withContext(Dispatchers.Main) {
                    when {
                        posiblesDomiciliosSimilares.isEmpty() -> {
                            // No matches found: Notify user over TTS and keep grid available for selection
                            _uiState.update { it.copy(lblTopMensaje = "Dirección no localizada. Por favor use los botones.") }
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
                                    currentStep = CaptureStep.CAPTURA_NOMBRE, // Advance step automatically
                                    lblTopMensaje = "Domicilio validado: $calleVerificada #$numeroVerificado. Ingrese Nombre."
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
                            val mensajeCompleto = "Múltiples opciones encontradas ($textoDomiciliosEncontrados). Seleccione con los botones el correcto:"
                            withContext(Dispatchers.Main) {
                                _uiState.update { current ->
                                    current.copy(
                                        lblTopMensaje = mensajeCompleto,
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
            if (stepActual == CaptureStep.CAPTURA_NOMBRE && nombre.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    guardarNombreYPasarAPlacas(nombre)
                }
            }

            // --- PASO 4: CAPTURA Y VALIDACIÓN DE PLACAS ---
            if (stepActual == CaptureStep.CAPTURA_PLACA && placa.isNotEmpty()) {
                val placaSanitizada = tipo.replace(Regex("[^a-zA-Z0-9]"), "").uppercase().trim()
                if (placaSanitizada.length >= 3) {
                    withContext(Dispatchers.Main) {
                        dispararProtocoloDeSeguridadYWhatsApp(placaSanitizada, "Autorizado por Voz Inteligente")
                    }
                }
            }
        }
    }
    /**
     * Motor de contingencia determinista local.
     * Realiza emparejamientos contra los catálogos válidos de Google Sheets almacenados en RAM.
     */
    private fun procesarEntradaVozLocalFallback(query: String, pasoActual: CaptureStep) {
        when (pasoActual) {
            CaptureStep.SELECCION_MOTIVO -> {
                // Match forzado contra la lista del archivo .ini (tiporegistro)
                val matchMotivo = listadoMotivosPredefinidos.find { it.uppercase() == query }
                if (matchMotivo != null) {
                    seleccionarMotivo(matchMotivo)
                } else {
                    // Si no es un comando exacto, buscamos si la frase contiene la palabra clave
                    val coincidenciaParcial = listadoMotivosPredefinidos.find { query.contains(it.uppercase()) }
                    if (coincidenciaParcial != null) {
                        seleccionarMotivo(coincidenciaParcial)
                    } else {
                        _uiState.update { it.copy(subtitulosAsistente = "🤖 No reconozco ese motivo. Elija uno de la lista en pantalla.") }
                    }
                }
            }

            CaptureStep.SELECCION_CALLE -> {
                // Extraer las calles únicas dadas de alta en el condominio
                val listaCallesUnicas = todosLosDomiciliosCache.map { it[0].toString() }.distinct()
                val matchCalle = listaCallesUnicas.find { it.uppercase() == query || query.contains(it.uppercase()) }

                if (matchCalle != null) {
                    seleccionarCalle(matchCalle)
                } else {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Calle no localizada. Presione un botón de la rejilla.") }
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
                    seleccionarNumero(matchNumero)
                } else {
                    // Invocamos la función analizada de similitud para proponer aproximaciones
                    // DataRawRondin.getDomiclioSimilar(calle, numero)
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Número inválido para la calle ${_uiState.value.calleInput}.") }
                }
            }

            CaptureStep.CAPTURA_NOMBRE -> {
                // En el paso del nombre no podemos forzar un catálogo.
                // Absorber el texto transcrito directamente de la voz limpia como el nombre del conductor
                val nombreLimpio = query.replace(Regex("[^a-zA-Z\\s]"), "").trim()
                if (nombreLimpio.length > 2) {
                    guardarNombreYPasarAPlacas(nombreLimpio)
                } else {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 El nombre parece muy corto. Repítalo o use el teclado.") }
                }
            }

            CaptureStep.CAPTURA_PLACA -> {
                // Sanitizar la matrícula eliminando guiones o espacios dictados por error
                val placaLimpia = query.replace(Regex("[^a-zA-Z0-9]"), "").trim().uppercase()

                // Expresión de nomenclatura nacional: Validar que contenga estructura alfanumérica mínima
                if (placaLimpia.length >= 3) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Placa validada correctamente por voz.") }
                    dispararProtocoloDeSeguridadYWhatsApp(placaLimpia, "Captura por Voz Exitosa")
                } else {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Formato de placa inválido. Digítela manualmente.") }
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