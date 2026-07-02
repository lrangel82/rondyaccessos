package com.larangel.rondyaccesos.peatonal

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.larangel.rondyaccesos.RondyApplication
import com.larangel.rondyaccesos.models.*
import com.larangel.rondyaccesos.models.network.*
import com.larangel.rondyaccesos.utils.*
import com.larangel.rondyaccesos.vehicular.ExcepcionRondin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

class IngresoPeatonalViewModel(
    application: Application,
    private val dataRaw: DataRawRondin,
    private val geminiVoiceAssistant: GeminiVoiceAssistant,
    private val apiService: BotCasetaApiService,
    private val mySettings: MySettings
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(IngresoPeatonalUiState())
    val uiState: StateFlow<IngresoPeatonalUiState> = _uiState.asStateFlow()

    private val TIMEOUT_INACTIVIDAD_TOTAL = 60 // 1 minuto para el Splash
    private val TIMEOUT_RESET_ESTANDAR = 30    // 30 segundos para resetear paso actual

    private val networkManager = getApplication<RondyApplication>().networkManager
    private val guardarMutex = Mutex()
    private var timerInactividadJob: Job? = null

    private val _whatsappStatus = MutableStateFlow<WhatsappAuthStatus>(WhatsappAuthStatus.Idle)
    val whatsappStatus: StateFlow<WhatsappAuthStatus> = _whatsappStatus.asStateFlow()
    private var whatsappPollingJob: Job? = null


    var listadoMotivosPredefinidos: List<TipoAccesos> = emptyList()
    var todosLosDomiciliosCache: List<List<Any>> = emptyList() // calle, numero, clave

    // Control parameters loaded dynamically from your S3 configuration
    var urlCamaraQrRtspFallback: String = ""
    var usarCamaraLocalParaQr: Boolean = true

    // State flows to toggle the hardware reconfiguration screens on connection drops
    private val _camaraPlacaFalla = MutableStateFlow(false)
    val camaraPlacaFalla: StateFlow<Boolean> = _camaraPlacaFalla.asStateFlow()

    private val _camaraQrFalla = MutableStateFlow(false)
    val camaraQrFalla: StateFlow<Boolean> = _camaraQrFalla.asStateFlow()

    private var urlFoto1: String=""
    private var urlFoto2: String=""

    // Flag controlling the activation of the OCR processing task loop
    private val _vlcStreamActive = MutableStateFlow(false)
    val vlcStreamActive: StateFlow<Boolean> = _vlcStreamActive.asStateFlow()

    private var qrCooldownActivo = false

    // ==========================================
    // VARIABLES DE CONTROL DE CONCURRENCIA (Nivel Clase)
    // ==========================================
    private var orquestadorJob: Job? = null
    private val flujoResuelto = AtomicBoolean(false)
    private val flujoSocketCasetaDisparado = AtomicBoolean(false)
    private val flujoWhatsAppDisparado = AtomicBoolean(false)
    private val flujoIVRDisparado = AtomicBoolean(false)

    init {
        cargarConfiguracionesIniciales()
        reiniciarAsistentePeatonal()
    }

    private fun cargarConfiguracionesIniciales() {
        //listadoMotivosPredefinidos = listOf("Visitante", "Uber/Taxi", "Residente sin tag", "Paqueteria", "Gas", "ComidaADomicilio", "Policia", "Camion Basura", "Grua", "Ambulancia")
        listadoMotivosPredefinidos = dataRaw.getTiposAccesos().filter { it.esPeatonal }
        todosLosDomiciliosCache = dataRaw.getDomiciliosUbicacion()
    }

    fun reiniciarAsistentePeatonal(porExito: Boolean = false) {
        detenerAllJobs()

        flujoResuelto.set(false)
        flujoSocketCasetaDisparado.set(false)
        flujoWhatsAppDisparado.set(false)
        flujoIVRDisparado.set(false)

        //Close whatsappAlert
        _whatsappStatus.value = WhatsappAuthStatus.Idle

        _uiState.update {
            IngresoPeatonalUiState(
                currentStep = CaptureStep.SELECCION_MOTIVO,
                lblTopMensaje = "Asistente iniciado. Seleccione motivo.",
                subtitulosAsistente = "Indiqueme el motivo de su ingreso, porfavor.",
                segundosRestantes = TIMEOUT_RESET_ESTANDAR,
                mencionarBienvenida = porExito, // Solo mencionar si viene de un éxito
            )
        }

        viewModelScope.launch {
            geminiVoiceAssistant.subtitulosState.collect { speechText ->
                _uiState.update { it.copy(subtitulosAsistente = speechText) }
            }
        }
        //Register this ViewModel's pipeline processor to the Application scope
        val app = getApplication<RondyApplication>()
        app.registroCallbackActivo = { calle, numero, nombre, motivo, placa ->
            procesarEntidadesExtraidasPorGemini(calle, numero, nombre, motivo, placa)
        }
        if (porExito) {
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Acceso autorizado. Bienvenido.")
        }
        if (_uiState.value.mencionarBienvenida) {
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Bienvenido al condominio. Cual es el motivo de su ingreso?.")
            // Una vez dicha, bajamos el flag para que reinicios por inactividad no la repitan
            _uiState.update { it.copy(mencionarBienvenida = false) }
        }
        iniciarTimerInactividad()
    }

    fun detenerAllJobs(){
        orquestadorJob?.cancel()
        timerInactividadJob?.cancel()
        whatsappPollingJob?.cancel()
    }

    private fun iniciarTimerInactividad() {
        timerInactividadJob?.cancel()
        _uiState.update { it.copy(segundosRestantes = TIMEOUT_INACTIVIDAD_TOTAL) }
        timerInactividadJob = viewModelScope.launch {
            try {
                while (isActive && _uiState.value.segundosRestantes > 0) {
                    delay(1000)

                    // Si el splash ya se está mostrando, no seguimos descontando
                    if (_uiState.value.mostrarSplash) break

                    val paso = _uiState.value.currentStep
                    if (paso == CaptureStep.PROCESANDO_AUTORIZACION || flujoResuelto.get()) {
                        continue
                    }
                    _uiState.update { current ->
                        current.copy(segundosRestantes = current.segundosRestantes - 1)
                    }
                }
                if (_uiState.value.segundosRestantes == 0) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(mostrarSplash = true) }
                        //reiniciarAsistenteCompleto()
                    }
                }
            } catch (e: CancellationException) {
                // Cancelación controlada
            }
        }
    }

    fun despertarAsistente(esPorVoz: Boolean = false) {
        if (_uiState.value.mostrarSplash) {
            _uiState.update { it.copy(mostrarSplash = false) }

            // Al despertar por toque o voz "Hola", sí damos la bienvenida
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Bienvenido al condominio. Indique el motivo de su visita.")
            iniciarTimerInactividad()
            reiniciarAsistentePeatonal()
        }
    }

    // -- CAMARAS
    fun reportarFallaConexionQr() {
        _camaraQrFalla.value = true
    }
    fun procesarContenidoQrDetectado(rawText: String) {
        if (qrCooldownActivo || flujoResuelto.get()) return

        // 1. FILTRO: Validación estricta del prefijo de seguridad "ginn"
        if (!rawText.startsWith("ginn")) {
            qrCooldownActivo = true
            // 🔴 POP-UP ROJO: QR Inválido (No pertenece a la aplicación)
            val msg="Código QR Inválido. No pertenece al sistema del condominio."
            geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
            _whatsappStatus.value = WhatsappAuthStatus.Error(msg)

            viewModelScope.launch(Dispatchers.Main) {
                delay(5000) // Sostiene el pop-up por 5 segundos exactos
                reiniciarAsistentePeatonal()
                qrCooldownActivo = false
            }
            return
        }
        // 2. DESENPAQUETADO: Limpieza del payload
        val payloadLimpio = rawText.substring(4).trim()
        qrCooldownActivo = true
        val msg="Leyendo código QR... Verificando credenciales"
        //geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
        _whatsappStatus.value = WhatsappAuthStatus.Info(msg)
        _uiState.update { current ->
            current.copy(
                qrData = payloadLimpio,
                lblTopMensaje = "Leyendo código QR... Verificando credenciales.",
                currentStep = CaptureStep.PROCESANDO_AUTORIZACION
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            // 3. CONSULTA: Buscar el QR en la capa de persistencia local indexada
            val registroQrList: List<Any> = dataRaw.getQR(payloadLimpio)

            // 4. FILTRO: Verificar existencia en base de datos (Requiere mínimo 8 columnas indexadas)
            if (registroQrList.isEmpty() || registroQrList.size < 7) {
                withContext(Dispatchers.Main) {
                    // 🟡 POP-UP AMARILLO: QR Inexistente (Se usa Info para representar el Warning visual)
                    val msg="QR inexistente en el sistema."
                    geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
                    _whatsappStatus.value = WhatsappAuthStatus.Error(msg)
                    delay(4000)
                    reiniciarAsistentePeatonal()
                    qrCooldownActivo = false
                }
                return@launch
            }

            // Mapeo posicional estricto del registro según tu layout:
            // [0: md5, 1: calle, 2: numero, 3: nombre, 4: placas, 5: telefono_creador, 6: fecha_creado, 7: vencido]
            val calleQr           = registroQrList[1].toString()
            val numeroQr          = registroQrList[2].toString()
            val nombreInvitadoQr  = registroQrList[3].toString()
            val placasQr          = registroQrList[4].toString()
            val telefonoCreadorQr = registroQrList[5].toString()
            val fechaCreadoStr    = registroQrList[6].toString() // Ejemplo: "2026-06-02T01:02:33.236622"
            val estaVencidoFlag   = registroQrList[7].toString()

            // 5. FILTRO: Verificar si ya fue marcado previamente como quemado/vencido ("1")
            if (estaVencidoFlag == "1") {
                withContext(Dispatchers.Main) {
                    // 🟡 POP-UP AMARILLO: QR Vencido por uso previo
                    val msg="QR vencido, este ya ha sido utilizado anteriormente."
                    geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
                    _whatsappStatus.value = WhatsappAuthStatus.Alerta(msg)
                    delay(5000)
                    reiniciarAsistentePeatonal()
                    qrCooldownActivo = false
                }
                return@launch
            }

            // 6. FILTRO: Validación cronológica de 72 horas máximas de ciclo de vida
            var esMayor72Horas = false
            try {
                // Reemplaza la 'T' para homologar parseos ISO nativos de Java 8+
                val limpiaFecha = fechaCreadoStr.replace(" ", "T")
                // Si no contiene indicador de zona horaria Z, se concatena para asegurar compatibilidad UTC
                val formatoIso = if (!limpiaFecha.endsWith("Z")) limpiaFecha + "Z" else limpiaFecha

                val fechaCreacionInstant = java.time.Instant.parse(formatoIso)
                val ahoraInstant = java.time.Instant.now()

                // Evalúa si la fecha actual es posterior al límite de vida (Creación + 72 horas)
                if (fechaCreacionInstant.plus(72, java.time.temporal.ChronoUnit.HOURS).isBefore(ahoraInstant)) {
                    esMayor72Horas = true
                }
            } catch (e: Exception) {
                Log.e("ValidacionQR", "Error al procesar la estampa de tiempo UTC del QR: ${e.message}")
            }

            if (esMayor72Horas) {
                // Quemamos el token de inmediato en la base de datos local
                dataRaw.vencerQR(payloadLimpio)
                withContext(Dispatchers.Main) {
                    // 🟡 POP-UP AMARILLO: Expiración de ventana de tiempo
                    val msg="QR de más de 72 hrs ya no es válido."
                    geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
                    _whatsappStatus.value = WhatsappAuthStatus.Alerta(msg)
                    delay(5000)
                    reiniciarAsistentePeatonal()
                    qrCooldownActivo = false
                }
                return@launch
            }

            // 7. EVALUACIÓN: Regla del Caso Especial de Amenidades ("TERRAZA" / Áreas Comunes)
            val esCasoTerraza = calleQr.uppercase().contains("TERRAZA")
            var esMismoDia = true

            if (esCasoTerraza) {
                try {
                    // Extrae solo la porción de fecha "YYYY-MM-DD"
                    val diaCreado = fechaCreadoStr.split("T")[0]
                    val diaActual = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())

                    if (diaCreado != diaActual) {
                        esMismoDia = false
                    }
                } catch (e: Exception) {
                    esMismoDia = false
                }

                if (!esMismoDia) {
                    // Si es otro día, procedemos a vencerlo formalmente
                    dataRaw.vencerQR(payloadLimpio)
                    withContext(Dispatchers.Main) {
                        val msg="QR de Terraza caducado. Solo era válido para el día de su creación."
                        geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
                        _whatsappStatus.value = WhatsappAuthStatus.Alerta(msg)
                        delay(7000)
                        reiniciarAsistentePeatonal()
                        qrCooldownActivo = false
                    }
                    return@launch
                }
            }

            // 8. CIERRE DE ACCESO Y QUEMA DE TOKEN (Si pasa todos los filtros de seguridad)
            if (!flujoResuelto.compareAndSet(false, true)) return@launch
            timerInactividadJob?.cancel()

            // Si NO es caso terraza (o es el mismo día de terraza pero requiere protección de un solo uso), se quema el QR
            if (!esCasoTerraza) {
                dataRaw.vencerQR(payloadLimpio)
            }
            // Re-inyectamos los datos estructurados del QR directo al UI State para la transacción
            _uiState.update { current ->
                current.copy(
                    calleInput = calleQr,
                    numeroInput = numeroQr,
                    conductorInput = nombreInvitadoQr,
                    tipoInput = if (esCasoTerraza) "Invitado Terraza" else "Invitado QR",
                    qrData = payloadLimpio,
                    descripcionInput = "Acceso validado exitosamente vía Código QR [$calleQr:$numeroQr]",
                    status = "AUTORIZADO"
                )
            }

            // 9. PERSISTENCIA: Ejecución del guardado transaccional final unificado
            withContext(Dispatchers.Main) {
                // 🟢 POP-UP VERDE: Acceso Autorizado con Destino Explicitado
                _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                _uiState.update { it.copy(lblTopMensaje = "ACCESO AUTORIZADO - BIENVENIDO") }

                geminiVoiceAssistant.forzarLocucionPorAltavoz("Código QR aceptado.")
                delay(3000)

                // Disparamos la lógica de almacenamiento e imágenes
                ejecutarGuardadoTransaccionalFinal(_uiState.value)

                // 10. NOTIFICACIÓN COMPLEMENTARIA: Caso especial Terraza avisa al creador por WhatsApp
                if (esCasoTerraza && telefonoCreadorQr.isNotEmpty()) {
                    val telefonoDestinoNotif = listOf(telefonoCreadorQr.telefonoParaTwilio())

                    // Creamos un registro bitácora temporal solo para alimentar el payload del mánager de mensajería
                    val accesoDummy = AccesoBitacora(
                        fechaCreado = fechaCreadoStr, fechaIngreso = fechaCreadoStr,
                        placa = "PEATONAL", calle = calleQr, numero = numeroQr,
                        tipo = "Invitado Terraza (Multi-uso Diario)", conductor = nombreInvitadoQr,
                        descripcion = "Tu invitado ha ingresado usando el QR de la Terraza.",
                        foto1Url = urlFoto1, foto2Url = urlFoto2, qrData = payloadLimpio, fechaSalida = "", status = "AUTORIZADO"
                    )

                    // Hilo secundario IO dedicado para despachar la alerta sin congelar la UI de salida de la pantalla
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            WhatsAppNotificationManager.despacharNotificacionesDomicilio(
                                telefonos = telefonoDestinoNotif,
                                acceso = accesoDummy,
                                mySettings = mySettings
                            )
                        } catch (e: Exception) {
                            Log.e("NotifTerraza", "Fallo al enviar confirmación de uso QR al copropietario: ${e.message}")
                        }
                    }
                }

                // Mantiene el banner verde de éxito en la pantalla física del guardia por 4 segundos antes de liberar la fila
                delay(4000)
                reiniciarAsistentePeatonal()
                qrCooldownActivo = false
            }

        }
    }


    // --- ACCIONES SECUENCIALES DEL FLUJO DE CAPTURA ---
    fun seleccionarMotivo(sel_motivo: String) {
        iniciarTimerInactividad()

        //Buscar motivo en listado
        val motivo = listadoMotivosPredefinidos.find{ it.name.uppercase() == sel_motivo.uppercase() || it.name.contains(sel_motivo,ignoreCase = true) || sel_motivo.contains(it.name,ignoreCase = true) }
        if (motivo != null ) {

            if(motivo.EsEmergencia) {
                // Caso Especial: Excepción Inmediata sin Dirección
                if (!flujoResuelto.compareAndSet(false, true)) return

                // Detenemos inmediatamente los timers y la escucha activa
                timerInactividadJob?.cancel()
                orquestadorJob?.cancel()

                val numerosdom =
                    if (motivo.numeroDefault.isNotEmpty()) motivo.numeroDefault.split(",") else listOf(
                        "1"
                    )
                val calledom =
                    if (motivo.calleDefault.isNotEmpty()) motivo.calleDefault else "Administracion"

                numerosdom.forEach { numero ->
                    _uiState.update {
                        it.copy(
                            tipoInput = motivo.name,
                            motivoInput = motivo,
                            calleInput = calledom,
                            numeroInput = numero,
                            conductorInput = "SERVICIO PÚBLICO / EMERGENCIA",
                            descripcionInput = "Ingreso Exprés: $motivo",
                            status = "AUTORIZADO"
                        )
                    }
                    // Guardamos directo sin pedir confirmaciones
                    ejecutarGuardadoTransaccionalFinal(_uiState.value)
                }
                viewModelScope.launch(Dispatchers.Main) {
                    // 🟢 POP-UP VERDE: Acceso Autorizado con Destino Explicitado
                    _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("ACCESO AUTORIZADO - BIENVENIDO")
                    _uiState.update { it.copy(lblTopMensaje = "ACCESO AUTORIZADO - BIENVENIDO") }
                    delay(5000)
                    reiniciarAsistentePeatonal()
                }
            }
            else{
                //MOTIVO VALIDO
                _uiState.update { it.copy(
                    tipoInput = motivo.name,
                    motivoInput = motivo,
                ) }
                //hay domicilio default en el motivo? y esta vacio
                if (motivo.calleDefault.isNotEmpty() && motivo.numeroDefault.isNotEmpty() && (_uiState.value.calleInput.isEmpty() || _uiState.value.numeroInput.isEmpty())) {
                    _uiState.update {
                        it.copy(
                            calleInput = motivo.calleDefault,
                            numeroInput = motivo.numeroDefault,
                        )
                    }
                }
                evaluaDatosMaquinaDeEstados()
            }
        } else {
            //NO HAY MOTIVO VALIDO
            _uiState.update { it.copy(
                subtitulosAsistente = "🤖 No reconozco el motivo '$sel_motivo'. Elija uno de la lista.",
                currentStep = CaptureStep.SELECCION_MOTIVO,
                tipoInput = ""
            ) }
            return
        }
    }

    fun seleccionarCalle(calle: String) {
        iniciarTimerInactividad()
        _uiState.update {
            it.copy(
                calleInput = calle,
                numeroInput = "",
                //currentStep = CaptureStep.SELECCION_NUMERO,
                lblTopMensaje = "Seleccione el número de casa para la calle $calle:"
            )
        }
        evaluaDatosMaquinaDeEstados()
    }

    fun seleccionarNumero(numero: String) {
        iniciarTimerInactividad()
        // 1. Validar si el motivo actual seleccionado es Paquetería
        if (_uiState.value.motivoInput?.variosDomicilios ?: false ) {
            // 🚀 DESVÍO: En lugar de pasar a pedir el nombre, mandamos esta dirección a la lista interna
            // Pasamos la calle que ya estaba guardada en el estado y el número que acaba de llegar
            registrarDireccionPaqueteriaActualYPreguntar(_uiState.value.calleInput, numero)
        } else {
            _uiState.update {
                it.copy(
                    numeroInput = numero,
                    //currentStep = CaptureStep.CAPTURA_NOMBRE,
                    lblTopMensaje = "Ingrese su nombre:"
                )
            }
            evaluaDatosMaquinaDeEstados()
        }
    }

    fun confirmarDireccion(respuesta: String){
        iniciarTimerInactividad()
        if (respuesta.contains("SI")){
            if (_uiState.value.motivoInput?.variosDomicilios ?: false ) {
                // Si el reingreso inteligente detecta paquetería, registramos esa primera casa y preguntamos por más
                registrarDireccionPaqueteriaActualYPreguntar(_uiState.value.calleInput, _uiState.value.numeroInput)
            } else {
                _uiState.update {
                    it.copy(
                        //currentStep = CaptureStep.CAPTURA_NOMBRE,
                        lblTopMensaje = "Ingrese su nombre:"
                    )
                }
                evaluaDatosMaquinaDeEstados()
            }
        }else{
            _uiState.update {
                it.copy(
                    calleInput = "",
                    numeroInput = "",
                    //currentStep = CaptureStep.SELECCION_CALLE,
                    lblTopMensaje = "Indique la calle de destino:"
                )
            }
            evaluaDatosMaquinaDeEstados()
        }
    }

    fun guardarNombreYPasarAAutorizar(nombre: String) {
        iniciarTimerInactividad()
        _uiState.update {
            it.copy(
                conductorInput = nombre.uppercase(),
                //currentStep = CaptureStep.CAPTURA_PLACA,
                lblTopMensaje = "Valide la placa tecleada o leída por la cámara:"
            )
        }
        evaluaDatosMaquinaDeEstados()
        //controlarCicloDeVidaDeStreaming()
        //validarMotivoYSolicitarAutorizacion()
    }

    fun registrarDireccionPaqueteriaActualYPreguntar(calle: String, numero: String) {
        val listaActual = _uiState.value.direccionesPaqueteria.toMutableList()
        listaActual.add(Pair(calle, numero))

        _uiState.update {
            it.copy(
                direccionesPaqueteria = listaActual,
                currentStep = CaptureStep.PREGUNTA_OTRA_DIRECCION,
                lblTopMensaje = "Dirección registrada (${calle} #${numero}). ¿Viene a otra dirección?"
            )
        }
        geminiVoiceAssistant.forzarLocucionPorAltavoz("¿Viene a otra dirección?")
    }

    fun cleanRostro(){
        if (_uiState.value.currentStep == CaptureStep.SELECCION_MOTIVO)
            _uiState.update {
                it.copy(
                    currentFaceBitmap = null,
                    currentFaceEmbedding = null,
                    qrData = ""
                )
            }
    }
    fun registrarRostro(faceBitmap: Bitmap, embedding: FloatArray) {
        val istheFirstTime = _uiState.value.currentFaceEmbedding == null || _uiState.value.currentFaceEmbedding!!.isEmpty()
        _uiState.update {
            it.copy(
                currentFaceBitmap = faceBitmap,
                currentFaceEmbedding = embedding,
                qrData = embedding.toJsonString()
            )
        }
        //Hay que procesar para buscar si este rostro ya habia ingresado anteriormente y si fue aprobado
        //debemos suponer que fue aprobado por el dia de hoy asi que dar acceso directo es correcto
        //y solo avisar que ingresa al mismo domicilio
        if (_uiState.value.currentStep == CaptureStep.SELECCION_MOTIVO)
            viewModelScope.launch(Dispatchers.Default) {
                // 3. CONSULTA: Buscar el ROSTRO en la capa de persistencia local indexada
                val registroPeatonalLast24hr: List<List<Any>> = dataRaw.getBitacoraPeatonalAccesos24Hrs()

                // 4. FILTRO: Verificar existencia en base de datos (Requiere mínimo 10 columnas indexadas)
                if (registroPeatonalLast24hr.isEmpty() ) {
                    //No hay registros en la base de datos
                    return@launch
                }

                //Buscar si hay concidencia
                run loop@{
                    registroPeatonalLast24hr.forEach { row ->
                        if (row.size < 10) return@loop
                        val _embeddingStoredData = row[10].toString().toFloatArray() //qr_Data
                        val similitud =_embeddingStoredData.euclideanDistance(embedding)
                        Log.d("ValidacionRostro","similitud rostro vs DB(${row[1]}:${row[2]}:${row[3]}): $similitud")
                        //Distancia ecuclidiana cercanas a 0 son el mismo rostro, mayores es un rostro distinto
                        if (  similitud <= 0.7f ) {
                            //Concidencia DE ROSTRO!!!!
                            Log.d("ValidacionRostro","Rostro encontrado con SIMILITUD: $similitud")

                            // Mapeo posicional estricto del registro según tu layout:
                            val calle24h          = row[3].toString()
                            val numero24h         = row[4].toString()
                            val tipo24h           = row[5].toString()
                            val nombreInvitado24h = row[6].toString()
                            val status24h         = row[12].toString()

                            Log.d("ValidacionRostro","status24h:${status24h} calle24h: ${calle24h}:${numero24h} ${tipo24h} ${nombreInvitado24h}")

                            //Es denegado nos salimos y que haga registro nuevo
                            if (status24h.esDenegado() == true) return@loop

                            // CIERRE DE ACCESO (Si pasa todos los filtros de seguridad)
                            if (!flujoResuelto.compareAndSet(false, true)) return@launch
                            timerInactividadJob?.cancel()

                            // Re-inyectamos los datos estructurados del QR directo al UI State para la transacción
                            _uiState.update { current ->
                                current.copy(
                                    calleInput = calle24h,
                                    numeroInput = numero24h,
                                    conductorInput = nombreInvitado24h,
                                    tipoInput = tipo24h,
                                    //qrData = ya esta cargado anteriormente,
                                    descripcionInput = "Acceso validado ROSTRO ultimas 24hrs [$calle24h:$numero24h]",
                                    status = "AUTORIZADO"
                                )
                            }

                            // 9. PERSISTENCIA: Ejecución del guardado transaccional final unificado
                            withContext(Dispatchers.Main) {
                                // 🟢 POP-UP VERDE: Acceso Autorizado con Destino Explicitado
                                _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                                _uiState.update { it.copy(lblTopMensaje = "ACCESO AUTORIZADO - BIENVENIDO") }

                                geminiVoiceAssistant.forzarLocucionPorAltavoz("Bienvenido de nuevo ${nombreInvitado24h}.")
                                delay(3000)

                                // Disparamos la lógica de almacenamiento e imágenes
                                ejecutarGuardadoTransaccionalFinal(_uiState.value)


                                // Mantiene el banner verde de éxito en la pantalla física del guardia por 4 segundos antes de liberar la fila
                                delay(4000)
                                reiniciarAsistentePeatonal()
                                qrCooldownActivo = false
                            }

                            return@loop
                        }
                    }
                } // Fin de Loop de busqueda de rostros!!!


            }

        if (istheFirstTime == true)
            evaluaDatosMaquinaDeEstados()
    }

    fun responderPreguntaOtraDireccion(quiereOtra: Boolean) {
        if (quiereOtra) {
            // Regresa el flujo a la captura de dirección limpando las variables de apoyo temporal
            _uiState.update {
                it.copy(
                    calleInput = "",
                    numeroInput = "",
                    currentStep = CaptureStep.SELECCION_CALLE,
                    lblTopMensaje = "Indique la siguiente calle de destino:"
                )
            }
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Indíqueme la siguiente calle.")
        } else {
            // Terminó el recorrido. Pasamos directamente a capturar los datos del chofer
            _uiState.update {
                it.copy(
                    currentStep = CaptureStep.CAPTURA_NOMBRE,
                    lblTopMensaje = "Ingrese el nombre del conductor:"
                )
            }
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Indíqueme el nombre del conductor.")
        }
    }

    fun validarMotivoYSolicitarAutorizacion(){
        //EL motivo acepta varias DIRECCIONES, si si gaurdar registro en todas
        if (_uiState.value.motivoInput!= null && _uiState.value.motivoInput!!.variosDomicilios && _uiState.value.direccionesPaqueteria.size > 1) {
            // CASO ESPECIAL: Es un recorrido. No se pide autorización, se procesan ráfagas de inserción
            if (!flujoResuelto.compareAndSet(false, true)) return
            timerInactividadJob?.cancel()

            viewModelScope.launch(Dispatchers.Default) {
                val chofer = _uiState.value.conductorInput
                val tipoReg = _uiState.value.tipoInput
                val direccionesAProcesar = _uiState.value.direccionesPaqueteria.toList()

                // Limpiamos la lista del estado de inmediato para que quede huérfana de ejecuciones extras
                _uiState.update { it.copy(direccionesPaqueteria = emptyList()) }

                direccionesAProcesar.forEach { (calle, numero) ->
                    val estadoIndividual = _uiState.value.copy(
                        calleInput = calle,
                        numeroInput = numero,
                        conductorInput = chofer,
                        tipoInput = tipoReg,
                        status = "AUTORIZADO",
                        descripcionInput = "Recorrido Multidireccional. Autorización Automática."
                    )
                    // Ejecuta inserciones transaccionales independientes respetando tu mutex por cada casa
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("Autorizado Bienvenido.")
                    ejecutarGuardadoTransaccionalFinal(estadoIndividual)
                    delay(500) // Pequeño espacio para desahogo de procesos e imágenes
                }

                withContext(Dispatchers.Main) {
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("ACCESO AUTORIZADO - BIENVENIDO")
                    delay(5000)
                    reiniciarAsistentePeatonal()
                }
            }
        }
        else{
            geminiVoiceAssistant.forzarLocucionPorAltavoz("Estamos solicitando Autorizacion al Residente, espere un poco.")
            _uiState.update {
                it.copy(
                    lblTopMensaje = "SOLICITANDO AUTORIZACION... espere un poco",
                    currentStep = CaptureStep.PROCESANDO_AUTORIZACION
                )
            }
            ejecutarFiltrosDeSeguridadCompleto()
        }
    }

    //MAQUINA DE ESTADOS DEL FLUJO DE CAPTURA
    fun evaluaDatosMaquinaDeEstados(){
        val estadoActual = _uiState.value

        // Si ya está procesando una autorización, congelamos cambios de pasos
        if (estadoActual.currentStep == CaptureStep.PROCESANDO_AUTORIZACION) return

        when {
            estadoActual.tipoInput.isEmpty() -> {
                geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme el motivo de su ingreso, porfavor.")
                _uiState.update { it.copy(currentStep = CaptureStep.SELECCION_MOTIVO) }
            }

            // Si es paquetería y no ha capturado ninguna dirección en la lista, pide la primera
            _uiState.value.motivoInput?.variosDomicilios == true  && estadoActual.direccionesPaqueteria.isEmpty() && estadoActual.calleInput.isEmpty() -> {
                geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme la calle del domicilio.")
                _uiState.update { it.copy(currentStep = CaptureStep.SELECCION_CALLE) }
            }

            // Si es paquetería y está respondiendo la pregunta del bucle
            estadoActual.currentStep == CaptureStep.PREGUNTA_OTRA_DIRECCION -> {
                // Mantiene el estado visual congelado hasta que responda SI o NO por voz/botón
            }

            // Para cualquier otro motivo que no tenga dirección capturada
            _uiState.value.motivoInput?.variosDomicilios != true && estadoActual.calleInput.isEmpty() -> {
                geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme la calle del domicilio.")
                _uiState.update { it.copy(currentStep = CaptureStep.SELECCION_CALLE) }
            }

            _uiState.value.motivoInput?.variosDomicilios != true && estadoActual.numeroInput.isEmpty() -> {
                geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme el numero del domicilio.")
                _uiState.update { it.copy(currentStep = CaptureStep.SELECCION_NUMERO) }
            }

            estadoActual.conductorInput.isEmpty() -> {
                geminiVoiceAssistant.forzarLocucionPorAltavoz("Indiqueme su nombre.")
                _uiState.update { it.copy(currentStep = CaptureStep.CAPTURA_NOMBRE) }
            }

            estadoActual.currentFaceEmbedding== null || estadoActual.currentFaceEmbedding.isEmpty() -> {
                geminiVoiceAssistant.forzarLocucionPorAltavoz("Porfavor mire a la camara para registrar su rostro.")
                _uiState.update { it.copy(currentStep = CaptureStep.CAPTURA_ROSTRO) }
            }


            else -> {
                // ¡DATOS COMPLETOS VIA ASISTENTE DE VOZ! Ejecuta la solicitud final
                validarMotivoYSolicitarAutorizacion()
            }
        }
    }

    //VALIDAR SEGURIDAD Y EXCEPCIONES
    fun ejecutarFiltrosDeSeguridadCompleto() {
        val calle = _uiState.value.calleInput
        val numero= _uiState.value.numeroInput
        viewModelScope.launch(Dispatchers.Default) {
            val currentState = _uiState.value

            // 1. Core Check: Delinquent Address Evaluation (Domicilios Morosos)
            val esMoroso = dataRaw.esDomicilioMoroso(calle, numero)
            if (esMoroso) {
                val motivoDenegacion = "RESTRICCION POR MOROSIDAD DEL DOMICILIO $calle $numero"
                _uiState.update {
                    it.copy(
                        mostrarPanelResultadoDerecho = true,
                        resultadoEsAutorizado = false,
                        status = "DENEGADO",
                        descripcionInput = motivoDenegacion
                    )
                }
                withContext(Dispatchers.Main) {
                    ejecutarGuardadoTransaccionalFinal(_uiState.value)
                    _whatsappStatus.value = WhatsappAuthStatus.Denegado
                    geminiVoiceAssistant.forzarLocucionPorAltavoz(
                        "Lo sentimos, pero este domicilio tiene restringido el servicio por morosidad. " +
                                "Comuníquese con el residente para que el mismo vaya a abrir la puerta. "
                    )
                    delay(10000)
                    reiniciarAsistentePeatonal()
                }

                return@launch
            }

            // 2. Matrix Validation: Dynamic Exception Check Engine
            val excepcionActiva = buscarExcepcionParaDomicilio(calle, numero, currentState.conductorInput, "")
            if (excepcionActiva != null) {

                // Hay excepcion de un solo USO y es del mismo tipo el ingreso
                val matchTipo = listadoMotivosPredefinidos.find { it.name.uppercase().contains(excepcionActiva.conductor.uppercase())  }
                if (matchTipo!=null && matchTipo.excepcionesDinamicas) {
                    dataRaw.vencerExcepcion(excepcionActiva.id) // Immediate single-use consumption burn

                    withContext(Dispatchers.Main) {
                        _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                        _uiState.update { it.copy(lblTopMensaje = "ACCESO AUTORIZADO - BIENVENIDO") }

                        geminiVoiceAssistant.forzarLocucionPorAltavoz("Autorizado Bienvenido.")
                        ejecutarGuardadoTransaccionalFinal(_uiState.value)
                        delay(5000)
                        reiniciarAsistentePeatonal()
                    }
                    return@launch
                }else{
                    //Hay excepcion entonces ejecutar lo que dice la excepcion
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                mostrarPanelResultadoDerecho = true,
                                resultadoEsAutorizado = excepcionActiva.resultadoEsAutorizado,
                                status = excepcionActiva.status,
                                descripcionInput = "APLICACION DE EXCEPCION CODIGO: ${excepcionActiva.id} - ${excepcionActiva.descripcion}",
                            )
                        }
                        ejecutarGuardadoTransaccionalFinal(_uiState.value)
                        delay(5000)
                        reiniciarAsistentePeatonal()
                    }
                    return@launch
                }


            }

            // 3. Permisos
            if(_uiState.value.motivoInput?.RequierePermisoAdmon == true){
                val permisosHoy = dataRaw.getPermisosCache_DeHoy()
                val permisoDom = permisosHoy.find {
                    it[1].toString().equals(calle,ignoreCase = true) && it[2].toString().equals(numero,ignoreCase = true)
                }
                //El permiso esta Denegado o no existe?
                if (permisoDom == null || "1 Si si X".contains(permisoDom[11].toString(), ignoreCase = true) == false) {
                    val motivoDenegacion = "No hay PERMISO activo para $calle $numero"
                    _uiState.update {
                        it.copy(
                            mostrarPanelResultadoDerecho = true,
                            resultadoEsAutorizado = false,
                            status = "DENEGADO",
                            descripcionInput = motivoDenegacion
                        )
                    }
                    withContext(Dispatchers.Main) {
                        ejecutarGuardadoTransaccionalFinal(_uiState.value)
                        _whatsappStatus.value = WhatsappAuthStatus.Denegado
                        geminiVoiceAssistant.forzarLocucionPorAltavoz(
                            "Lo siento el residente no tiene permiso activo para el ingreso de Trabajos o Mudanzas. " +
                                    "Comuníquese con el residente para que el mismo vaya a abrir la puerta. "
                        )
                        delay(10000)
                        reiniciarAsistentePeatonal()
                    }
                    return@launch
                }
            }


            // 4. Fallback: Base authorization flow sequence if no flags are triggered
            iniciarFlujoAutorizacion()

        }
    }
    private fun buscarExcepcionParaDomicilio(calle: String, numero: String, conductor: String, placa: String): ExcepcionRondin? {
        val listadoExcepciones = dataRaw.getExcepcionesDomicilio(calle, numero)
        val fechaActual = java.util.Date()

        for (exc in listadoExcepciones) {
            val status      = exc[10].toString()
            val fechaInicio = exc[7].toString()
            val fechaFin    = exc[8].toString()
            val conductor_ex= exc[3].toString()
            val placas_ex   = exc[4].toString()
            val descripcion = exc[5].toString()
            val status_vs_descripcion= exc[6].toString()
            if (!status.equals("aprobada", ignoreCase = true)) continue

            // Parse limits
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val inicio = try { sdf.parse(fechaInicio) } catch(e: Exception) { null }
            val fin = try { sdf.parse(fechaFin) } catch(e: Exception) { null }

            if (inicio != null && fin != null) {
                if (fechaActual.before(inicio) || fechaActual.after(fin)) continue // Out of lifetime frame boundary
            }

            // Verification layers
            val tieneConductorFiltrado = conductor_ex.isNotEmpty()
            val tienePlacaFiltrada = placas_ex.isNotEmpty()

            val posiblesDenegados: List<String> = listOf("NO","MOROSO","NIEGA ACCESO","DENEGADO")
            val esDenegado = posiblesDenegados.any { palabra ->
                status_vs_descripcion.contains(palabra, ignoreCase = true)
            }
            val excepcion = ExcepcionRondin(
                id = exc[0].toString(),
                calle = calle,
                numero = numero,
                placas = placas_ex,
                conductor= conductor_ex,
                resultadoEsAutorizado = !esDenegado,
                status = status_vs_descripcion,
                descripcion = descripcion
            )

            if (!tieneConductorFiltrado && !tienePlacaFiltrada) {
                return excepcion // Universal Address Global Exception rule match
            }

            val coincideConductor = tieneConductorFiltrado && conductor_ex.equals(conductor, ignoreCase = true)
            val coincidePlaca = tienePlacaFiltrada && placas_ex.equals(placa, ignoreCase = true)

            if (coincideConductor || coincidePlaca) {
                return excepcion // Match found
            }
        }
        return null
    }


    // --- PROCESADOR DE ENTRADAS DE VOZ DE GEMINI (Match forzado de Catálogo) ---
    fun procesarEntradaVozAsistenteGemini(textoEscuchado: String) {

        val query = textoEscuchado.trim().lowercase()
        if (query.isEmpty() || query.length < 2) return

        iniciarTimerInactividad()

        // Si está en splash y dice "Hola" o algo coherente
        if (_uiState.value.mostrarSplash) {
            if (query.contains("hola", ignoreCase = true) || query.length > 10) {
                despertarAsistente(esPorVoz = true)
                //return // No procesamos Gemini aún, solo despertamos
            }
        }

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
                      "placa_actual": "PEATONAL"
                    }
                """.trimIndent()

                        // Llamamos a la infraestructura que creamos en la Fase 1 (GeminiVoiceAssistant)
                        // El SDK procesará la locución libre del usuario y rellenará los campos correspondientes
                        timerInactividadJob?.cancel()
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
            //var stepActual = _uiState.value.currentStep

            // --- PASO 0: MOTIVO ---
            if (tipo.isNotEmpty() && _uiState.value.tipoInput != tipo) {

                //Buscar motivo en listado
                val motivo = listadoMotivosPredefinidos.find{ it.name.uppercase() == tipo.uppercase() || it.name.contains(tipo,ignoreCase = true) || tipo.contains(it.name,ignoreCase = true) }
                if (motivo != null) {
                    // Caso Especial: Excepción Inmediata sin Dirección
                    if (motivo.EsEmergencia) {
                        if (!flujoResuelto.compareAndSet(false, true)) return@launch

                        // Detenemos inmediatamente los timers y la escucha activa
                        timerInactividadJob?.cancel()
                        orquestadorJob?.cancel()

                        val numerosdom =
                            if (motivo.numeroDefault.isNotEmpty()) motivo.numeroDefault.split(",") else listOf(
                                "1"
                            )
                        val calledom =
                            if (motivo.calleDefault.isNotEmpty()) motivo.calleDefault else "Administracion"

                        numerosdom.forEach { numero ->
                            _uiState.update {
                                it.copy(
                                    tipoInput = motivo.name,
                                    motivoInput = motivo,
                                    calleInput = calledom,
                                    numeroInput = numero,
                                    conductorInput = "SERVICIO PÚBLICO / EMERGENCIA",
                                    descripcionInput = "Ingreso Exprés: $motivo",
                                    status = "AUTORIZADO"
                                )
                            }
                            // Guardamos directo sin pedir confirmaciones
                            ejecutarGuardadoTransaccionalFinal(_uiState.value)
                        }
                        viewModelScope.launch(Dispatchers.Main) {
                            // 🟢 POP-UP VERDE: Acceso Autorizado con Destino Explicitado
                            _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                            geminiVoiceAssistant.forzarLocucionPorAltavoz("ACCESO AUTORIZADO - BIENVENIDO")
                            _uiState.update { it.copy(lblTopMensaje = "ACCESO AUTORIZADO - BIENVENIDO") }
                            delay(5000)
                            reiniciarAsistentePeatonal()
                            return@launch // Terminación inmediata del flujo
                        }
                    }
                    else{
                        //MOTIVO VALIDO
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(
                                tipoInput = motivo.name,
                                motivoInput = motivo
                            ) }
                        }
                        //hay domicilio default en el motivo? y esta vacio
                        if (motivo.calleDefault.isNotEmpty() && motivo.numeroDefault.isNotEmpty() && (calle.isEmpty() || numero.isEmpty())) {
                            withContext(Dispatchers.Main) {
                                _uiState.update {
                                    it.copy(
                                        calleInput = motivo.calleDefault,
                                        numeroInput = motivo.numeroDefault
                                    )
                                }
                            }
                        }
                    }
                }else {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(
                            subtitulosAsistente = "🤖 No reconozco el motivo '$tipo'. Elija uno de la lista.",
                            currentStep = CaptureStep.SELECCION_MOTIVO,
                            tipoInput = "",
                            motivoInput = null
                        ) }
                    }
                    return@launch
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

                            if (_uiState.value.motivoInput?.variosDomicilios == true) {
                                // 🚀 REGLA UNIFICADA: Interceptamos y mandamos al bucle multidireccional de paquetería
                                registrarDireccionPaqueteriaActualYPreguntar(calleVerificada, numeroVerificado)
                            } else {
                                _uiState.update {
                                    it.copy(
                                        calleInput = calleVerificada,
                                        numeroInput = numeroVerificado,
                                        //currentStep = CaptureStep.CAPTURA_NOMBRE, // Advance step automatically
                                        subtitulosAsistente = "Domicilio validado: $calleVerificada #$numeroVerificado. Ingrese Nombre."
                                    )
                                }
                            }
                            iniciarTimerInactividad()
                        }
                        posiblesDomiciliosSimilares.size > 1 -> {
                            // Multiple partial matches: Update local list cache to force activity to render selection chips
                            // Mapped from your python implementation (open_multiple_domicilios_dialog criteria)
                            val textoDomiciliosEncontrados = posiblesDomiciliosSimilares.joinToString(", ") { "${it[0]}:${it[1]}" }
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

            //// --- PASO 4: CAPTURA Y VALIDACIÓN DE PLACAS ---
            //if (placa.isNotEmpty() && _uiState.value.placaInput != placa) {
            //    val placaSanitizada = placa.extraerPlaca().toString()
            //    if (placaSanitizada.length >= 3) {
            //        _uiState.update { it.copy(placaInput = placaSanitizada) }
            //    }
            //}

            // --- VERIFICAR SI REQUIRE IR A UN PASO ESPECIFICO
            // =========================================================================
            // 5. MÁQUINA DE ESTADOS REPOSITORIO: CALCULAR EL PASO SIGUIENTE CORRECTO
            // =========================================================================
            withContext(Dispatchers.Main) {
                evaluaDatosMaquinaDeEstados()
            }

        }
    }
    /** Validar palabra contra opciones */
    private fun procesarEntradaVozLocalFallback(query: String, pasoActual: CaptureStep) {
        when (pasoActual) {
            CaptureStep.SELECCION_MOTIVO -> {
                // Match forzado contra la lista del archivo .ini (tiporegistro)
                val matchMotivo = listadoMotivosPredefinidos.find { it.name.uppercase() == query.uppercase() }
                if (matchMotivo != null) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Motivo: $matchMotivo") }
                    seleccionarMotivo(matchMotivo.name)
                } else {
                    // Si no es un comando exacto, buscamos si la frase contiene la palabra clave
                    val coincidenciaParcial = listadoMotivosPredefinidos.find { query.uppercase().contains(it.name.uppercase()) || it.name.uppercase().contains(query.uppercase()) }
                    if (coincidenciaParcial != null) {
                        _uiState.update { it.copy(subtitulosAsistente = "🤖 Motivo: $coincidenciaParcial") }
                        seleccionarMotivo(coincidenciaParcial.name)
                    } else {
                        geminiVoiceAssistant.forzarLocucionPorAltavoz("Lo siento no reconozco ese motivo. Elija uno de la lista en pantalla.", false)
                        _uiState.update { it.copy(subtitulosAsistente = "🤖 Escucue: \"$query\"\n No reconozco ese motivo. Elija uno de la lista en pantalla.") }
                    }
                }
            }

            CaptureStep.SELECCION_CALLE -> {
                // Extraer las calles únicas dadas de alta en el condominio
                val listaCallesUnicas = todosLosDomiciliosCache.map { it[0].toString() }.distinct()
                val matchCalle = listaCallesUnicas.find { it.uppercase() == query || query.uppercase().contains(it.uppercase()) || it.uppercase().contains(query.uppercase()) }

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
                    .filter { it[0].toString().equals(_uiState.value.calleInput, ignoreCase = true) }
                    .map { it[1].toString() }

                // Buscamos si el dictado numérico coincide con alguna de las casas existentes
                val matchNumero = numerosValidosParaCalle.find { it.uppercase() == query.uppercase() }

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

            CaptureStep.CONFRMAR_DOMICILIO -> {
                //Si respondio que NO etnonces hay que regresar, de otra forma continuar
                if (query.contains("NO",ignoreCase = true)){
                    confirmarDireccion("NO")
                }else if (query.contains("SI",ignoreCase = true)){
                    confirmarDireccion("SI")
                }else{
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("No entendi su respuesta. Seleccione de las opciones.", false)
                    _uiState.update { it.copy(subtitulosAsistente = "🤖Escucue: \"$query\"\n  valor invalido, es SI o NO") }
                }
            }

            CaptureStep.PREGUNTA_OTRA_DIRECCION -> {
                if (query.contains("NO") || query.contains("FIN") || query.contains("TERMINAR")) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Finalizar captura de domicilios.") }
                    responderPreguntaOtraDireccion(quiereOtra = false)
                } else if (query.contains("SI") || query.contains("OTRA") || query.contains("MAS")) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Siguiente dirección.") }
                    responderPreguntaOtraDireccion(quiereOtra = true)
                } else {
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("No entendí su respuesta. Indique si viene a otra dirección, sí o no.", false)
                }
            }

            CaptureStep.CAPTURA_NOMBRE -> {
                // En el paso del nombre no podemos forzar un catálogo.
                // Absorber el texto transcrito directamente de la voz limpia como el nombre del conductor
                val nombreLimpio = query.replace(Regex("[^a-zA-Z\\s]"), "").trim()
                if (nombreLimpio.length > 2) {
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 Nombre: $nombreLimpio") }
                    guardarNombreYPasarAAutorizar(nombreLimpio)
                } else {
                    geminiVoiceAssistant.forzarLocucionPorAltavoz("El nombre parece muy corto. Repítalo o use el teclado.", false)
                    _uiState.update { it.copy(subtitulosAsistente = "🤖 El nombre parece muy corto. Repítalo o use el teclado.") }
                }
            }

            //CaptureStep.CAPTURA_PLACA -> {
            //    // Sanitizar la matrícula eliminando guiones o espacios dictados por error
            //    val placaLimpia = query.replace(Regex("[^a-zA-Z0-9]"), "").trim().uppercase()
            //
            //    // Expresión de nomenclatura nacional: Validar que contenga estructura alfanumérica mínima
            //    if (placaLimpia.length >= 3) {
            //        _uiState.update { it.copy(
            //            subtitulosAsistente = "🤖 Placa validada correctamente por voz.",
            //            placaInput = placaLimpia ,
            //            lblTopMensaje = "SOLICITANDO AUTORIZACION... espere un poco"
            //        ) }
            //        ejecutarFiltrosDeSeguridadCompleto()
            //     } else {
            //        geminiVoiceAssistant.forzarLocucionPorAltavoz("Formato de placa inválido. Digítela manualmente.", false)
            //        _uiState.update { it.copy(subtitulosAsistente = "🤖 Escucue: \"$query\"\n Formato de placa inválido. Digítela manualmente.") }
            //    }
            //}

            else -> {}
        }
    }


    //----NUEVO FLUJO DE AUTORIZACION----------------
    //Arquitectura Visual del Flujo de Eventos
    // Inicio: Se lee la base de datos offline.
    //     Si NO hay teléfonos: Salta la espera -> Lanza IVR de inmediato.
    //     Si SÍ hay teléfonos:
    //          Inicia contador visual en pantalla (1s en 1s).
    //          Hilo 1: Inicia Polling de WhatsApp (Consulta cada 3s).
    //          Hilo 2: Espera pasiva de 30s -> Lanza llamada IVR.
    //          Hilo 3: Espera pasiva de 60s -> Envía Socket a Caseta.
    // Fin del Flujo (Cualquiera de estas 4 opciones destruye a las demás):
    //      WhatsApp responde -> Notifica a copropietarios -> Guarda con Mutex -> Muestra resultado 4s -> Reset.
    //      IVR responde -> Guarda con Mutex -> Muestra resultado 4s -> Reset.
    //      Error de Red (WhatsApp/IVR) -> Despacha Socket Caseta de inmediato -> Cancela flujo.
    //      Timeout Total (5 min) -> Muestra pantalla grande de error -> Guarda como "visita_acceso_no_respondido".

    // ==========================================
    // 2. ORQUESTADOR CENTRAL DEL FLUJO MULTI-CANAL
    // ==========================================
    fun iniciarFlujoAutorizacion() {
        val state = _uiState.value.copy()
        val telefonosArray = dataRaw.getWhatsappTelefonosDomicilio(state.calleInput, state.numeroInput)
        val domicilio_data = dataRaw.getDomiciliosSimilares(state.calleInput, state.numeroInput).firstOrNull()
        val telFijo = if (!domicilio_data.isNullOrEmpty() && domicilio_data.size >= 6 ) domicilio_data.get(5).toString().telefonoParaTwilio() else ""


        // Cancelar de forma segura cualquier hilo o residuo visual anterior
        orquestadorJob?.cancel()
        whatsappPollingJob?.cancel()
        timerInactividadJob?.cancel()

        // Reiniciar los estados atómicos para esta nueva consulta
        flujoResuelto.set(false)
        flujoIVRDisparado.set(false)
        flujoWhatsAppDisparado.set(false)
        flujoSocketCasetaDisparado.set(false)


        //No hay forma de contactar al residente
        if (telefonosArray.isEmpty() && telFijo.isEmpty() && state.motivoInput?.autorizadoPorCaseta == false) {
            val msg = "No hay telefonos registrados para este domicilio, SE DENIEGA ACCESO."
            _whatsappStatus.value = WhatsappAuthStatus.Error(msg)
            geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
            viewModelScope.launch(Dispatchers.Main) {
                delay(5000) // Sostiene el pop-up por 5 segundos exactos
                procesarEjecucionGuardadoFinal(state, "SINTELEFONO", "visita_acceso_denegado")
                reiniciarAsistentePeatonal()
            }
            return
        }


        // Temporizador Visual Nativo para el operador en Caseta (Sigue corriendo en Main)
        val startTime = System.currentTimeMillis()
        timerInactividadJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive && !flujoResuelto.get()) {
                val totalSecs = 180
                val elapsedSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                var msg = "Solicitando autorizacion...    ${elapsedSeconds}seg"

                if (flujoIVRDisparado.get()) msg = msg + "\n Llamado a residente..."
                if (flujoWhatsAppDisparado.get()) msg = msg + "\n Enviando Whatsapp residente..."
                if (flujoSocketCasetaDisparado.get()) msg = msg + "\n Preguntando Autorizacion Guardia Caseta..."
                //if (telefonosArray.isEmpty() && !telFijo.isNullOrEmpty()) {
                _whatsappStatus.update {
                    WhatsappAuthStatus.Info(msg)
                }
                //}
                _uiState.update { it.copy(
                    currentStep = CaptureStep.PROCESANDO_AUTORIZACION,
                    tiempoTranscurrido = elapsedSeconds
                ) }
                delay(1000)
            }
        }

        // Creación del contenedor asíncrono controlado en hilos de Entrada/Salida (IO)
        orquestadorJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Límite de tiempo global de 3 minutos (180,000 milisegundos)
                withTimeout(180_000L) {

                    // SUB-HILO 1: Espera pasiva de 1 minuto para activar el Socket de Caseta
                    launch {
                        if (_uiState.value.motivoInput == null || _uiState.value.motivoInput!!.autorizadoPorCaseta == false)
                            delay(60_000L) //Si se autoriza por caseta se lanza de inmediato el flujo
                        dispararSocketCasetaManual(state)
                    }

                    if (_uiState.value.motivoInput == null || _uiState.value.motivoInput!!.autorizadoPorCaseta == false) {
                        // EVALUACIÓN DE INICIO: ¿Tenemos infraestructura telefónica para WhatsApp?
                        if (telefonosArray.isEmpty()) {
                            //Hay telefono fijo?
                            if (!telFijo.isNullOrEmpty()) {
                                // ESPECIFICACIÓN: Si no hay números de WhatsApp, el IVR se dispara al instante
                                launch { lanzarFlujoIVRSeguro(state) }
                            } else {
                                withContext(Dispatchers.Main) {
                                    _whatsappStatus.value =
                                        WhatsappAuthStatus.Error("No hay telefonos registrados para este domicilio, SE DENIEGA ACCESO.")
                                }
                                manejarFalloCriticoEnCanal(
                                    state,
                                    "No hay telefonos registrados para ese domicilio"
                                )
                            }
                        } else {
                            // SUB-HILO 2: Ciclo de consulta frecuente (Polling) de WhatsApp
                            launch {
                                ejecutarSondeoWhatsApp(state, telefonosArray)
                            }

                            // SUB-HILO 3: ESPECIFICACIÓN: Esperar 15 segundos pasivos antes de respaldar con IVR
                            launch {
                                delay(15_000L)
                                lanzarFlujoIVRSeguro(state)
                            }
                        }
                    } // Fin if (motivo == null || motivo.autorizadoPorCaseta == false) Solo caseta autoriza
                }
            } catch (e: TimeoutCancellationException) {
                // ESPECIFICACIÓN: Finalización por expiración de tiempo global sin respuesta de nadie
                manejarTerminacionPorTimeout(state)
            }
        }
    }
    private suspend fun ejecutarSondeoWhatsApp(state: IngresoPeatonalUiState, telefonosArray: List<List<Any>>) {
        if (flujoWhatsAppDisparado.compareAndSet(false, true) == false) return //Indicar que ya se disparo
        val tokenApi = "Bearer " + mySettings.getString("TOKEN_API_BOTCASETA", "")
        var segundosRestantes = 180
        var contadorErrores = 0

        while (currentCoroutineContext().isActive && !flujoResuelto.get() && segundosRestantes > 0) {

            // Ejecutar bloque cada 3 segundos de forma exacta
            if (segundosRestantes % 3 == 0) {
                for (row in telefonosArray) {
                    if (!currentCoroutineContext().isActive || flujoResuelto.get()) return

                    val telefono = row[2].toString()
                    val requestPayload = ValidarVisitaRequest(
                        telefono = telefono, calle = state.calleInput, numero = state.numeroInput,
                        placas = "PEATONAL", nombre = state.conductorInput, tiporegistro = state.tipoInput
                    )

                    try {
                        val response = apiService.validarVisita(tokenApi, requestPayload)

                        if (!response.isSuccessful) {
                            contadorErrores++
                            if (contadorErrores > 10) {
                                flujoWhatsAppDisparado.set(false)
                                // ESPECIFICACIÓN: Si la API falla, activa Socket Caseta de inmediato y rompe el flujo
                                manejarFalloCriticoEnCanal(
                                    state,
                                    "Error API validar_visita: ${response.code()}"
                                )
                                Log.e("sondeoWhatsApp", "Error API validar_visita: ${response.code()}")
                                return
                            }
                        }

                        val body = response.body() ?: continue
                        val lastAct = body.last_actividad
                        val fechaLastActividad = body.fecha_ultima_actualizacion ?: ""

                        if (validarVigenciaUtc(fechaLastActividad)) {
                            when (lastAct) {
                                "visita_acceso_permitido" -> {
                                    flujoWhatsAppDisparado.set(false)
                                    terminarFlujoConGanador {
                                        val toInform = telefonosArray.filter { it[2].toString() != telefono }
                                        informarOtrosCopropietarios(tokenApi, toInform, telefono, "Acceso permitido", requestPayload)
                                        _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                                        procesarEjecucionGuardadoFinal(state, telefono, "visita_acceso_permitido")
                                    }
                                    return
                                }
                                "visita_acceso_denegado" -> {
                                    flujoWhatsAppDisparado.set(false)
                                    terminarFlujoConGanador {
                                        val toInform = telefonosArray.filter { it[2].toString() != telefono }
                                        informarOtrosCopropietarios(tokenApi, toInform, telefono, "Acceso denegado", requestPayload)
                                        _whatsappStatus.value = WhatsappAuthStatus.Denegado
                                        procesarEjecucionGuardadoFinal(state, telefono, "visita_acceso_denegado")
                                    }
                                    return
                                }
//                                "timeout_visita" -> {
//                                    withContext(Dispatchers.Main) {
//                                        _whatsappStatus.value = WhatsappAuthStatus.Timeout
//                                    }
//                                    finalStatusFound = true
//                                    break
//                                }
                            }
                        }
                    } catch (e: Exception) {
                        contadorErrores++
                        if (contadorErrores > 10) {
                            // ESPECIFICACIÓN: Si hay caída de red/excepción, dispara Socket Caseta al instante
                            flujoWhatsAppDisparado.set(false)
                            manejarFalloCriticoEnCanal(
                                state,
                                "Fallo de conexión en red en WhatsApp: ${e.message}"
                            )
                            Log.e("sondeoWhatsApp", "Fallo de conexión en red en WhatsApp: ${e.message}")
                            return
                        }
                    }
                }
            }
            delay(1000)
            segundosRestantes--
        }
    }
    private suspend fun lanzarFlujoIVRSeguro(state: IngresoPeatonalUiState) {
        val tokenApi = "Bearer " + mySettings.getString("TOKEN_API_BOTCASETA", "")
        var contadorErrores = 0

        var segundosRestantes = 180
        val telefonosArray = dataRaw.getWhatsappTelefonosDomicilio(state.calleInput, state.numeroInput)
        val telFijoArray: List<String>
        //Cuantos telefonos en IVR tenemos
        val domicilio = dataRaw.getDomiciliosSimilares(state.calleInput,state.numeroInput)
        if (domicilio.isEmpty() || domicilio.size != 1){
            //Checar si hay telefonos de whstasapp para usarlos en el IVR
            val telefonosArray = dataRaw.getWhatsappTelefonosDomicilio(state.calleInput, state.numeroInput)
            if (telefonosArray.isEmpty()) return
            telFijoArray = telefonosArray.map { it[2].toString().telefonoParaTwilio() }

        } else { //si no es unico o es vacio los domicilios retornamos
            telFijoArray =
                domicilio.first().getOrNull(5).toString().split(",").map { it.trim() }
        }
        if (telFijoArray.isEmpty()) return

        if (flujoIVRDisparado.compareAndSet(false, true) == false) return //Indicar que ya se disparo

        while (currentCoroutineContext().isActive && !flujoResuelto.get() && segundosRestantes > 0){
            //Ciclo mientras no tengamos respuesta de alguien
            // Actualización visual segura cambiando al hilo principal (Main)
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(lblTopMensaje = "Llamando al domicilio... ${180-segundosRestantes}seg") }
            }

            if (segundosRestantes % 3 == 0) {
                for (telFIJO in telFijoArray){
                    if (!currentCoroutineContext().isActive || flujoResuelto.get()) return

                    val requestPayload = ValidarVisitaIVRRequest(
                        telefono = telFIJO, calle = state.calleInput, numero = state.numeroInput,
                        conductor = state.conductorInput, motivo = state.tipoInput
                    )
                    val requestToInformResult = ValidarVisitaRequest(
                        telefono = telFIJO, calle = state.calleInput, numero = state.numeroInput,
                        placas = "", nombre = state.conductorInput, tiporegistro = state.tipoInput
                    )

                    try {
                        val response = apiService.validarVisitaIVR(tokenApi, requestPayload)

                        if (!response.isSuccessful) {
                            // ESPECIFICACIÓN: Si la API falla, activa Socket Caseta de inmediato y rompe el flujo
                            contadorErrores++
                            if (contadorErrores > 10) {
                                flujoIVRDisparado.set(false)
                                manejarFalloCriticoEnCanal(
                                    state,
                                    "Error API validar_visita de IVR: ${response.code()}"
                                )
                                return
                            }
                        }

                        val body = response.body() ?: continue
                        val lastAct = body.last_actividad
                        val fechaLastActividad = body.fecha_ultima_actualizacion ?: ""

                        if (validarVigenciaUtc(fechaLastActividad)) {
                            when (lastAct) {
                                "ivr_inicia_validacion" -> {
                                    //LANZAR LLAMADA IVR, ya se creo registro en BOT para llevar trazo
                                    //#################################
                                    callIVR_Twilio(state,telFIJO)
                                    //#################################
                                }
                                "ivr_esperando_respuesta" -> {
                                    withContext(Dispatchers.Main) {
                                        _uiState.update { it.copy( subtitulosAsistente = "esperando respuesta Tel FIJO... ") }
                                    }
                                }
                                "ivr_aprobado" -> {
                                    flujoIVRDisparado.set(false)
                                    terminarFlujoConGanador {
                                        informarOtrosCopropietarios(tokenApi, telefonosArray, telFIJO, "Acceso permitido", requestToInformResult)
                                        _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                                        procesarEjecucionGuardadoFinal(state, telFIJO, "visita_acceso_permitido")
                                    }
                                    return
                                }
                                "ivr_denegado" -> {
                                    flujoIVRDisparado.set(false)
                                    terminarFlujoConGanador {
                                        informarOtrosCopropietarios(tokenApi, telefonosArray, telFIJO, "Acceso denegado", requestToInformResult)
                                        _whatsappStatus.value = WhatsappAuthStatus.Denegado
                                        procesarEjecucionGuardadoFinal(state, telFIJO, "visita_acceso_denegado")
                                    }
                                    return
                                }
                                "ivr_timeout" -> {
                                    flujoIVRDisparado.set(false)
                                    withContext(Dispatchers.Main) {
                                        _whatsappStatus.value = WhatsappAuthStatus.Timeout
                                    }
                                    //finalStatusFound = true
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ESPECIFICACIÓN: Si hay caída de red/excepción, dispara Socket Caseta al instante
                        contadorErrores++
                        if (contadorErrores > 10) {
                            flujoIVRDisparado.set(false)
                            manejarFalloCriticoEnCanal(
                                state,
                                "Fallo de conexión en red en Flujo IVR: ${e.message}"
                            )
                            return
                        }
                    }
                    //Fin ciclo por telefono fijo
                }
            }

            delay(1000)
            segundosRestantes--
        }

    }
    private suspend fun dispararSocketCasetaManual(state: IngresoPeatonalUiState) {
        // Valida que el mensaje de contingencia a la pantalla física del guardia se envíe solo una vez
        if (flujoSocketCasetaDisparado.compareAndSet(false, true)) {
            Log.w(
                "SocketCaseta",
                "Despachando evento socket hacia el dispositivo de la Caseta Física."
            )

            val accesoBitacora = AccesoBitacora(
                placa = "PEATONAL",
                calle = state.calleInput,
                numero = state.numeroInput,
                tipo = state.tipoInput,
                conductor = state.conductorInput,
                descripcion = state.descripcionInput,
                qrData = state.qrData
            )
            networkManager.solicitarAutorizacionCaseta(accesoBitacora)

            // El bucle lee atómicamente el valor mientras sea true
            var segundosRestantes = 60
            while (currentCoroutineContext().isActive && !flujoResuelto.get() && segundosRestantes > 0){
                try {
                    if (segundosRestantes % 3 == 0) {
                        //Verificar Estatus respuesta
                        when (networkManager.RESPUESTA_SOLICITAR_AUTORIZACION.get()) {
                            "AUTORIZADO" -> {
                                flujoSocketCasetaDisparado.set(false)
                                terminarFlujoConGanador {
                                    _whatsappStatus.value = WhatsappAuthStatus.Autorizado
                                    procesarEjecucionGuardadoFinal(
                                        state,
                                        "CASETA",
                                        "visita_acceso_permitido"
                                    )
                                }
                                return
                            }

                            "DENEGADO" -> {
                                flujoSocketCasetaDisparado.set(false)
                                terminarFlujoConGanador {
                                    _whatsappStatus.value = WhatsappAuthStatus.Denegado
                                    procesarEjecucionGuardadoFinal(
                                        state,
                                        "CASETA",
                                        "visita_acceso_denegado"
                                    )
                                }
                                return
                            }
                        }
                    }
                }catch (e: Exception) {
                    flujoSocketCasetaDisparado.set(false)
                    manejarFalloCriticoEnCanal(state, "Fallo de conexión en red en Socket Flow: ${e.message}")
                    return
                }
                delay(1000)
                segundosRestantes--
            }
        }
    }
    private suspend fun callIVR_Twilio(state: IngresoPeatonalUiState, telFijo: String){
        try {
            //invocar la petición HTTP que dispara tu telefonía IVR.
            val telTO = telFijo.telefonoParaTwilio()
            if (telTO.isEmpty()) return
            val TWILIO_ACCOUNT_SID = mySettings.getString("TWILIO_ACCOUNT_SID","TIWILIO ACCOUNT SID")
            val TWILIO_TOKEN    = mySettings.getString("TWILIO_TOKEN", "TWILIO_TOKEN")
            val TWILIO_URL_FLOW = mySettings.getString("TWILIO_URL_FLOW", "https://studio.twilio.com/v2/Flows/xxxxx/Executions")
            val TWILIO_TELEFONO = mySettings.getString("TWILIO_TELEFONO", "+12394454165")
            val client = OkHttpClient()
            val formBody = FormBody.Builder()
                .add("To", telTO) // Teléfono del condómino extraído de row[2]
                .add("From", TWILIO_TELEFONO) // Tu número comprado en Twilio
                .add("Parameters", "{\"calle\":\"${state.calleInput}\", \"numero\":\"${state.numeroInput}\", \"conductor\":\"${state.conductorInput}\",\"motivo\":\"${state.tipoInput}\"}")
                .build()
            val request = Request.Builder()
                .url(TWILIO_URL_FLOW)
                .addHeader("Authorization", Credentials.basic(TWILIO_ACCOUNT_SID, TWILIO_TOKEN))
                .post(formBody)
                .build()
            withContext(Dispatchers.IO) {
                try {
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        Log.e("TwilioIVR", "Error al disparar flujo: ${response.code}")
                        flujoIVRDisparado.set(false)
                        manejarFalloCriticoEnCanal(state, "Fallo API Twilio Studio")
                    }
                } catch (e: Exception) {
                    flujoIVRDisparado.set(false)
                    manejarFalloCriticoEnCanal(state, "Excepción de red al llamar a Twilio: ${e.message}")
                }
            }

        } catch (e: Exception) {
            // ESPECIFICACIÓN: Si la API del IVR falla, activa de inmediato la Caseta física por Socket
            manejarFalloCriticoEnCanal(state, "Excepción en canal telefónico IVR: ${e.message}")
        }
    }
    private inline fun terminarFlujoConGanador(crossinline uiBlock: () -> Unit) {
        // Si compareAndSet encuentra 'false', lo pasa a 'true' de forma atómica y entra al bloque.
        // Si ya era 'true' (porque otro canal ganó la carrera), se salta por completo evitando duplicidad.
        if (flujoResuelto.compareAndSet(false, true)) {
            orquestadorJob?.cancel() // Detiene delays y peticiones HTTP de los otros canales en paralelo
            timerInactividadJob?.cancel()
            runBlocking(Dispatchers.Main) {
                uiBlock()
            }
        }
    }
    private fun manejarFalloCriticoEnCanal(state: IngresoPeatonalUiState, razon: String) {
        Log.e("OrquestadorFlujo", razon)
        // ESPECIFICACIÓN: Disparo inmediato del socket si ocurre un error en cualquier flujo
        //  if (flujoSocketCasetaDisparado.get() == false)
        //      runBlocking(Dispatchers.Main) {
        //          dispararSocketCasetaManual(state)
        //      }
        //Si aun hay un flow corriendo, no se debe cancelar, solo avisar que uno fallo
        if (flujoIVRDisparado.get() || flujoWhatsAppDisparado.get() || flujoSocketCasetaDisparado.get()) {
            _uiState.update { it.copy(subtitulosAsistente = "Fallo en flujo: \"$razon\"") }
        }else {
            //Todos los flujos terminaron, cancelar el acceso
            if (flujoResuelto.compareAndSet(false, true)) {
                orquestadorJob?.cancel()
                timerInactividadJob?.cancel()
                runBlocking(Dispatchers.Main) {
                    val msg =
                        "Error critico al solicitar autorizacion, se deniega el acceso, razon:${razon}"
                    geminiVoiceAssistant.forzarLocucionPorAltavoz(msg)
                    _whatsappStatus.value = WhatsappAuthStatus.Error(msg)
                    delay(5000)
                    cancelarFlujoPorError()
                }
            }
        }
    }
    private fun manejarTerminacionPorTimeout(state: IngresoPeatonalUiState) {
        if (flujoResuelto.compareAndSet(false, true)){
            timerInactividadJob?.cancel()
            orquestadorJob?.cancel()
            runBlocking(Dispatchers.Main) {
                val mensajeDenegacionLargo = "No se obtuvo ninguna respuesta, no fue posible contactar al residente, favor de comunicarse directamente con él, su acceso ha sido denegado por seguridad, por favor salga de la fila para permitir a otros visitantes ingresar."
                geminiVoiceAssistant.forzarLocucionPorAltavoz(mensajeDenegacionLargo)

                // ESPECIFICACIÓN: Guarda el registro como no autorizado automáticamente
                val stateTimeout = state.copy(
                    descripcionInput = "Acceso denegado por expiración de tiempo (Timeout general)",
                    status = "visita_acceso_no_respondido",
                    lblTopMensaje = "ACCESO DENEGADO POR TIMEOUT")

                // Actualizamos el status de WhatsApp para que la Activity se entere del mensaje largo
                _whatsappStatus.value = WhatsappAuthStatus.Error(mensajeDenegacionLargo)

                ejecutarGuardadoTransaccionalFinal(stateTimeout)

                delay(5000)
                reiniciarAsistentePeatonal()
            }
        }
    }
    private fun validarVigenciaUtc(fechaLastActividad: String): Boolean {
        if (fechaLastActividad.length <= 10) return false
        return try {
            val formatoIsoStandard = fechaLastActividad.replace(" ", "T") + "Z"
            val dateUtcLast = Instant.parse(formatoIsoStandard)
            val ahoraUtc = Instant.now()
            !dateUtcLast.plus(5, ChronoUnit.MINUTES).isBefore(ahoraUtc)
        } catch (e: DateTimeParseException) {false}
    }
    private fun procesarEjecucionGuardadoFinal(state: IngresoPeatonalUiState, quienValido: String, estatusFinal: String) {
        viewModelScope.launch(Dispatchers.Main) {
            _whatsappStatus.value = WhatsappAuthStatus.Idle

            // Dar formato detallado a la columna de descripción de quién procesó la entrada
            val mensajeModificado = when (quienValido) {
                "IVR_LLAMADA" -> "Autorizado por llamada telefónica IVR"
                "TIMEOUT" -> "Acceso denegado por expiración de tiempo"
                "SINTELEFONO" -> "Domicilio sin TELEFONOS registrados"
                "CASETA" -> "Procesado por VIGILANTE CASETA"
                else -> "Whatsapp respondio:..(${quienValido.takeLast(3)})"
            }

            // Invocamos la función transaccional pasándole el estatus mapeado
            val stateModificado = state.copy(
                descripcionInput = mensajeModificado,
                status = estatusFinal
            )
            ejecutarGuardadoTransaccionalFinal(stateModificado)
        }
    }
    fun cancelarSolicitudManual() {
        orquestadorJob?.cancel()
        whatsappPollingJob?.cancel()
        timerInactividadJob?.cancel()
        _whatsappStatus.value = WhatsappAuthStatus.Idle
        geminiVoiceAssistant.forzarLocucionPorAltavoz("Proceso cancelado")
        reiniciarAsistentePeatonal()
    }
    private fun cancelarFlujoPorError() {
        orquestadorJob?.cancel()
        whatsappPollingJob?.cancel()
        timerInactividadJob?.cancel()
        _whatsappStatus.value = WhatsappAuthStatus.Idle
        reiniciarAsistentePeatonal()
    }
    //----FINALIZA FLUJO DE AUTORIZACION-----------------


    ////////////////////////////////////////////////////
    ///////////////////// GUARDADO /////////////////////
    private fun ejecutarGuardadoTransaccionalFinal(state: IngresoPeatonalUiState) {
        viewModelScope.launch {
            // Validación de reentrada atómica para evitar duplicados por dobles clics táctiles
            if (guardarMutex.isLocked) return@launch

            guardarMutex.withLock {
                _uiState.update { it.copy(lblTopMensaje = "Procesando registro...") }
                // 1. Safe extraction from the Application dynamic callback link
                val app = getApplication<RondyApplication>()
                //val bitmapPlacaLive: Bitmap? = app.imagenesCallBackActivo?.invoke("PLACA")
                val bitmapRostroLive: Bitmap? = app.imagenesCallBackActivo?.invoke("ROSTRO")


                // 1. Generar estampas de tiempo unificadas para consistencia de datos
                val fechaActualStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                val horaActualStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                val timestampCombinado = "$fechaActualStr $horaActualStr"
                val esDenegado = state.status.esDenegado()
                val esAutorizado = !esDenegado

                _uiState.update { it.copy(lblTopMensaje = "Almacenando evidencias...") }

                val uploadAndCloseJob = async(context = Dispatchers.IO) {
                    // Feature 4: Temporal 30-day Image Ephemeral Upload Module
                    val urlRostro = CloudStorageManager.subirImagenTemporal(
                        context = getApplication(),
                        fileName = "rostro_${state.calleInput}_${state.numeroInput}_${System.currentTimeMillis()}.jpg",
                        bitmap = bitmapRostroLive,
                        calidad = 60,
                        mySettings = mySettings
                    )

                    // Feature 5: Update previous open records lacking 'fechaSalida'
                    dataRaw.actulizarSalidaAccesosPeatonal(state.conductorInput, state.calleInput,state.numeroInput, timestampCombinado)

                    urlRostro
                }

                val url1 = uploadAndCloseJob.await()
                urlFoto1 = url1

                //  Construir la Entidad Estructurada para DataRawRondin (Alineada a las 13 columnas de Sheets)
                val accesoBitacora = AccesoBitacora(
                    fechaCreado = timestampCombinado,
                    fechaIngreso = timestampCombinado,
                    placa = "PEATONAL",
                    calle = state.calleInput,
                    numero = state.numeroInput,
                    tipo = state.tipoInput,
                    conductor = state.conductorInput,
                    descripcion = state.descripcionInput,
                    foto1Url = urlFoto1,
                    foto2Url = urlFoto2,
                    qrData = state.qrData,
                    fechaSalida = "",
                    status = if (esAutorizado) "AUTORIZADO" else "DENEGADO"
                )

                _uiState.update { it.copy(
                    lblTopMensaje = if (esAutorizado) "ACCESO AUTORIZADO" else "ACCESO DENEGADO",
                    mostrarPanelResultadoDerecho = true,
                    resultadoEsAutorizado = esAutorizado,
                    resultadoMotivoPrincipal = if (esAutorizado) "¡PASE ENTRADA!" else "INGRESO PROHIBIDO",
                    resultadoMotivoDetalle = state.descripcionInput
                ) }

                // Envío Distribuido al Nodo Central (Caseta Padre) protegido contra fallas de red
                try {
                    // Se ejecuta de manera asíncrona dedicada para no colgar el flujo si el servidor TCP tarda en responder
                    withContext(Dispatchers.IO) {
                        //socketClient.enviarRegistroACaseta(msg)
                        networkManager.replicarIngreso(accesoBitacora)
                    }
                } catch (e: Exception) {
                    Log.e("IngresoPEATONALVM", "Fallo de red local en Ktor Socket (Caseta remota inaccesible). Continuando en modo Offline-First.", e)
                }

                // 5. Persistir de forma inmediata en la capa de datos unificada de la app (RAM + MySettings + Queue)
                _uiState.update { it.copy(lblTopMensaje = "Guardando en bitácora local...") }
                val guardadoLocalExitoso = withContext(Dispatchers.IO) {
                    dataRaw.addBitacoraAccesos(accesoBitacora)
                }

                if (!guardadoLocalExitoso) {
                    Log.e("IngresoPEATONALVM", "Error crítico al intentar indexar el acceso en las estructuras de DataRawRondin.")
                }

                // Feature 6: WhatsApp Async Double Notification Pipeline (Template + Images)
                val telefonosDomicilio = dataRaw.getWhatsappTelefonosDomicilio(state.calleInput, state.numeroInput)
                val telefonosFiltrados: List<String> = telefonosDomicilio.map { fila ->
                    fila[2].toString()
                }
                WhatsAppNotificationManager.despacharNotificacionesDomicilio(
                    telefonos = telefonosFiltrados,
                    acceso = accesoBitacora,
                    mySettings = mySettings
                )

                // Retraso controlado para permitir que el operario visualice el estatus en la pantalla antes del reset
                delay(4000)

                // Complete Reset & wipe visual panels
                _uiState.update {
                    it.copy(
                        mostrarPanelResultadoDerecho = false,
                        lblTopMensaje = ""
                    )
                }

                // Retorna el flujo guiado al paso 1, apaga el Timer de Inactividad y reestablece parámetros de IA
                reiniciarAsistentePeatonal()
            }
        }
    }
    ////////////////////////////////////////////////////
    ////////////////////////////////////////////////////


    // --- PROCESAMIENTO CON WHATSAPP Y ENLACE DE RENDERING API ---
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

    override fun onCleared() {
        super.onCleared()
        // Anti-memory leak rule: Clear callback reference when the current view session is destroyed
        val app = getApplication<Application>() as RondyApplication
        app.registroCallbackActivo = null
    }
}