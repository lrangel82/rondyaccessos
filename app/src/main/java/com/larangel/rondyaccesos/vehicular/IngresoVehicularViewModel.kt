package com.larangel.rondyaccesos.vehicular

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

        // ESTRATEGIA EN LÍNEA: Si hay red, delegamos la extracción estructurada a Gemini
        viewModelScope.launch(Dispatchers.Default) {
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
}